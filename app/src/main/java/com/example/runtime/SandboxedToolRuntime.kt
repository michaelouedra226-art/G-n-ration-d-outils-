package com.example.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.core.ai.AiRequest
import com.example.core.ai.ApiRouterService
import com.example.core.ai.LoadBalancingStrategy
import com.example.core.data.repository.ToolRepository
import com.example.core.security.SafeExpressionEvaluator
import com.example.core.security.SandboxGuard
import com.example.domain.manifest.ManifestValidator
import com.example.domain.manifest.ToolManifest
import com.example.domain.manifest.ToolPermission
import com.example.domain.manifest.ToolStatus
import com.example.domain.manifest.UiFieldType
import com.example.domain.manifest.WorkflowStepType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.util.Locale

data class StepExecutionTrace(
    val stepTitle: String,
    val stepType: WorkflowStepType,
    val durationMs: Long,
    val detail: String
)

data class ToolExecutionResult(
    val success: Boolean,
    val outputs: Map<String, String>,
    val pdfArtifact: GeneratedPdfArtifact?,
    val traces: List<StepExecutionTrace>,
    val providerUsed: String,
    val totalDurationMs: Long,
    val errorMessage: String? = null
)

/**
 * Sandboxed plugin runtime executing declarative [ToolManifest] workflows inside strict
 * permission, timeout, input-size, and storage isolation boundaries.
 */
