package dev.heckr.comicconverter.converter.writer

import android.content.Context
import android.graphics.Bitmap
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Image
import dev.heckr.comicconverter.converter.OutputFormat
import dev.heckr.comicconverter.converter.OutputHelper
import dev.heckr.comicconverter.converter.source.PageSource
import java.io.ByteArrayOutputStream

class PdfComicWriter(private val context: Context) {

    suspend fun write(
        source: PageSource,
        title: String,
        subfolder: String? = null,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): String {
        val (stream, displayPath) = OutputHelper.getOutputStream(context, title, OutputFormat.PDF, subfolder)
        PdfWriter(stream).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                pdfDoc.documentInfo.title = source.metadata.title
                source.metadata.author?.let { pdfDoc.documentInfo.author = it }
                source.metadata.publisher?.let { pdfDoc.documentInfo.subject = it }
                Document(pdfDoc).use { doc ->
                    doc.setMargins(0f, 0f, 0f, 0f)
                    for (i in 0 until source.pageCount) {
                        onProgress(i + 1, source.pageCount)
                        val bitmap = source.getPage(i)
                        addPage(doc, bitmap, i > 0)
                        bitmap.recycle()
                    }
                }
            }
        }
        return displayPath
    }

    private fun addPage(doc: Document, bitmap: Bitmap, addBreak: Boolean) {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val iData = ImageDataFactory.create(baos.toByteArray())
        val image = Image(iData)

        val pageWidth = 595f
        val pageHeight = 842f
        val scale = minOf(pageWidth / bitmap.width, pageHeight / bitmap.height)
        image.scaleToFit(bitmap.width * scale, bitmap.height * scale)
        image.setFixedPosition(
            (pageWidth - bitmap.width * scale) / 2,
            (pageHeight - bitmap.height * scale) / 2
        )

        if (addBreak) doc.add(AreaBreak())
        doc.add(image)
    }
}
