package com.example.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.core.ai.AiMessage
import com.example.core.ai.AiRequest
import com.example.core.ai.ApiRouterService
import com.example.core.ai.LoadBalancingStrategy
import com.example.core.ai.NetworkChecker
import com.example.core.data.repository.ToolRepository
import com.example.core.security.SafeExpressionEvaluator
import com.example.core.security.SandboxGuard
import com.example.domain.manifest.ManifestValidator
import com.example.domain.manifest.ToolManifest
import com.example.domain.manifest.ToolPermission
import com.example.domain.manifest.ToolStatus
import com.example.domain.manifest.UiFieldType
import com.example.domain.manifest.WorkflowStepSpec
import com.example.domain.manifest.WorkflowStepType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DecimalFormat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    val errorMessage: String? = null,
    val speakableText: String? = null
)

/**
 * Sandboxed plugin runtime executing declarative [ToolManifest] workflows inside strict
 * permission, timeout, input-size, SSRF, tool-call depth, and storage isolation boundaries.
 */
class SandboxedToolRuntime(
    private val context: Context?,
    private val toolRepository: ToolRepository,
    private val apiRouterService: ApiRouterService,
    private val pdfGenerator: PdfGenerator,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val networkChecker: NetworkChecker? = null
) {

    suspend fun execute(
        manifest: ToolManifest,
        rawInputs: Map<String, String>,
        strategy: LoadBalancingStrategy = LoadBalancingStrategy.PRIORITY_AND_ROTATION,
        globalTimeoutMs: Long = SandboxGuard.DEFAULT_EXECUTION_TIMEOUT_MS,
        conversationHistory: List<AiMessage> = emptyList(),
        callDepth: Int = 0,
        visitedToolIds: Set<String> = emptySet()
    ): ToolExecutionResult {
        val startMs = System.currentTimeMillis()
        val traces = mutableListOf<StepExecutionTrace>()
        var providerUsed = "Local Sandbox"
        var generatedPdf: GeneratedPdfArtifact? = null
        var speakableText: String? = null

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
                            validateFieldValues(manifest, variables)
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

                        WorkflowStepType.CONDITION_BRANCH -> {
                            val conditionMet = evaluateCondition(step, variables)
                            val chosenTemplate = if (conditionMet) {
                                step.thenTemplate.ifBlank { "true" }
                            } else {
                                step.elseTemplate.ifBlank { "false" }
                            }
                            val resolved = interpolateTemplate(chosenTemplate, variables)
                            variables[step.outputVariable] = resolved
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    if (conditionMet) "Condition vraie" else "Condition fausse"
                                )
                            )
                        }

                        WorkflowStepType.TEXT_TRANSFORM -> {
                            val transformed = executeTextTransform(step, variables)
                            variables[step.outputVariable] = transformed
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Opération ${step.operation.uppercase(Locale.ROOT)} (${transformed.length} car.)"
                                )
                            )
                        }

                        WorkflowStepType.DATE_TIME -> {
                            val dateResult = executeDateTime(step, variables)
                            variables[step.outputVariable] = dateResult
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    dateResult
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
                                    imageMimeType = attachedImage?.second ?: "image/jpeg",
                                    conversationHistory = conversationHistory
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

                        WorkflowStepType.HTTP_REQUEST -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.NETWORK_FETCH)
                            val httpOut = executeHttpRequest(step, variables)
                            variables[step.outputVariable] = httpOut
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "HTTPS ${step.httpMethod.uppercase(Locale.ROOT).ifBlank { "GET" }} OK (${httpOut.length} car.)"
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

                        WorkflowStepType.LIST_STORAGE_APPEND -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.LOCAL_STORAGE)
                            val updatedArrayJson = appendListStorage(manifest.id, step, variables)
                            variables[step.outputVariable] = updatedArrayJson
                            val count = runCatching { JSONArray(updatedArrayJson).length() }.getOrDefault(0)
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "$count élément(s) dans '${step.storageKey}'"
                                )
                            )
                        }

                        WorkflowStepType.LIST_STORAGE_READ -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.LOCAL_STORAGE)
                            val resultText = readListStorage(manifest.id, step)
                            variables[step.outputVariable] = resultText
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Lecture liste '${step.storageKey}'"
                                )
                            )
                        }

                        WorkflowStepType.SPEAK_TEXT -> {
                            SandboxGuard.requirePermission(manifest, ToolPermission.TEXT_TO_SPEECH)
                            val toSpeak = if (step.template.isNotBlank()) {
                                interpolateTemplate(step.template, variables)
                            } else {
                                variables["result"] ?: variables.values.lastOrNull().orEmpty()
                            }
                            variables[step.outputVariable] = toSpeak
                            speakableText = toSpeak
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Synthèse vocale prête (${toSpeak.length} car.)"
                                )
                            )
                        }

                        WorkflowStepType.CALL_TOOL -> {
                            require(callDepth < SandboxGuard.MAX_TOOL_CALL_DEPTH) {
                                "Profondeur d'appel inter-outils dépassée (max ${SandboxGuard.MAX_TOOL_CALL_DEPTH})."
                            }
                            val nextVisited = visitedToolIds + manifest.id
                            require(step.targetToolId !in nextVisited) {
                                "Cycle d'appel détecté vers l'outil '${step.targetToolId}'."
                            }
                            val childTool = toolRepository.getTool(step.targetToolId)
                                ?: throw IllegalStateException("Outil cible introuvable : '${step.targetToolId}'.")

                            val childInputs = parseChildToolInputs(step.template, variables, childTool.manifest)
                            val childResult = execute(
                                manifest = childTool.manifest,
                                rawInputs = childInputs,
                                strategy = strategy,
                                globalTimeoutMs = effectiveTimeout,
                                conversationHistory = emptyList(),
                                callDepth = callDepth + 1,
                                visitedToolIds = nextVisited
                            )
                            if (!childResult.success) {
                                throw IllegalStateException(
                                    "Échec sous-outil '${childTool.manifest.name}' : ${childResult.errorMessage}"
                                )
                            }
                            val combinedOut = childResult.outputs.values.firstOrNull().orEmpty()
                            variables[step.outputVariable] = combinedOut
                            traces.add(
                                StepExecutionTrace(
                                    step.title,
                                    step.type,
                                    System.currentTimeMillis() - stepStart,
                                    "Sous-outil '${childTool.manifest.name}' exécuté"
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

            if (callDepth == 0) {
                toolRepository.recordExecution(
                    toolId = manifest.id,
                    toolVersion = manifest.version,
                    success = true,
                    durationMs = totalDuration,
                    providerUsed = providerUsed,
                    summary = outputMap.values.firstOrNull()?.take(160) ?: "Exécution réussie"
                )
            }

            ToolExecutionResult(
                success = true,
                outputs = outputMap,
                pdfArtifact = generatedPdf,
                traces = traces,
                providerUsed = providerUsed,
                totalDurationMs = totalDuration,
                speakableText = speakableText
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startMs
            val msg = e.localizedMessage ?: e.message ?: "Erreur d'exécution dans le sandbox."
            if (callDepth == 0) {
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

    /**
     * In-memory QA sandbox dry-run used by the autonomous pipeline to verify formulas,
     * templates, conditions, and text/date transforms without persisting any fake logs or storage.
     */
    suspend fun dryRunDeterministic(
        manifest: ToolManifest,
        sampleInputs: Map<String, String>
    ): Result<Unit> = runCatching {
        ManifestValidator.validate(manifest).getOrThrow()
        val variables = mutableMapOf<String, String>()
        for (field in manifest.fields) {
            variables[field.id] = sampleInputs[field.id] ?: when (field.type) {
                UiFieldType.NUMBER, UiFieldType.SLIDER -> {
                    val mid = (field.minValue + field.maxValue) / 2.0
                    if (mid == 0.0) "10" else formatNumber(mid)
                }
                UiFieldType.SELECT -> field.options.firstOrNull() ?: "Option"
                UiFieldType.TOGGLE -> "true"
                UiFieldType.FILE_DOCUMENT -> "Contenu exemple pour test QA."
                UiFieldType.IMAGE_INPUT -> "[Image test]"
                else -> field.defaultValue.ifBlank { "Test" }
            }
        }

        for (step in manifest.workflow.take(SandboxGuard.MAX_WORKFLOW_STEPS)) {
            when (step.type) {
                WorkflowStepType.VALIDATE_INPUT -> validateFieldValues(manifest, variables)
                WorkflowStepType.SANDBOX_EXPRESSION -> {
                    val numVars = buildNumericVariableMap(variables)
                    val value = SafeExpressionEvaluator.evaluate(step.expression, numVars)
                    variables[step.outputVariable] = formatNumber(value)
                }
                WorkflowStepType.TEMPLATE_FORMAT, WorkflowStepType.SET_OUTPUT -> {
                    variables[step.outputVariable] = interpolateTemplate(step.template, variables)
                }
                WorkflowStepType.CONDITION_BRANCH -> {
                    val met = evaluateCondition(step, variables)
                    val tmpl = if (met) step.thenTemplate.ifBlank { "true" } else step.elseTemplate.ifBlank { "false" }
                    variables[step.outputVariable] = interpolateTemplate(tmpl, variables)
                }
                WorkflowStepType.TEXT_TRANSFORM -> {
                    variables[step.outputVariable] = executeTextTransform(step, variables)
                }
                WorkflowStepType.DATE_TIME -> {
                    variables[step.outputVariable] = executeDateTime(step, variables)
                }
                WorkflowStepType.SPEAK_TEXT -> {
                    variables[step.outputVariable] = interpolateTemplate(step.template, variables)
                }
                WorkflowStepType.AI_PROMPT,
                WorkflowStepType.HTTP_REQUEST,
                WorkflowStepType.GENERATE_PDF,
                WorkflowStepType.TOOL_STORAGE_SAVE,
                WorkflowStepType.TOOL_STORAGE_LOAD,
                WorkflowStepType.LIST_STORAGE_APPEND,
                WorkflowStepType.LIST_STORAGE_READ,
                WorkflowStepType.CALL_TOOL -> {
                    // Non-persistent synthetic placeholder so downstream deterministic steps can resolve variables
                    variables[step.outputVariable] = variables[step.outputVariable] ?: "1"
                }
            }
        }
    }

    private fun validateFieldValues(manifest: ToolManifest, variables: Map<String, String>) {
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
    }

    private fun evaluateCondition(step: WorkflowStepSpec, variables: Map<String, String>): Boolean {
        if (step.expression.isNotBlank()) {
            val numericVars = buildNumericVariableMap(variables)
            return abs(SafeExpressionEvaluator.evaluate(step.expression, numericVars)) > 1e-9
        }
        val raw = interpolateTemplate(step.template, variables).trim()
        return when {
            "==" in raw -> {
                val parts = raw.split("==", limit = 2)
                parts[0].trim().equals(parts[1].trim(), ignoreCase = true)
            }
            "!=" in raw -> {
                val parts = raw.split("!=", limit = 2)
                !parts[0].trim().equals(parts[1].trim(), ignoreCase = true)
            }
            " contains " in raw -> {
                val parts = raw.split(" contains ", ignoreCase = true, limit = 2)
                parts[0].trim().contains(parts[1].trim(), ignoreCase = true)
            }
            else -> raw.isNotBlank() && !raw.equals("false", ignoreCase = true) && raw != "0"
        }
    }

    private fun executeTextTransform(step: WorkflowStepSpec, variables: Map<String, String>): String {
        val input = if (step.template.isNotBlank()) {
            interpolateTemplate(step.template, variables)
        } else {
            variables.values.lastOrNull().orEmpty()
        }
        val op = step.operation.trim().uppercase(Locale.ROOT)
        val param = interpolateTemplate(step.operationParam, variables).trim()

        return when (op) {
            "UPPERCASE" -> input.uppercase(Locale.ROOT)
            "LOWERCASE" -> input.lowercase(Locale.ROOT)
            "TRIM" -> input.trim()
            "WORD_COUNT" -> input.trim().split(Regex("\\s+")).count { it.isNotBlank() }.toString()
            "CHAR_COUNT" -> input.length.toString()
            "LINE_COUNT" -> if (input.isEmpty()) "0" else input.lines().size.toString()
            "SLUGIFY" -> {
                val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                normalized.lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
            }
            "BASE64_ENCODE" -> Base64.encodeToString(input.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "BASE64_DECODE" -> String(Base64.decode(input.trim(), Base64.DEFAULT), Charsets.UTF_8)
            "SHA256" -> {
                val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                digest.joinToString("") { "%02x".format(it) }
            }
            "UUID" -> UUID.randomUUID().toString()
            "JSON_PRETTY" -> {
                val trimmed = input.trim()
                if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2)
                else JSONObject(trimmed).toString(2)
            }
            "JSON_EXTRACT" -> extractJsonPath(input, param)
            "CSV_TO_TABLE" -> csvToMarkdownTable(input)
            "REGEX_EXTRACT" -> {
                require(param.isNotBlank()) { "REGEX_EXTRACT requiert operationParam (motif regex)." }
                val regex = Regex(param)
                regex.findAll(input).map { it.value }.joinToString("\n")
            }
            "PASSWORD_GEN" -> {
                val length = (param.toIntOrNull() ?: input.trim().toIntOrNull() ?: 16).coerceIn(8, 128)
                val charset = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%&*-_+"
                val rnd = SecureRandom()
                (0 until length).map { charset[rnd.nextInt(charset.length)] }.joinToString("")
            }
            else -> throw IllegalArgumentException("Opération TEXT_TRANSFORM inconnue : '$op'")
        }
    }

    private fun executeDateTime(step: WorkflowStepSpec, variables: Map<String, String>): String {
        val op = step.operation.trim().uppercase(Locale.ROOT)
        val input = interpolateTemplate(step.template, variables).trim()
        val param = interpolateTemplate(step.operationParam, variables).trim()
        val isoDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        return when (op) {
            "NOW_FR" -> SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())
            "NOW_ISO" -> SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            "TIMESTAMP_MS" -> System.currentTimeMillis().toString()
            "ADD_DAYS" -> {
                val baseDate = if (input.isNotBlank()) {
                    isoDateFmt.parse(input) ?: throw IllegalArgumentException("Date attendue au format AAAA-MM-JJ.")
                } else {
                    Date()
                }
                val days = param.toIntOrNull() ?: 0
                val cal = Calendar.getInstance().apply {
                    time = baseDate
                    add(Calendar.DAY_OF_YEAR, days)
                }
                isoDateFmt.format(cal.time)
            }
            "DAYS_BETWEEN" -> {
                val parts = input.split(',', '\n', ';').map { it.trim() }.filter { it.isNotBlank() }
                require(parts.size >= 2) { "Fournissez deux dates (AAAA-MM-JJ) séparées par une virgule." }
                val d1 = isoDateFmt.parse(parts[0]) ?: throw IllegalArgumentException("Date 1 invalide (${parts[0]}).")
                val d2 = isoDateFmt.parse(parts[1]) ?: throw IllegalArgumentException("Date 2 invalide (${parts[1]}).")
                val diffDays = TimeUnit.MILLISECONDS.toDays(d2.time - d1.time)
                diffDays.toString()
            }
            else -> throw IllegalArgumentException("Opération DATE_TIME inconnue : '$op'")
        }
    }

    private suspend fun executeHttpRequest(step: WorkflowStepSpec, variables: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            if (networkChecker != null && !networkChecker.isOnline()) {
                throw IllegalStateException("Hors ligne : connexion Internet requise pour cette requête.")
            }
            val rawUrl = interpolateTemplate(step.urlTemplate, variables).trim()
            val safeUrl = SandboxGuard.validateHttpsEndpoint(rawUrl).getOrThrow()
            val method = step.httpMethod.trim().uppercase(Locale.ROOT).ifBlank { "GET" }

            val builder = Request.Builder().url(safeUrl)
            if (step.requestHeadersJson.isNotBlank()) {
                val headersObj = JSONObject(interpolateTemplate(step.requestHeadersJson, variables))
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val valStr = headersObj.optString(key)
                    if (key.isNotBlank() && valStr.isNotBlank()) {
                        builder.addHeader(key, valStr)
                    }
                }
            }

            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                "POST" -> {
                    val bodyText = interpolateTemplate(step.template, variables).ifBlank { "{}" }
                    builder.post(bodyText.toRequestBody(JSON_MEDIA_TYPE))
                }
                "PUT" -> {
                    val bodyText = interpolateTemplate(step.template, variables).ifBlank { "{}" }
                    builder.put(bodyText.toRequestBody(JSON_MEDIA_TYPE))
                }
                else -> throw IllegalArgumentException("Méthode HTTP non supportée : $method")
            }

            httpClient.newCall(builder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty().take(SandboxGuard.MAX_INPUT_TEXT_LENGTH)
                if (!response.isSuccessful) {
                    throw IllegalStateException("Erreur HTTP ${response.code} depuis $safeUrl")
                }
                if (step.operationParam.isNotBlank()) {
                    extractJsonPath(rawBody, step.operationParam.trim())
                } else {
                    rawBody
                }
            }
        }

    private suspend fun appendListStorage(
        toolId: String,
        step: WorkflowStepSpec,
        variables: Map<String, String>
    ): String {
        val existingRaw = toolRepository.readSandboxedStorage(toolId, step.storageKey).orEmpty()
        val array = runCatching {
            if (existingRaw.isBlank()) JSONArray() else JSONArray(existingRaw)
        }.getOrElse { JSONArray() }

        val entryRaw = interpolateTemplate(step.template, variables).trim()
        val nowFr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())
        val itemJson: Any = if (entryRaw.startsWith("{") && entryRaw.endsWith("}")) {
            runCatching {
                JSONObject(entryRaw).apply {
                    if (!has("timestamp")) put("timestamp", nowFr)
                }
            }.getOrElse {
                JSONObject().apply {
                    put("value", entryRaw)
                    put("timestamp", nowFr)
                }
            }
        } else {
            JSONObject().apply {
                put("value", entryRaw)
                put("timestamp", nowFr)
            }
        }
        array.put(itemJson)

        val trimmedArray = JSONArray()
        val startIdx = (array.length() - 200).coerceAtLeast(0)
        for (i in startIdx until array.length()) {
            trimmedArray.put(array.get(i))
        }
        var serialized = trimmedArray.toString()
        while (serialized.length > SandboxGuard.MAX_STORAGE_VALUE_LENGTH && trimmedArray.length() > 1) {
            val reduced = JSONArray()
            for (i in 1 until trimmedArray.length()) {
                reduced.put(trimmedArray.get(i))
            }
            serialized = reduced.toString()
            if (serialized.length <= SandboxGuard.MAX_STORAGE_VALUE_LENGTH) {
                toolRepository.writeSandboxedStorage(toolId, step.storageKey, serialized)
                return serialized
            }
        }
        toolRepository.writeSandboxedStorage(toolId, step.storageKey, serialized)
        return serialized
    }

    private suspend fun readListStorage(toolId: String, step: WorkflowStepSpec): String {
        val raw = toolRepository.readSandboxedStorage(toolId, step.storageKey).orEmpty().ifBlank { "[]" }
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return when (step.operation.trim().uppercase(Locale.ROOT)) {
            "COUNT" -> array.length().toString()
            "SUM" -> {
                val field = step.operationParam.trim()
                var sum = 0.0
                for (i in 0 until array.length()) {
                    val elem = array.opt(i)
                    val num = when (elem) {
                        is Number -> elem.toDouble()
                        is JSONObject -> {
                            val targetKey = field.ifBlank { "value" }
                            elem.optString(targetKey).replace(",", ".").toDoubleOrNull() ?: 0.0
                        }
                        else -> elem?.toString()?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                    }
                    sum += num
                }
                formatNumber(sum)
            }
            else -> array.toString(2)
        }
    }

    private fun extractJsonPath(rawJson: String, path: String): String {
        if (path.isBlank()) return rawJson
        var current: Any? = runCatching {
            val trimmed = rawJson.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        }.getOrElse {
            throw IllegalArgumentException("JSON invalide pour l'extraction '$path'.")
        }
        for (segment in path.split('.').map { it.trim() }.filter { it.isNotBlank() }) {
            current = when (current) {
                is JSONObject -> current.opt(segment)
                is JSONArray -> {
                    val idx = segment.toIntOrNull()
                        ?: throw IllegalArgumentException("Index tableau JSON attendu pour '$segment'.")
                    current.opt(idx)
                }
                else -> null
            } ?: return ""
        }
        return current.toString()
    }

    private fun csvToMarkdownTable(csv: String): String {
        val lines = csv.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        val separator = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val rows = lines.map { line -> line.split(separator).map { it.trim() } }
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val header = rows.first().let { it + List((cols - it.size).coerceAtLeast(0)) { "" } }
        val sb = StringBuilder()
        sb.append("| ").append(header.joinToString(" | ")).append(" |\n")
        sb.append("| ").append(List(cols) { "---" }.joinToString(" | ")).append(" |\n")
        rows.drop(1).forEach { row ->
            val padded = row + List((cols - row.size).coerceAtLeast(0)) { "" }
            sb.append("| ").append(padded.joinToString(" | ")).append(" |\n")
        }
        return sb.toString().trim()
    }

    private fun parseChildToolInputs(
        template: String,
        variables: Map<String, String>,
        childManifest: ToolManifest
    ): Map<String, String> {
        val interpolated = interpolateTemplate(template, variables).trim()
        if (interpolated.startsWith("{") && interpolated.endsWith("}")) {
            val parsed = runCatching {
                val obj = JSONObject(interpolated)
                val map = mutableMapOf<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = obj.optString(k)
                }
                map
            }.getOrNull()
            if (parsed != null) return parsed
        }
        val firstFieldId = childManifest.fields.firstOrNull()?.id ?: return emptyMap()
        return mapOf(firstFieldId to interpolated)
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
