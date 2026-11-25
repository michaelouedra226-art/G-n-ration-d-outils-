package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.ai.AiMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Renders structured JSON objects or `Key: Value` lines as clean Material 3 cards.
 * Falls back to [MarkdownTextView] when the output has no structured pairs.
 */
@Composable
fun KeyValueCardsOutputView(
    rawText: String,
    modifier: Modifier = Modifier
) {
    val pairs = remember(rawText) { parseKeyValuePairs(rawText) }
    if (pairs.isEmpty()) {
        MarkdownTextView(markdown = rawText, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        pairs.forEach { (key, value) ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun parseKeyValuePairs(rawText: String): List<Pair<String, String>> {
    val trimmed = rawText.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        val jsonPairs = runCatching {
            val obj = JSONObject(trimmed)
            val list = mutableListOf<Pair<String, String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.opt(k)?.toString().orEmpty()
                if (k.isNotBlank() && v.isNotBlank()) {
                    list.add(k to v)
                }
            }
            list
        }.getOrNull()
        if (!jsonPairs.isNullOrEmpty()) return jsonPairs
    }

    val linePairs = mutableListOf<Pair<String, String>>()
    for (line in trimmed.lines()) {
        val clean = line.trim().removePrefix("- ").removePrefix("* ")
        val idx = clean.indexOf(':')
        if (idx in 1..48) {
            val k = clean.substring(0, idx).replace("**", "").trim()
            val v = clean.substring(idx + 1).trim()
            if (k.isNotBlank() && v.isNotBlank() && !k.startsWith("http")) {
                linePairs.add(k to v)
            }
        }
    }
    return if (linePairs.size >= 2) linePairs else emptyList()
}

/**
 * Renders JSON arrays of objects or Markdown tables as a scrollable Material 3 data table.
 */
@Composable
fun TableOutputView(
    rawText: String,
    modifier: Modifier = Modifier
) {
    val tableData = remember(rawText) { parseTableData(rawText) }
    if (tableData == null) {
        MarkdownTextView(markdown = rawText, modifier = modifier)
        return
    }
    val (headers, rows) = tableData

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .horizontalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                .padding(vertical = 8.dp, horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            headers.forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp)
                )
            }
        }
        rows.forEachIndexed { idx, row ->
            val bgColor = if (idx % 2 == 0) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
            Row(
                modifier = Modifier
                    .background(bgColor)
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                headers.indices.forEach { colIdx ->
                    Text(
                        text = row.getOrNull(colIdx).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp)
                    )
                }
            }
        }
    }
}

private fun parseTableData(rawText: String): Pair<List<String>, List<List<String>>>? {
    val trimmed = rawText.trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        val parsed = runCatching {
            val arr = JSONArray(trimmed)
            if (arr.length() == 0) return null
            val firstObj = arr.optJSONObject(0) ?: return null
            val headers = mutableListOf<String>()
            val keyIt = firstObj.keys()
            while (keyIt.hasNext()) headers.add(keyIt.next())
            if (headers.isEmpty()) return null
            val rows = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                headers.map { h -> obj.opt(h)?.toString().orEmpty() }
            }
            headers to rows
        }.getOrNull()
        if (parsed != null && parsed.second.isNotEmpty()) return parsed
    }

    val pipeLines = trimmed.lines().map { it.trim() }.filter { it.startsWith("|") && it.endsWith("|") }
    if (pipeLines.size >= 2) {
        val cleanRows = pipeLines
            .filterNot { line -> line.replace("|", "").replace("-", "").replace(":", "").isBlank() }
            .map { line -> line.trim('|').split('|').map { it.trim() } }
        if (cleanRows.size >= 2 && cleanRows.first().isNotEmpty()) {
            return cleanRows.first() to cleanRows.drop(1)
        }
    }
    return null
}

/**
 * Renders numeric JSON objects, arrays, or `Label: Number` series as a native Canvas bar chart.
 */
