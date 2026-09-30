package io.github.noisetta.nikonpull.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** The system folder picker grants access only to the directory chosen by the user. */
class DownloadLocation(context: Context) {
    private val preferences = context.getSharedPreferences("download_location", Context.MODE_PRIVATE)

    val treeUri: Uri?
        get() = preferences.getString("tree_uri", null)?.let(Uri::parse)

    val label: String
        get() = preferences.getString("label", null).orEmpty().ifBlank { DEFAULT_LABEL }

    fun save(context: Context, uri: Uri) {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val directory = DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
        val name = context.contentResolver.query(
            directory,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf(String::isNotBlank) ?: treeId.substringAfterLast(':')
        preferences.edit().putString("tree_uri", uri.toString()).putString("label", name).apply()
    }

    fun reset() {
        preferences.edit().remove("tree_uri").remove("label").apply()
    }

    companion object {
        const val DEFAULT_LABEL = "系统相册 · Zf AP Transfer"
    }
}