class SandboxedToolRuntime(
    private val context: Context?,
    private val toolRepository: ToolRepository,
    private val apiRouterService: ApiRouterService,
    private val pdfGenerator: PdfGenerator
) {

    suspend fun execute(
        manifest: ToolManifest,
        rawInputs: Map<String, String>,
        strategy: LoadBalancingStrategy = LoadBalancingStrategy.PRIORITY_AND_ROTATION,
        globalTimeoutMs: Long = SandboxGuard.DEFAULT_EXECUTION_TIMEOUT_MS
    ): ToolExecutionResult {
        val startMs = System.currentTimeMillis()
        val traces = mutableListOf<StepExecutionTrace>()
        var providerUsed = "Local Sandbox"
        var generatedPdf: GeneratedPdfArtifact? = null

        return try {
            if (manifest.status == ToolStatus.PAUSED || manifest.status == ToolStatus.ARCHIVED) {
                throw IllegalStateException(
                    "Cet outil est actuellement '${manifest.status.label}'. Activez-le pour l'exécuter."
                )
            }

            ManifestValidator.validate(manifest).getOrThrow()

            val effectiveTimeout = minOf(manifest.timeoutMs, globalTimeoutMs)
                .coerceIn(5_000L, 120_000L)

            val variables = mutableMapOf<String, String>()
            val imageAttachments = mutableMapOf<String, Pair<String, String>>()

            // Pre-populate and sanitize inputs according to field types & declared permissions
            for (field in manifest.fields) {
                val raw = rawInputs[field.id] ?: field.defaultValue
                val sanitized = SandboxGuard.sanitizeInput(raw, field.label)
                if (field.required && sanitized.isBlank()) {
                    throw IllegalArgumentException("Le champ obligatoire « ${field.label} » est vide.")
                }

                when (field.type) {
                    UiFieldType.FILE_DOCUMENT -> {
                        if (sanitized.isNotBlank()) {
                            SandboxGuard.requirePermission(manifest, ToolPermission.FILE_PICKER)
                            val docText = readTextFromUri(sanitized)
                            variables[field.id] = docText
                        } else {
                            variables[field.id] = ""
                        }
                    }
                    UiFieldType.IMAGE_INPUT -> {
                        if (sanitized.isNotBlank()) {
                            SandboxGuard.requirePermission(manifest, ToolPermission.IMAGE_PICKER)
                            val encoded = readImageBase64FromUri(sanitized)
                            imageAttachments[field.id] = encoded
                            variables[field.id] = "[Image jointe : ${field.label}]"
                        } else {
                            variables[field.id] = ""
                        }
                    }
                    else -> variables[field.id] = sanitized
                }
            }

            withTimeout(effectiveTimeout) {
                for (step in manifest.workflow.take(SandboxGuard.MAX_WORKFLOW_STEPS)) {
                    val stepStart = System.currentTimeMillis()
                    when (step.type) {
                        WorkflowStepType.VALIDATE_INPUT -> {
                            for (field in manifest.fields) {
                                val valStr = variables[field.id].orEmpty()
                                if (field.required && valStr.isBlank()) {
                                    throw IllegalArgumentException("Champ requis manquant : ${field.label}")
                                }
                                if (field.type == UiFieldType.NUMBER && valStr.isNotBlank()) {
                                    requireNotNull(valStr.replace(",", ".").toDoubleOrNull()) {
                                        "Le champ « ${field.label} » doit contenir un nombre valide."
                                    }
                                }
                            }
                            variables[step.outputVariable] = "OK"
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Entrées validées"
                                )
                            )
                        }

                        WorkflowStepType.SANDBOX_EXPRESSION -> {
                            val numericVars = buildNumericVariableMap(variables)
                            val value = SafeExpressionEvaluator.evaluate(step.expression, numericVars)
                            val formatted = formatNumber(value)
                            variables[step.outputVariable] = formatted
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Résultat calculé : $formatted"
                                )
                            )
                        }

                        WorkflowStepType.TEMPLATE_FORMAT, WorkflowStepType.SET_OUTPUT -> {
                            val interpolated = interpolateTemplate(step.template, variables)
                            variables[step.outputVariable] = interpolated
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Formatage terminé (${interpolated.length} car.)"
                                )
                            )
                        }

                        WorkflowStepType.AI_PROMPT -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.AI_INFERENCE)
                            val interpolatedUserPrompt = interpolateTemplate(step.template, variables)
                                .ifBlank {
                                    variables.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                                }
                            val interpolatedSystem = interpolateTemplate(step.systemPrompt, variables)
                            val attachedImage = step.includeImageFieldId
                                .takeIf { it.isNotBlank() }
                                ?.let { imageAttachments[it] }

                            val aiResult = apiRouterService.executeWithFailover(
                                request = AiRequest(
                                    systemPrompt = interpolatedSystem,
                                    userPrompt = interpolatedUserPrompt,
                                    preferredModel = step.preferredModel,
                                    temperature = step.temperature.coerceIn(0.0, 1.5),
                                    imageBase64 = attachedImage?.first,
                                    imageMimeType = attachedImage?.second ?: "image/jpeg"
                                ),
                                strategy = strategy
                            ).getOrThrow()

                            providerUsed = "${aiResult.providerName} • ${aiResult.modelUsed}"
                            variables[step.outputVariable] = aiResult.content
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "$providerUsed (${aiResult.latencyMs} ms)"
                                )
                            )
                        }

                        WorkflowStepType.GENERATE_PDF -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.PDF_EXPORT)
                            val bodyContent = if (step.template.isNotBlank()) {
                                interpolateTemplate(step.template, variables)
                            } else {
                                variables["result"] ?: variables.values.lastOrNull().orEmpty()
                            }
                            require(bodyContent.isNotBlank()) {
                                "Contenu PDF vide : rien à exporter."
                            }
                            val artifact = pdfGenerator.generatePdf(
                                title = manifest.name,
                                subtitle = "Version ${manifest.version} • ${manifest.author}",
                                body = bodyContent
                            ).getOrThrow()
                            generatedPdf = artifact
                            variables[step.outputVariable] =
                                "PDF généré : ${artifact.fileName} (${artifact.pageCount} page(s), ${artifact.byteSize / 1024} Ko)"
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    artifact.fileName
                                )
                            )
                        }

                        WorkflowStepType.TOOL_STORAGE_SAVE -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.LOCAL_STORAGE)
                            val valueToSave = if (step.template.isNotBlank()) {
                                interpolateTemplate(step.template, variables)
                            } else {
                                variables["result"].orEmpty()
                            }
                            toolRepository.writeSandboxedStorage(manifest.id, step.storageKey, valueToSave)
                            variables[step.outputVariable] = valueToSave
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Enregistré dans l'espace isolé '${step.storageKey}'"
                                )
                            )
                        }

                        WorkflowStepType.TOOL_STORAGE_LOAD -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.LOCAL_STORAGE)
                            val loaded = toolRepository.readSandboxedStorage(manifest.id, step.storageKey).orEmpty()
                            variables[step.outputVariable] = loaded
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    if (loaded.isEmpty()) "Aucune donnée enregistrée" else "Chargé (${loaded.length} car.)"
                                )
                            )
                        }
                    }
                }
            }

            val totalDuration = System.currentTimeMillis() - startMs
            val outputMap = manifest.outputs.associate { out ->
                out.id to (variables[out.sourceVariable] ?: variables["result"].orEmpty())
            }

            toolRepository.recordExecution(
                toolId = manifest.id,
                toolVersion = manifest.version,
                success = true,
                durationMs = totalDuration,
                providerUsed = providerUsed,
                summary = outputMap.values.firstOrNull()?.take(160) ?: "Exécution réussie"
            )

            ToolExecutionResult(
                success = true,
                outputs = outputMap,
                pdfArtifact = generatedPdf,
                traces = traces,
                providerUsed = providerUsed,
                totalDurationMs = totalDuration
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startMs
            val msg = e.localizedMessage ?: e.message ?: "Erreur d'exécution dans le sandbox."
            runCatching {
                toolRepository.recordExecution(
                    toolId = manifest.id,
                    toolVersion = manifest.version,
                    success = false,
                    durationMs = totalDuration,
                    providerUsed = providerUsed,
                    summary = "Échec d'exécution",
                    errorMessage = msg
                )
            }
            ToolExecutionResult(
                success = false,
                outputs = emptyMap(),
                pdfArtifact = null,
                traces = traces,
                providerUsed = providerUsed,
                totalDurationMs = totalDuration,
                errorMessage = msg
            )
        }
    }

    private fun buildNumericVariableMap(variables: Map<String, String>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for ((k, v) in variables) {
            v.replace(",", ".").trim().toDoubleOrNull()?.let { result[k] = it }
        }
        return result
    }

    private fun interpolateTemplate(template: String, variables: Map<String, String>): String {
        if (template.isBlank()) return ""
        return PLACEHOLDER_REGEX.replace(template) { match ->
            val key = match.groupValues[1].trim()
            variables[key] ?: ""
        }
    }

    private fun formatNumber(value: Double): String {
        val df = DecimalFormat("#.######")
        return df.format(value)
    }

    private suspend fun readTextFromUri(uriString: String): String = withContext(Dispatchers.IO) {
        val ctx = context ?: throw IllegalStateException("Contexte Android requis pour lire un fichier.")
        val uri = Uri.parse(uriString)
        val raw = ctx.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("Impossible d'ouvrir le fichier sélectionné.")
        raw.take(SandboxGuard.MAX_INPUT_TEXT_LENGTH)
    }

    private suspend fun readImageBase64FromUri(uriString: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val ctx = context ?: throw IllegalStateException("Contexte Android requis pour lire une image.")
            val uri = Uri.parse(uriString)
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Impossible de lire l'image sélectionnée.")
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Format d'image non supporté.")
            val scaled = scaleBitmapIfNeeded(original, maxDimension = 1280)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            encoded to "image/jpeg"
        }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / maxSide.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    companion object {
        private val PLACEHOLDER_REGEX = Regex("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}")
    }
}
