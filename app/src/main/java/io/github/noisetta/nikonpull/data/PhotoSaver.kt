package io.github.noisetta.nikonpull.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.noisetta.nikonpull.ptp.CameraObject
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.OutputStream

object PhotoSaver {
    fun isAlreadyDownloaded(context: Context, photo: CameraObject): Boolean {
        if (photo.size <= 0L) return false
        DownloadLocation(context).treeUri?.let { tree ->
            return runCatching { findDocument(context, tree, photo.filename, photo.size) != null }
                .getOrDefault(false)
        }
        val destination = destinationDirectory(photo)
        val projection = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.SIZE} = ? AND " +
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
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

    fun cleanupIncomplete(context: Context, retainedUris: Set<String> = emptySet()) {
        DownloadLocation(context).treeUri?.let { tree ->
            runCatching {
                listDocuments(context, tree)
                    .filter { it.name.endsWith(PARTIAL_SUFFIX) && it.uri.toString() !in retainedUris }
                    .forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } }
            }
        }
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
            staleUris
                .filterNot { it.toString() in retainedUris }
                .forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    fun openPending(
        context: Context,
        photo: CameraObject,
        pendingUri: String,
        resumeBytes: Long,
    ): PendingPhoto {
        val safeResumeBytes = resumeBytes.coerceIn(0L, photo.size.coerceAtLeast(0L))
        DownloadLocation(context).treeUri?.let { tree ->
            if (pendingUri.isNotBlank()) {
                val existing = runCatching {
                    openExistingDocument(context, tree, photo, Uri.parse(pendingUri), safeResumeBytes)
                }.getOrNull()
                if (existing != null) return existing
                discardIncomplete(context, pendingUri)
            }
            return createPendingDocument(context, tree, photo)
        }
        if (pendingUri.isNotBlank()) {
            val existing = openExisting(context, photo, Uri.parse(pendingUri), safeResumeBytes)
            if (existing != null) return existing
            discardIncomplete(context, pendingUri)
        }
        return createPending(context, photo)
    }

    fun discardIncomplete(context: Context, uriString: String) {
        if (uriString.isBlank()) return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val name = runCatching {
                context.contentResolver.query(
                    uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
            if (name?.endsWith(PARTIAL_SUFFIX) == true) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
            return
        }
        val isPending = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 } == true
        }.getOrDefault(false)
        if (isPending) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun openExisting(
        context: Context,
        photo: CameraObject,
        uri: Uri,
        resumeBytes: Long,
    ): PendingPhoto? {
        val expectedDirectory = destinationDirectory(photo)
        val validMetadata = context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            cursor.getString(0) == photo.filename &&
                cursor.getString(1).orEmpty().trimEnd('/') == expectedDirectory &&
                cursor.getInt(2) == 1
        } == true
        if (!validMetadata) return null

        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
        return try {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            // ParcelFileDescriptor.statSize may be unknown or stale for an IS_PENDING
            // MediaStore item. The channel is backed by the actual file and reports the
            // durable bytes written before the previous process stopped.
            val storedSize = output.channel.size()
            if (storedSize < resumeBytes) {
                output.close()
                return null
            }
            output.channel.truncate(resumeBytes)
            output.channel.position(resumeBytes)
            PendingPhoto(context, uri, descriptor, output, resumeBytes)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    private fun createPending(context: Context, photo: CameraObject): PendingPhoto {
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
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: error("Android could not open ${photo.filename}")
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            PendingPhoto(context, uri, descriptor, output, 0L)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    class PendingPhoto internal constructor(
        private val context: Context,
        val uri: Uri,
        private val descriptor: ParcelFileDescriptor,
        private val fileOutput: ParcelFileDescriptor.AutoCloseOutputStream,
        initialBytes: Long,
        private val finalName: String? = null,
    ) : Closeable {
        private var committed = false
        private var closed = false
        private val countingOutput = CountingOutputStream(fileOutput, initialBytes)
        val output: OutputStream get() = countingOutput
        val bytesWritten: Long get() = countingOutput.bytesWritten

        fun checkpoint(expectedBytes: Long) {
            if (countingOutput.bytesWritten != expectedBytes) {
                throw IllegalStateException(
                    "Downloaded ${countingOutput.bytesWritten} bytes; expected checkpoint $expectedBytes",
                )
            }
            countingOutput.flush()
            descriptor.fileDescriptor.sync()
        }

        fun commit(expectedSize: Long) {
            checkpoint(expectedSize)
            if (expectedSize > 0L && countingOutput.bytesWritten != expectedSize) {
                throw IllegalStateException(
                    "Downloaded ${countingOutput.bytesWritten} of $expectedSize bytes",
                )
            }
            // Verify the actual file before publishing it, while a failed check can still be discarded.
            val storedSize = fileOutput.channel.size()
            if (expectedSize > 0L && storedSize != expectedSize) {
                throw IllegalStateException("Android stored $storedSize of $expectedSize bytes")
            }
            closeOutput()
            if (finalName != null) {
                val savedSize = context.contentResolver.query(
                    uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null,
                )?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
                if (expectedSize > 0L && savedSize >= 0L && savedSize != expectedSize) {
                    throw IllegalStateException("Android stored $savedSize of $expectedSize bytes")
                }
                val renamed = DocumentsContract.renameDocument(context.contentResolver, uri, finalName)
                    ?: throw IllegalStateException("Android could not finish $finalName")
                if (renamed == Uri.EMPTY) throw IllegalStateException("Android could not finish $finalName")
                committed = true
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (context.contentResolver.update(uri, values, null, null) != 1) {
                throw IllegalStateException("Android could not finish ${uri.lastPathSegment.orEmpty()}")
            }
            committed = true
        }

        fun discard() {
            closeOutput()
            if (!committed) {
                discardIncomplete(context, uri.toString())
            }
        }

        override fun close() {
            closeOutput()
        }

        private fun closeOutput() {
            if (closed) return
            closed = true
            runCatching { countingOutput.close() }
        }
    }

    private class CountingOutputStream(output: OutputStream, initialBytes: Long) : FilterOutputStream(output) {
        var bytesWritten: Long = initialBytes
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

    private data class TreeDocument(val uri: Uri, val name: String, val size: Long)

    private fun listDocuments(context: Context, tree: Uri): List<TreeDocument> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(TreeDocument(
                        DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                        cursor.getString(1).orEmpty(),
                        if (cursor.isNull(2)) -1L else cursor.getLong(2),
                    ))
                }
            }
        }.orEmpty()
    }

    private fun findDocument(context: Context, tree: Uri, name: String, size: Long): TreeDocument? =
        listDocuments(context, tree).firstOrNull { it.name == name && it.size == size }

    private fun openExistingDocument(
        context: Context, tree: Uri, photo: CameraObject, uri: Uri, resumeBytes: Long,
    ): PendingPhoto? {
        if (listDocuments(context, tree).none { it.uri == uri && it.name == photo.filename + PARTIAL_SUFFIX }) return null
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
        return try {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            if (output.channel.size() < resumeBytes) {
                output.close()
                return null
            }
            output.channel.truncate(resumeBytes)
            output.channel.position(resumeBytes)
            PendingPhoto(context, uri, descriptor, output, resumeBytes, photo.filename)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    private fun createPendingDocument(context: Context, tree: Uri, photo: CameraObject): PendingPhoto {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(
            resolver, parent, photo.mimeType, photo.filename + PARTIAL_SUFFIX,
        ) ?: error("Android could not create ${photo.filename}")
        return try {
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: error("Android could not open ${photo.filename}")
            val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            PendingPhoto(context, uri, descriptor, output, 0L, photo.filename)
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw error
        }
    }

    private fun collection(photo: CameraObject): Uri = if (photo.isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun destinationDirectory(photo: CameraObject): String =
        if (photo.isVideo) VIDEO_DESTINATION_DIRECTORY else PHOTO_DESTINATION_DIRECTORY

    private const val PHOTO_DESTINATION_DIRECTORY = "Pictures/Zf AP Transfer"
    private const val VIDEO_DESTINATION_DIRECTORY = "Movies/Zf AP Transfer"
    private const val PARTIAL_SUFFIX = ".nikonpull-part"
}
