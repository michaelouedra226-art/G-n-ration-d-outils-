package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.data.preferences.AppPreferencesRepository
import com.example.core.data.preferences.ForgeSettings
import com.example.core.data.preferences.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesRepository: AppPreferencesRepository
) : ViewModel() {

    val settings: StateFlow<ForgeSettings> = preferencesRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), ForgeSettings())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(mode)
        }
    }

    fun setSandboxTimeoutSeconds(seconds: Int) {
        viewModelScope.launch {
            preferencesRepository.setSandboxTimeoutMs(seconds * 1000L)
        }
    }

    fun setAuthorName(name: String) {
        viewModelScope.launch {
            preferencesRepository.setAuthorName(name)
        }
    }
}
