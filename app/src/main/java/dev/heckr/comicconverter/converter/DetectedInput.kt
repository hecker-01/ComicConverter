package dev.heckr.comicconverter.converter

import android.net.Uri

data class DetectedInput(
    val uri: Uri,
    val format: InputFormat,
    val title: String,
    val fileCount: Int = 1,
    val displayLabel: String
)
