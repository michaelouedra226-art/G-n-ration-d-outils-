package com.example.core.ai

import com.example.domain.manifest.ManifestValidator
import com.example.domain.manifest.ToolManifest
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Specialized AI agents collaborating before generating a ForgeAI plugin.
 */
enum class ForgeAgentRole(
    val title: String,
    val badge: String,
    val focus: String
) {
    ARCHITECT("Architecte Logiciel", "ARCH", "Analyse des besoins, capacités, permissions et stockage"),
    UX_DESIGNER("UX Designer", "UX", "Conception du formulaire minimaliste et des vues de sortie"),
    DEVELOPER("Développeur Plugin", "DEV", "Compilation du manifeste déclaratif et du workflow"),
    AI_EXPERT("Expert IA", "IA", "Optimisation des prompts système, modèles et températures"),
    TESTER("Testeur QA", "QA", "Validation structurelle du schéma et des expressions"),
    CORRECTOR("Correcteur Sécurité", "SEC", "Durcissement sandbox et principe du moindre privilège"),
    OPTIMIZER("Optimiseur", "OPT", "Performance, timeouts et réduction des étapes redondantes"),
    DOCUMENTATION("Agent Documentation", "DOC", "Rédaction de la documentation et du changelog initial")
}

enum class AgentStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

data class AgentLogItem(
    val role: ForgeAgentRole,
    val status: AgentStepStatus,
    val summary: String
)

sealed interface ForgePipelineEvent {
    data class Progress(
        val activeRole: ForgeAgentRole,
        val logs: List<AgentLogItem>,
        val providerInfo: String
    ) : ForgePipelineEvent

    data class Success(
        val manifest: ToolManifest,
        val logs: List<AgentLogItem>,
        val providerInfo: String,
        val totalLatencyMs: Long
    ) : ForgePipelineEvent

    data class Failure(
        val errorMessage: String,
        val isMissingProviderOrFirebase: Boolean,
        val logs: List<AgentLogItem>
    ) : ForgePipelineEvent
}

/**
 * Multi-agent orchestrator coordinating 8 specialized roles to transform a natural language prompt
 * into a validated, sandbox-hardened [ToolManifest].
 */
