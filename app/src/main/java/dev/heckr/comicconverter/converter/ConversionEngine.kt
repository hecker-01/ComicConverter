package dev.heckr.comicconverter.converter

import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import dev.heckr.comicconverter.converter.source.*
import dev.heckr.comicconverter.converter.writer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ConversionEngine {

    suspend fun convert(
        context: Context,
        input: DetectedInput,
        outputFormat: OutputFormat,
        onProgress: suspend (status: String, percent: Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (input.format == InputFormat.COMIC_FOLDER) {
            convertBatch(context, input, outputFormat, onProgress)
        } else {
            convertSingle(context, input, outputFormat, onProgress)
        }
    }

    private suspend fun convertSingle(
        context: Context,
        input: DetectedInput,
        outputFormat: OutputFormat,
        onProgress: suspend (status: String, percent: Int) -> Unit
    ): String {
        onProgress("Loading ${input.format.name}…", 0)
        val source = buildSource(context, input)
        return try {
            writeOutput(context, source, source.metadata.title, null, outputFormat, onProgress)
        } finally {
            source.close()
        }
    }

    private suspend fun convertBatch(
        context: Context,
        input: DetectedInput,
        outputFormat: OutputFormat,
        onProgress: suspend (status: String, percent: Int) -> Unit
    ): String {
        context.contentResolver.takePersistableUriPermission(
            input.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val folder = DocumentFile.fromTreeUri(context, input.uri)
            ?: throw Exception("Cannot access folder")

        val archives = folder.listFiles()
            .filter { file ->
                val name = file.name?.lowercase() ?: ""
                name.endsWith(".cbz") || name.endsWith(".cbr") || name.endsWith(".rar")
            }
            .sortedBy { it.name?.lowercase() }

        if (archives.isEmpty()) throw Exception("No comic archives found in folder")

        archives.forEachIndexed { idx, file ->
            val chapterTitle = file.name?.substringBeforeLast('.') ?: "chapter_${idx + 1}"
            val chapterPct = ((idx + 1) * 100 / archives.size)
            onProgress("Converting ${idx + 1}/${archives.size}: ${file.name}", chapterPct)

            val ext = file.name?.substringAfterLast('.')?.lowercase() ?: "cbz"
            val chapterFormat = if (ext == "cbr" || ext == "rar") InputFormat.CBR else InputFormat.CBZ
            val chapterInput = DetectedInput(file.uri, chapterFormat, chapterTitle, 1, "")

            val source = buildSource(context, chapterInput)
            try {
                writeOutput(context, source, chapterTitle, input.title, outputFormat) { _, p ->
                    onProgress("Converting ${idx + 1}/${archives.size}: ${file.name} ($p%)", chapterPct)
                }
            } finally {
                source.close()
            }
        }

        return "Saved ${archives.size} files to: ${OutputHelper.getDisplayRoot(context)}/${input.title}"
    }

    private fun buildSource(context: Context, input: DetectedInput): PageSource = when (input.format) {
        InputFormat.CBZ -> CbzSource(context, input.uri, input.title)
        InputFormat.CBR -> CbrSource(context, input.uri, input.title)
        InputFormat.PDF -> PdfSource(context, input.uri, input.title)
        InputFormat.EPUB -> EpubSource(context, input.uri, input.title)
        InputFormat.IMAGE_FOLDER -> ImageFolderSource(context, input.uri, input.title)
        InputFormat.COMIC_FOLDER -> throw IllegalArgumentException("Use convertBatch for COMIC_FOLDER")
    }

    private suspend fun writeOutput(
        context: Context,
        source: PageSource,
        title: String,
        subfolder: String?,
        outputFormat: OutputFormat,
        onProgress: suspend (status: String, percent: Int) -> Unit
    ): String = when (outputFormat) {
        OutputFormat.PDF -> PdfComicWriter(context).write(source, title, subfolder) { cur, total ->
            onProgress("Page $cur/$total", pct(cur, total))
        }
        OutputFormat.CBZ -> CbzComicWriter(context).write(source, title, subfolder) { cur, total ->
            onProgress("Page $cur/$total", pct(cur, total))
        }
        OutputFormat.EPUB -> EpubComicWriter(context).write(source, title, subfolder) { cur, total ->
            onProgress("Page $cur/$total", pct(cur, total))
        }
        OutputFormat.IMAGES -> ImageFolderWriter(context).write(source, title, subfolder) { cur, total ->
            onProgress("Image $cur/$total", pct(cur, total))
        }
    }

    private fun pct(cur: Int, total: Int) = if (total > 0) cur * 100 / total else 0
}
