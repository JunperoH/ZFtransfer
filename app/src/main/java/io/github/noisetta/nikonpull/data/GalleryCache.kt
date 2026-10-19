package io.github.noisetta.nikonpull.data

import android.content.Context
import io.github.noisetta.nikonpull.ptp.CameraObject
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class CachedGallery(
    val objects: List<CameraObject>,
    val syncedAtMillis: Long,
    val newIdentities: Set<String> = emptySet(),
)

class GalleryCache(context: Context) {
    private val root = File(context.filesDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val camerasDirectory = File(root, CAMERAS_DIRECTORY).apply { mkdirs() }
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var activeCameraKey: String = preferences.getString(PREFERENCE_LAST_CAMERA, null)
        ?.takeIf(String::isNotBlank)
        ?: LEGACY_CAMERA_KEY

    val cameraKey: String get() = activeCameraKey

    @Synchronized
    fun selectCamera(stableIdentity: String, migrateLegacy: Boolean): CachedGallery? {
        val selectedKey = identityHash(stableIdentity)
        val selectedRoot = File(camerasDirectory, selectedKey).apply { mkdirs() }
        if (migrateLegacy && !File(selectedRoot, CATALOG_FILENAME).isFile) {
            val legacyCatalog = File(root, CATALOG_FILENAME)
            if (legacyCatalog.isFile) legacyCatalog.copyTo(File(selectedRoot, CATALOG_FILENAME), overwrite = false)
            val legacyThumbnails = File(root, THUMBNAILS_DIRECTORY)
            val selectedThumbnails = File(selectedRoot, THUMBNAILS_DIRECTORY).apply { mkdirs() }
            legacyThumbnails.listFiles()?.filter(File::isFile)?.forEach { source ->
                runCatching { source.copyTo(File(selectedThumbnails, source.name), overwrite = false) }
            }
        }
        activeCameraKey = selectedKey
        preferences.edit().putString(PREFERENCE_LAST_CAMERA, selectedKey).apply()
        return loadCatalog()
    }

    @Synchronized
    fun loadCatalog(): CachedGallery? = runCatching {
        val catalogFile = catalogFile()
        if (!catalogFile.isFile) return null
        val rootJson = JSONObject(catalogFile.readText())
        val items = rootJson.getJSONArray("objects")
        val objects = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    CameraObject(
                        handle = item.getLong("handle"),
                        storageId = item.getLong("storageId"),
                        format = item.getInt("format"),
                        size = item.getLong("size"),
                        thumbnailSize = item.getLong("thumbnailSize"),
                        filename = item.getString("filename"),
                        captureDate = item.optString("captureDate"),
                    ),
                )
            }
        }
        val newItems = rootJson.optJSONArray("newIdentities")
        val newIdentities = buildSet {
            if (newItems != null) {
                for (index in 0 until newItems.length()) add(newItems.getString(index))
            }
        }
        CachedGallery(objects, rootJson.optLong("syncedAtMillis"), newIdentities)
    }.getOrNull()

    @Synchronized
    fun saveCatalog(objects: List<CameraObject>, syncedAtMillis: Long, newIdentities: Set<String> = emptySet()) {
        val activeRoot = activeRoot().apply { mkdirs() }
        val catalogFile = File(activeRoot, CATALOG_FILENAME)
        val items = JSONArray()
        objects.forEach { item ->
            items.put(
                JSONObject()
                    .put("handle", item.handle)
                    .put("storageId", item.storageId)
                    .put("format", item.format)
                    .put("size", item.size)
                    .put("thumbnailSize", item.thumbnailSize)
                    .put("filename", item.filename)
                    .put("captureDate", item.captureDate),
            )
        }
        val json = JSONObject()
            .put("version", CATALOG_VERSION)
            .put("syncedAtMillis", syncedAtMillis)
            .put("newIdentities", JSONArray().also { array -> newIdentities.forEach(array::put) })
            .put("objects", items)
            .toString()
        val temporary = File(activeRoot, "$CATALOG_FILENAME.tmp")
        temporary.writeText(json)
        if (!temporary.renameTo(catalogFile)) {
            catalogFile.writeText(json)
            temporary.delete()
        }
    }

    @Synchronized
    fun readThumbnail(photo: CameraObject): ByteArray? {
        val file = thumbnailFile(photo)
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }.getOrNull()
    }

    @Synchronized
    fun writeThumbnail(photo: CameraObject, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val destination = thumbnailFile(photo)
        val temporary = File(thumbnailsDirectory(), "${destination.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(destination)) {
            destination.writeBytes(bytes)
            temporary.delete()
        }
        destination.setLastModified(System.currentTimeMillis())
        pruneThumbnails(thumbnailLimitBytes)
    }

    @Synchronized
    fun deleteThumbnail(photo: CameraObject) {
        thumbnailFile(photo).delete()
    }

    @Synchronized
    fun clearThumbnails() {
        allThumbnailFiles().forEach(File::delete)
    }

    @Synchronized
    fun thumbnailUsageBytes(): Long = allThumbnailFiles().sumOf(File::length)

    var thumbnailLimitBytes: Long
        get() = preferences.getInt(PREFERENCE_LIMIT_MB, DEFAULT_LIMIT_MB).toLong() * BYTES_PER_MB
        @Synchronized set(value) {
            val megabytes = (value / BYTES_PER_MB).toInt().coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
            preferences.edit().putInt(PREFERENCE_LIMIT_MB, megabytes).apply()
            pruneThumbnails(megabytes.toLong() * BYTES_PER_MB)
        }

    private fun pruneThumbnails(limitBytes: Long) {
        val files = allThumbnailFiles().sortedBy(File::lastModified)
        var usage = files.sumOf(File::length)
        files.forEach { file ->
            if (usage <= limitBytes) return
            val length = file.length()
            if (file.delete()) usage -= length
        }
    }

    private fun thumbnailFile(photo: CameraObject): File {
        val identity = listOf(
            photo.storageId,
            photo.handle,
            photo.filename,
            photo.size,
            photo.captureDate,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(thumbnailsDirectory(), "$digest.jpg")
    }

    private fun activeRoot(): File = if (activeCameraKey == LEGACY_CAMERA_KEY) {
        root
    } else {
        File(camerasDirectory, activeCameraKey)
    }

    private fun catalogFile(): File = File(activeRoot(), CATALOG_FILENAME)

    private fun thumbnailsDirectory(): File =
        File(activeRoot(), THUMBNAILS_DIRECTORY).apply { mkdirs() }

    private fun allThumbnailFiles(): List<File> = buildList {
        File(root, THUMBNAILS_DIRECTORY).listFiles()?.filterTo(this, File::isFile)
        camerasDirectory.listFiles()?.filter(File::isDirectory)?.forEach { cameraRoot ->
            File(cameraRoot, THUMBNAILS_DIRECTORY).listFiles()?.filterTo(this, File::isFile)
        }
    }

    private fun identityHash(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(24)

    companion object {
        const val DEFAULT_LIMIT_MB = 250
        private const val MIN_LIMIT_MB = 50
        private const val MAX_LIMIT_MB = 1_000
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val CATALOG_VERSION = 1
        private const val CACHE_DIRECTORY = "gallery"
        private const val CAMERAS_DIRECTORY = "cameras"
        private const val CATALOG_FILENAME = "catalog.json"
        private const val THUMBNAILS_DIRECTORY = "thumbnails"
        private const val PREFERENCES_NAME = "gallery_cache"
        private const val PREFERENCE_LIMIT_MB = "thumbnail_limit_mb"
        private const val PREFERENCE_LAST_CAMERA = "last_camera_key"
        private const val LEGACY_CAMERA_KEY = "legacy"
    }
}
