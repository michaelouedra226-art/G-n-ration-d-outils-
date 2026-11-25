package com.example.runtime

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GeneratedPdfArtifact(
    val filePath: String,
    val fileName: String,
    val pageCount: Int,
    val byteSize: Long
)

/**
 * Generates real paginated A4 PDF documents using Android's native [PdfDocument] API
 * inside the application's sandboxed `cache/exports/` directory.
 */
interface PdfGenerator {
    suspend fun generatePdf(
        title: String,
        body: String,
        subtitle: String = ""
    ): Result<GeneratedPdfArtifact>
}

class AndroidPdfGeneratorService(
    private val context: Context
) : PdfGenerator {

    override suspend fun generatePdf(
        title: String,
        body: String,
        subtitle: String
    ): Result<GeneratedPdfArtifact> = withContext(Dispatchers.IO) {
        runCatching {
            val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeBase = title
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "forgeai_document" }
                .take(36)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            val fileName = "${safeBase}_$stamp.pdf"
            val outFile = File(exportsDir, fileName)

            val pdf = PdfDocument()
            try {
                val pageWidth = 595
                val pageHeight = 842
                val marginX = 48f
                val maxTextWidth = pageWidth - (marginX * 2)

                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(15, 23, 42)
                    textSize = 20f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(71, 85, 105)
                    textSize = 11f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                }
                val dividerPaint = Paint().apply {
                    color = Color.rgb(203, 213, 225)
                    strokeWidth = 1f
                }
                val h1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(15, 23, 42)
                    textSize = 15.5f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                val h2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(30, 41, 59)
                    textSize = 13.5f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(30, 41, 59)
                    textSize = 11.2f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                }
                val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(15, 23, 42)
                    textSize = 9.8f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                }
                val codeBgPaint = Paint().apply {
                    color = Color.rgb(241, 245, 249)
                }
                val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(100, 116, 139)
                    textSize = 9.5f
                }

                val styledLines = buildStyledLines(body, maxTextWidth, h1Paint, h2Paint, bodyPaint, codePaint)
                var lineIndex = 0
                var pageNumber = 1

                while (lineIndex < styledLines.size || pageNumber == 1) {
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    val page = pdf.startPage(pageInfo)
                    val canvas = page.canvas
                    var y = 56f

                    if (pageNumber == 1) {
                        val titleLines = wrapText(title, titlePaint, maxTextWidth)
                        for (tLine in titleLines) {
                            canvas.drawText(tLine, marginX, y, titlePaint)
                            y += 26f
                        }
                        if (subtitle.isNotBlank()) {
                            canvas.drawText(subtitle.take(90), marginX, y, subtitlePaint)
                            y += 18f
                        }
                        val generatedLabel = "Généré par ForgeAI • ${
                            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date())
                        }"
                        canvas.drawText(generatedLabel, marginX, y, subtitlePaint)
                        y += 14f
                        canvas.drawLine(marginX, y, pageWidth - marginX, y, dividerPaint)
                        y += 22f
                    }

                    val bottomLimit = pageHeight - 54f
                    while (lineIndex < styledLines.size && y <= bottomLimit) {
                        val entry = styledLines[lineIndex]
                        when (entry.kind) {
                            PdfLineKind.BLANK -> {
                                y += 10f
                            }
                            PdfLineKind.H1 -> {
                                y += 4f
                                canvas.drawText(entry.text, marginX, y, h1Paint)
                                y += 22f
                            }
                            PdfLineKind.H2 -> {
                                y += 2f
                                canvas.drawText(entry.text, marginX, y, h2Paint)
                                y += 19f
                            }
                            PdfLineKind.BULLET -> {
                                canvas.drawText(entry.text, marginX + 10f, y, bodyPaint)
                                y += 16.5f
                            }
                            PdfLineKind.CODE -> {
                                canvas.drawRect(
                                    marginX - 4f,
                                    y - 11f,
                                    pageWidth - marginX + 4f,
                                    y + 5f,
                                    codeBgPaint
                                )
                                canvas.drawText(entry.text, marginX + 4f, y, codePaint)
                                y += 15f
                            }
                            PdfLineKind.BODY -> {
                                canvas.drawText(entry.text, marginX, y, bodyPaint)
                                y += 16.5f
                            }
                        }
                        lineIndex++
                    }

                    canvas.drawText(
                        "Page $pageNumber",
                        pageWidth - marginX - 42f,
                        pageHeight - 28f,
                        footerPaint
                    )
                    pdf.finishPage(page)
                    if (lineIndex >= styledLines.size) break
                    pageNumber++
                }

                FileOutputStream(outFile).use { stream ->
                    pdf.writeTo(stream)
                }

                GeneratedPdfArtifact(
                    filePath = outFile.absolutePath,
                    fileName = fileName,
                    pageCount = pageNumber,
                    byteSize = outFile.length()
                )
            } finally {
                pdf.close()
            }
        }
    }

    private enum class PdfLineKind { BLANK, H1, H2, BULLET, CODE, BODY }

    private data class StyledPdfLine(val kind: PdfLineKind, val text: String)

    private fun buildStyledLines(
        raw: String,
        maxWidth: Float,
        h1Paint: Paint,
        h2Paint: Paint,
        bodyPaint: Paint,
        codePaint: Paint
    ): List<StyledPdfLine> {
        val out = mutableListOf<StyledPdfLine>()
        val lines = raw.replace("\r\n", "\n").split("\n")
        var inCode = false
        for (line in lines) {
            val trimmed = line.trimEnd()
            if (trimmed.trimStart().startsWith("```")) {
                inCode = !inCode
                continue
            }
            if (inCode) {
                wrapText(trimmed, codePaint, maxWidth - 8f).forEach {
                    out.add(StyledPdfLine(PdfLineKind.CODE, it))
                }
                continue
            }
            val clean = trimmed.trimStart()
            when {
                clean.isEmpty() -> out.add(StyledPdfLine(PdfLineKind.BLANK, ""))
                clean.startsWith("# ") -> wrapText(stripInlineMd(clean.drop(2)), h1Paint, maxWidth)
                    .forEach { out.add(StyledPdfLine(PdfLineKind.H1, it)) }
                clean.startsWith("## ") || clean.startsWith("### ") -> {
                    val content = clean.removePrefix("### ").removePrefix("## ")
                    wrapText(stripInlineMd(content), h2Paint, maxWidth)
                        .forEach { out.add(StyledPdfLine(PdfLineKind.H2, it)) }
                }
                clean.startsWith("- ") || clean.startsWith("* ") -> {
                    val bulletLines = wrapText(stripInlineMd(clean.drop(2)), bodyPaint, maxWidth - 18f)
                    bulletLines.forEachIndexed { idx, part ->
                        val prefix = if (idx == 0) "• " else "  "
                        out.add(StyledPdfLine(PdfLineKind.BULLET, "$prefix$part"))
                    }
                }
                else -> wrapText(stripInlineMd(clean), bodyPaint, maxWidth)
                    .forEach { out.add(StyledPdfLine(PdfLineKind.BODY, it)) }
            }
        }
        return out
    }

    private fun stripInlineMd(text: String): String =
        text.replace("**", "").replace("`", "")

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        val words = text.split(Regex("\\s+"))
        var currentLine = StringBuilder()
        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(candidate) <= maxWidth) {
                currentLine = StringBuilder(candidate)
            } else {
                if (currentLine.isNotEmpty()) {
                    result.add(currentLine.toString())
                }
                if (paint.measureText(word) <= maxWidth) {
                    currentLine = StringBuilder(word)
                } else {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (paint.measureText(chunk.toString() + ch) > maxWidth && chunk.isNotEmpty()) {
                            result.add(chunk.toString())
                            chunk = StringBuilder()
                        }
                        chunk.append(ch)
                    }
                    currentLine = chunk
                }
            }
        }
        if (currentLine.isNotEmpty()) {
            result.add(currentLine.toString())
        }
        return result
    }
}
