package com.example.domain.manifest

import com.squareup.moshi.JsonClass

/**
 * Categorization of user-forged plugins inside ForgeAI.
 */
enum class ToolCategory(val label: String) {
    DOCUMENT("Documents & PDF"),
    AI_ASSISTANT("Assistant & Chat"),
    CONVERTER("Convertisseur"),
    CALCULATOR("Calculateur"),
    PRODUCTIVITY("Productivité"),
    VISION_OCR("Vision & OCR"),
    DEVELOPER("Développeur"),
    CUSTOM("Personnalisé")
}

/**
 * Lifecycle state of an installed plugin.
 */
enum class ToolStatus(val label: String) {
    ACTIVE("Actif"),
    DRAFT("Brouillon"),
    PAUSED("En pause"),
    ARCHIVED("Archivé")
}

/**
 * Explicit sandbox permissions granted to an individual plugin.
 */
enum class ToolPermission(val label: String, val description: String) {
    AI_INFERENCE("Inférence IA", "Appeler le routeur IA configuré avec le prompt du plugin"),
    LOCAL_STORAGE("Stockage isolé", "Lire et écrire des données privées isolées pour cet outil"),
    PDF_EXPORT("Export PDF", "Générer et exporter des documents PDF sur l'appareil"),
    FILE_PICKER("Sélecteur de fichiers", "Lire un document sélectionné par l'utilisateur via SAF"),
    IMAGE_PICKER("Sélecteur d'images", "Lire une image choisie via le Photo Picker Android"),
    CLIPBOARD("Presse-papiers", "Copier le résultat généré vers le presse-papiers")
}

/**
 * Supported dynamic UI input field widgets rendered by the ForgeAI runtime.
 */
enum class UiFieldType {
    TEXT,
    MULTILINE_TEXT,
    NUMBER,
    SLIDER,
    SELECT,
    TOGGLE,
    FILE_DOCUMENT,
    IMAGE_INPUT
}

/**
 * Supported dynamic UI output presentation widgets.
 */
enum class UiOutputType {
    TEXT_OUTPUT,
    MARKDOWN_VIEW,
    CALCULATION_RESULT,
    KEY_VALUE_CARDS,
    PDF_PREVIEW_EXPORT,
    CHAT_TRANSCRIPT,
    JSON_VIEW
}

/**
 * Atomic workflow step types supported by the sandboxed execution engine.
 */
enum class WorkflowStepType {
    VALIDATE_INPUT,
    SANDBOX_EXPRESSION,
    TEMPLATE_FORMAT,
    AI_PROMPT,
    GENERATE_PDF,
    TOOL_STORAGE_SAVE,
    TOOL_STORAGE_LOAD,
    SET_OUTPUT
}

/**
 * Declarative specification of a single form field rendered inside a plugin.
 */
@JsonClass(generateAdapter = true)
data class UiFieldSpec(
    val id: String,
    val label: String,
    val type: UiFieldType = UiFieldType.TEXT,
    val placeholder: String = "",
    val required: Boolean = true,
    val defaultValue: String = "",
    val options: List<String> = emptyList(),
    val minValue: Double = 0.0,
    val maxValue: Double = 100.0
)

/**
 * Declarative specification of an output widget shown after workflow execution.
 */
@JsonClass(generateAdapter = true)
data class UiOutputSpec(
    val id: String,
    val label: String,
    val type: UiOutputType = UiOutputType.MARKDOWN_VIEW,
    val sourceVariable: String = "result"
)

/**
 * Declarative execution step inside a plugin's sandboxed workflow.
 */
@JsonClass(generateAdapter = true)
data class WorkflowStepSpec(
    val id: String,
    val type: WorkflowStepType,
    val title: String,
    val outputVariable: String = "result",
    val expression: String = "",
    val template: String = "",
    val systemPrompt: String = "",
    val preferredModel: String = "",
    val storageKey: String = "",
    val includeImageFieldId: String = "",
    val temperature: Double = 0.4
)

/**
 * Complete declarative manifest for a ForgeAI plugin.
 *
 * Each tool is self-contained, sandboxed, versioned, and independently updatable or removable.
 */
@JsonClass(generateAdapter = true)
data class ToolManifest(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val author: String = "Utilisateur",
    val iconName: String = "auto_awesome",
    val category: ToolCategory = ToolCategory.CUSTOM,
    val status: ToolStatus = ToolStatus.ACTIVE,
    val permissions: List<ToolPermission> = emptyList(),
    val fields: List<UiFieldSpec> = emptyList(),
    val outputs: List<UiOutputSpec> = emptyList(),
    val workflow: List<WorkflowStepSpec> = emptyList(),
    val submitButtonLabel: String = "Exécuter l'outil",
    val documentation: String = "",
    val timeoutMs: Long = 60_000L
)
