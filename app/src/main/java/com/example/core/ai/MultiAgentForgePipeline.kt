package com.example.core.ai

import com.example.domain.manifest.ManifestValidator
import com.example.domain.manifest.ToolManifest
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.util.UUID

/**
 * Specialized AI agents collaborating before generating a ForgeAI plugin.
 */
enum class ForgeAgentRole(
    val title: String,
    val badge: String,
    val focus: String
) {
    ARCHITECT("Architecte Logiciel", "ARCH", "Décomposition autonome, capacités, permissions et stockage"),
    UX_DESIGNER("UX Designer", "UX", "Conception du formulaire et des vues (tableaux, graphiques, PDF, chat)"),
    DEVELOPER("Développeur Plugin", "DEV", "Compilation du manifeste déclaratif et du workflow"),
    AI_EXPERT("Expert IA", "IA", "Optimisation des prompts système, modèles et températures"),
    TESTER("Testeur QA", "QA", "Validation structurelle et test d'exécution à blanc (dry-run)"),
    CORRECTOR("Correcteur Sécurité", "SEC", "Auto-réparation et durcissement sandbox moindre privilège"),
    OPTIMIZER("Optimiseur", "OPT", "Performance, timeouts et réduction des étapes redondantes"),
    DOCUMENTATION("Agent Documentation", "DOC", "Rédaction de la documentation et du changelog initial")
}

enum class ForgeAutonomyMode(val label: String, val description: String) {
    AUTONOMOUS(
        label = "Autonome (Auto / Suite)",
        description = "Décompose librement les demandes complexes en 1 ou plusieurs plugins coordonnés, avec auto-test QA et auto-réparation."
    ),
    SINGLE(
        label = "Plugin unique",
        description = "Conçoit un seul outil ciblé avec auto-test QA sandbox."
    )
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
        val totalLatencyMs: Long,
        val allManifests: List<ToolManifest> = listOf(manifest)
    ) : ForgePipelineEvent

    data class Failure(
        val errorMessage: String,
        val isMissingProviderOrFirebase: Boolean,
        val logs: List<AgentLogItem>
    ) : ForgePipelineEvent
}

private data class PlannedPluginSpec(
    val title: String,
    val spec: String
)

/**
 * Multi-agent orchestrator coordinating 8 specialized roles to autonomously plan,
 * compile, dry-run test, self-repair, and harden declarative [ToolManifest] plugins or suites.
 */