class MultiAgentForgePipeline(
    private val apiRouterService: ApiRouterService,
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
) {
    private val manifestAdapter = moshi.adapter(ToolManifest::class.java)

    fun forgeTool(
        userPrompt: String,
        authorName: String,
        strategy: LoadBalancingStrategy
    ): Flow<ForgePipelineEvent> = flow {
        val cleanPrompt = userPrompt.trim()
        val startMs = System.currentTimeMillis()
        val logs = ForgeAgentRole.entries.map {
            AgentLogItem(it, AgentStepStatus.PENDING, "En attente")
        }.toMutableList()

        fun updateLog(role: ForgeAgentRole, status: AgentStepStatus, summary: String) {
            val idx = logs.indexOfFirst { it.role == role }
            if (idx >= 0) {
                logs[idx] = AgentLogItem(role, status, summary)
            }
        }

        // Phase 1: Architect + UX Designer
        updateLog(ForgeAgentRole.ARCHITECT, AgentStepStatus.RUNNING, "Analyse fonctionnelle de la demande...")
        updateLog(ForgeAgentRole.UX_DESIGNER, AgentStepStatus.RUNNING, "Conception de l'interface dynamique...")
        emit(ForgePipelineEvent.Progress(ForgeAgentRole.ARCHITECT, logs.toList(), "Routage IA..."))

        val blueprintResponse = apiRouterService.executeWithFailover(
            request = AiRequest(
                systemPrompt = ARCHITECT_UX_SYSTEM_PROMPT,
                userPrompt = "Demande utilisateur : $cleanPrompt\n" +
                    "Produis un plan technique concis (max 12 lignes) précisant : catégorie, permissions strictement nécessaires, champs de saisie, étapes de workflow et format de sortie.",
                temperature = 0.25,
                maxTokens = 700
            ),
            strategy = strategy
        )

        if (blueprintResponse.isFailure) {
            val err = blueprintResponse.exceptionOrNull()
            val gwErr = err as? AiGatewayException
            updateLog(ForgeAgentRole.ARCHITECT, AgentStepStatus.FAILED, err?.message ?: "Échec IA")
            updateLog(ForgeAgentRole.UX_DESIGNER, AgentStepStatus.FAILED, "Interrompu")
            emit(
                ForgePipelineEvent.Failure(
                    errorMessage = err?.message ?: "Impossible de contacter un fournisseur IA.",
                    isMissingProviderOrFirebase = gwErr?.kind == AiErrorKind.NO_PROVIDER_CONFIGURED ||
                        gwErr?.kind == AiErrorKind.FIREBASE_REQUIRED,
                    logs = logs.toList()
                )
            )
            return@flow
        }

        val bp = blueprintResponse.getOrThrow()
        val providerLabel = "${bp.providerName} (${bp.modelUsed} • ${bp.keyLabel})"
        updateLog(ForgeAgentRole.ARCHITECT, AgentStepStatus.COMPLETED, "Architecture et permissions définies")
        updateLog(ForgeAgentRole.UX_DESIGNER, AgentStepStatus.COMPLETED, "Schéma UX structuré")

        // Phase 2: Developer + AI Expert -> Generate ToolManifest JSON
        updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.RUNNING, "Génération du manifeste JSON déclaratif...")
        updateLog(ForgeAgentRole.AI_EXPERT, AgentStepStatus.RUNNING, "Calibration des prompts et paramètres IA...")
        emit(ForgePipelineEvent.Progress(ForgeAgentRole.DEVELOPER, logs.toList(), providerLabel))

        val generatedToolId = "tool_${UUID.randomUUID().toString().take(8)}"
        val manifestGenResponse = apiRouterService.executeWithFailover(
            request = AiRequest(
                systemPrompt = MANIFEST_GENERATOR_SYSTEM_PROMPT,
                userPrompt = """
                    Demande utilisateur : $cleanPrompt
                    Plan Architecte & UX :
                    ${bp.content}

                    Paramètres imposés :
                    - id : "$generatedToolId"
                    - author : "$authorName"
                    - version : "1.0.0"
                    - status : "ACTIVE"

                    Réponds UNIQUEMENT par un objet JSON valide conforme au schéma ToolManifest, sans bloc markdown.
                """.trimIndent(),
                temperature = 0.2,
                maxTokens = 2200,
                jsonMode = true
            ),
            strategy = strategy
        )

        if (manifestGenResponse.isFailure) {
            val err = manifestGenResponse.exceptionOrNull()
            updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.FAILED, err?.message ?: "Échec génération")
            updateLog(ForgeAgentRole.AI_EXPERT, AgentStepStatus.FAILED, "Interrompu")
            emit(
                ForgePipelineEvent.Failure(
                    errorMessage = err?.message ?: "Échec lors de la génération du manifeste.",
                    isMissingProviderOrFirebase = false,
                    logs = logs.toList()
                )
            )
            return@flow
        }

        val rawJson = stripOptionalCodeFences(manifestGenResponse.getOrThrow().content)
        val parsedManifest = runCatching {
            manifestAdapter.fromJson(rawJson)
        }.getOrNull()

        if (parsedManifest == null) {
            updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.FAILED, "JSON malformé produit par le modèle")
            emit(
                ForgePipelineEvent.Failure(
                    errorMessage = "Le modèle a renvoyé un JSON non conforme au schéma ToolManifest.",
                    isMissingProviderOrFirebase = false,
                    logs = logs.toList()
                )
            )
            return@flow
        }

        val normalizedManifest = parsedManifest.copy(
            id = generatedToolId,
            author = authorName,
            version = "1.0.0"
        )
        updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.COMPLETED, "Manifeste généré (${normalizedManifest.workflow.size} étapes)")
        updateLog(ForgeAgentRole.AI_EXPERT, AgentStepStatus.COMPLETED, "Prompts système configurés")

        // Phase 3: Tester + Corrector + Optimizer + Documentation
        updateLog(ForgeAgentRole.TESTER, AgentStepStatus.RUNNING, "Vérification du schéma et des expressions...")
        updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.RUNNING, "Audit sécurité sandbox...")
        emit(ForgePipelineEvent.Progress(ForgeAgentRole.TESTER, logs.toList(), providerLabel))

        val validationResult = ManifestValidator.validate(normalizedManifest)
        val finalManifest = if (validationResult.isSuccess) {
            updateLog(ForgeAgentRole.TESTER, AgentStepStatus.COMPLETED, "Tous les contrôles structurels validés")
            updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.COMPLETED, "Permissions et templates vérifiés")
            validationResult.getOrThrow()
        } else {
            // Corrector attempts auto-repair via AI if validator flagged an issue
            val validationError = validationResult.exceptionOrNull()?.message ?: "Erreur de validation"
            updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.RUNNING, "Correction automatique : $validationError")
            emit(ForgePipelineEvent.Progress(ForgeAgentRole.CORRECTOR, logs.toList(), providerLabel))

            val repairResp = apiRouterService.executeWithFailover(
                request = AiRequest(
                    systemPrompt = MANIFEST_GENERATOR_SYSTEM_PROMPT,
                    userPrompt = "Corrige ce JSON ToolManifest qui a échoué la validation avec l'erreur : $validationError\nJSON:\n$rawJson",
                    temperature = 0.1,
                    maxTokens = 2200,
                    jsonMode = true
                ),
                strategy = strategy
            )
            val repairedRaw = repairResp.getOrNull()?.content?.let(::stripOptionalCodeFences)
            val repaired = repairedRaw?.let { runCatching { manifestAdapter.fromJson(it) }.getOrNull() }
                ?.copy(id = generatedToolId, author = authorName, version = "1.0.0")
            val revalidated = repaired?.let { ManifestValidator.validate(it) }

            if (revalidated == null || revalidated.isFailure) {
                val msg = revalidated?.exceptionOrNull()?.message ?: validationError
                updateLog(ForgeAgentRole.TESTER, AgentStepStatus.FAILED, msg)
                updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.FAILED, "Correction impossible")
                emit(
                    ForgePipelineEvent.Failure(
                        errorMessage = "Validation sandbox échouée : $msg",
                        isMissingProviderOrFirebase = false,
                        logs = logs.toList()
                    )
                )
                return@flow
            }
            updateLog(ForgeAgentRole.TESTER, AgentStepStatus.COMPLETED, "Re-validation QA réussie")
            updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.COMPLETED, "Anomalie corrigée automatiquement")
            revalidated.getOrThrow()
        }

        // Optimizer + Documentation deterministic pass
        updateLog(ForgeAgentRole.OPTIMIZER, AgentStepStatus.RUNNING, "Calibration timeout et bornes mémoire...")
        val optimizedManifest = finalManifest.copy(
            timeoutMs = finalManifest.timeoutMs.coerceIn(15_000L, 60_000L)
        )
        updateLog(ForgeAgentRole.OPTIMIZER, AgentStepStatus.COMPLETED, "Timeout borné à ${optimizedManifest.timeoutMs / 1000}s")

        val docSummary = optimizedManifest.documentation.ifBlank {
            "Plugin généré par ForgeAI (${optimizedManifest.category.label}). Permissions : ${
                optimizedManifest.permissions.joinToString { it.label }.ifBlank { "Aucune" }
            }."
        }
        val documentedManifest = optimizedManifest.copy(documentation = docSummary)
        updateLog(ForgeAgentRole.DOCUMENTATION, AgentStepStatus.COMPLETED, "Documentation v${documentedManifest.version} prête")

        emit(
            ForgePipelineEvent.Success(
                manifest = documentedManifest,
                logs = logs.toList(),
                providerInfo = providerLabel,
                totalLatencyMs = System.currentTimeMillis() - startMs
            )
        )
    }

    private fun stripOptionalCodeFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstNewLine = trimmed.indexOf('\n')
        val lastFence = trimmed.lastIndexOf("```")
        return if (firstNewLine > 0 && lastFence > firstNewLine) {
            trimmed.substring(firstNewLine + 1, lastFence).trim()
        } else {
            trimmed
        }
    }

    companion object {
        private const val ARCHITECT_UX_SYSTEM_PROMPT = """
            Tu es une équipe composée d'un Architecte Logiciel et d'un UX Designer senior.
            Tu conçois des plugins sécurisés et déclaratifs pour l'application Android ForgeAI.
            Tu réponds en français de manière concise, structurée et exploitable techniquement.
        """

        private const val MANIFEST_GENERATOR_SYSTEM_PROMPT = """
            Tu es le compilateur déclaratif ForgeAI. Tu génères exclusivement du JSON valide conforme au schéma ToolManifest suivant :
            {
              "id": "string",
              "name": "string (max 64 chars)",
              "description": "string (max 400 chars)",
              "version": "1.0.0",
              "author": "string",
              "iconName": "auto_awesome | description | calculate | translate | summarize | image | code | chat | folder | tune",
              "category": "DOCUMENT | AI_ASSISTANT | CONVERTER | CALCULATOR | PRODUCTIVITY | VISION_OCR | DEVELOPER | CUSTOM",
              "status": "ACTIVE",
              "permissions": ["AI_INFERENCE", "LOCAL_STORAGE", "PDF_EXPORT", "FILE_PICKER", "IMAGE_PICKER", "CLIPBOARD"],
              "fields": [
                {
                  "id": "camelCaseOrSnakeCaseId",
                  "label": "Libellé utilisateur",
                  "type": "TEXT | MULTILINE_TEXT | NUMBER | SLIDER | SELECT | TOGGLE | FILE_DOCUMENT | IMAGE_INPUT",
                  "placeholder": "string",
                  "required": true,
                  "defaultValue": "string",
                  "options": ["Option 1"],
                  "minValue": 0.0,
                  "maxValue": 100.0
                }
              ],
              "outputs": [
                {
                  "id": "out_main",
                  "label": "Résultat",
                  "type": "TEXT_OUTPUT | MARKDOWN_VIEW | CALCULATION_RESULT | KEY_VALUE_CARDS | PDF_PREVIEW_EXPORT | CHAT_TRANSCRIPT | JSON_VIEW",
                  "sourceVariable": "result"
                }
              ],
              "workflow": [
                {
                  "id": "step_1",
                  "type": "VALIDATE_INPUT | SANDBOX_EXPRESSION | TEMPLATE_FORMAT | AI_PROMPT | GENERATE_PDF | TOOL_STORAGE_SAVE | TOOL_STORAGE_LOAD | SET_OUTPUT",
                  "title": "Nom de l'étape",
                  "outputVariable": "result",
                  "expression": "uniquement pour SANDBOX_EXPRESSION (ex: weight / ((height / 100) ^ 2))",
                  "template": "texte avec {{variableId}} pour TEMPLATE_FORMAT, AI_PROMPT, GENERATE_PDF ou SET_OUTPUT",
                  "systemPrompt": "instruction système pour AI_PROMPT",
                  "preferredModel": "",
                  "storageKey": "clé alphanumérique pour TOOL_STORAGE_*",
                  "includeImageFieldId": "id d'un champ IMAGE_INPUT si vision requise",
                  "temperature": 0.4
                }
              ],
              "submitButtonLabel": "Exécuter",
              "documentation": "Documentation claire de l'outil en français",
              "timeoutMs": 60000
            }

            RÈGLES STRICTES :
            1. Déclare UNIQUEMENT les permissions réellement utilisées par les étapes du workflow.
            2. Si une étape est AI_PROMPT, inclus "AI_INFERENCE" dans permissions.
            3. Si une étape est GENERATE_PDF, inclus "PDF_EXPORT" dans permissions et un output de type "PDF_PREVIEW_EXPORT" pointant vers la variable de sortie de l'étape GENERATE_PDF.
            4. Si l'outil est déterministe (calculatrice, convertisseur mathématique, formatage), utilise SANDBOX_EXPRESSION ou TEMPLATE_FORMAT sans AI_PROMPT.
            5. Tous les id de champs et outputVariable doivent correspondre à ^[a-zA-Z][a-zA-Z0-9_]{0,47}$.
        """
    }
}
