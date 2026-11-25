package com.example.ui.screens.atelier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ai.AgentLogItem
import com.example.core.ai.ForgeAgentRole
import com.example.core.ai.ForgeAutonomyMode
import com.example.core.ai.ForgePipelineEvent
import com.example.core.ai.MultiAgentForgePipeline
import com.example.core.ai.ProviderProtocol
import com.example.core.ai.ProviderType
import com.example.core.data.preferences.AppPreferencesRepository
import com.example.core.data.preferences.ForgeSettings
import com.example.core.data.repository.ApiManagerRepository
import com.example.core.data.repository.InstalledTool
import com.example.core.data.repository.ToolRepository
import com.example.domain.manifest.ToolCategory
import com.example.domain.manifest.ToolManifest
import com.example.domain.manifest.ToolPermission
import com.example.domain.manifest.ToolStatus
import com.example.domain.manifest.UiFieldSpec
import com.example.domain.manifest.UiFieldType
import com.example.domain.manifest.UiOutputSpec
import com.example.domain.manifest.UiOutputType
import com.example.domain.manifest.WorkflowStepSpec
import com.example.domain.manifest.WorkflowStepType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface ForgeUiState {
    data object Idle : ForgeUiState
    data class Forging(
        val activeRole: ForgeAgentRole,
        val logs: List<AgentLogItem>,
        val providerInfo: String
    ) : ForgeUiState

    data class ForgedSuccess(
        val tool: InstalledTool,
        val logs: List<AgentLogItem>,
        val providerInfo: String,
        val totalLatencyMs: Long,
        val allTools: List<InstalledTool> = listOf(tool)
    ) : ForgeUiState

    data class Error(
        val message: String,
        val showFirebaseOrApiNotice: Boolean,
        val logs: List<AgentLogItem>
    ) : ForgeUiState
}

data class ProviderReadiness(
    val hasActiveExternalProvider: Boolean,
    val activeProviderCount: Int,
    val activeKeyCount: Int
)

