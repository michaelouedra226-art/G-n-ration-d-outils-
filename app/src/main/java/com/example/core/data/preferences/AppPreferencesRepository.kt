package com.example.core.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.ai.LoadBalancingStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.forgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "forge_settings")

enum class ThemeMode(val label: String) {
    SYSTEM("Système"),
    DARK("Sombre"),
    LIGHT("Clair")
}

data class ForgeSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val loadBalancingStrategy: LoadBalancingStrategy = LoadBalancingStrategy.PRIORITY_AND_ROTATION,
    val sandboxTimeoutMs: Long = 60_000L,
    val authorName: String = "Atelier Local"
)

/**
 * Reactive DataStore-backed preferences repository for theme, routing, and sandbox policy settings.
 */
class AppPreferencesRepository(private val context: Context) {

    val settings: Flow<ForgeSettings> = context.forgeDataStore.data.map { prefs ->
        val themeRaw = prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name
        val strategyRaw = prefs[KEY_LOAD_BALANCING] ?: LoadBalancingStrategy.PRIORITY_AND_ROTATION.name
        ForgeSettings(
            themeMode = ThemeMode.entries.firstOrNull { it.name == themeRaw } ?: ThemeMode.SYSTEM,
            loadBalancingStrategy = LoadBalancingStrategy.entries.firstOrNull { it.name == strategyRaw }
                ?: LoadBalancingStrategy.PRIORITY_AND_ROTATION,
            sandboxTimeoutMs = (prefs[KEY_SANDBOX_TIMEOUT] ?: 60_000L).coerceIn(10_000L, 120_000L),
            authorName = prefs[KEY_AUTHOR_NAME]?.takeIf { it.isNotBlank() } ?: "Atelier Local"
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.forgeDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setLoadBalancingStrategy(strategy: LoadBalancingStrategy) {
        context.forgeDataStore.edit { it[KEY_LOAD_BALANCING] = strategy.name }
    }

    suspend fun setSandboxTimeoutMs(timeoutMs: Long) {
        context.forgeDataStore.edit {
            it[KEY_SANDBOX_TIMEOUT] = timeoutMs.coerceIn(10_000L, 120_000L)
        }
    }

    suspend fun setAuthorName(name: String) {
        val clean = name.trim().take(48)
        context.forgeDataStore.edit {
            it[KEY_AUTHOR_NAME] = clean.ifBlank { "Atelier Local" }
        }
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_LOAD_BALANCING = stringPreferencesKey("load_balancing_strategy")
        val KEY_SANDBOX_TIMEOUT = longPreferencesKey("sandbox_timeout_ms")
        val KEY_AUTHOR_NAME = stringPreferencesKey("author_name")
    }
}
