package dev.heckr.comicconverter.converter.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.github.junrar.Archive
import dev.heckr.comicconverter.converter.ComicMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class CbrSource(context: Context, uri: Uri, fallbackTitle: String) : PageSource {

    private val pages: List<ByteArray>
    private val tempFile: File
    override val metadata: ComicMetadata
    override val pageCount get() = pages.size

    init {
        tempFile = File(context.cacheDir, "temp_${System.currentTimeMillis()}.rar")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }

        val images = mutableListOf<Pair<String, ByteArray>>()

        Archive(tempFile).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                val name = header.fileName ?: ""
                if (!header.isDirectory && name.matches(
                        Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE)
                    )
                ) {
                    val baos = ByteArrayOutputStream()
                    archive.extractFile(header, baos)
                    images.add(name to baos.toByteArray())
                }
                header = archive.nextFileHeader()
            }
        }

        if (images.isEmpty()) throw Exception("No images found in CBR file")
        images.sortBy { it.first.lowercase() }
        pages = images.map { it.second }
        metadata = ComicMetadata(title = fallbackTitle)
    }

    override fun getPage(index: Int): Bitmap {
        val data = pages[index]
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    override fun close() {
        tempFile.delete()
    }
}
