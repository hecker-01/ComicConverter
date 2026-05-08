package dev.heckr.comicconverter.converter

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile

object FormatDetector {

    fun detectFile(context: Context, uri: Uri): DetectedInput {
        val fileName = getFileName(context, uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val format = when (ext) {
            "cbz" -> InputFormat.CBZ
            "cbr", "rar" -> InputFormat.CBR
            "pdf" -> InputFormat.PDF
            "epub" -> InputFormat.EPUB
            "zip" -> InputFormat.CBZ
            else -> {
                val mime = context.contentResolver.getType(uri) ?: ""
                when {
                    mime.contains("pdf") -> InputFormat.PDF
                    mime.contains("epub") -> InputFormat.EPUB
                    mime.contains("rar") -> InputFormat.CBR
                    else -> InputFormat.CBZ
                }
            }
        }
        val title = fileName.substringBeforeLast('.')
        val label = "${format.name} · $title"
        return DetectedInput(uri, format, title, 1, label)
    }

    fun detectFolder(context: Context, uri: Uri): DetectedInput {
        val folder = DocumentFile.fromTreeUri(context, uri)
            ?: throw Exception("Cannot access folder")
        val folderName = folder.name ?: "Comic"

        var cbzCount = 0
        var cbrCount = 0
        var imageCount = 0

        folder.listFiles().forEach { file ->
            val name = file.name?.lowercase() ?: return@forEach
            when {
                name.endsWith(".cbz") -> cbzCount++
                name.endsWith(".cbr") || name.endsWith(".rar") -> cbrCount++
                name.matches(Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)")) -> imageCount++
            }
        }

        val archiveCount = cbzCount + cbrCount
        return when {
            archiveCount > 0 -> {
                val archiveType = when {
                    cbzCount > 0 && cbrCount > 0 -> "CBZ/CBR"
                    cbrCount > 0 -> "CBR"
                    else -> "CBZ"
                }
                DetectedInput(
                    uri = uri,
                    format = InputFormat.COMIC_FOLDER,
                    title = folderName,
                    fileCount = archiveCount,
                    displayLabel = "Batch · $archiveCount $archiveType files"
                )
            }
            imageCount > 0 -> DetectedInput(
                uri = uri,
                format = InputFormat.IMAGE_FOLDER,
                title = folderName,
                fileCount = imageCount,
                displayLabel = "Image folder · $imageCount images"
            )
            else -> throw Exception("No supported files found in folder")
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result = "comic.cbz"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) result = cursor.getString(idx)
            }
        }
        return result
    }
}
