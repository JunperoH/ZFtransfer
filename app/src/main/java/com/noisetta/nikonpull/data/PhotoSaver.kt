package com.noisetta.nikonpull.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.noisetta.nikonpull.ptp.CameraObject
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.OutputStream

object PhotoSaver {
    fun isAlreadyDownloaded(context: Context, photo: CameraObject): Boolean {
        if (photo.size <= 0L) return false
        val destination = destinationDirectory(photo)
        val projection = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.SIZE} = ?"
        val arguments = arrayOf(photo.filename, photo.size.toString())
        return context.contentResolver.query(
            collection(photo),
            projection,
            selection,
            arguments,
            null,
        )?.use { cursor ->
            val pathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            generateSequence { if (cursor.moveToNext()) cursor else null }.any {
                val path = if (pathColumn >= 0) cursor.getString(pathColumn).orEmpty() else ""
                val size = if (sizeColumn >= 0) cursor.getLong(sizeColumn) else -1L
                path.trimEnd('/') == destination && size == photo.size
            }
        } ?: false
    }

    fun cleanupIncomplete(context: Context) {
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to PHOTO_DESTINATION_DIRECTORY,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to VIDEO_DESTINATION_DIRECTORY,
        ).forEach { (collection, directory) ->
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val arguments = arrayOf("$directory/")
            val staleUris = context.contentResolver.query(
                collection,
                projection,
                selection,
                arguments,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                buildList {
                    while (cursor.moveToNext()) {
                        add(Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString()))
                    }
                }
            }.orEmpty()
            staleUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    fun createPending(context: Context, photo: CameraObject): PendingPhoto {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, photo.mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                destinationDirectory(photo),
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = collection(photo)
        val uri = resolver.insert(collection, values)
            ?: error("Android could not create ${photo.filename}")
        return try {
            val output = resolver.openOutputStream(uri, "w")
                ?: error("Android could not open ${photo.filename}")
            PendingPhoto(context, uri, output)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    class PendingPhoto internal constructor(
        private val context: Context,
        val uri: Uri,
        rawOutput: OutputStream,
    ) : Closeable {
        private var committed = false
        private val countingOutput = CountingOutputStream(rawOutput)
        val output: OutputStream get() = countingOutput

        fun commit(expectedSize: Long) {
            countingOutput.flush()
            countingOutput.close()
            if (expectedSize > 0L && countingOutput.bytesWritten != expectedSize) {
                throw IllegalStateException(
                    "Downloaded ${countingOutput.bytesWritten} of $expectedSize bytes",
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
            val storedSize = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            if (expectedSize > 0L && storedSize != expectedSize) {
                context.contentResolver.delete(uri, null, null)
                throw IllegalStateException("Android stored ${storedSize ?: 0L} of $expectedSize bytes")
            }
            committed = true
        }

        override fun close() {
            runCatching { countingOutput.close() }
            if (!committed) {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var bytesWritten: Long = 0L
            private set

        override fun write(byte: Int) {
            out.write(byte)
            bytesWritten++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }
    }

    private fun collection(photo: CameraObject): Uri = if (photo.isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun destinationDirectory(photo: CameraObject): String =
        if (photo.isVideo) VIDEO_DESTINATION_DIRECTORY else PHOTO_DESTINATION_DIRECTORY

    private const val PHOTO_DESTINATION_DIRECTORY = "Pictures/Nikon Pull"
    private const val VIDEO_DESTINATION_DIRECTORY = "Movies/Nikon Pull"
}
