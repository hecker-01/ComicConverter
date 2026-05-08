package dev.heckr.comicconverter.converter

import android.content.Context
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import dev.heckr.comicconverter.AppSettings
import dev.heckr.comicconverter.R
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object OutputHelper {

    fun getOutputStream(
        context: Context,
        name: String,
        format: OutputFormat,
        subfolder: String? = null
    ): Pair<OutputStream, String> {
        val safeTitle = sanitize(name)
        val ext = format.extension
        val outputUri = AppSettings.getOutputFolderUri(context)

        return if (outputUri != null) {
            val root = DocumentFile.fromTreeUri(context, outputUri)
                ?: throw Exception(context.getString(R.string.output_folder_inaccessible))
            val dir = resolveSubfolder(context, root, subfolder)
            dir.findFile("$safeTitle.$ext")?.delete()
            val docFile = dir.createFile(format.mimeType, "$safeTitle.$ext")
                ?: throw Exception(context.getString(R.string.output_folder_inaccessible))
            val stream = context.contentResolver.openOutputStream(docFile.uri)
                ?: throw Exception(context.getString(R.string.output_folder_inaccessible))
            val rootName = root.name ?: ""
            val displayPath = if (subfolder != null) "$rootName/$subfolder/$safeTitle.$ext"
            else "$rootName/$safeTitle.$ext"
            stream to displayPath
        } else {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = if (subfolder != null) File(base, "ComicConverter/$subfolder")
            else File(base, "ComicConverter")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$safeTitle.$ext")
            FileOutputStream(file) to file.absolutePath
        }
    }

    fun getOrCreateOutputFolder(context: Context, name: String, parent: String? = null): DocumentFile {
        val safeName = sanitize(name)
        val outputUri = AppSettings.getOutputFolderUri(context)

        return if (outputUri != null) {
            val root = DocumentFile.fromTreeUri(context, outputUri)
                ?: throw Exception(context.getString(R.string.output_folder_inaccessible))
            val base = if (parent != null) {
                val safeParent = sanitize(parent)
                root.findFile(safeParent)?.takeIf { it.isDirectory }
                    ?: root.createDirectory(safeParent)
                    ?: throw Exception(context.getString(R.string.output_folder_inaccessible))
            } else root
            base.findFile(safeName)?.takeIf { it.isDirectory }
                ?: base.createDirectory(safeName)
                ?: throw Exception(context.getString(R.string.output_folder_inaccessible))
        } else {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = if (parent != null)
                File(File(base, "ComicConverter/${sanitize(parent)}"), safeName)
            else
                File(base, "ComicConverter/$safeName")
            if (!dir.exists()) dir.mkdirs()
            DocumentFile.fromFile(dir)
        }
    }

    fun getDisplayRoot(context: Context): String {
        val outputUri = AppSettings.getOutputFolderUri(context)
        return if (outputUri != null) {
            DocumentFile.fromTreeUri(context, outputUri)?.name ?: "selected folder"
        } else {
            "Documents/ComicConverter"
        }
    }

    private fun resolveSubfolder(context: Context, root: DocumentFile, subfolder: String?): DocumentFile {
        if (subfolder == null) return root
        val safe = sanitize(subfolder)
        return root.findFile(safe)?.takeIf { it.isDirectory }
            ?: root.createDirectory(safe)
            ?: throw Exception(context.getString(R.string.output_folder_inaccessible))
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
