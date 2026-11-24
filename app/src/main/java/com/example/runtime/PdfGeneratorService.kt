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
                val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(30, 41, 59)
                    textSize = 11.5f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                }
                val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(100, 116, 139)
                    textSize = 9.5f
                }

                val wrappedLines = wrapParagraphs(body, bodyPaint, maxTextWidth)
                var lineIndex = 0
                var pageNumber = 1

                while (lineIndex < wrappedLines.size || pageNumber == 1) {
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    val page = pdf.startPage(pageInfo)
                    val canvas = page.canvas
                    var y = 56f

                    if (pageNumber == 1) {
                        val titleLines = wrapParagraphs(title, titlePaint, maxTextWidth)
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
                    while (lineIndex < wrappedLines.size && y <= bottomLimit) {
                        val line = wrappedLines[lineIndex]
                        if (line.isNotEmpty()) {
                            canvas.drawText(line, marginX, y, bodyPaint)
                        }
                        y += 17f
                        lineIndex++
                    }

                    canvas.drawText(
                        "Page $pageNumber",
                        pageWidth - marginX - 42f,
                        pageHeight - 28f,
                        footerPaint
                    )
                    pdf.finishPage(page)
                    if (lineIndex >= wrappedLines.size) break
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

    private fun wrapParagraphs(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val normalized = text.replace("\r\n", "\n")
        for (paragraph in normalized.split("\n")) {
            val trimmed = paragraph.trimEnd()
            if (trimmed.isEmpty()) {
                result.add("")
                continue
            }
            val words = trimmed.split(Regex("\\s+"))
            var currentLine = StringBuilder()
            for (word in words) {
                val candidate = if (currentLine.isEmpty()) word else "${currentLine} $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    currentLine = StringBuilder(candidate)
                } else {
                    if (currentLine.isNotEmpty()) {
                        result.add(currentLine.toString())
                    }
                    if (paint.measureText(word) <= maxWidth) {
                        currentLine = StringBuilder(word)
                    } else {
                        // Break extra-long tokens safely
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
        }
        return result
    }
}
