package com.example.ui.screens.apimanager

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.ai.LoadBalancingStrategy
import com.example.core.ai.ProviderProtocol
import com.example.core.ai.ProviderType
import com.example.core.data.local.ApiKeyEntity
import com.example.core.data.local.ProviderWithKeys
import com.example.ui.components.ForgeCard
import com.example.ui.components.ForgeEmptyState
import com.example.ui.components.ForgePill
import com.example.ui.theme.ForgeAmber
import com.example.ui.theme.ForgeEmerald
import com.example.ui.theme.ForgeRose
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiManagerScreen(
    viewModel: ApiManagerViewModel,
    modifier: Modifier = Modifier
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testingKeyIds by viewModel.testingKeyIds.collectAsStateWithLifecycle()
    val testBanner by viewModel.lastTestBanner.collectAsStateWithLifecycle()
    val formError by viewModel.formError.collectAsStateWithLifecycle()

    var showAddProviderDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderWithKeys?>(null) }
    var addKeyForProvider by remember { mutableStateOf<ProviderWithKeys?>(null) }

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gestionnaire API",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Fournisseurs et clés chiffrés via Android Keystore AES-256-GCM.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.clearFormError()
                            showAddProviderDialog = true
                        },
                        modifier = Modifier.testTag("add_provider_button")
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Fournisseur")
                    }
                }
            }

            testBanner?.let { (label, result) ->
                item {
                    val color = if (result.success) ForgeEmerald else ForgeRose
                    ForgeCard(
                        borderColor = color.copy(alpha = 0.5f),
                        containerColor = color.copy(alpha = 0.08f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (result.success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = color
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Test : $label", style = MaterialTheme.typography.titleSmall, color = color)
                                Text(result.message, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = viewModel::dismissTestBanner) {
                                Text("OK")
                            }
                        }
                    }
                }
            }

            if (providers.isEmpty()) {
                item {
                    ForgeEmptyState(
                        icon = Icons.Outlined.VpnKey,
                        title = "Aucun fournisseur API",
                        subtitle = "Ajoutez OpenAI, Anthropic, DeepSeek, Agnes AI, OpenRouter ou tout serveur compatible OpenAI. Vos clés restent chiffrées localement.",
                        actionLabel = "Ajouter un fournisseur",
                        actionTestTag = "empty_add_provider_button",
                        onAction = {
                            viewModel.clearFormError()
                            showAddProviderDialog = true
                        }
                    )
                }
            }

            item {
                ForgeCard {
                    Text(
                        text = "Stratégie de routage & équilibrage de charge",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LoadBalancingStrategy.entries.forEach { strategy ->
                            FilterChip(
                                selected = settings.loadBalancingStrategy == strategy,
                                onClick = { viewModel.setLoadBalancingStrategy(strategy) },
                                label = { Text(strategy.label) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = settings.loadBalancingStrategy.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (providers.isNotEmpty()) {
                items(providers, key = { it.provider.id }) { pwk ->
                    ProviderCard(
                        pwk = pwk,
                        testingKeyIds = testingKeyIds,
                        onToggleProvider = { enabled ->
                            viewModel.setProviderEnabled(pwk.provider.id, enabled)
                        },
                        onEditProvider = {
                            viewModel.clearFormError()
                            editingProvider = pwk
                        },
                        onDeleteProvider = { viewModel.deleteProvider(pwk.provider.id) },
                        onAddKey = {
                            viewModel.clearFormError()
                            addKeyForProvider = pwk
                        },
                        onToggleKey = { keyId, enabled -> viewModel.setKeyEnabled(keyId, enabled) },
                        onTestKey = { key -> viewModel.testKey(pwk.provider.id, key.id, key.label) },
                        onDeleteKey = { keyId -> viewModel.deleteKey(keyId) }
                    )
                }
            }
        }
    }

    if (showAddProviderDialog) {
        ProviderConfigDialog(
            initial = null,
            errorMessage = formError,
            onDismiss = { showAddProviderDialog = false },
            onConfirm = { name, type, url, modelsCsv, priority ->
                viewModel.addProvider(name, type, url, modelsCsv, priority) {
                    showAddProviderDialog = false
                }
            }
        )
    }

    editingProvider?.let { target ->
        val pType = ProviderType.entries.firstOrNull { it.name == target.provider.providerType }
            ?: ProviderType.OPENAI_COMPATIBLE
        ProviderConfigDialog(
            initial = target,
            errorMessage = formError,
            onDismiss = { editingProvider = null },
            onConfirm = { name, _, url, modelsCsv, priority ->
                viewModel.updateProvider(target.provider.id, name, url, modelsCsv, priority) {
                    editingProvider = null
                }
            }
        )
    }

    addKeyForProvider?.let { target ->
        AddApiKeyDialog(
            providerName = target.provider.name,
            errorMessage = formError,
            onDismiss = { addKeyForProvider = null },
            onConfirm = { label, secret, priority, testNow ->
                viewModel.addApiKey(
                    providerId = target.provider.id,
                    label = label,
                    rawSecretKey = secret,
                    priority = priority,
                    validateImmediately = testNow
                ) {
                    addKeyForProvider = null
                }
            }
        )
    }
}

@Composable
private fun ProviderCard(
    pwk: ProviderWithKeys,
    testingKeyIds: Set<String>,
    onToggleProvider: (Boolean) -> Unit,
    onEditProvider: () -> Unit,
    onDeleteProvider: () -> Unit,
    onAddKey: () -> Unit,
    onToggleKey: (String, Boolean) -> Unit,
    onTestKey: (ApiKeyEntity) -> Unit,
    onDeleteKey: (String) -> Unit
) {
    val provider = pwk.provider
    val providerType = ProviderType.entries.firstOrNull { it.name == provider.providerType }
        ?: ProviderType.OPENAI_COMPATIBLE

    ForgeCard(modifier = Modifier.testTag("provider_card_${provider.id}")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    ForgePill(text = "P${provider.priority}")
                }
                Text(
                    text = "${providerType.displayName} • ${provider.baseUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggleProvider
            )
        }

        if (providerType.protocol == ProviderProtocol.FIREBASE_AI_PLACEHOLDER) {
            Spacer(modifier = Modifier.height(8.dp))
            ForgePill(
                text = "Requiert Firebase AI Logic (non activé) — utilisez OpenRouter pour Gemini",
                color = ForgeAmber
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Modèles prioritaires : ${provider.modelPriorityCsv}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onAddKey,
                enabled = providerType.protocol != ProviderProtocol.FIREBASE_AI_PLACEHOLDER,
                modifier = Modifier.testTag("add_key_button_${provider.id}")
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Ajouter une clé (${pwk.keys.size})")
            }
            Row {
                IconButton(onClick = onEditProvider) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Modifier le fournisseur")
                }
                IconButton(onClick = onDeleteProvider) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Supprimer le fournisseur",
                        tint = ForgeRose
                    )
                }
            }
        }

        if (pwk.keys.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pwk.keys.sortedBy { it.priority }.forEach { key ->
                    ApiKeyRow(
                        key = key,
                        isTesting = key.id in testingKeyIds,
                        onToggle = { enabled -> onToggleKey(key.id, enabled) },
                        onTest = { onTestKey(key) },
                        onDelete = { onDeleteKey(key.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyRow(
    key: ApiKeyEntity,
    isTesting: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    val inCooldown = key.quotaResetAtEpochMs != null && key.quotaResetAtEpochMs > System.currentTimeMillis()
    val statusColor = when {
        !key.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        inCooldown -> ForgeAmber
        key.lastError != null -> ForgeRose
        key.lastStatus.startsWith("Valid") || key.lastStatus == "Opérationnel" -> ForgeEmerald
        else -> MaterialTheme.colorScheme.primary
    }

    ForgeCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(key.label, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = key.maskedKey,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = key.enabled, onCheckedChange = onToggle)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ForgePill(
                text = if (inCooldown) "Pause quota" else key.lastStatus,
                color = statusColor
            )
            Text(
                text = "${key.totalRequests} req • ${key.totalErrors} err • ${key.avgLatencyMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!key.lastError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = key.lastError,
                style = MaterialTheme.typography.bodySmall,
                color = ForgeRose,
                maxLines = 2
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onTest,
                enabled = !isTesting,
                modifier = Modifier.testTag("test_key_${key.id}")
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.size(4.dp))
                Text("Tester")
            }
            TextButton(onClick = onDelete) {
                Text("Supprimer", color = ForgeRose)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderConfigDialog(
    initial: ProviderWithKeys?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: ProviderType, baseUrl: String, modelsCsv: String, priority: Int) -> Unit
) {
    val initialType = initial?.let { p ->
        ProviderType.entries.firstOrNull { it.name == p.provider.providerType }
    } ?: ProviderType.OPENAI

    var selectedType by remember { mutableStateOf(initialType) }
    var name by remember { mutableStateOf(initial?.provider?.name ?: initialType.displayName) }
    var baseUrl by remember { mutableStateOf(initial?.provider?.baseUrl ?: initialType.defaultBaseUrl) }
    var modelsCsv by remember {
        mutableStateOf(
            initial?.provider?.modelPriorityCsv ?: initialType.defaultModels.joinToString(", ")
        )
    }
    var priorityText by remember { mutableStateOf((initial?.provider?.priority ?: 1).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Ajouter un fournisseur IA" else "Modifier le fournisseur") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (initial == null) {
                    Text("Type de fournisseur", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProviderType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = {
                                    selectedType = type
                                    name = type.displayName
                                    baseUrl = type.defaultBaseUrl
                                    modelsCsv = type.defaultModels.joinToString(", ")
                                },
                                label = { Text(type.displayName) }
                            )
                        }
                    }
                    Text(
                        text = selectedType.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom affiché") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_name_input")
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("URL de base HTTPS") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_url_input")
                )
                OutlinedTextField(
                    value = modelsCsv,
                    onValueChange = { modelsCsv = it },
                    label = { Text("Modèles prioritaires (séparés par virgule)") },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_models_input")
                )
                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it },
                    label = { Text("Priorité (1 = plus élevée)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = ForgeRose)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        name,
                        selectedType,
                        baseUrl,
                        modelsCsv,
                        priorityText.toIntOrNull() ?: 1
                    )
                },
                modifier = Modifier.testTag("confirm_provider_button")
            ) {
                Text(if (initial == null) "Ajouter" else "Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun AddApiKeyDialog(
    providerName: String,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (label: String, secretKey: String, priority: Int, validateNow: Boolean) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var priorityText by remember { mutableStateOf("1") }
    var validateNow by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle clé — $providerName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Libellé (ex : Clé Production 1)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_label_input")
                )
                OutlinedTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = { Text("Clé secrète API") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_secret_input")
                )
                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it },
                    label = { Text("Priorité de rotation (1 = principale)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = validateNow, onCheckedChange = { validateNow = it })
                    Text("Tester la connexion automatiquement", style = MaterialTheme.typography.bodySmall)
                }
                if (!errorMessage.isNullOrBlank()) {
                    Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = ForgeRose)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(label, secretKey, priorityText.toIntOrNull() ?: 1, validateNow)
                },
                modifier = Modifier.testTag("confirm_add_key_button")
            ) {
                Text("Chiffrer & Ajouter")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
