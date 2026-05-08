package dev.heckr.comicconverter.converter.source

import android.graphics.Bitmap
import dev.heckr.comicconverter.converter.ComicMetadata

interface PageSource : AutoCloseable {
    val metadata: ComicMetadata
    val pageCount: Int
    fun getPage(index: Int): Bitmap
}
