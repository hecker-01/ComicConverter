package dev.heckr.comicconverter.converter.source

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.heckr.comicconverter.converter.ComicMetadata

class ImageFolderSource(context: Context, folderUri: Uri, folderName: String) : PageSource {

    private val ctx = context
    private val imageUris: List<Uri>
    override val metadata = ComicMetadata(title = folderName)
    override val pageCount get() = imageUris.size

    init {
        context.contentResolver.takePersistableUriPermission(
            folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw Exception("Cannot access folder")
        imageUris = folder.listFiles()
            .filter { file ->
                file.name?.matches(
                    Regex(".*\\.(jpg|jpeg|png|gif|bmp|webp)", RegexOption.IGNORE_CASE)
                ) == true
            }
            .sortedBy { it.name?.lowercase() }
            .map { it.uri }

        if (imageUris.isEmpty()) throw Exception("No images found in folder")
    }

    override fun getPage(index: Int): Bitmap {
        return ctx.contentResolver.openInputStream(imageUris[index])?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw Exception("Cannot read image at index $index")
    }

    override fun close() {}
}
