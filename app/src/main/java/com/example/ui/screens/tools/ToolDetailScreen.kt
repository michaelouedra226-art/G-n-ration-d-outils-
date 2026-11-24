package com.example.ui.screens.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.manifest.ToolStatus
import com.example.ui.components.ForgeCard
import com.example.ui.components.ForgePill
import com.example.ui.theme.ForgeEmerald
import com.example.ui.theme.ForgeRose
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolDetailScreen(
    toolId: String,
    viewModel: ToolsViewModel,
    onBack: () -> Unit,
    onRunTool: () -> Unit
) {
    BackHandler(onBack = onBack)

    val toolState by remember(toolId) { viewModel.observeTool(toolId) }
        .collectAsStateWithLifecycle()
    val changelogs by remember(toolId) { viewModel.observeChangelogs(toolId) }
        .collectAsStateWithLifecycle()
    val executionLogs by remember(toolId) { viewModel.observeExecutionLogs(toolId) }
        .collectAsStateWithLifecycle()
    val storageEntries by remember(toolId) { viewModel.observeToolStorage(toolId) }
        .collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showManifestEditor by remember { mutableStateOf(false) }
    var manifestJsonDraft by remember { mutableStateOf("") }
    var changelogSummaryDraft by remember { mutableStateOf("") }
    var editorError by remember { mutableStateOf<String?>(null) }

    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(toolState?.manifest?.name ?: "Détails du plugin") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.testTag("delete_tool_button")
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Supprimer le plugin",
                            tint = ForgeRose
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        val tool = toolState
        if (tool == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("Plugin introuvable.")
            }
            return@Scaffold
        }

        val manifest = tool.manifest

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    ForgeCard {
                        Text(
                            text = manifest.name,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Auteur : ${manifest.author} • Version : ${manifest.version} • Créé le ${
                                dateFormatter.format(Date(tool.entity.createdAt))
                            }",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ToolStatus.entries.forEach { st ->
                                FilterChip(
                                    selected = manifest.status == st,
                                    onClick = { viewModel.updateStatus(toolId, st) },
                                    label = { Text(st.label) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onRunTool) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Exécuter")
                            }
                            OutlinedButton(
                                onClick = {
                                    manifestJsonDraft = viewModel.serializeManifest(tool)
                                    changelogSummaryDraft = ""
                                    editorError = null
                                    showManifestEditor = true
                                },
                                modifier = Modifier.testTag("edit_manifest_button")
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Éditer manifeste")
                            }
                        }
                    }
                }

                item {
                    ForgeCard {
                        Text("Permissions déclarées", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (manifest.permissions.isEmpty()) {
                            Text(
                                "Aucune permission système requise (sandbox math/texte pur).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                manifest.permissions.forEach { perm ->
                                    ForgePill(text = perm.label)
                                }
                            }
                        }
                        if (manifest.documentation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Documentation", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                manifest.documentation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (storageEntries.isNotEmpty()) {
                    item {
                        ForgeCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Stockage isolé du plugin", style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { viewModel.clearToolStorage(toolId) }) {
                                    Text("Vider")
                                }
                            }
                            storageEntries.forEach { entry ->
                                Text(
                                    text = "${entry.storageKey}: ${entry.value.take(120)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Journal des modifications", style = MaterialTheme.typography.titleMedium)
                    }
                }

                items(changelogs, key = { it.id }) { entry ->
                    ForgeCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "v${entry.version} • ${entry.author}",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = dateFormatter.format(Date(entry.timestamp)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
                        if (entry.details.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                entry.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (executionLogs.isNotEmpty()) {
                    item {
                        Text("Exécutions récentes", style = MaterialTheme.typography.titleMedium)
                    }
                    items(executionLogs, key = { it.id }) { log ->
                        ForgeCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ForgePill(
                                    text = if (log.success) "Succès • ${log.durationMs} ms" else "Échec",
                                    color = if (log.success) ForgeEmerald else ForgeRose
                                )
                                Text(
                                    text = dateFormatter.format(Date(log.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = log.errorMessage ?: log.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer ce plugin ?") },
            text = { Text("Le manifeste, l'historique et le stockage isolé de cet outil seront supprimés définitivement.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteTool(toolId, onBack)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeRose)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Annuler") }
            }
        )
    }

    if (showManifestEditor) {
        AlertDialog(
            onDismissRequest = { showManifestEditor = false },
            title = { Text("Éditeur JSON du manifeste") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = changelogSummaryDraft,
                        onValueChange = { changelogSummaryDraft = it },
                        label = { Text("Note de version (changelog)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = manifestJsonDraft,
                        onValueChange = { manifestJsonDraft = it },
                        label = { Text("JSON Manifeste") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        minLines = 8,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!editorError.isNullOrBlank()) {
                        Text(
                            text = editorError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = ForgeRose
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateManifestJson(
                        toolId = toolId,
                        rawJson = manifestJsonDraft,
                        changelogSummary = changelogSummaryDraft.ifBlank { "Mise à jour manuelle du manifeste" }
                    ) { result ->
                        result.fold(
                            onSuccess = { showManifestEditor = false },
                            onFailure = { err -> editorError = err.localizedMessage ?: "JSON invalide" }
                        )
                    }
                }) {
                    Text("Valider & Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManifestEditor = false }) { Text("Annuler") }
            }
        )
    }
}
