package dev.heckr.comicconverter.converter.writer

import android.content.Context
import android.graphics.Bitmap
import dev.heckr.comicconverter.converter.OutputFormat
import dev.heckr.comicconverter.converter.OutputHelper
import dev.heckr.comicconverter.converter.source.PageSource
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubComicWriter(private val context: Context) {

    suspend fun write(
        source: PageSource,
        title: String,
        subfolder: String? = null,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): String {
        val (stream, displayPath) = OutputHelper.getOutputStream(context, title, OutputFormat.EPUB, subfolder)
        ZipOutputStream(stream).use { zip ->
            // mimetype must be first and uncompressed
            val mimetypeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimetypeEntry = ZipEntry("mimetype").also {
                it.method = ZipEntry.STORED
                it.size = mimetypeBytes.size.toLong()
                it.compressedSize = mimetypeBytes.size.toLong()
                it.crc = CRC32().also { c -> c.update(mimetypeBytes) }.value
            }
            zip.putNextEntry(mimetypeEntry)
            zip.write(mimetypeBytes)
            zip.closeEntry()

            addEntry(zip, "META-INF/container.xml", containerXml())
            addEntry(zip, "OEBPS/content.opf", contentOpf(source, title))
            addEntry(zip, "OEBPS/nav.xhtml", navXhtml(title, source.pageCount))

            for (i in 0 until source.pageCount) {
                onProgress(i + 1, source.pageCount)
                val bitmap = source.getPage(i)
                val w = bitmap.width
                val h = bitmap.height
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                bitmap.recycle()
                val num = String.format("%04d", i + 1)
                addEntry(zip, "OEBPS/images/page_$num.jpg", baos.toByteArray())
                addEntry(zip, "OEBPS/pages/page_$num.xhtml", pageXhtml(i + 1, w, h))
            }
        }
        return displayPath
    }

    private fun addEntry(zip: ZipOutputStream, path: String, content: String) =
        addEntry(zip, path, content.toByteArray(Charsets.UTF_8))

    private fun addEntry(zip: ZipOutputStream, path: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(data)
        zip.closeEntry()
    }

    private fun containerXml() = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun contentOpf(source: PageSource, title: String): String {
        val uid = UUID.randomUUID()
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${title.escapeXml()}</dc:title>
    <dc:identifier id="uid">urn:uuid:$uid</dc:identifier>
    <dc:language>en</dc:language>
    <meta property="rendition:layout">pre-paginated</meta>
    <meta property="rendition:spread">landscape</meta>""")
        source.metadata.author?.let { sb.append("\n    <dc:creator>${it.escapeXml()}</dc:creator>") }
        sb.append("""
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
""")
        for (i in 1..source.pageCount) {
            val n = String.format("%04d", i)
            sb.append("""    <item id="img$n" href="images/page_$n.jpg" media-type="image/jpeg"/>
""")
            sb.append("""    <item id="page$n" href="pages/page_$n.xhtml" media-type="application/xhtml+xml"/>
""")
        }
        sb.append("  </manifest>\n  <spine page-progression-direction=\"ltr\">\n")
        for (i in 1..source.pageCount) {
            sb.append("""    <itemref idref="page${String.format("%04d", i)}"/>
""")
        }
        sb.append("  </spine>\n</package>")
        return sb.toString()
    }

    private fun navXhtml(title: String, pageCount: Int): String {
        val items = (1..pageCount).joinToString("\n") { i ->
            val n = String.format("%04d", i)
            """      <li><a href="pages/page_$n.xhtml">Page $i</a></li>"""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>${title.escapeXml()}</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <ol>
$items
    </ol>
  </nav>
</body>
</html>"""
    }

    private fun pageXhtml(pageNum: Int, width: Int, height: Int): String {
        val n = String.format("%04d", pageNum)
        val w = if (width > 0) width else 1200
        val h = if (height > 0) height else 1697
        return """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>Page $pageNum</title>
  <meta name="viewport" content="width=$w, height=$h"/>
  <style>html,body{margin:0;padding:0;width:${w}px;height:${h}px;overflow:hidden;}img{width:${w}px;height:${h}px;}</style>
</head>
<body><img src="../images/page_$n.jpg" alt="Page $pageNum"/></body>
</html>"""
    }

    private fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
