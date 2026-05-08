package dev.heckr.comicconverter.converter.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.heckr.comicconverter.converter.ComicMetadata
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class EpubSource(context: Context, uri: Uri, fallbackTitle: String) : PageSource {

    private val pages: List<ByteArray>
    override val metadata: ComicMetadata
    override val pageCount get() = pages.size

    init {
        val entries = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        var title = fallbackTitle
        var orderedImages: List<ByteArray>? = null

        try {
            val containerBytes = entries["META-INF/container.xml"]
            if (containerBytes != null) {
                val opfPath = parseContainerForOpfPath(containerBytes)
                val opfBytes = entries[opfPath]
                if (opfBytes != null) {
                    val opfDir = opfPath.substringBeforeLast("/", "")
                    val (parsedTitle, imageOrder) = parseOpf(opfBytes)
                    if (parsedTitle.isNotEmpty()) title = parsedTitle
                    // Resolve image paths relative to OPF dir
                    val resolved = imageOrder.mapNotNull { rel ->
                        val key = if (opfDir.isNotEmpty()) "$opfDir/$rel" else rel
                        entries[key] ?: entries[rel]
                    }
                    if (resolved.isNotEmpty()) orderedImages = resolved
                }
            }
        } catch (_: Exception) {}

        // Fall back to all images sorted by path
        if (orderedImages == null) {
            orderedImages = entries.entries
                .filter { (path, _) ->
                    path.matches(Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE))
                }
                .sortedBy { it.key.lowercase() }
                .map { it.value }
        }

        if (orderedImages!!.isEmpty()) throw Exception("No images found in EPUB")
        pages = orderedImages!!
        metadata = ComicMetadata(title = title)
    }

    private fun parseContainerForOpfPath(data: ByteArray): String {
        val parser = newParser(data)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
                    ?: throw Exception("No full-path in container.xml")
            }
            eventType = parser.next()
        }
        throw Exception("No rootfile in container.xml")
    }

    private fun parseOpf(data: ByteArray): Pair<String, List<String>> {
        val manifest = mutableMapOf<String, String>() // id -> href (images only)
        val spine = mutableListOf<String>()
        var title = ""
        var inManifest = false
        var inSpine = false
        var inTitle = false

        val parser = newParser(data)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val local = parser.name.substringAfterLast(':')
                    when (local) {
                        "title" -> inTitle = true
                        "manifest" -> inManifest = true
                        "spine" -> inSpine = true
                        "item" -> if (inManifest) {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            if (mediaType.startsWith("image/") ||
                                href.matches(Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE))
                            ) {
                                manifest[id] = href
                            }
                        }
                        "itemref" -> if (inSpine) {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            if (idref.isNotEmpty()) spine.add(idref)
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inTitle && title.isEmpty()) title = parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    val local = parser.name.substringAfterLast(':')
                    when (local) {
                        "title" -> inTitle = false
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
            }
            eventType = parser.next()
        }

        val imageOrder = if (spine.isNotEmpty()) {
            spine.mapNotNull { manifest[it] }
        } else {
            manifest.values.sorted()
        }
        return title to imageOrder
    }

    private fun newParser(data: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(ByteArrayInputStream(data), null)
        return parser
    }

    override fun getPage(index: Int): Bitmap {
        val data = pages[index]
        return BitmapFactory.decodeByteArray(data, 0, data.size)
    }

    override fun close() {}
}
