package dev.heckr.comicconverter.converter

enum class OutputFormat(val displayName: String, val extension: String, val mimeType: String) {
    PDF("PDF", "pdf", "application/pdf"),
    CBZ("CBZ", "cbz", "application/zip"),
    EPUB("EPUB", "epub", "application/epub+zip"),
    IMAGES("Images", "", "")
}

fun InputFormat.availableOutputFormats(): List<OutputFormat> = when (this) {
    InputFormat.CBZ -> listOf(OutputFormat.PDF, OutputFormat.EPUB, OutputFormat.IMAGES)
    InputFormat.CBR -> listOf(OutputFormat.PDF, OutputFormat.CBZ, OutputFormat.EPUB, OutputFormat.IMAGES)
    InputFormat.PDF -> listOf(OutputFormat.CBZ, OutputFormat.EPUB, OutputFormat.IMAGES)
    InputFormat.EPUB -> listOf(OutputFormat.PDF, OutputFormat.CBZ, OutputFormat.IMAGES)
    InputFormat.IMAGE_FOLDER -> listOf(OutputFormat.PDF, OutputFormat.CBZ, OutputFormat.EPUB)
    InputFormat.COMIC_FOLDER -> listOf(OutputFormat.PDF, OutputFormat.CBZ, OutputFormat.EPUB, OutputFormat.IMAGES)
}
