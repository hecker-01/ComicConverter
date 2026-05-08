package dev.heckr.comicconverter.converter

data class ComicMetadata(
    val title: String,
    val author: String? = null,
    val publisher: String? = null
)
