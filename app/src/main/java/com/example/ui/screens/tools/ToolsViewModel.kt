package com.example.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ai.AgentLogItem
import com.example.core.ai.AiMessage
import com.example.core.ai.ForgePipelineEvent
import com.example.core.ai.MultiAgentForgePipeline
import com.example.core.data.local.ToolChangelogEntity
import com.example.core.data.local.ToolExecutionLogEntity
import com.example.core.data.local.ToolStorageEntryEntity
import com.example.core.data.preferences.AppPreferencesRepository
import com.example.core.data.preferences.ForgeSettings
import com.example.core.data.repository.InstalledTool
import com.example.core.data.repository.ToolRepository
import com.example.domain.manifest.ToolCategory
import com.example.domain.manifest.ToolStatus
import com.example.domain.manifest.UiOutputType
import com.example.runtime.SandboxedToolRuntime
import com.example.runtime.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RefineToolState {
    data object Idle : RefineToolState
    data class Running(val logs: List<AgentLogItem>, val providerInfo: String) : RefineToolState
    data class Success(val newVersion: String) : RefineToolState
    data class Error(val message: String) : RefineToolState
}

class ToolsViewModel(
    private val toolRepository: ToolRepository,
    private val runtime: SandboxedToolRuntime,
    private val preferencesRepository: AppPreferencesRepository,
    private val multiAgentPipeline: MultiAgentForgePipeline? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow<ToolCategory?>(null)
    val selectedCategory: StateFlow<ToolCategory?> = _selectedCategory.asStateFlow()

    val settings: StateFlow<ForgeSettings> = preferencesRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ForgeSettings())

    val tools: StateFlow<List<InstalledTool>> = combine(
        toolRepository.observeTools(),
        _searchQuery,
        _selectedCategory
    ) { all, query, cat ->
        all.filter { item ->
            val matchesCategory = cat == null || item.manifest.category == cat
            val matchesQuery = query.isBlank() ||
                item.manifest.name.contains(query, ignoreCase = true) ||
                item.manifest.description.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onCategorySelected(category: ToolCategory?) {
        _selectedCategory.value = category
    }

    fun observeTool(toolId: String): StateFlow<InstalledTool?> =
        toolRepository.observeTool(toolId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    fun observeChangelogs(toolId: String): StateFlow<List<ToolChangelogEntity>> =
        toolRepository.observeChangelogs(toolId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun observeExecutionLogs(toolId: String): StateFlow<List<ToolExecutionLogEntity>> =
        toolRepository.observeExecutionLogs(toolId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    fun observeToolStorage(toolId: String): StateFlow<List<ToolStorageEntryEntity>> =
        toolRepository.observeToolStorage(toolId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _executionResult = MutableStateFlow<ToolExecutionResult?>(null)
    val executionResult: StateFlow<ToolExecutionResult?> = _executionResult.asStateFlow()

    private val _chatHistory = MutableStateFlow<List<AiMessage>>(emptyList())
    val chatHistory: StateFlow<List<AiMessage>> = _chatHistory.asStateFlow()

    private val _refineState = MutableStateFlow<RefineToolState>(RefineToolState.Idle)
    val refineState: StateFlow<RefineToolState> = _refineState.asStateFlow()

    fun clearExecutionResult() {
        _executionResult.value = null
        _chatHistory.value = emptyList()
    }

    fun clearChatHistory() {
        _chatHistory.value = emptyList()
        _executionResult.value = null
    }

    fun executeTool(tool: InstalledTool, inputs: Map<String, String>) {
        viewModelScope.launch {
            _isExecuting.value = true
            try {
                val currentSettings = settings.value
                val isChatPlugin = tool.manifest.outputs.any { it.type == UiOutputType.CHAT_TRANSCRIPT } ||
                    tool.manifest.category == ToolCategory.AI_ASSISTANT
                val historyToSend = if (isChatPlugin) _chatHistory.value else emptyList()

                val res = runtime.execute(
                    manifest = tool.manifest,
                    rawInputs = inputs,
                    strategy = currentSettings.loadBalancingStrategy,
                    globalTimeoutMs = currentSettings.sandboxTimeoutMs,
                    conversationHistory = historyToSend
                )
                _executionResult.value = res

                if (res.success && isChatPlugin) {
                    val userText = inputs.values.firstOrNull { it.isNotBlank() }.orEmpty()
                    val chatOutputId = tool.manifest.outputs
                        .firstOrNull { it.type == UiOutputType.CHAT_TRANSCRIPT }?.id
                        ?: tool.manifest.outputs.firstOrNull()?.id
                    val assistantText = chatOutputId?.let { res.outputs[it] }.orEmpty()
                    if (userText.isNotBlank() && assistantText.isNotBlank()) {
                        _chatHistory.value = (_chatHistory.value + listOf(
                            AiMessage(role = "user", content = userText),
                            AiMessage(role = "assistant", content = assistantText)
                        )).takeLast(20)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isExecuting.value = false
            }
        }
    }

    fun refineToolWithAi(tool: InstalledTool, modificationPrompt: String) {
        val pipeline = multiAgentPipeline ?: return
        val cleanPrompt = modificationPrompt.trim()
        if (cleanPrompt.isBlank()) {
            _refineState.value = RefineToolState.Error("Décrivez la modification souhaitée.")
            return
        }
        viewModelScope.launch {
            val currentSettings = settings.value
            pipeline.refineTool(
                existingManifest = tool.manifest,
                modificationPrompt = cleanPrompt,
                authorName = currentSettings.authorName,
                strategy = currentSettings.loadBalancingStrategy
            ).collect { event ->
                when (event) {
                    is ForgePipelineEvent.Progress -> {
                        _refineState.value = RefineToolState.Running(event.logs, event.providerInfo)
                    }
                    is ForgePipelineEvent.Failure -> {
                        _refineState.value = RefineToolState.Error(event.errorMessage)
                    }
                    is ForgePipelineEvent.Success -> {
                        val saveRes = toolRepository.saveTool(
                            manifest = event.manifest,
                            changelogSummary = cleanPrompt.take(120),
                            changelogDetails = "Évolution IA (${event.providerInfo}, ${event.totalLatencyMs} ms)"
                        )
                        saveRes.fold(
                            onSuccess = { saved ->
                                _refineState.value = RefineToolState.Success(saved.manifest.version)
                            },
                            onFailure = { err ->
                                _refineState.value = RefineToolState.Error(
                                    err.localizedMessage ?: "Échec de sauvegarde."
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    fun resetRefineState() {
        _refineState.value = RefineToolState.Idle
    }

    fun importToolFromJson(rawJson: String, onResult: (Result<InstalledTool>) -> Unit) {
        viewModelScope.launch {
            onResult(toolRepository.importToolFromJson(rawJson))
        }
    }

    fun updateStatus(toolId: String, status: ToolStatus) {
        viewModelScope.launch {
            toolRepository.updateToolStatus(toolId, status)
        }
    }

    fun updateManifestJson(
        toolId: String,
        rawJson: String,
        changelogSummary: String,
        onResult: (Result<InstalledTool>) -> Unit
    ) {
        viewModelScope.launch {
            val res = toolRepository.updateToolFromJson(toolId, rawJson, changelogSummary)
            onResult(res)
        }
    }

    fun clearToolStorage(toolId: String) {
        viewModelScope.launch {
            toolRepository.clearToolStorage(toolId)
        }
    }

    fun deleteTool(toolId: String, onDeleted: () -> Unit) {
        viewModelScope.launch {
            toolRepository.deleteTool(toolId)
            onDeleted()
        }
    }

    fun serializeManifest(tool: InstalledTool): String =
        toolRepository.serializeManifest(tool.manifest)
}
