package dev.heckr.comicconverter.converter.source

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import dev.heckr.comicconverter.converter.ComicMetadata

class PdfSource(context: Context, uri: Uri, title: String) : PageSource {

    private val fd = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw Exception("Cannot open PDF file")
    private val renderer = PdfRenderer(fd)

    override val pageCount = renderer.pageCount
    override val metadata = ComicMetadata(title = title)

    override fun getPage(index: Int): Bitmap {
        val page = renderer.openPage(index)
        val scale = 2
        val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        page.close()
        return bitmap
    }

    override fun close() {
        renderer.close()
        fd.close()
    }
}