class ForgeAtelierViewModel(
    private val pipeline: MultiAgentForgePipeline,
    private val toolRepository: ToolRepository,
    private val apiManagerRepository: ApiManagerRepository,
    private val preferencesRepository: AppPreferencesRepository
) : ViewModel() {

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val _forgeState = MutableStateFlow<ForgeUiState>(ForgeUiState.Idle)
    val forgeState: StateFlow<ForgeUiState> = _forgeState.asStateFlow()

    private val _manualError = MutableStateFlow<String?>(null)
    val manualError: StateFlow<String?> = _manualError.asStateFlow()

    private val _autonomyMode = MutableStateFlow(ForgeAutonomyMode.AUTONOMOUS)
    val autonomyMode: StateFlow<ForgeAutonomyMode> = _autonomyMode.asStateFlow()

    val settings: StateFlow<ForgeSettings> = preferencesRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ForgeSettings())

    val providerReadiness: StateFlow<ProviderReadiness> = apiManagerRepository.observeProviders()
        .combine(preferencesRepository.settings) { providers, _ ->
            val eligibleProviders = providers.filter { pwk ->
                val type = ProviderType.entries.firstOrNull { it.name == pwk.provider.providerType }
                pwk.provider.enabled &&
                    type?.protocol != ProviderProtocol.FIREBASE_AI_PLACEHOLDER &&
                    pwk.keys.any { it.enabled }
            }
            val keyCount = eligibleProviders.sumOf { pwk -> pwk.keys.count { it.enabled } }
            ProviderReadiness(
                hasActiveExternalProvider = keyCount > 0,
                activeProviderCount = eligibleProviders.size,
                activeKeyCount = keyCount
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            ProviderReadiness(false, 0, 0)
        )

    fun onPromptChanged(newValue: String) {
        _prompt.value = newValue
    }

    fun onAutonomyModeChanged(mode: ForgeAutonomyMode) {
        _autonomyMode.value = mode
    }

    fun resetState() {
        _forgeState.value = ForgeUiState.Idle
        _manualError.value = null
    }

    fun forgeFromPrompt() {
        val cleanPrompt = _prompt.value.trim()
        if (cleanPrompt.length < 4) {
            _forgeState.value = ForgeUiState.Error(
                message = "Décrivez l'outil souhaité en au moins quelques mots.",
                showFirebaseOrApiNotice = false,
                logs = emptyList()
            )
            return
        }

        viewModelScope.launch {
            val currentSettings = settings.value
            val existingToolsSummary = runCatching {
                toolRepository.observeTools().first().map { it.manifest.id to it.manifest.name }
            }.getOrDefault(emptyList())

            try {
                pipeline.forgeAutonomously(
                    userPrompt = cleanPrompt,
                    authorName = currentSettings.authorName,
                    strategy = currentSettings.loadBalancingStrategy,
                    autonomyMode = _autonomyMode.value,
                    existingToolsSummary = existingToolsSummary
                ).collect { event ->
                    when (event) {
                        is ForgePipelineEvent.Progress -> {
                            _forgeState.value = ForgeUiState.Forging(
                                activeRole = event.activeRole,
                                logs = event.logs,
                                providerInfo = event.providerInfo
                            )
                        }
                        is ForgePipelineEvent.Success -> {
                            val installedList = mutableListOf<InstalledTool>()
                            var saveFailure: Throwable? = null
                            for (manifest in event.allManifests) {
                                val res = toolRepository.saveTool(
                                    manifest = manifest,
                                    changelogSummary = "Forgé via pipeline multi-agents autonome",
                                    changelogDetails = "Prompt : ${cleanPrompt.take(180)} | Provider : ${event.providerInfo}"
                                )
                                res.fold(
                                    onSuccess = { installedList.add(it) },
                                    onFailure = { saveFailure = it }
                                )
                            }

                            if (installedList.isNotEmpty()) {
                                _prompt.value = ""
                                _forgeState.value = ForgeUiState.ForgedSuccess(
                                    tool = installedList.first(),
                                    logs = event.logs,
                                    providerInfo = event.providerInfo,
                                    totalLatencyMs = event.totalLatencyMs,
                                    allTools = installedList
                                )
                            } else {
                                _forgeState.value = ForgeUiState.Error(
                                    message = saveFailure?.localizedMessage ?: "Échec de sauvegarde du plugin.",
                                    showFirebaseOrApiNotice = false,
                                    logs = event.logs
                                )
                            }
                        }
                        is ForgePipelineEvent.Failure -> {
                            _forgeState.value = ForgeUiState.Error(
                                message = event.errorMessage,
                                showFirebaseOrApiNotice = event.isMissingProviderOrFirebase,
                                logs = event.logs
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _forgeState.value = ForgeUiState.Error(
                    message = e.localizedMessage ?: "Erreur inattendue lors de la forge.",
                    showFirebaseOrApiNotice = false,
                    logs = emptyList()
                )
            }
        }
    }

    /**
     * Allows users to manually create a real deterministic or custom manifest plugin
     * without requiring any external AI API key.
     */
    fun createManualPlugin(
        name: String,
        description: String,
        category: ToolCategory,
        inputLabel: String,
        inputType: UiFieldType,
        expressionOrTemplate: String,
        isFormulaMode: Boolean,
        enablePdfExport: Boolean,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _manualError.value = null
            val toolId = "tool_${UUID.randomUUID().toString().take(8)}"
            val author = settings.value.authorName
            val permissions = buildList {
                if (enablePdfExport) add(ToolPermission.PDF_EXPORT)
                if (inputType == UiFieldType.FILE_DOCUMENT) add(ToolPermission.FILE_PICKER)
                if (inputType == UiFieldType.IMAGE_INPUT) add(ToolPermission.IMAGE_PICKER)
                add(ToolPermission.CLIPBOARD)
            }

            val fieldId = "input_value"
            val steps = mutableListOf(
                WorkflowStepSpec(
                    id = "step_validate",
                    type = WorkflowStepType.VALIDATE_INPUT,
                    title = "Validation sandbox",
                    outputVariable = "validated"
                )
            )

            if (isFormulaMode) {
                steps.add(
                    WorkflowStepSpec(
                        id = "step_calc",
                        type = WorkflowStepType.SANDBOX_EXPRESSION,
                        title = "Évaluation mathématique sécurisée",
                        outputVariable = "result",
                        expression = expressionOrTemplate.trim()
                    )
                )
            } else {
                steps.add(
                    WorkflowStepSpec(
                        id = "step_template",
                        type = WorkflowStepType.TEMPLATE_FORMAT,
                        title = "Transformation du contenu",
                        outputVariable = "result",
                        template = expressionOrTemplate.ifBlank { "{{$fieldId}}" }
                    )
                )
            }

            if (enablePdfExport) {
                steps.add(
                    WorkflowStepSpec(
                        id = "step_pdf",
                        type = WorkflowStepType.GENERATE_PDF,
                        title = "Génération du PDF A4",
                        outputVariable = "pdf_status",
                        template = "{{result}}"
                    )
                )
            }

            val outputs = buildList {
                add(
                    UiOutputSpec(
                        id = "out_primary",
                        label = "Résultat",
                        type = if (isFormulaMode) UiOutputType.CALCULATION_RESULT else UiOutputType.MARKDOWN_VIEW,
                        sourceVariable = "result"
                    )
                )
                if (enablePdfExport) {
                    add(
                        UiOutputSpec(
                            id = "out_pdf",
                            label = "Document PDF généré",
                            type = UiOutputType.PDF_PREVIEW_EXPORT,
                            sourceVariable = "pdf_status"
                        )
                    )
                }
            }

            val manifest = ToolManifest(
                id = toolId,
                name = name.trim(),
                description = description.trim(),
                version = "1.0.0",
                author = author,
                iconName = when (category) {
                    ToolCategory.CALCULATOR -> "calculate"
                    ToolCategory.DOCUMENT -> "description"
                    ToolCategory.CONVERTER -> "tune"
                    else -> "auto_awesome"
                },
                category = category,
                status = ToolStatus.ACTIVE,
                permissions = permissions,
                fields = listOf(
                    UiFieldSpec(
                        id = fieldId,
                        label = inputLabel.trim().ifBlank { "Entrée" },
                        type = inputType,
                        required = true
                    )
                ),
                outputs = outputs,
                workflow = steps,
                submitButtonLabel = "Exécuter",
                documentation = "Plugin créé manuellement par $author via l'éditeur déclaratif ForgeAI."
            )

            toolRepository.saveTool(
                manifest = manifest,
                changelogSummary = "Création manuelle du plugin"
            ).fold(
                onSuccess = { onCreated(it.manifest.id) },
                onFailure = { err ->
                    _manualError.value = err.localizedMessage ?: "Manifeste invalide."
                }
            )
        }
    }
}