@Composable
fun BarChartOutputView(
    rawText: String,
    modifier: Modifier = Modifier
) {
    val series = remember(rawText) { parseNumericSeries(rawText) }
    if (series.size < 2) {
        KeyValueCardsOutputView(rawText = rawText, modifier = modifier)
        return
    }

    val maxVal = (series.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1e-6)
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        series.take(12).forEach { (label, value) ->
            val ratio = (value / maxVal).toFloat().coerceIn(0f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                ) {
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                    if (ratio > 0f) {
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(width = size.width * ratio, height = size.height),
                            cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

private fun parseNumericSeries(rawText: String): List<Pair<String, Double>> {
    val trimmed = rawText.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        val parsed = runCatching {
            val obj = JSONObject(trimmed)
            val list = mutableListOf<Pair<String, Double>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val num = obj.opt(k)?.toString()?.replace(",", ".")?.toDoubleOrNull()
                if (num != null && num >= 0.0) list.add(k to num)
            }
            list
        }.getOrNull()
        if (!parsed.isNullOrEmpty()) return parsed
    }
    val lines = mutableListOf<Pair<String, Double>>()
    for ((label, rawVal) in parseKeyValuePairs(trimmed)) {
        val num = rawVal.replace(Regex("[^0-9.,-]"), "").replace(",", ".").toDoubleOrNull()
        if (num != null && num >= 0.0) lines.add(label to num)
    }
    return lines
}

/**
 * Renders JSON arrays or bulleted lists as clean itemized Material 3 cards.
 */
@Composable
fun ListItemsOutputView(
    rawText: String,
    modifier: Modifier = Modifier
) {
    val items = remember(rawText) { parseListItems(rawText) }
    if (items.isEmpty()) {
        MarkdownTextView(markdown = rawText, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEachIndexed { idx, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                ForgePill(text = "#${idx + 1}")
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.first,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!entry.second.isNullOrBlank()) {
                        Text(
                            text = entry.second!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun parseListItems(rawText: String): List<Pair<String, String?>> {
    val trimmed = rawText.trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        val parsed = runCatching {
            val arr = JSONArray(trimmed)
            (0 until arr.length()).mapNotNull { i ->
                when (val elem = arr.opt(i)) {
                    is JSONObject -> {
                        val ts = elem.optString("timestamp").takeIf { it.isNotBlank() }
                        val valText = elem.optString("value").ifBlank {
                            val keys = elem.keys().asSequence().filter { it != "timestamp" }.toList()
                            keys.joinToString(" • ") { k -> "$k: ${elem.optString(k)}" }
                        }
                        if (valText.isNotBlank()) valText to ts else null
                    }
                    else -> elem?.toString()?.takeIf { it.isNotBlank() }?.let { it to null }
                }
            }
        }.getOrNull()
        if (!parsed.isNullOrEmpty()) return parsed
    }

    return trimmed.lines()
        .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
        .filter { it.isNotBlank() }
        .map { it to null }
}

/**
 * Interactive Android [TextToSpeech] playback button for plugins declaring [ToolPermission.TEXT_TO_SPEECH].
 */
@Composable
fun SpeakTextButton(
    text: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isReady by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var ttsInstance by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine.language = Locale.getDefault()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                    }
                })
                isReady = true
            }
        }
        ttsInstance = engine
        onDispose {
            runCatching {
                engine.stop()
                engine.shutdown()
            }
            ttsInstance = null
        }
    }

    OutlinedButton(
        onClick = {
            val engine = ttsInstance ?: return@OutlinedButton
            if (isSpeaking) {
                engine.stop()
                isSpeaking = false
            } else if (text.isNotBlank()) {
                isSpeaking = true
                engine.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "forge_tts_output")
            }
        },
        enabled = isReady && text.isNotBlank(),
        modifier = modifier.testTag("speak_output_button")
    ) {
        Icon(
            imageVector = if (isSpeaking) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(if (isSpeaking) "Arrêter la lecture" else "Écouter (TTS)")
    }
}

/**
 * Renders a multi-turn conversational transcript for chatbot plugins.
 */
@Composable
fun ChatTranscriptOutputView(
    messages: List<AiMessage>,
    latestReply: String,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayMessages = remember(messages, latestReply) {
        if (messages.isNotEmpty()) {
            messages
        } else if (latestReply.isNotBlank()) {
            listOf(AiMessage(role = "assistant", content = latestReply))
        } else {
            emptyList()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (messages.size > 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onClearHistory,
                    modifier = Modifier.testTag("clear_chat_history_button")
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Nouvelle conversation")
                }
            }
        }

        displayMessages.forEach { msg ->
            val isUser = msg.role.equals("user", ignoreCase = true)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isUser) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isUser) "Vous" else "Assistant",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                        if (isUser) {
                            Text(
                                text = msg.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            MarkdownTextView(markdown = msg.content)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Native in-app PDF page preview powered by [PdfRenderer] with page navigation and clean bitmap disposal.
 */
@Composable
fun PdfPreviewCard(
    filePath: String,
    modifier: Modifier = Modifier
) {
    var pageIndex by remember(filePath) { mutableIntStateOf(0) }
    var pageCount by remember(filePath) { mutableIntStateOf(0) }
    var renderedBitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(filePath, pageIndex) {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        var bmp: Bitmap? = null

        runCatching {
            val file = File(filePath)
            if (file.exists()) {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                fd = pfd
                val pdfRenderer = PdfRenderer(pfd)
                renderer = pdfRenderer
                pageCount = pdfRenderer.pageCount
                if (pageCount > 0) {
                    val safeIdx = pageIndex.coerceIn(0, pageCount - 1)
                    val openedPage = pdfRenderer.openPage(safeIdx)
                    page = openedPage
                    val targetWidth = (openedPage.width * 2).coerceAtMost(1190)
                    val targetHeight = (openedPage.height * 2).coerceAtMost(1684)
                    val createdBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bmp = createdBitmap
                    createdBitmap.eraseColor(android.graphics.Color.WHITE)
                    openedPage.render(createdBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    renderedBitmap = createdBitmap
                }
            }
        }

        onDispose {
            runCatching { page?.close() }
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
            renderedBitmap = null
            bmp?.recycle()
        }
    }

    val preview = renderedBitmap ?: return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(595f / 842f)
                .clip(MaterialTheme.shapes.small)
                .background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
        ) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "Aperçu page ${pageIndex + 1} sur $pageCount",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (pageIndex > 0) pageIndex-- },
                    enabled = pageIndex > 0
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = "Page précédente"
                    )
                }
                Text(
                    text = "Page ${pageIndex + 1} / $pageCount",
                    style = MaterialTheme.typography.labelMedium
                )
                IconButton(
                    onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                    enabled = pageIndex < pageCount - 1
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = "Page suivante"
                    )
                }
            }
        }
    }
}
