package com.example.ui.screens.apimanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ai.ApiRouterService
import com.example.core.ai.ConnectionTestResult
import com.example.core.ai.LoadBalancingStrategy
import com.example.core.ai.ProviderType
import com.example.core.data.local.ProviderWithKeys
import com.example.core.data.preferences.AppPreferencesRepository
import com.example.core.data.preferences.ForgeSettings
import com.example.core.data.repository.ApiManagerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ApiManagerViewModel(
    private val apiManagerRepository: ApiManagerRepository,
    private val apiRouterService: ApiRouterService,
    private val preferencesRepository: AppPreferencesRepository
) : ViewModel() {

    val providers: StateFlow<List<ProviderWithKeys>> = apiManagerRepository.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val settings: StateFlow<ForgeSettings> = preferencesRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ForgeSettings())

    private val _testingKeyIds = MutableStateFlow<Set<String>>(emptySet())
    val testingKeyIds: StateFlow<Set<String>> = _testingKeyIds.asStateFlow()

    private val _lastTestBanner = MutableStateFlow<Pair<String, ConnectionTestResult>?>(null)
    val lastTestBanner: StateFlow<Pair<String, ConnectionTestResult>?> = _lastTestBanner.asStateFlow()

    private val _formError = MutableStateFlow<String?>(null)
    val formError: StateFlow<String?> = _formError.asStateFlow()

    fun clearFormError() {
        _formError.value = null
    }

    fun dismissTestBanner() {
        _lastTestBanner.value = null
    }

    fun setLoadBalancingStrategy(strategy: LoadBalancingStrategy) {
        viewModelScope.launch {
            preferencesRepository.setLoadBalancingStrategy(strategy)
        }
    }

    fun addProvider(
        name: String,
        type: ProviderType,
        baseUrl: String,
        modelsCsv: String,
        priority: Int,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _formError.value = null
            val models = modelsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { type.defaultModels }
            apiManagerRepository.addProvider(
                name = name.ifBlank { type.displayName },
                providerType = type,
                baseUrl = baseUrl,
                modelPriority = models,
                priority = priority
            ).fold(
                onSuccess = { onSuccess() },
                onFailure = { err -> _formError.value = err.localizedMessage ?: "Erreur d'ajout." }
            )
        }
    }

    fun updateProvider(
        providerId: String,
        name: String,
        baseUrl: String,
        modelsCsv: String,
        priority: Int,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _formError.value = null
            val models = modelsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
            apiManagerRepository.updateProviderConfig(
                providerId = providerId,
                name = name,
                baseUrl = baseUrl,
                modelPriority = models,
                priority = priority
            ).fold(
                onSuccess = { onSuccess() },
                onFailure = { err -> _formError.value = err.localizedMessage ?: "Erreur de mise à jour." }
            )
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            apiManagerRepository.setProviderEnabled(providerId, enabled)
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            apiManagerRepository.deleteProvider(providerId)
        }
    }

    fun addApiKey(
        providerId: String,
        label: String,
        rawSecretKey: String,
        priority: Int,
        validateImmediately: Boolean,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _formError.value = null
            apiManagerRepository.addApiKey(
                providerId = providerId,
                label = label,
                rawSecretKey = rawSecretKey,
                priority = priority
            ).fold(
                onSuccess = { created ->
                    onSuccess()
                    if (validateImmediately) {
                        testKey(providerId, created.id, created.label)
                    }
                },
                onFailure = { err ->
                    _formError.value = err.localizedMessage ?: "Clé invalide."
                }
            )
        }
    }

    fun setKeyEnabled(keyId: String, enabled: Boolean) {
        viewModelScope.launch {
            apiManagerRepository.setKeyEnabled(keyId, enabled)
        }
    }

    fun deleteKey(keyId: String) {
        viewModelScope.launch {
            apiManagerRepository.deleteKey(keyId)
        }
    }

    fun testKey(providerId: String, keyId: String, keyLabel: String) {
        viewModelScope.launch {
            _testingKeyIds.value = _testingKeyIds.value + keyId
            try {
                val res = apiRouterService.testSingleKey(providerId, keyId)
                _lastTestBanner.value = keyLabel to res
            } finally {
                _testingKeyIds.value = _testingKeyIds.value - keyId
            }
        }
    }
}
