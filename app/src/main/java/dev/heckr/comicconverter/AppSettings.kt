package dev.heckr.comicconverter

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_OUTPUT_FOLDER_URI = "output_folder_uri"

    fun getOutputFolderUri(context: Context): Uri? {
        val str = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OUTPUT_FOLDER_URI, null) ?: return null
        return Uri.parse(str)
    }

    fun setOutputFolderUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_OUTPUT_FOLDER_URI, uri.toString()).apply()
    }

    fun clearOutputFolderUri(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_OUTPUT_FOLDER_URI).apply()
    }

    fun getDisplayPath(context: Context): String {
        val uri = getOutputFolderUri(context) ?: return context.getString(R.string.output_folder_default)
        val docFile = DocumentFile.fromTreeUri(context, uri)
        return docFile?.name ?: context.getString(R.string.output_folder_default)
    }
}