class MultiAgentForgePipeline(
    private val apiRouterService: ApiRouterService,
    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
    private val dryRunVerifier: (suspend (ToolManifest, Map<String, String>) -> Result<Unit>)? = null
) {
    private val manifestAdapter = moshi.adapter(ToolManifest::class.java)

    fun forgeTool(
        userPrompt: String,
        authorName: String,
        strategy: LoadBalancingStrategy
    ): Flow<ForgePipelineEvent> = forgeAutonomously(
        userPrompt = userPrompt,
        authorName = authorName,
        strategy = strategy,
        autonomyMode = ForgeAutonomyMode.SINGLE,
        existingToolsSummary = emptyList()
    )

    fun forgeAutonomously(
        userPrompt: String,
        authorName: String,
        strategy: LoadBalancingStrategy,
        autonomyMode: ForgeAutonomyMode = ForgeAutonomyMode.AUTONOMOUS,
        existingToolsSummary: List<Pair<String, String>> = emptyList()
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

        // Phase 1: Autonomous Architect + UX Designer
        updateLog(ForgeAgentRole.ARCHITECT, AgentStepStatus.RUNNING, "Analyse autonome de l'objectif...")
        updateLog(ForgeAgentRole.UX_DESIGNER, AgentStepStatus.RUNNING, "Conception des interfaces et sorties...")
        emit(ForgePipelineEvent.Progress(ForgeAgentRole.ARCHITECT, logs.toList(), "Planification autonome..."))

        val installedCatalogHint = if (existingToolsSummary.isEmpty()) {
            "Aucun outil préexistant."
        } else {
            existingToolsSummary.take(12).joinToString("\n") { (id, name) -> "- $id : $name" }
        }

        val planningPrompt = if (autonomyMode == ForgeAutonomyMode.AUTONOMOUS) {
            """
                Demande utilisateur : $cleanPrompt
                Outils déjà installés (réutilisables via CALL_TOOL si utile) :
                $installedCatalogHint

                Analyse librement la demande. Si un seul outil suffit, choisis "mode": "SINGLE" (1 plugin).
                Si la demande couvre un système complet ou plusieurs usages distincts, choisis "mode": "SUITE" (2 à 4 plugins complémentaires).
                Réponds UNIQUEMENT en JSON :
                {
                  "mode": "SINGLE" | "SUITE",
                  "rationale": "court résumé",
                  "plugins": [
                    {
                      "title": "Nom court du plugin",
                      "spec": "Description technique complète : permissions, champs, étapes de workflow, sorties visuelles"
                    }
                  ]
                }
            """.trimIndent()
        } else {
            """
                Demande utilisateur : $cleanPrompt
                Outils déjà installés :
                $installedCatalogHint

                Produis un plan technique concis (max 12 lignes) précisant : catégorie, permissions strictement nécessaires, champs de saisie, étapes de workflow et format de sortie pour UN seul plugin.
            """.trimIndent()
        }

        val blueprintResponse = apiRouterService.executeWithFailover(
            request = AiRequest(
                systemPrompt = ARCHITECT_UX_SYSTEM_PROMPT,
                userPrompt = planningPrompt,
                temperature = 0.25,
                maxTokens = 1100,
                jsonMode = autonomyMode == ForgeAutonomyMode.AUTONOMOUS
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
        var providerLabel = "${bp.providerName} (${bp.modelUsed} • ${bp.keyLabel})"
        val plannedPlugins = parseAutonomousPlan(bp.content, cleanPrompt, autonomyMode)

        updateLog(
            ForgeAgentRole.ARCHITECT,
            AgentStepStatus.COMPLETED,
            if (plannedPlugins.size > 1) "Suite autonome planifiée (${plannedPlugins.size} plugins)"
            else "Architecture et permissions définies"
        )
        updateLog(ForgeAgentRole.UX_DESIGNER, AgentStepStatus.COMPLETED, "Schéma UX et vues de sortie structurés")

        // Phase 2 & 3: Compile, validate, dry-run, and self-repair each planned plugin
        val builtManifests = mutableListOf<ToolManifest>()
        val createdSoFar = existingToolsSummary.toMutableList()

        for ((index, planItem) in plannedPlugins.withIndex()) {
            val stepPrefix = if (plannedPlugins.size > 1) "[${index + 1}/${plannedPlugins.size}] ${planItem.title} : " else ""
            updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.RUNNING, "${stepPrefix}Génération du manifeste JSON...")
            updateLog(ForgeAgentRole.AI_EXPERT, AgentStepStatus.RUNNING, "${stepPrefix}Calibration des prompts et paramètres...")
            emit(ForgePipelineEvent.Progress(ForgeAgentRole.DEVELOPER, logs.toList(), providerLabel))

            val generatedToolId = "tool_${UUID.randomUUID().toString().take(8)}"
            val companionToolsHint = if (createdSoFar.isEmpty()) {
                "Aucun"
            } else {
                createdSoFar.takeLast(12).joinToString("\n") { (id, name) -> "- $id : $name" }
            }

            val manifestGenResponse = apiRouterService.executeWithFailover(
                request = AiRequest(
                    systemPrompt = MANIFEST_GENERATOR_SYSTEM_PROMPT,
                    userPrompt = """
                        Demande globale : $cleanPrompt
                        Plugin à générer : ${planItem.title}
                        Spécification Architecte & UX :
                        ${planItem.spec}

                        Outils disponibles (pour CALL_TOOL uniquement si pertinent) :
                        $companionToolsHint

                        Paramètres imposés :
                        - id : "$generatedToolId"
                        - author : "$authorName"
                        - version : "1.0.0"
                        - status : "ACTIVE"

                        Réponds UNIQUEMENT par un objet JSON valide conforme au schéma ToolManifest, sans bloc markdown.
                    """.trimIndent(),
                    temperature = 0.2,
                    maxTokens = 2600,
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

            val genResp = manifestGenResponse.getOrThrow()
            providerLabel = "${genResp.providerName} (${genResp.modelUsed} • ${genResp.keyLabel})"
            var currentRawJson = stripOptionalCodeFences(genResp.content)
            var candidate = runCatching { manifestAdapter.fromJson(currentRawJson) }.getOrNull()
                ?.copy(id = generatedToolId, author = authorName, version = "1.0.0")

            if (candidate == null) {
                updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.FAILED, "${stepPrefix}JSON malformé produit par le modèle")
                emit(
                    ForgePipelineEvent.Failure(
                        errorMessage = "Le modèle a renvoyé un JSON non conforme au schéma ToolManifest.",
                        isMissingProviderOrFirebase = false,
                        logs = logs.toList()
                    )
                )
                return@flow
            }

            updateLog(
                ForgeAgentRole.DEVELOPER,
                AgentStepStatus.COMPLETED,
                "${stepPrefix}Manifeste généré (${candidate.workflow.size} étapes)"
            )
            updateLog(ForgeAgentRole.AI_EXPERT, AgentStepStatus.COMPLETED, "${stepPrefix}Logique et prompts calibrés")

            // QA Validation + Dry-Run + Self-Repair loop (up to 2 repair attempts)
            var verifiedManifest: ToolManifest? = null
            var lastVerificationError = ""

            for (attempt in 0..MAX_REPAIR_ATTEMPTS) {
                updateLog(ForgeAgentRole.TESTER, AgentStepStatus.RUNNING, "${stepPrefix}Validation & test à blanc (essai ${attempt + 1})...")
                updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.RUNNING, "${stepPrefix}Audit sécurité sandbox...")
                emit(ForgePipelineEvent.Progress(ForgeAgentRole.TESTER, logs.toList(), providerLabel))

                val currentCandidate = candidate
                val checkResult = if (currentCandidate == null) {
                    Result.failure(IllegalArgumentException("JSON malformé lors de la réparation."))
                } else {
                    verifyCandidate(currentCandidate)
                }

                if (checkResult.isSuccess) {
                    verifiedManifest = checkResult.getOrThrow()
                    updateLog(
                        ForgeAgentRole.TESTER,
                        AgentStepStatus.COMPLETED,
                        if (dryRunVerifier != null) "${stepPrefix}Schéma et dry-run QA réussis" else "${stepPrefix}Contrôles structurels validés"
                    )
                    updateLog(
                        ForgeAgentRole.CORRECTOR,
                        AgentStepStatus.COMPLETED,
                        if (attempt == 0) "${stepPrefix}Permissions et sandbox conformes" else "${stepPrefix}Auto-corrigé ($attempt tentative(s))"
                    )
                    break
                }

                lastVerificationError = checkResult.exceptionOrNull()?.message ?: "Erreur de validation sandbox"
                if (attempt == MAX_REPAIR_ATTEMPTS) {
                    updateLog(ForgeAgentRole.TESTER, AgentStepStatus.FAILED, lastVerificationError)
                    updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.FAILED, "Correction automatique épuisée")
                    emit(
                        ForgePipelineEvent.Failure(
                            errorMessage = "Validation sandbox échouée : $lastVerificationError",
                            isMissingProviderOrFirebase = false,
                            logs = logs.toList()
                        )
                    )
                    return@flow
                }

                updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.RUNNING, "${stepPrefix}Auto-réparation : $lastVerificationError")
                emit(ForgePipelineEvent.Progress(ForgeAgentRole.CORRECTOR, logs.toList(), providerLabel))

                val repairResp = apiRouterService.executeWithFailover(
                    request = AiRequest(
                        systemPrompt = MANIFEST_GENERATOR_SYSTEM_PROMPT,
                        userPrompt = """
                            Corrige ce JSON ToolManifest qui a échoué la vérification QA/sandbox avec l'erreur :
                            $lastVerificationError

                            Conserve impérativement :
                            - id : "$generatedToolId"
                            - author : "$authorName"
                            - version : "1.0.0"

                            JSON fautif :
                            $currentRawJson
                        """.trimIndent(),
                        temperature = 0.1,
                        maxTokens = 2600,
                        jsonMode = true
                    ),
                    strategy = strategy
                )

                val repairedRaw = repairResp.getOrNull()?.content?.let(::stripOptionalCodeFences)
                if (repairedRaw != null) {
                    currentRawJson = repairedRaw
                    candidate = runCatching { manifestAdapter.fromJson(repairedRaw) }.getOrNull()
                        ?.copy(id = generatedToolId, author = authorName, version = "1.0.0")
                } else {
                    candidate = null
                }
            }

            val ready = verifiedManifest ?: return@flow
            val optimizedManifest = ready.copy(
                timeoutMs = ready.timeoutMs.coerceIn(15_000L, 60_000L),
                documentation = ready.documentation.ifBlank {
                    "Plugin généré par ForgeAI (${ready.category.label}). Permissions : ${
                        ready.permissions.joinToString { it.label }.ifBlank { "Aucune" }
                    }."
                }
            )
            builtManifests.add(optimizedManifest)
            createdSoFar.add(optimizedManifest.id to optimizedManifest.name)
        }

        // Optimizer + Documentation summary
        updateLog(
            ForgeAgentRole.OPTIMIZER,
            AgentStepStatus.COMPLETED,
            "${builtManifests.size} plugin(s) optimisé(s) (timeouts bornés)"
        )
        updateLog(
            ForgeAgentRole.DOCUMENTATION,
            AgentStepStatus.COMPLETED,
            "Documentation et manifestes prêts"
        )

        emit(
            ForgePipelineEvent.Success(
                manifest = builtManifests.first(),
                logs = logs.toList(),
                providerInfo = providerLabel,
                totalLatencyMs = System.currentTimeMillis() - startMs,
                allManifests = builtManifests.toList()
            )
        )
    }

    private suspend fun verifyCandidate(candidate: ToolManifest): Result<ToolManifest> {
        val validated = ManifestValidator.validate(candidate).getOrElse { return Result.failure(it) }
        val verifier = dryRunVerifier ?: return Result.success(validated)
        verifier(validated, emptyMap()).getOrElse { return Result.failure(it) }
        return Result.success(validated)
    }

    private fun parseAutonomousPlan(
        rawBlueprint: String,
        originalPrompt: String,
        mode: ForgeAutonomyMode
    ): List<PlannedPluginSpec> {
        if (mode == ForgeAutonomyMode.SINGLE) {
            return listOf(PlannedPluginSpec(title = "Plugin principal", spec = rawBlueprint))
        }
        val cleaned = stripOptionalCodeFences(rawBlueprint)
        val parsed = runCatching {
            val root = JSONObject(cleaned)
            val pluginsArray = root.optJSONArray("plugins") ?: return@runCatching emptyList()
            val list = mutableListOf<PlannedPluginSpec>()
            for (i in 0 until minOf(pluginsArray.length(), MAX_AUTONOMOUS_SUITE_SIZE)) {
                val obj = pluginsArray.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim().ifBlank { "Outil ${i + 1}" }
                val spec = obj.optString("spec").trim()
                if (spec.isNotBlank()) {
                    list.add(PlannedPluginSpec(title = title, spec = spec))
                }
            }
            list
        }.getOrNull()

        return if (!parsed.isNullOrEmpty()) {
            parsed
        } else {
            listOf(
                PlannedPluginSpec(
                    title = "Plugin principal",
                    spec = rawBlueprint.ifBlank { originalPrompt }
                )
            )
        }
    }

    /**
     * Iteratively refines an existing [ToolManifest] from a natural-language modification request,
     * preserving tool identity, incrementing SemVer, and enforcing sandbox validation + dry-run.
     */
    fun refineTool(
        existingManifest: ToolManifest,
        modificationPrompt: String,
        authorName: String,
        strategy: LoadBalancingStrategy
    ): Flow<ForgePipelineEvent> = flow {
        val cleanRequest = modificationPrompt.trim()
        val startMs = System.currentTimeMillis()
        val nextVersion = bumpMinorVersion(existingManifest.version)
        val logs = ForgeAgentRole.entries.map {
            AgentLogItem(it, AgentStepStatus.PENDING, "En attente")
        }.toMutableList()

        fun updateLog(role: ForgeAgentRole, status: AgentStepStatus, summary: String) {
            val idx = logs.indexOfFirst { it.role == role }
            if (idx >= 0) {
                logs[idx] = AgentLogItem(role, status, summary)
            }
        }

        val currentJson = manifestAdapter.toJson(existingManifest)
        updateLog(ForgeAgentRole.ARCHITECT, AgentStepStatus.RUNNING, "Analyse de l'évolution demandée...")
        updateLog(ForgeAgentRole.UX_DESIGNER, AgentStepStatus.RUNNING, "Révision de l'interface du plugin...")
        emit(ForgePipelineEvent.Progress(ForgeAgentRole.ARCHITECT, logs.toList(), "Routage IA..."))

        val blueprintResponse = apiRouterService.executeWithFailover(
            request = AiRequest(
                systemPrompt = ARCHITECT_UX_SYSTEM_PROMPT,
                userPrompt = """
                    Manifeste actuel (${existingManifest.name} v${existingManifest.version}) :
                    $currentJson

                    Modification demandée par l'utilisateur : $cleanRequest

                    Décris en max 10 lignes les changements précis à apporter aux permissions, champs, workflow et sorties.
                """.trimIndent(),
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
        updateLog(ForgeAgentRole.ARCHITECT, AgentStepStatus.COMPLETED, "Évolution planifiée (v$nextVersion)")
        updateLog(ForgeAgentRole.UX_DESIGNER, AgentStepStatus.COMPLETED, "Champs et sorties ajustés")

        updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.RUNNING, "Mise à jour du manifeste déclaratif...")
        updateLog(ForgeAgentRole.AI_EXPERT, AgentStepStatus.RUNNING, "Calibration des prompts...")
        emit(ForgePipelineEvent.Progress(ForgeAgentRole.DEVELOPER, logs.toList(), providerLabel))

        val manifestGenResponse = apiRouterService.executeWithFailover(
            request = AiRequest(
                systemPrompt = MANIFEST_GENERATOR_SYSTEM_PROMPT,
                userPrompt = """
                    Manifeste existant :
                    $currentJson

                    Demande d'évolution : $cleanRequest
                    Plan Architecte & UX :
                    ${bp.content}

                    Paramètres imposés :
                    - id : "${existingManifest.id}"
                    - author : "$authorName"
                    - version : "$nextVersion"
                    - status : "${existingManifest.status.name}"

                    Réponds UNIQUEMENT par l'objet JSON ToolManifest complet mis à jour, sans bloc markdown.
                """.trimIndent(),
                temperature = 0.2,
                maxTokens = 2600,
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
                    errorMessage = err?.message ?: "Échec lors de la mise à jour du manifeste.",
                    isMissingProviderOrFirebase = false,
                    logs = logs.toList()
                )
            )
            return@flow
        }

        val rawJson = stripOptionalCodeFences(manifestGenResponse.getOrThrow().content)
        val parsed = runCatching { manifestAdapter.fromJson(rawJson) }.getOrNull()
        if (parsed == null) {
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

        val normalized = parsed.copy(
            id = existingManifest.id,
            author = authorName,
            version = nextVersion,
            status = existingManifest.status
        )
        updateLog(ForgeAgentRole.DEVELOPER, AgentStepStatus.COMPLETED, "Manifeste v$nextVersion généré")
        updateLog(ForgeAgentRole.AI_EXPERT, AgentStepStatus.COMPLETED, "Prompts calibrés")
        updateLog(ForgeAgentRole.TESTER, AgentStepStatus.RUNNING, "Validation structurelle et dry-run...")
        updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.RUNNING, "Audit sécurité sandbox...")
        emit(ForgePipelineEvent.Progress(ForgeAgentRole.TESTER, logs.toList(), providerLabel))

        val verified = verifyCandidate(normalized).getOrElse { err ->
            updateLog(ForgeAgentRole.TESTER, AgentStepStatus.FAILED, err.message ?: "Validation échouée")
            updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.FAILED, "Contrôle bloquant")
            emit(
                ForgePipelineEvent.Failure(
                    errorMessage = "Validation sandbox échouée : ${err.message}",
                    isMissingProviderOrFirebase = false,
                    logs = logs.toList()
                )
            )
            return@flow
        }

        updateLog(ForgeAgentRole.TESTER, AgentStepStatus.COMPLETED, "Contrôles structurels et QA validés")
        updateLog(ForgeAgentRole.CORRECTOR, AgentStepStatus.COMPLETED, "Permissions et templates vérifiés")
        val optimized = verified.copy(timeoutMs = verified.timeoutMs.coerceIn(15_000L, 60_000L))
        updateLog(ForgeAgentRole.OPTIMIZER, AgentStepStatus.COMPLETED, "Timeout borné à ${optimized.timeoutMs / 1000}s")
        updateLog(ForgeAgentRole.DOCUMENTATION, AgentStepStatus.COMPLETED, "Changelog v${optimized.version} prêt")

        emit(
            ForgePipelineEvent.Success(
                manifest = optimized,
                logs = logs.toList(),
                providerInfo = providerLabel,
                totalLatencyMs = System.currentTimeMillis() - startMs
            )
        )
    }

    private fun bumpMinorVersion(version: String): String {
        val parts = version.trim().split(".").mapNotNull { it.toIntOrNull() }
        return if (parts.size >= 2) {
            "${parts[0]}.${parts[1] + 1}.0"
        } else {
            "1.1.0"
        }
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
        private const val MAX_AUTONOMOUS_SUITE_SIZE = 4
        private const val MAX_REPAIR_ATTEMPTS = 2

        private const val ARCHITECT_UX_SYSTEM_PROMPT = """
            Tu es une équipe composée d'un Architecte Logiciel et d'un UX Designer senior.
            Tu conçois des plugins et suites d'outils déclaratifs sécurisés pour l'application Android ForgeAI.
            Tu exploites pleinement le moteur sandbox : formules mathématiques, branchements conditionnels,
            transformations de texte/JSON/CSV/SHA-256/Base64/mots de passe, calculs de dates, stockage persistant
            de listes (suivi dépenses, tâches, habitudes), appels HTTPS publics, synthèse vocale TTS, génération PDF A4,
            vision par image, chat multi-tours et composition inter-outils (CALL_TOOL).
            Tu réponds en français de manière concise, structurée et exploitable techniquement.
        """

        private const val MANIFEST_GENERATOR_SYSTEM_PROMPT = """
            Tu es le compilateur déclaratif autonome ForgeAI. Tu génères exclusivement du JSON valide conforme au schéma ToolManifest suivant :
            {
              "id": "string",
              "name": "string (max 64 chars)",
              "description": "string (max 400 chars)",
              "version": "1.0.0",
              "author": "string",
              "iconName": "auto_awesome | description | calculate | translate | summarize | image | code | chat | folder | tune",
              "category": "DOCUMENT | AI_ASSISTANT | CONVERTER | CALCULATOR | PRODUCTIVITY | VISION_OCR | DEVELOPER | CUSTOM",
              "status": "ACTIVE",
              "permissions": ["AI_INFERENCE", "LOCAL_STORAGE", "PDF_EXPORT", "FILE_PICKER", "IMAGE_PICKER", "CLIPBOARD", "NETWORK_FETCH", "TEXT_TO_SPEECH"],
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
                  "type": "TEXT_OUTPUT | MARKDOWN_VIEW | CALCULATION_RESULT | KEY_VALUE_CARDS | PDF_PREVIEW_EXPORT | CHAT_TRANSCRIPT | JSON_VIEW | TABLE_VIEW | BAR_CHART | LIST_ITEMS",
                  "sourceVariable": "result"
                }
              ],
              "workflow": [
                {
                  "id": "step_1",
                  "type": "VALIDATE_INPUT | SANDBOX_EXPRESSION | TEMPLATE_FORMAT | CONDITION_BRANCH | TEXT_TRANSFORM | DATE_TIME | AI_PROMPT | HTTP_REQUEST | GENERATE_PDF | TOOL_STORAGE_SAVE | TOOL_STORAGE_LOAD | LIST_STORAGE_APPEND | LIST_STORAGE_READ | SPEAK_TEXT | CALL_TOOL | SET_OUTPUT",
                  "title": "Nom de l'étape",
                  "outputVariable": "result",
                  "expression": "expression math/comparaison pour SANDBOX_EXPRESSION ou CONDITION_BRANCH (ex: price * (1 + tax/100), score >= 50)",
                  "template": "texte avec {{variableId}}",
                  "systemPrompt": "instruction système pour AI_PROMPT",
                  "preferredModel": "",
                  "storageKey": "clé alphanumérique pour *_STORAGE_*",
                  "includeImageFieldId": "id champ IMAGE_INPUT si vision requise",
                  "temperature": 0.4,
                  "operation": "Pour TEXT_TRANSFORM: UPPERCASE|LOWERCASE|TRIM|WORD_COUNT|CHAR_COUNT|LINE_COUNT|SLUGIFY|BASE64_ENCODE|BASE64_DECODE|SHA256|UUID|JSON_PRETTY|JSON_EXTRACT|CSV_TO_TABLE|REGEX_EXTRACT|PASSWORD_GEN | Pour DATE_TIME: NOW_FR|NOW_ISO|TIMESTAMP_MS|ADD_DAYS|DAYS_BETWEEN | Pour LIST_STORAGE_READ: COUNT|SUM|''",
                  "operationParam": "paramètre optionnel (chemin JSON ex: data.price, regex, longueur mot de passe, nb jours, clé numérique pour SUM)",
                  "thenTemplate": "sortie si CONDITION_BRANCH vraie",
                  "elseTemplate": "sortie si CONDITION_BRANCH fausse",
                  "urlTemplate": "URL https://... avec {{var}} pour HTTP_REQUEST",
                  "httpMethod": "GET | POST | PUT | DELETE",
                  "requestHeadersJson": "{\"Accept\":\"application/json\"}",
                  "targetToolId": "id outil existant pour CALL_TOOL"
                }
              ],
              "submitButtonLabel": "Exécuter",
              "documentation": "Documentation claire de l'outil en français",
              "timeoutMs": 60000
            }

            RÈGLES STRICTES :
            1. Déclare UNIQUEMENT les permissions réellement utilisées par les étapes du workflow :
               - AI_PROMPT -> AI_INFERENCE
               - GENERATE_PDF -> PDF_EXPORT
               - *_STORAGE_* -> LOCAL_STORAGE
               - HTTP_REQUEST -> NETWORK_FETCH
               - SPEAK_TEXT -> TEXT_TO_SPEECH
               - Champ FILE_DOCUMENT -> FILE_PICKER ; Champ IMAGE_INPUT -> IMAGE_PICKER
            2. Privilégie les étapes déterministes (SANDBOX_EXPRESSION, CONDITION_BRANCH, TEXT_TRANSFORM, DATE_TIME, LIST_STORAGE_*, HTTP_REQUEST) lorsque le problème ne requiert pas de rédaction/raisonnement IA.
            3. Pour un suivi persistant (dépenses, tâches, notes, habitudes), utilise LIST_STORAGE_APPEND puis LIST_ITEMS, TABLE_VIEW ou BAR_CHART.
            4. Si une étape est GENERATE_PDF, inclus un output PDF_PREVIEW_EXPORT pointant vers la variable de sortie de GENERATE_PDF.
            5. Tous les id de champs et outputVariable doivent correspondre à ^[a-zA-Z][a-zA-Z0-9_]{0,47}$.
        """
    }
}
