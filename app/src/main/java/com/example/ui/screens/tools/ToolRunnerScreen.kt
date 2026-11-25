package com.example.ui.screens.tools

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.domain.manifest.ToolPermission
import com.example.domain.manifest.UiFieldSpec
import com.example.domain.manifest.UiFieldType
import com.example.domain.manifest.UiOutputType
import com.example.ui.components.BarChartOutputView
import com.example.ui.components.ChatTranscriptOutputView
import com.example.ui.components.ForgeCard
import com.example.ui.components.ForgePill
import com.example.ui.components.KeyValueCardsOutputView
import com.example.ui.components.ListItemsOutputView
import com.example.ui.components.MarkdownTextView
import com.example.ui.components.PdfPreviewCard
import com.example.ui.components.SpeakTextButton
import com.example.ui.components.TableOutputView
import com.example.ui.theme.ForgeEmerald
import com.example.ui.theme.ForgeRose
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ToolRunnerScreen(
    toolId: String,
    viewModel: ToolsViewModel,
    onBack: () -> Unit,
    onInspectDetails: () -> Unit
) {
    BackHandler(onBack = onBack)

    DisposableEffect(toolId) {
        onDispose { viewModel.clearExecutionResult() }
    }

    val toolState by remember(toolId) { viewModel.observeTool(toolId) }
        .collectAsStateWithLifecycle()
    val isExecuting by viewModel.isExecuting.collectAsStateWithLifecycle()
    val executionResult by viewModel.executionResult.collectAsStateWithLifecycle()
    val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(executionResult) {
        val res = executionResult
        val manifest = toolState?.manifest
        if (res != null && res.success && manifest != null &&
            ToolPermission.CLIPBOARD in manifest.permissions
        ) {
            val primaryText = manifest.outputs.firstOrNull()?.let { res.outputs[it.id] }.orEmpty()
            if (primaryText.isNotBlank()) {
                clipboard.setText(AnnotatedString(primaryText))
                snackbarHostState.showSnackbar("Résultat copié automatiquement dans le presse-papiers")
            }
        }
    }

    val fieldValues = remember(toolId, toolState?.manifest?.version) {
        mutableStateMapOf<String, String>().apply {
            toolState?.manifest?.fields?.forEach { field ->
                put(field.id, field.defaultValue)
            }
        }
    }

    var activePickerFieldId by remember { mutableStateOf<String?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val fid = activePickerFieldId
        if (uri != null && fid != null) {
            fieldValues[fid] = uri.toString()
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val fid = activePickerFieldId
        if (uri != null && fid != null) {
            fieldValues[fid] = uri.toString()
        }
    }

    val pdfExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        val pdfPath = executionResult?.pdfArtifact?.filePath
        if (destUri != null && pdfPath != null) {
            runCatching {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    File(pdfPath).inputStream().use { input -> input.copyTo(out) }
                }
            }.onSuccess {
                scope.launch { snackbarHostState.showSnackbar("PDF enregistré avec succès.") }
            }.onFailure { err ->
                scope.launch { snackbarHostState.showSnackbar("Échec export PDF : ${err.localizedMessage}") }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0.dp),
                title = {
                    Text(toolState?.manifest?.name ?: "Exécution Plugin")
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("runner_back_button")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onInspectDetails, modifier = Modifier.testTag("runner_details_button")) {
                        Icon(Icons.Outlined.Info, contentDescription = "Détails du plugin")
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
                Text("Chargement du plugin...")
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ForgePill(text = manifest.category.label)
                            ForgePill(
                                text = "Sandbox v${manifest.version}",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = manifest.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    ForgeCard {
                        Text(
                            text = "Paramètres d'exécution",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            manifest.fields.forEach { field ->
                                DynamicFieldInput(
                                    field = field,
                                    value = fieldValues[field.id] ?: field.defaultValue,
                                    onValueChange = { fieldValues[field.id] = it },
                                    onPickImage = {
                                        activePickerFieldId = field.id
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    onPickDocument = {
                                        activePickerFieldId = field.id
                                        documentPickerLauncher.launch(
                                            arrayOf("text/plain", "text/markdown", "application/json", "text/*")
                                        )
                                    }
                                )
                            }

                            Button(
                                onClick = { viewModel.executeTool(tool, fieldValues.toMap()) },
                                enabled = !isExecuting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("execute_tool_button")
                            ) {
                                if (isExecuting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(if (isExecuting) "Exécution sandbox..." else manifest.submitButtonLabel)
                            }
                        }
                    }
                }

                executionResult?.let { result ->
                    if (!result.success) {
                        item {
                            ForgeCard(
                                borderColor = ForgeRose.copy(alpha = 0.5f),
                                containerColor = ForgeRose.copy(alpha = 0.08f)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        Icons.Outlined.ErrorOutline,
                                        contentDescription = null,
                                        tint = ForgeRose
                                    )
                                    Column {
                                        Text(
                                            text = "Erreur d'exécution",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = ForgeRose
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = result.errorMessage ?: "Échec inconnu",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        manifest.outputs.forEach { outputSpec ->
                            val outputText = result.outputs[outputSpec.id].orEmpty()
                            item(key = outputSpec.id) {
                                ForgeCard(
                                    borderColor = ForgeEmerald.copy(alpha = 0.45f)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = outputSpec.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(outputText))
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("Copié dans le presse-papiers")
                                                }
                                            },
                                            modifier = Modifier.testTag("copy_output_${outputSpec.id}")
                                        ) {
                                            Icon(
                                                Icons.Outlined.ContentCopy,
                                                contentDescription = "Copier le résultat"
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))

                                    when (outputSpec.type) {
                                        UiOutputType.CALCULATION_RESULT -> {
                                            Text(
                                                text = outputText,
                                                style = MaterialTheme.typography.displaySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.testTag("calculation_output_text")
                                            )
                                        }
                                        UiOutputType.JSON_VIEW -> {
                                            Text(
                                                text = outputText,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = JetBrainsMonoFontFamily
                                                )
                                            )
                                        }
                                        UiOutputType.KEY_VALUE_CARDS -> {
                                            KeyValueCardsOutputView(rawText = outputText)
                                        }
                                        UiOutputType.TABLE_VIEW -> {
                                            TableOutputView(rawText = outputText)
                                        }
                                        UiOutputType.BAR_CHART -> {
                                            BarChartOutputView(rawText = outputText)
                                        }
                                        UiOutputType.LIST_ITEMS -> {
                                            ListItemsOutputView(rawText = outputText)
                                        }
                                        UiOutputType.CHAT_TRANSCRIPT -> {
                                            ChatTranscriptOutputView(
                                                messages = chatHistory,
                                                latestReply = outputText,
                                                onClearHistory = viewModel::clearChatHistory
                                            )
                                        }
                                        UiOutputType.PDF_PREVIEW_EXPORT -> {
                                            val pdf = result.pdfArtifact
                                            if (pdf != null) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.PictureAsPdf,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(pdf.fileName, style = MaterialTheme.typography.titleSmall)
                                                        Text(
                                                            "${pdf.pageCount} page(s) • ${(pdf.byteSize / 1024).coerceAtLeast(1)} Ko",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(10.dp))
                                                PdfPreviewCard(filePath = pdf.filePath)
                                                Spacer(modifier = Modifier.height(10.dp))
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = { pdfExportLauncher.launch(pdf.fileName) },
                                                        modifier = Modifier.testTag("save_pdf_button")
                                                    ) {
                                                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.size(6.dp))
                                                        Text("Enregistrer PDF")
                                                    }
                                                    OutlinedButton(
                                                        onClick = {
                                                            val uri = FileProvider.getUriForFile(
                                                                context,
                                                                "${context.packageName}.fileprovider",
                                                                File(pdf.filePath)
                                                            )
                                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                                type = "application/pdf"
                                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            context.startActivity(
                                                                Intent.createChooser(shareIntent, "Partager le PDF")
                                                            )
                                                        },
                                                        modifier = Modifier.testTag("share_pdf_button")
                                                    ) {
                                                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        Spacer(modifier = Modifier.size(6.dp))
                                                        Text("Partager")
                                                    }
                                                }
                                            } else {
                                                Text(outputText, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        else -> {
                                            MarkdownTextView(markdown = outputText)
                                        }
                                    }

                                    val ttsPayload = result.speakableText?.takeIf { it.isNotBlank() } ?: outputText
                                    if (ToolPermission.TEXT_TO_SPEECH in manifest.permissions &&
                                        outputSpec.type != UiOutputType.PDF_PREVIEW_EXPORT &&
                                        ttsPayload.isNotBlank()
                                    ) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        SpeakTextButton(text = ttsPayload)
                                    }
                                }
                            }
                        }

                        item {
                            ForgeCard {
                                Text(
                                    text = "Trace sandbox (${result.totalDurationMs} ms • ${result.providerUsed})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                result.traces.forEach { trace ->
                                    Text(
                                        text = "• ${trace.stepTitle} (${trace.durationMs} ms) — ${trace.detail}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DynamicFieldInput(
    field: UiFieldSpec,
    value: String,
    onValueChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit
) {
    when (field.type) {
        UiFieldType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(field.label) },
            placeholder = if (field.placeholder.isNotBlank()) ({ Text(field.placeholder) }) else null,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field_${field.id}")
        )
        UiFieldType.MULTILINE_TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(field.label) },
            placeholder = if (field.placeholder.isNotBlank()) ({ Text(field.placeholder) }) else null,
            minLines = 3,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field_${field.id}")
        )
        UiFieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(field.label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("field_${field.id}")
        )
        UiFieldType.SLIDER -> {
            val numeric = value.toFloatOrNull() ?: field.minValue.toFloat()
            Column {
                Text(
                    text = "${field.label} : ${numeric.toInt()}",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = numeric.coerceIn(field.minValue.toFloat(), field.maxValue.toFloat().coerceAtLeast(field.minValue.toFloat() + 1f)),
                    onValueChange = { onValueChange(it.toInt().toString()) },
                    valueRange = field.minValue.toFloat()..field.maxValue.toFloat().coerceAtLeast(field.minValue.toFloat() + 1f)
                )
            }
        }
        UiFieldType.SELECT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(field.label, style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                field.options.forEach { option ->
                    FilterChip(
                        selected = value == option,
                        onClick = { onValueChange(option) },
                        label = { Text(option) }
                    )
                }
            }
        }
        UiFieldType.TOGGLE -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(field.label, style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = value.equals("true", ignoreCase = true),
                onCheckedChange = { onValueChange(it.toString()) }
            )
        }
        UiFieldType.IMAGE_INPUT -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickImage, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(if (value.isBlank()) "Choisir une image (${field.label})" else "Changer l'image")
            }
            if (value.isNotBlank()) {
                AsyncImage(
                    model = value,
                    contentDescription = field.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
            }
        }
        UiFieldType.FILE_DOCUMENT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onPickDocument, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text(if (value.isBlank()) "Sélectionner un fichier (${field.label})" else "Fichier sélectionné ✓")
            }
        }
    }
}
