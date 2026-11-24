package com.example.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.data.preferences.ThemeMode
import com.example.core.security.SandboxGuard
import com.example.ui.components.FirebaseAiNoticeBanner
import com.example.ui.components.ForgeCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenApiManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var authorDraft by remember { mutableStateOf(settings.authorName) }

    LaunchedEffect(settings.authorName) {
        authorDraft = settings.authorName
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Paramètres & Sécurité",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                FirebaseAiNoticeBanner(onOpenApiManager = onOpenApiManager)
            }

            item {
                ForgeCard {
                    Text("Apparence", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.label) },
                                modifier = Modifier.testTag("theme_chip_${mode.name}")
                            )
                        }
                    }
                }
            }

            item {
                ForgeCard {
                    Text("Identité d'auteur des plugins", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authorDraft,
                        onValueChange = { authorDraft = it },
                        label = { Text("Nom d'auteur enregistré dans les manifestes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.setAuthorName(authorDraft) },
                        enabled = authorDraft.isNotBlank() && authorDraft != settings.authorName
                    ) {
                        Text("Enregistrer")
                    }
                }
            }

            item {
                ForgeCard {
                    val timeoutSec = (settings.sandboxTimeoutMs / 1000L).toInt()
                    Text("Politique Sandbox", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Délai maximal d'exécution : ${timeoutSec}s",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = timeoutSec.toFloat(),
                        onValueChange = { viewModel.setSandboxTimeoutSeconds(it.toInt()) },
                        valueRange = 10f..120f
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Limite d'étapes par workflow : ${SandboxGuard.MAX_WORKFLOW_STEPS}\n" +
                            "• Taille max d'entrée : ${SandboxGuard.MAX_INPUT_TEXT_LENGTH / 1000} Ko\n" +
                            "• Stockage par outil cloisonné par ID\n" +
                            "• Chiffrement clés API : Android Keystore AES-256-GCM\n" +
                            "• Trafic réseau : HTTPS obligatoire",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
