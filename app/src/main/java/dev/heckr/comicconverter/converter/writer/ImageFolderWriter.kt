package dev.heckr.comicconverter.converter.writer

import android.content.Context
import android.graphics.Bitmap
import dev.heckr.comicconverter.converter.OutputHelper
import dev.heckr.comicconverter.converter.source.PageSource
import java.io.ByteArrayOutputStream

class ImageFolderWriter(private val context: Context) {

    suspend fun write(
        source: PageSource,
        title: String,
        subfolder: String? = null,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): String {
        val folder = OutputHelper.getOrCreateOutputFolder(context, title, subfolder)
        val displayRoot = OutputHelper.getDisplayRoot(context)
        val displayPath = if (subfolder != null) "$displayRoot/$subfolder/$title" else "$displayRoot/$title"

        for (i in 0 until source.pageCount) {
            onProgress(i + 1, source.pageCount)
            val bitmap = source.getPage(i)
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
            bitmap.recycle()
            val fileName = "page_${String.format("%04d", i + 1)}.jpg"
            folder.findFile(fileName)?.delete()
            val file = folder.createFile("image/jpeg", fileName)
                ?: throw Exception("Cannot create image file in output folder")
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(baos.toByteArray())
            }
        }
        return displayPath
    }
}
