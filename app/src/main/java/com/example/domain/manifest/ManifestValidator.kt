package com.example.domain.manifest

import com.example.core.security.SafeExpressionEvaluator
import com.example.core.security.SandboxGuard

/**
 * Validates any [ToolManifest] produced by the multi-agent pipeline or edited manually
 * before saving or executing it inside the ForgeAI runtime.
 */
object ManifestValidator {

    private val SEMVER_REGEX = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val VARIABLE_ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,47}$")
    private val ALLOWED_TEXT_OPS = setOf(
        "UPPERCASE", "LOWERCASE", "TRIM", "WORD_COUNT", "CHAR_COUNT", "LINE_COUNT",
        "SLUGIFY", "BASE64_ENCODE", "BASE64_DECODE", "SHA256", "UUID",
        "JSON_PRETTY", "JSON_EXTRACT", "CSV_TO_TABLE", "REGEX_EXTRACT", "PASSWORD_GEN"
    )
    private val ALLOWED_DATE_OPS = setOf(
        "NOW_FR", "NOW_ISO", "TIMESTAMP_MS", "ADD_DAYS", "DAYS_BETWEEN"
    )
    private val ALLOWED_HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE")

    fun validate(manifest: ToolManifest): Result<ToolManifest> = runCatching {
        require(manifest.id.isNotBlank()) { "L'identifiant de l'outil est requis." }
        require(manifest.name.isNotBlank() && manifest.name.length <= 64) {
            "Le nom de l'outil doit contenir entre 1 et 64 caractères."
        }
        require(manifest.description.isNotBlank() && manifest.description.length <= 400) {
            "La description doit contenir entre 1 et 400 caractères."
        }
        require(SEMVER_REGEX.matches(manifest.version)) {
            "La version '${manifest.version}' doit suivre le format SemVer (ex: 1.0.0)."
        }
        require(manifest.workflow.isNotEmpty()) {
            "Le workflow doit contenir au moins une étape."
        }
        require(manifest.workflow.size <= SandboxGuard.MAX_WORKFLOW_STEPS) {
            "Le workflow dépasse le maximum de ${SandboxGuard.MAX_WORKFLOW_STEPS} étapes."
        }
        require(manifest.outputs.isNotEmpty()) {
            "Le manifeste doit définir au moins un bloc de sortie."
        }
        require(manifest.timeoutMs in 1_000L..120_000L) {
            "Le délai d'exécution doit être compris entre 1 et 120 secondes."
        }

        val fieldIds = mutableSetOf<String>()
        manifest.fields.forEach { field ->
            require(VARIABLE_ID_REGEX.matches(field.id)) {
                "Identifiant de champ invalide : '${field.id}'."
            }
            require(fieldIds.add(field.id)) {
                "Identifiant de champ en double : '${field.id}'."
            }
            require(field.label.isNotBlank()) {
                "Le libellé du champ '${field.id}' est vide."
            }
            if (field.type == UiFieldType.SELECT) {
                require(field.options.isNotEmpty()) {
                    "Le champ de sélection '${field.label}' doit contenir au moins une option."
                }
            }
        }

        val knownVariables = fieldIds.toMutableSet()
        manifest.workflow.forEach { step ->
            require(step.id.isNotBlank()) { "Chaque étape du workflow doit avoir un ID." }
            require(VARIABLE_ID_REGEX.matches(step.outputVariable)) {
                "Variable de sortie invalide dans l'étape '${step.title}': '${step.outputVariable}'."
            }

            if (step.template.isNotBlank()) SandboxGuard.assertSafeTemplate(step.template)
            if (step.systemPrompt.isNotBlank()) SandboxGuard.assertSafeTemplate(step.systemPrompt)
            if (step.thenTemplate.isNotBlank()) SandboxGuard.assertSafeTemplate(step.thenTemplate)
            if (step.elseTemplate.isNotBlank()) SandboxGuard.assertSafeTemplate(step.elseTemplate)
            if (step.urlTemplate.isNotBlank()) SandboxGuard.assertSafeTemplate(step.urlTemplate)

            when (step.type) {
                WorkflowStepType.SANDBOX_EXPRESSION -> {
                    require(step.expression.isNotBlank()) {
                        "L'étape '${step.title}' requiert une expression mathématique."
                    }
                    SafeExpressionEvaluator.validateSyntax(step.expression, knownVariables)
                        .getOrThrow()
                }
                WorkflowStepType.CONDITION_BRANCH -> {
                    require(step.expression.isNotBlank() || step.template.isNotBlank()) {
                        "L'étape conditionnelle '${step.title}' doit définir expression ou template."
                    }
                    if (step.expression.isNotBlank()) {
                        SafeExpressionEvaluator.validateSyntax(step.expression, knownVariables)
                            .getOrThrow()
                    }
                }
                WorkflowStepType.TEXT_TRANSFORM -> {
                    val op = step.operation.trim().uppercase()
                    require(op in ALLOWED_TEXT_OPS) {
                        "Opération TEXT_TRANSFORM non supportée : '${step.operation}'."
                    }
                }
                WorkflowStepType.DATE_TIME -> {
                    val op = step.operation.trim().uppercase()
                    require(op in ALLOWED_DATE_OPS) {
                        "Opération DATE_TIME non supportée : '${step.operation}'."
                    }
                }
                WorkflowStepType.AI_PROMPT -> {
                    require(ToolPermission.AI_INFERENCE in manifest.permissions) {
                        "L'étape IA '${step.title}' exige la permission ${ToolPermission.AI_INFERENCE.name}."
                    }
                    require(step.template.isNotBlank() || step.systemPrompt.isNotBlank()) {
                        "L'étape IA '${step.title}' doit définir un prompt."
                    }
                }
                WorkflowStepType.HTTP_REQUEST -> {
                    require(ToolPermission.NETWORK_FETCH in manifest.permissions) {
                        "L'étape HTTP '${step.title}' exige la permission ${ToolPermission.NETWORK_FETCH.name}."
                    }
                    require(step.urlTemplate.isNotBlank()) {
                        "L'étape HTTP '${step.title}' requiert urlTemplate."
                    }
                    val method = step.httpMethod.trim().uppercase().ifBlank { "GET" }
                    require(method in ALLOWED_HTTP_METHODS) {
                        "Méthode HTTP non autorisée : '$method'."
                    }
                    if (!step.urlTemplate.contains("{{")) {
                        SandboxGuard.validateHttpsEndpoint(step.urlTemplate).getOrThrow()
                    } else {
                        require(step.urlTemplate.trim().startsWith("https://", ignoreCase = true)) {
                            "urlTemplate doit commencer par https://."
                        }
                    }
                }
                WorkflowStepType.GENERATE_PDF -> {
                    require(ToolPermission.PDF_EXPORT in manifest.permissions) {
                        "L'étape PDF '${step.title}' exige la permission ${ToolPermission.PDF_EXPORT.name}."
                    }
                }
                WorkflowStepType.TOOL_STORAGE_SAVE,
                WorkflowStepType.TOOL_STORAGE_LOAD,
                WorkflowStepType.LIST_STORAGE_APPEND,
                WorkflowStepType.LIST_STORAGE_READ -> {
                    require(ToolPermission.LOCAL_STORAGE in manifest.permissions) {
                        "L'étape de stockage '${step.title}' exige la permission ${ToolPermission.LOCAL_STORAGE.name}."
                    }
                    SandboxGuard.validateStorageKey(step.storageKey)
                }
                WorkflowStepType.SPEAK_TEXT -> {
                    require(ToolPermission.TEXT_TO_SPEECH in manifest.permissions) {
                        "L'étape vocale '${step.title}' exige la permission ${ToolPermission.TEXT_TO_SPEECH.name}."
                    }
                }
                WorkflowStepType.CALL_TOOL -> {
                    require(step.targetToolId.isNotBlank()) {
                        "L'étape CALL_TOOL '${step.title}' requiert targetToolId."
                    }
                    require(step.targetToolId != manifest.id) {
                        "Un plugin ne peut pas s'appeler récursivement (${manifest.id})."
                    }
                }
                WorkflowStepType.VALIDATE_INPUT,
                WorkflowStepType.TEMPLATE_FORMAT,
                WorkflowStepType.SET_OUTPUT -> Unit
            }

            knownVariables.add(step.outputVariable)
        }

        manifest
    }
}
