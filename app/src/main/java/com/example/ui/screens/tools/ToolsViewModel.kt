package com.example.ui.screens.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.local.ToolChangelogEntity
import com.example.core.data.local.ToolExecutionLogEntity
import com.example.core.data.local.ToolStorageEntryEntity
import com.example.core.data.preferences.AppPreferencesRepository
import com.example.core.data.preferences.ForgeSettings
import com.example.core.data.repository.InstalledTool
import com.example.core.data.repository.ToolRepository
import com.example.domain.manifest.ToolCategory
import com.example.domain.manifest.ToolStatus
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

class ToolsViewModel(
    private val toolRepository: ToolRepository,
    private val runtime: SandboxedToolRuntime,
    private val preferencesRepository: AppPreferencesRepository
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

    fun clearExecutionResult() {
        _executionResult.value = null
    }

    fun executeTool(tool: InstalledTool, inputs: Map<String, String>) {
        viewModelScope.launch {
            _isExecuting.value = true
            try {
                val currentSettings = settings.value
                val res = runtime.execute(
                    manifest = tool.manifest,
                    rawInputs = inputs,
                    strategy = currentSettings.loadBalancingStrategy,
                    globalTimeoutMs = currentSettings.sandboxTimeoutMs
                )
                _executionResult.value = res
            } catch (e: CancellationException) {
                throw e
            } finally {
                _isExecuting.value = false
            }
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
