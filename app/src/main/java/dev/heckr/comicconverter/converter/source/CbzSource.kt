package dev.heckr.comicconverter.converter.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.heckr.comicconverter.converter.ComicMetadata
import org.json.JSONObject
import java.util.zip.ZipInputStream

class CbzSource(context: Context, uri: Uri, fallbackTitle: String) : PageSource {

    private val pages: List<ByteArray>
    override val metadata: ComicMetadata
    override val pageCount get() = pages.size

    init {
        val images = mutableListOf<Pair<String, ByteArray>>()
        var metaJson: JSONObject? = null

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name.equals("index.json", ignoreCase = true) -> {
                            metaJson = JSONObject(String(zip.readBytes()))
                        }
                        name.matches(Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE)) -> {
                            images.add(name to zip.readBytes())
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        if (images.isEmpty()) throw Exception("No images found in CBZ file")
        images.sortBy { it.first.lowercase() }
        pages = images.map { it.second }
        metadata = ComicMetadata(
            title = metaJson?.optString("title")?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
            author = metaJson?.optString("author")?.takeIf { it.isNotEmpty() },
            publisher = metaJson?.optString("publisher")?.takeIf { it.isNotEmpty() }
        )
    }

    override fun getPage(index: Int): Bitmap {
        val data = pages[index]
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    override fun close() {}
}
