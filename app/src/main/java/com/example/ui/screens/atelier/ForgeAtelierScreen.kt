package com.example.ui.screens.atelier

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ai.AgentLogItem
import com.example.core.ai.AgentStepStatus
import com.example.core.ai.ForgeAgentRole
import com.example.domain.manifest.ToolCategory
import com.example.domain.manifest.UiFieldType
import com.example.ui.components.FirebaseAiNoticeBanner
import com.example.ui.components.ForgeCard
import com.example.ui.components.ForgePill
import com.example.ui.theme.ForgeEmerald
import com.example.ui.theme.ForgeIndigoBright
import com.example.ui.theme.ForgeRose

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForgeAtelierScreen(
    viewModel: ForgeAtelierViewModel,
    onOpenTool: (String) -> Unit,
    onOpenApiManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val forgeState by viewModel.forgeState.collectAsStateWithLifecycle()
    val readiness by viewModel.providerReadiness.collectAsStateWithLifecycle()
    val manualError by viewModel.manualError.collectAsStateWithLifecycle()
    var showManualDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Atelier ForgeAI",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (readiness.hasActiveExternalProvider) {
                            ForgePill(
                                text = "${readiness.activeProviderCount} API • ${readiness.activeKeyCount} clé(s)",
                                color = ForgeEmerald
                            )
                        } else {
                            ForgePill(
                                text = "Aucune clé active",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Text(
                        text = "Décrivez l'outil souhaité en langage naturel. Les 8 agents spécialisés conçoivent, sécurisent et compilent son plugin sandboxé.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!readiness.hasActiveExternalProvider) {
                item {
                    FirebaseAiNoticeBanner(onOpenApiManager = onOpenApiManager)
                }
            }

            item {
                ForgeCard {
                    Text(
                        text = "Nouvelle commande de plugin",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = viewModel::onPromptChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("forge_prompt_input"),
                        label = { Text("Que souhaitez-vous créer ?") },
                        placeholder = {
                            Text("Ex : Crée un outil de synthèse de texte avec export PDF A4...")
                        },
                        minLines = 3,
                        maxLines = 6,
                        enabled = forgeState !is ForgeUiState.Forging
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = viewModel::forgeFromPrompt,
                            enabled = forgeState !is ForgeUiState.Forging && prompt.isNotBlank(),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("forge_submit_button")
                        ) {
                            if (forgeState is ForgeUiState.Forging) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                if (forgeState is ForgeUiState.Forging) "Forge en cours..." else "Forger avec les agents"
                            )
                        }

                        OutlinedButton(
                            onClick = { showManualDialog = true },
                            enabled = forgeState !is ForgeUiState.Forging,
                            modifier = Modifier.testTag("manual_builder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Build,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Manuel")
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(
                    visible = forgeState !is ForgeUiState.Idle,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    when (val state = forgeState) {
                        is ForgeUiState.Idle -> Unit
                        is ForgeUiState.Forging -> AgentPipelineCard(
                            title = "Collaboration des 8 agents (${state.providerInfo})",
                            logs = state.logs,
                            isRunning = true
                        )
                        is ForgeUiState.ForgedSuccess -> Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ForgeCard(
                                borderColor = ForgeEmerald.copy(alpha = 0.5f),
                                containerColor = ForgeEmerald.copy(alpha = 0.08f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = ForgeEmerald
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Plugin prêt : ${state.tool.manifest.name}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "v${state.tool.manifest.version} • ${state.totalLatencyMs} ms • ${state.providerInfo}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = state.tool.manifest.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = { onOpenTool(state.tool.manifest.id) },
                                        modifier = Modifier.testTag("open_forged_tool_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.size(6.dp))
                                        Text("Utiliser l'outil")
                                    }
                                    TextButton(onClick = viewModel::resetState) {
                                        Text("Nouvelle forge")
                                    }
                                }
                            }
                            AgentPipelineCard(
                                title = "Journal des agents",
                                logs = state.logs,
                                isRunning = false
                            )
                        }
                        is ForgeUiState.Error -> Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ForgeCard(
                                borderColor = ForgeRose.copy(alpha = 0.5f),
                                containerColor = ForgeRose.copy(alpha = 0.08f)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = ForgeRose
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Génération interrompue",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = ForgeRose
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (state.showFirebaseOrApiNotice) {
                                                Button(onClick = onOpenApiManager) {
                                                    Text("Ajouter une clé API")
                                                }
                                            }
                                            TextButton(onClick = viewModel::resetState) {
                                                Text("Fermer")
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.logs.isNotEmpty()) {
                                AgentPipelineCard(
                                    title = "État du pipeline",
                                    logs = state.logs,
                                    isRunning = false
                                )
                            }
                        }
                    }
                }
            }

            item {
                ForgeCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Architecture des agents & Sandbox",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ForgeAgentRole.entries.forEach { role ->
                            ForgePill(
                                text = "${role.badge} • ${role.title}",
                                color = ForgeIndigoBright
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Aucun code natif compilé à chaud : chaque outil s'exécute dans un runtime déclaratif isolé avec permissions explicites, évaluateur d'expressions sans réflexion et stockage cloisonné.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showManualDialog) {
        ManualPluginBuilderDialog(
            errorMessage = manualError,
            onDismiss = { showManualDialog = false },
            onCreate = { name, desc, cat, inputLabel, inputType, expr, isFormula, pdf ->
                viewModel.createManualPlugin(
                    name = name,
                    description = desc,
                    category = cat,
                    inputLabel = inputLabel,
                    inputType = inputType,
                    expressionOrTemplate = expr,
                    isFormulaMode = isFormula,
                    enablePdfExport = pdf
                ) { createdId ->
                    showManualDialog = false
                    onOpenTool(createdId)
                }
            }
        )
    }
}

@Composable
private fun AgentPipelineCard(
    title: String,
    logs: List<AgentLogItem>,
    isRunning: Boolean
) {
    ForgeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (isRunning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            logs.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (item.status) {
                        AgentStepStatus.PENDING -> Icon(
                            imageVector = Icons.Outlined.HourglassEmpty,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        AgentStepStatus.RUNNING -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        AgentStepStatus.COMPLETED -> Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = ForgeEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        AgentStepStatus.FAILED -> Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = ForgeRose,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${item.role.badge} — ${item.role.title}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = item.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualPluginBuilderDialog(
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        description: String,
        category: ToolCategory,
        inputLabel: String,
        inputType: UiFieldType,
        expressionOrTemplate: String,
        isFormulaMode: Boolean,
        enablePdfExport: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ToolCategory.CALCULATOR) }
    var inputLabel by remember { mutableStateOf("Valeur d'entrée") }
    var isFormulaMode by remember { mutableStateOf(true) }
    var expressionOrTemplate by remember { mutableStateOf("input_value * 1.2") }
    var enablePdfExport by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Constructeur manuel de plugin") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom du plugin") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_tool_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_tool_desc_input")
                )
                Text("Catégorie", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        ToolCategory.CALCULATOR,
                        ToolCategory.DOCUMENT,
                        ToolCategory.CONVERTER,
                        ToolCategory.PRODUCTIVITY
                    ).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label) }
                        )
                    }
                }
                OutlinedTextField(
                    value = inputLabel,
                    onValueChange = { inputLabel = it },
                    label = { Text("Libellé du champ (id: input_value)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isFormulaMode) "Mode formule mathématique" else "Mode modèle texte / Markdown",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = isFormulaMode,
                        onCheckedChange = {
                            isFormulaMode = it
                            expressionOrTemplate = if (it) "input_value * 1.2" else "# Rapport\n\n{{input_value}}"
                        }
                    )
                }
                OutlinedTextField(
                    value = expressionOrTemplate,
                    onValueChange = { expressionOrTemplate = it },
                    label = {
                        Text(if (isFormulaMode) "Expression sandbox (ex: input_value * 1.2)" else "Template ({{input_value}})")
                    },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_tool_logic_input")
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = enablePdfExport,
                        onCheckedChange = { enablePdfExport = it }
                    )
                    Text("Activer l'export PDF A4 natif", style = MaterialTheme.typography.bodyMedium)
                }
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ForgeRose
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name,
                        description,
                        category,
                        inputLabel,
                        if (isFormulaMode) UiFieldType.NUMBER else UiFieldType.MULTILINE_TEXT,
                        expressionOrTemplate,
                        isFormulaMode,
                        enablePdfExport
                    )
                },
                modifier = Modifier.testTag("manual_tool_create_confirm")
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}
