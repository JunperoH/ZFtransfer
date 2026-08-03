package com.noisetta.nikonpull.data

import android.content.Context
import com.noisetta.nikonpull.ptp.CameraObject
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class QueuedDownload(
    val media: CameraObject,
    val attempts: Int = 0,
)

data class DownloadQueueSnapshot(
    val cameraIdentity: String = "",
    val items: List<QueuedDownload>,
    val totalCount: Int,
    val totalBytes: Long,
    val processedBytes: Long = 0L,
    val downloadedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedFilenames: List<String> = emptyList(),
) {
    val failedCount: Int get() = failedFilenames.size
    val processedCount: Int get() = downloadedCount + skippedCount + failedCount

    companion object {
        fun create(media: List<CameraObject>, cameraIdentity: String = "") = DownloadQueueSnapshot(
            cameraIdentity = cameraIdentity,
            items = media.map(::QueuedDownload),
            totalCount = media.size,
            totalBytes = media.sumOf { it.size.coerceAtLeast(0L) },
        )
    }
}

class DownloadQueueStore(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
    private val queueFile = File(directory, QUEUE_FILENAME)

    @Synchronized
    fun load(): DownloadQueueSnapshot? = runCatching {
        if (!queueFile.isFile) return null
        val root = JSONObject(queueFile.readText())
        val itemsJson = root.getJSONArray("items")
        val items = buildList(itemsJson.length()) {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                add(
                    QueuedDownload(
                        media = CameraObject(
                            handle = item.getLong("handle"),
                            storageId = item.getLong("storageId"),
                            format = item.getInt("format"),
                            size = item.getLong("size"),
                            thumbnailSize = item.getLong("thumbnailSize"),
                            filename = item.getString("filename"),
                            captureDate = item.optString("captureDate"),
                        ),
                        attempts = item.optInt("attempts"),
                    ),
                )
            }
        }
        val failedJson = root.optJSONArray("failedFilenames") ?: JSONArray()
        val failed = buildList(failedJson.length()) {
            for (index in 0 until failedJson.length()) add(failedJson.getString(index))
        }
        DownloadQueueSnapshot(
            cameraIdentity = root.optString("cameraIdentity"),
            items = items,
            totalCount = root.optInt("totalCount", items.size),
            totalBytes = root.optLong("totalBytes", items.sumOf { it.media.size }),
            processedBytes = root.optLong("processedBytes"),
            downloadedCount = root.optInt("downloadedCount"),
            skippedCount = root.optInt("skippedCount"),
            failedFilenames = failed,
        )
    }.getOrNull()

    @Synchronized
    fun save(snapshot: DownloadQueueSnapshot) {
        val items = JSONArray()
        snapshot.items.forEach { queued ->
            val media = queued.media
            items.put(
                JSONObject()
                    .put("handle", media.handle)
                    .put("storageId", media.storageId)
                    .put("format", media.format)
                    .put("size", media.size)
                    .put("thumbnailSize", media.thumbnailSize)
                    .put("filename", media.filename)
                    .put("captureDate", media.captureDate)
                    .put("attempts", queued.attempts),
            )
        }
        val failed = JSONArray().also { array ->
            snapshot.failedFilenames.forEach(array::put)
        }
        val json = JSONObject()
            .put("version", VERSION)
            .put("cameraIdentity", snapshot.cameraIdentity)
            .put("totalCount", snapshot.totalCount)
            .put("totalBytes", snapshot.totalBytes)
            .put("processedBytes", snapshot.processedBytes)
            .put("downloadedCount", snapshot.downloadedCount)
            .put("skippedCount", snapshot.skippedCount)
            .put("failedFilenames", failed)
            .put("items", items)
            .toString()
        val temporary = File(directory, "$QUEUE_FILENAME.tmp")
        temporary.writeText(json)
        if (!temporary.renameTo(queueFile)) {
            queueFile.writeText(json)
            temporary.delete()
        }
    }

    @Synchronized
    fun clear() {
        queueFile.delete()
        File(directory, "$QUEUE_FILENAME.tmp").delete()
    }

    private companion object {
        const val VERSION = 2
        const val DIRECTORY = "downloads"
        const val QUEUE_FILENAME = "queue.json"
    }
}
