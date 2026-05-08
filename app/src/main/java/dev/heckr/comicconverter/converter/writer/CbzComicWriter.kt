package dev.heckr.comicconverter.converter.writer

import android.content.Context
import android.graphics.Bitmap
import dev.heckr.comicconverter.converter.OutputFormat
import dev.heckr.comicconverter.converter.OutputHelper
import dev.heckr.comicconverter.converter.source.PageSource
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CbzComicWriter(private val context: Context) {

    suspend fun write(
        source: PageSource,
        title: String,
        subfolder: String? = null,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): String {
        val (stream, displayPath) = OutputHelper.getOutputStream(context, title, OutputFormat.CBZ, subfolder)
        ZipOutputStream(stream).use { zip ->
            for (i in 0 until source.pageCount) {
                onProgress(i + 1, source.pageCount)
                val bitmap = source.getPage(i)
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                bitmap.recycle()
                val name = "page_${String.format("%04d", i + 1)}.jpg"
                zip.putNextEntry(ZipEntry(name))
                zip.write(baos.toByteArray())
                zip.closeEntry()
            }
            val meta = JSONObject().apply {
                put("title", source.metadata.title)
                source.metadata.author?.let { put("author", it) }
                source.metadata.publisher?.let { put("publisher", it) }
            }
            zip.putNextEntry(ZipEntry("index.json"))
            zip.write(meta.toString().toByteArray())
            zip.closeEntry()
        }
        return displayPath
    }
}
