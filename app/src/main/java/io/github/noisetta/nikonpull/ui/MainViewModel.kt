package io.github.noisetta.nikonpull.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.noisetta.nikonpull.BuildConfig
import io.github.noisetta.nikonpull.data.CameraEndpointSettings
import io.github.noisetta.nikonpull.data.CameraSettings
import io.github.noisetta.nikonpull.data.GalleryCache
import io.github.noisetta.nikonpull.data.DownloadQueueSnapshot
import io.github.noisetta.nikonpull.data.DownloadQueueStore
import io.github.noisetta.nikonpull.data.PhotoSaver
import io.github.noisetta.nikonpull.network.CameraNetworkLease
import io.github.noisetta.nikonpull.network.CameraNetworkResolver
import io.github.noisetta.nikonpull.ptp.CameraDeviceInfo
import io.github.noisetta.nikonpull.ptp.CameraObject
import io.github.noisetta.nikonpull.ptp.PtpException
import io.github.noisetta.nikonpull.ptp.PtpIpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class AppState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Enable Wi-Fi on a Nikon WMU camera, then connect this phone to its network.",
    val cameraName: String = "Nikon WMU camera",
    val cameraInfo: CameraDeviceInfo? = null,
    val compatibilityStatus: String = "Not checked",
    val connectedEndpoint: String = "",
    val manualCameraHost: String = "",
    val cameraPort: Int = PtpIpClient.DEFAULT_PORT,
    val librarySummary: String = "",
    val photos: List<CameraObject> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val thumbnails: Map<Long, ByteArray> = emptyMap(),
    val transferProgress: Float? = null,
    val currentFileProgress: Float? = null,
    val downloadActive: Boolean = false,
    val downloadQueueRemaining: Int = 0,
    val downloadQueueTotal: Int = 0,
    val downloadSummary: DownloadResultSummary? = null,
    val lastSyncedAtMillis: Long = 0L,
    val thumbnailCacheBytes: Long = 0L,
    val thumbnailCacheLimitBytes: Long = GalleryCache.DEFAULT_LIMIT_MB * 1024L * 1024L,
)

data class DownloadResultSummary(
    val totalCount: Int,
    val downloadedCount: Int,
    val skippedCount: Int,
    val failedFilenames: List<String>,
    val failedDetails: List<String>,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var client: PtpIpClient? = null
    private var networkLease: CameraNetworkLease? = null
    private var connectionJob: Job? = null
    private var autoConnectJob: Job? = null
    private var transferJob: Job? = null
    @Volatile private var transferCancellationRequested = false
    private val galleryCache = GalleryCache(application)
    private val cameraSettings = CameraSettings(application)
    private val downloadQueueStore = DownloadQueueStore(application)
    private var currentCameraIdentity: String = ""
    private var cachedCameraObjects: List<CameraObject> = emptyList()
    private val cacheLoadJob: Job
    private val connectionStateLock = Any()
    private val downloadQueueStateLock = Any()
    private val thumbnailRequests = HashSet<Long>()
    private val mutableState = MutableStateFlow(
        AppState(
            manualCameraHost = cameraSettings.endpoint.host,
            cameraPort = cameraSettings.endpoint.port,
        ),
    )
    val state = mutableState.asStateFlow()

    init {
        cacheLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val queuedDownloads = downloadQueueStore.load()
            val retainedPendingUris = queuedDownloads?.items.orEmpty()
                .mapNotNull { it.pendingUri.takeIf(String::isNotBlank) }
                .toSet()
            PhotoSaver.cleanupIncomplete(getApplication(), retainedPendingUris)
            val cached = galleryCache.loadCatalog()
            if (cached != null) {
                cachedCameraObjects = cached.objects
                val media = cached.objects.filter { it.isPhoto || it.isVideo }
                val summary = inventorySummary(cached.objects, cached.objects.size)
                mutableState.update {
                    it.copy(
                        photos = media,
                        librarySummary = summary,
                        message = "Cached gallery available",
                        lastSyncedAtMillis = cached.syncedAtMillis,
                        thumbnailCacheBytes = galleryCache.thumbnailUsageBytes(),
                        thumbnailCacheLimitBytes = galleryCache.thumbnailLimitBytes,
                    )
                }
            } else {
                mutableState.update {
                    it.copy(
                        thumbnailCacheBytes = galleryCache.thumbnailUsageBytes(),
                        thumbnailCacheLimitBytes = galleryCache.thumbnailLimitBytes,
                    )
                }
            }
            queuedDownloads?.takeIf { it.items.isNotEmpty() }?.let { queue ->
                val savedBytes = queue.items.first().downloadedBytes
                mutableState.update {
                    it.copy(
                        downloadQueueRemaining = queue.items.size,
                        downloadQueueTotal = queue.totalCount,
                        transferProgress = queue.overallProgress(),
                        message = if (savedBytes > 0L) {
                            "${queue.items.first().media.filename} is ${formatPercent(savedBytes, queue.items.first().media.size)} saved; waiting to resume"
                        } else {
                            "${queue.items.size} queued downloads waiting for the camera"
                        },
                    )
                }
            }
        }
    }

    fun connect() {
        if (mutableState.value.connected || connectionJob?.isActive == true) return
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            cacheLoadJob.join()
            runCatching { client?.close() }
            runCatching { networkLease?.close() }
            client = null
            networkLease = null

            runWork("Waiting for Nikon Wi-Fi…") {
                var connectedClient: PtpIpClient? = null
                var connectedDevice: CameraDeviceInfo? = null
                var connectedEndpoint = ""
                val endpointSettings = cameraSettings.endpoint
                for (attempt in 1..CONNECTION_ATTEMPTS) {
                    if (attempt > 1) {
                        mutableState.update {
                            it.copy(message = "Camera Wi-Fi changed; reconnecting ($attempt/$CONNECTION_ATTEMPTS)…")
                        }
                        delay(CONNECTION_RETRY_DELAY_MS)
                    }

                    runCatching { client?.close() }
                    runCatching { networkLease?.close() }
                    client = null
                    networkLease = null

                    val cameraNetwork = CameraNetworkResolver.acquire(
                        getApplication(),
                        endpointSettings.host.takeIf(String::isNotBlank),
                    )
                    val endpoint = "${cameraNetwork.gatewayAddress}:${endpointSettings.port}"
                    val routeDescription = "${cameraNetwork.phoneAddress} → $endpoint"
                    mutableState.update {
                        it.copy(message = "Nikon Wi-Fi route: $routeDescription (attempt $attempt/$CONNECTION_ATTEMPTS)")
                    }
                    val candidate = PtpIpClient(
                        host = cameraNetwork.gatewayAddress,
                        cameraPort = endpointSettings.port,
                        clientGuid = cameraSettings.clientGuid,
                        socketFactory = cameraNetwork.network.socketFactory,
                    )
                    client = candidate
                    networkLease = cameraNetwork

                    try {
                        candidate.connect()
                        val device = candidate.getDeviceInfo()
                        if (!device.isBrowseCompatible) {
                            val missing = device.missingRequiredOperations
                                .joinToString { "0x${it.toString(16).uppercase()}" }
                            throw PtpException(
                                "${device.displayName} is missing required PTP operations: $missing",
                            )
                        }
                        connectedClient = candidate
                        connectedDevice = device
                        connectedEndpoint = endpoint
                        break
                    } catch (error: Throwable) {
                        runCatching { candidate.close() }
                        client = null
                        runCatching { cameraNetwork.close() }
                        networkLease = null
                        if (attempt == CONNECTION_ATTEMPTS) throw error
                    }
                }

                val cameraClient = connectedClient
                    ?: throw PtpException("Camera connection retry ended unexpectedly")
                val device = connectedDevice
                    ?: throw PtpException("Camera identity could not be read")
                client = cameraClient
                currentCameraIdentity = device.stableIdentity
                val cameraCache = galleryCache.selectCamera(
                    stableIdentity = device.stableIdentity,
                    migrateLegacy = device.model.contains("D5300", ignoreCase = true),
                )
                cachedCameraObjects = cameraCache?.objects.orEmpty()
                val cachedMedia = cachedCameraObjects.filter { it.isPhoto || it.isVideo }
                mutableState.update {
                    it.copy(
                        connected = true,
                        cameraName = device.displayName,
                        cameraInfo = device,
                        compatibilityStatus = if (device.model.contains("D5300", ignoreCase = true)) {
                            "Verified with this app"
                        } else {
                            "Required browsing operations available; hardware verification requested"
                        },
                        connectedEndpoint = connectedEndpoint,
                        photos = cachedMedia,
                        thumbnails = emptyMap(),
                        selected = emptySet(),
                        lastSyncedAtMillis = cameraCache?.syncedAtMillis ?: 0L,
                        thumbnailCacheBytes = galleryCache.thumbnailUsageBytes(),
                        message = "${device.displayName} detected; checking for new media…",
                    )
                }
                synchronizeLibrary(cameraClient, forceMetadataRefresh = false)
            }
            if (!mutableState.value.connected) {
                runCatching { networkLease?.close() }
                networkLease = null
            } else {
                resumeQueuedDownloads()
            }
        }
    }

    fun startAutoConnect() {
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = viewModelScope.launch(Dispatchers.IO) {
            var nextHeartbeatAt = 0L
            while (isActive) {
                val connected = mutableState.value.connected
                if (!connected &&
                    connectionJob?.isActive != true &&
                    CameraNetworkResolver.find(
                        getApplication(),
                        cameraSettings.endpoint.host.takeIf(String::isNotBlank),
                    ) != null
                ) {
                    connect()
                } else if (connected && connectionJob?.isActive != true) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= nextHeartbeatAt) {
                        nextHeartbeatAt = now + HEARTBEAT_INTERVAL_MS
                        client?.let { cameraClient ->
                            runCatching { cameraClient.ping() }
                                .onFailure { markConnectionLost(cameraClient) }
                        }
                    }
                }
                delay(AUTO_CONNECT_POLL_MS)
            }
        }
    }

    fun toggle(handle: Long) {
        mutableState.update { current ->
            current.copy(
                selected = if (handle in current.selected) {
                    current.selected - handle
                } else {
                    current.selected + handle
                },
            )
        }
    }

    fun selectAll() {
        mutableState.update { it.copy(selected = it.photos.map(CameraObject::handle).toSet()) }
    }

    fun select(handles: Collection<Long>) {
        mutableState.update { it.copy(selected = handles.toSet()) }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selected = emptySet()) }
    }

    fun refreshLibrary() {
        if (!mutableState.value.connected || mutableState.value.busy) return
        val cameraClient = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runWork("Refreshing camera library…") {
                synchronizeLibrary(cameraClient, forceMetadataRefresh = true)
            }
        }
    }

    fun saveCameraEndpoint(host: String, portText: String) {
        val trimmedHost = host.trim()
        val port = portText.toIntOrNull()
        if (trimmedHost.isNotEmpty() && !trimmedHost.isValidIpv4Address()) {
            mutableState.update { it.copy(message = "Enter a valid IPv4 camera address") }
            return
        }
        if (port == null || port !in 1..65535) {
            mutableState.update { it.copy(message = "Enter a valid camera port") }
            return
        }
        cameraSettings.endpoint = CameraEndpointSettings(trimmedHost, port)
        mutableState.update {
            it.copy(
                manualCameraHost = trimmedHost,
                cameraPort = port,
                message = if (trimmedHost.isBlank()) {
                    "Automatic camera gateway discovery enabled"
                } else {
                    "Manual camera endpoint saved: $trimmedHost:$port"
                },
            )
        }
        client?.let(::markConnectionLost)
    }

    fun compatibilityReport(): String {
        val current = mutableState.value
        val info = current.cameraInfo
        return buildString {
            appendLine("Nikon Pull compatibility report")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Connection: ${if (current.connected) "connected" else "disconnected"}")
            appendLine(
                "Endpoint mode: ${if (current.manualCameraHost.isBlank()) "automatic gateway" else "manual"}; " +
                    "port ${current.cameraPort}",
            )
            appendLine("Status: ${current.compatibilityStatus}")
            appendLine("Camera: ${info?.displayName ?: "not detected"}")
            appendLine("Firmware: ${info?.deviceVersion.orEmpty().ifBlank { "not reported" }}")
            appendLine("PTP standard: ${info?.standardVersion ?: 0}")
            appendLine(
                "Vendor extension: ${info?.vendorExtensionId ?: 0} / " +
                    "${info?.vendorExtensionVersion ?: 0}",
            )
            appendLine("GetThumb: ${if (info?.supportsThumbnail == true) "yes" else "no/unknown"}")
            appendLine(
                "GetPartialObject: " +
                    if (info?.supportsPartialObject == true) "yes" else "no/unknown",
            )
            appendLine(
                "Operations: " + info?.operationsSupported.orEmpty()
                    .sorted()
                    .joinToString { "0x${it.toString(16).uppercase()}" },
            )
            appendLine("Media: ${current.librarySummary.ifBlank { "not synchronized" }}")
            append("Serial numbers, filenames, and image contents are intentionally omitted.")
        }
    }

    fun setThumbnailCacheLimit(megabytes: Int) = viewModelScope.launch(Dispatchers.IO) {
        galleryCache.thumbnailLimitBytes = megabytes.toLong() * BYTES_PER_MB
        mutableState.update {
            it.copy(
                thumbnailCacheBytes = galleryCache.thumbnailUsageBytes(),
                thumbnailCacheLimitBytes = galleryCache.thumbnailLimitBytes,
            )
        }
    }

    fun clearThumbnailCache() = viewModelScope.launch(Dispatchers.IO) {
        galleryCache.clearThumbnails()
        mutableState.update {
            it.copy(
                thumbnailCacheBytes = 0L,
                message = "Preview cache cleared; the media catalog is still available offline.",
            )
        }
    }

    fun downloadSelected() {
        if (transferJob?.isActive == true) return
        val selectedPhotos = mutableState.value.photos
            .filter { it.handle in mutableState.value.selected }
        if (selectedPhotos.isEmpty()) return

        val existing = downloadQueueStore.load()?.takeIf { it.items.isNotEmpty() }
        if (existing != null && existing.cameraIdentity.isNotBlank() &&
            existing.cameraIdentity != currentCameraIdentity
        ) {
            mutableState.update {
                it.copy(message = "The existing download queue belongs to another camera")
            }
            return
        }
        val existingKeys = existing?.items.orEmpty().map { it.media.downloadIdentity() }.toSet()
        val additions = selectedPhotos.distinctBy { it.downloadIdentity() }
            .filterNot { it.downloadIdentity() in existingKeys }
        if (additions.isEmpty()) {
            mutableState.update { it.copy(message = "Those files are already in the download queue") }
            return
        }
        val queue = existing?.copy(
            cameraIdentity = existing.cameraIdentity.ifBlank { currentCameraIdentity },
            items = existing.items + additions.map { io.github.noisetta.nikonpull.data.QueuedDownload(it) },
            totalCount = existing.totalCount + additions.size,
            totalBytes = existing.totalBytes + additions.sumOf { it.size.coerceAtLeast(0L) },
        ) ?: DownloadQueueSnapshot.create(additions, currentCameraIdentity)
        downloadQueueStore.save(queue)
        mutableState.update {
            it.copy(
                selected = emptySet(),
                downloadQueueRemaining = queue.items.size,
                downloadQueueTotal = queue.totalCount,
                transferProgress = queue.overallProgress(),
                downloadSummary = null,
                message = "Queued ${additions.size} original files",
            )
        }
        resumeQueuedDownloads()
    }

    fun cancelDownload() {
        if (mutableState.value.downloadQueueRemaining == 0 && transferJob?.isActive != true) return
        val pendingUris = synchronized(downloadQueueStateLock) {
            transferCancellationRequested = true
            val uris = downloadQueueStore.load()?.items.orEmpty()
                .mapNotNull { it.pendingUri.takeIf(String::isNotBlank) }
            downloadQueueStore.clear()
            uris
        }
        val job = transferJob?.takeIf(Job::isActive)
        if (job != null) {
            mutableState.update { it.copy(message = "Cancelling download queue…") }
            job.cancel()
            client?.let(::markConnectionLost)
        }
        viewModelScope.launch(Dispatchers.IO) {
            pendingUris.forEach { PhotoSaver.discardIncomplete(getApplication(), it) }
        }
        mutableState.update {
            it.copy(
                downloadQueueRemaining = 0,
                downloadQueueTotal = 0,
                transferProgress = null,
                currentFileProgress = null,
                message = if (job == null) "Download queue cancelled" else "Download cancelled; reconnecting…",
            )
        }
    }

    fun dismissDownloadSummary() {
        mutableState.update { it.copy(downloadSummary = null) }
    }

    private fun resumeQueuedDownloads() {
        if (!mutableState.value.connected || transferJob?.isActive == true) return
        val queue = downloadQueueStore.load() ?: return
        if (queue.cameraIdentity.isNotBlank() && queue.cameraIdentity != currentCameraIdentity) {
            mutableState.update {
                it.copy(message = "${queue.items.size} downloads are waiting for their original camera")
            }
            return
        }
        if (queue.items.isEmpty()) {
            finishDownloadQueue(queue)
            return
        }

        transferCancellationRequested = false
        transferJob = viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            val powerManager = application.getSystemService(PowerManager::class.java)
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NikonPull:download",
            ).apply { setReferenceCounted(false) }
            @Suppress("DEPRECATION")
            val wifiLock = application.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "NikonPull:download")
                .apply { setReferenceCounted(false) }
            wakeLock.acquire(MAX_TRANSFER_WAKE_LOCK_MS)
            try {
                wifiLock.acquire()
                try {
                    mutableState.update {
                        it.copy(
                            downloadActive = true,
                            downloadQueueRemaining = queue.items.size,
                            downloadQueueTotal = queue.totalCount,
                            transferProgress = queue.overallProgress(),
                        )
                    }
                    processDownloadQueue()
                } finally {
                    if (wifiLock.isHeld) wifiLock.release()
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                transferCancellationRequested = false
                transferJob = null
                mutableState.update {
                    it.copy(downloadActive = false, currentFileProgress = null)
                }
            }
        }
    }

    private suspend fun processDownloadQueue() {
        var queue = downloadQueueStore.load() ?: return
        while (queue.items.isNotEmpty()) {
            val cameraClient = client ?: return
            if (!mutableState.value.connected) return
            var queued = queue.items.first()
            val media = queued.media
            val ordinal = queue.processedCount + 1

            if (PhotoSaver.isAlreadyDownloaded(getApplication(), media)) {
                PhotoSaver.discardIncomplete(getApplication(), queued.pendingUri)
                queue = queue.completeFirst(skipped = true)
                persistQueueProgress(queue, "Already downloaded: ${media.filename}")
                continue
            }

            var pendingPhoto: PhotoSaver.PendingPhoto? = null
            try {
                var resumeBytes = queued.downloadedBytes.coerceIn(0L, media.size.coerceAtLeast(0L))
                var pendingUri = queued.pendingUri
                if (resumeBytes > 0L) {
                    if (pendingUri.isBlank()) {
                        resumeBytes = 0L
                    } else {
                        val currentObject = cameraClient.getObjectInfo(media.handle)
                        if (!currentObject.matchesResumeIdentity(media)) {
                            throw IllegalStateException(
                                "${media.filename} changed on the camera; refusing to resume the partial file",
                            )
                        }
                        if (mutableState.value.cameraInfo?.supportsPartialObject != true) {
                            PhotoSaver.discardIncomplete(getApplication(), pendingUri)
                            pendingUri = ""
                            resumeBytes = 0L
                        }
                    }
                }

                pendingPhoto = PhotoSaver.openPending(
                    context = getApplication(),
                    photo = media,
                    pendingUri = pendingUri,
                    resumeBytes = resumeBytes,
                )
                queue = queue.updateFirstResume(
                    pendingUri = pendingPhoto.uri.toString(),
                    downloadedBytes = pendingPhoto.bytesWritten,
                )
                queued = queue.items.first()
                synchronized(downloadQueueStateLock) {
                    if (transferCancellationRequested) throw CancellationException("Download cancelled")
                    downloadQueueStore.save(queue)
                }
                val resumedFrom = pendingPhoto.bytesWritten

                cameraClient.downloadObject(
                    handle = media.handle,
                    objectSize = media.size,
                    destination = pendingPhoto.output,
                    startOffset = resumedFrom,
                    onChunkCompleted = { completedBytes ->
                        synchronized(downloadQueueStateLock) {
                            if (transferCancellationRequested) {
                                throw CancellationException("Download cancelled")
                            }
                            pendingPhoto.checkpoint(completedBytes)
                            queue = queue.updateFirstResume(
                                pendingUri = pendingPhoto.uri.toString(),
                                downloadedBytes = completedBytes,
                                resetAttempts = true,
                            )
                            queued = queue.items.first()
                            downloadQueueStore.save(queue)
                        }
                    },
                    onProgress = { progress ->
                        val fileFraction = progress.totalBytes
                            ?.takeIf { it > 0L }
                            ?.let { progress.bytesTransferred.toFloat() / it.toFloat() }
                            ?.coerceIn(0f, 1f)
                        val transferred = progress.bytesTransferred
                            .coerceAtMost(media.size.coerceAtLeast(0L))
                        val overall = queue.overallProgress(transferred, fileFraction)
                        val percent = fileFraction?.times(100)?.roundToInt()?.coerceIn(0, 100)
                        mutableState.update {
                            it.copy(
                                message = buildString {
                                    append(if (resumedFrom > 0L) "Resuming " else "Downloading ")
                                    append("$ordinal/${queue.totalCount}: ${media.filename}")
                                    if (percent != null) append(" ($percent%)")
                                    if (queued.attempts > 0) {
                                        append(" • retry ${queued.attempts}/$MAX_DOWNLOAD_ATTEMPTS")
                                    }
                                },
                                currentFileProgress = fileFraction,
                                transferProgress = overall,
                            )
                        }
                    },
                )
                pendingPhoto.commit(media.size)
            } catch (error: Throwable) {
                if (error is CancellationException ||
                    transferCancellationRequested ||
                    !currentCoroutineContext().isActive
                ) {
                    pendingPhoto?.discard()
                    throw CancellationException("Download cancelled")
                }
                pendingPhoto?.close()
                queued = queue.items.first()
                val nextAttempt = queued.attempts + 1
                val connectionFailure = error.isConnectionFailure()
                val failureReason = friendlyMessage(error)
                queue = if (nextAttempt >= MAX_DOWNLOAD_ATTEMPTS) {
                    pendingPhoto?.discard()
                        ?: PhotoSaver.discardIncomplete(getApplication(), queued.pendingUri)
                    queue.failFirst(failureReason)
                } else {
                    queue.copy(
                        items = listOf(queued.copy(attempts = nextAttempt)) + queue.items.drop(1),
                    )
                }
                synchronized(downloadQueueStateLock) {
                    if (transferCancellationRequested) {
                        throw CancellationException("Download cancelled")
                    }
                    downloadQueueStore.save(queue)
                }
                mutableState.update {
                    it.copy(
                        downloadQueueRemaining = queue.items.size,
                        downloadQueueTotal = queue.totalCount,
                        currentFileProgress = null,
                        transferProgress = queue.overallProgress(),
                        message = when {
                            connectionFailure && nextAttempt < MAX_DOWNLOAD_ATTEMPTS ->
                                "Camera disconnected during ${media.filename}; queued for retry $nextAttempt/$MAX_DOWNLOAD_ATTEMPTS"
                            connectionFailure ->
                                "Camera disconnected; ${media.filename} failed after $MAX_DOWNLOAD_ATTEMPTS attempts"
                            nextAttempt < MAX_DOWNLOAD_ATTEMPTS ->
                                "Retrying ${media.filename} ($nextAttempt/$MAX_DOWNLOAD_ATTEMPTS): $failureReason"
                            else ->
                                "Skipped ${media.filename} after $MAX_DOWNLOAD_ATTEMPTS attempts: $failureReason"
                        },
                    )
                }
                if (connectionFailure) {
                    markConnectionLost(cameraClient)
                    return
                }
                if (nextAttempt < MAX_DOWNLOAD_ATTEMPTS) delay(DOWNLOAD_RETRY_DELAY_MS)
                continue
            } finally {
                pendingPhoto?.close()
            }

            queue = queue.completeFirst(skipped = false)
            persistQueueProgress(queue, "Verified ${media.filename}")
        }
        finishDownloadQueue(queue)
    }

    private fun persistQueueProgress(queue: DownloadQueueSnapshot, message: String) {
        synchronized(downloadQueueStateLock) {
            if (transferCancellationRequested) throw CancellationException("Download cancelled")
            downloadQueueStore.save(queue)
        }
        mutableState.update {
            it.copy(
                downloadQueueRemaining = queue.items.size,
                downloadQueueTotal = queue.totalCount,
                currentFileProgress = null,
                transferProgress = queue.overallProgress(),
                message = "$message • ${queue.items.size} remaining",
            )
        }
    }

    private fun finishDownloadQueue(queue: DownloadQueueSnapshot) {
        synchronized(downloadQueueStateLock) {
            downloadQueueStore.clear()
        }
        val summary = DownloadResultSummary(
            totalCount = queue.totalCount,
            downloadedCount = queue.downloadedCount,
            skippedCount = queue.skippedCount,
            failedFilenames = queue.failedFilenames,
            failedDetails = queue.failedDetails,
        )
        mutableState.update {
            it.copy(
                downloadQueueRemaining = 0,
                downloadQueueTotal = 0,
                transferProgress = null,
                currentFileProgress = null,
                downloadSummary = summary,
                message = buildString {
                    append("Download complete: ${summary.downloadedCount} saved")
                    if (summary.skippedCount > 0) append(", ${summary.skippedCount} already present")
                    if (summary.failedFilenames.isNotEmpty()) append(", ${summary.failedFilenames.size} failed")
                },
            )
        }
    }

    private fun DownloadQueueSnapshot.completeFirst(skipped: Boolean): DownloadQueueSnapshot {
        val media = items.first().media
        return copy(
            items = items.drop(1),
            processedBytes = processedBytes + media.size.coerceAtLeast(0L),
            downloadedCount = downloadedCount + if (skipped) 0 else 1,
            skippedCount = skippedCount + if (skipped) 1 else 0,
        )
    }

    private fun DownloadQueueSnapshot.failFirst(reason: String): DownloadQueueSnapshot {
        val media = items.first().media
        return copy(
            items = items.drop(1),
            processedBytes = processedBytes + media.size.coerceAtLeast(0L),
            failedFilenames = failedFilenames + media.filename,
            failedDetails = failedDetails + "${media.filename}: $reason",
        )
    }

    private fun DownloadQueueSnapshot.updateFirstResume(
        pendingUri: String,
        downloadedBytes: Long,
        resetAttempts: Boolean = false,
    ): DownloadQueueSnapshot {
        val first = items.first()
        return copy(
            items = listOf(
                first.copy(
                    attempts = if (resetAttempts) 0 else first.attempts,
                    pendingUri = pendingUri,
                    downloadedBytes = downloadedBytes.coerceIn(
                        0L,
                        first.media.size.coerceAtLeast(0L),
                    ),
                ),
            ) + items.drop(1),
        )
    }

    private fun DownloadQueueSnapshot.overallProgress(
        currentBytes: Long? = null,
        currentFraction: Float? = null,
    ): Float? {
        val activeBytes = currentBytes ?: items.firstOrNull()?.downloadedBytes ?: 0L
        return when {
            totalBytes > 0L -> ((processedBytes + activeBytes).toDouble() / totalBytes.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
            totalCount > 0 -> ((processedCount + (currentFraction ?: 0f)) / totalCount.toFloat())
                .coerceIn(0f, 1f)
            else -> null
        }
    }

    private fun CameraObject.downloadIdentity(): String =
        "$storageId|$handle|$filename|$size"

    private fun CameraObject.matchesResumeIdentity(expected: CameraObject): Boolean =
        storageId == expected.storageId &&
            handle == expected.handle &&
            format == expected.format &&
            size == expected.size &&
            filename == expected.filename &&
            (captureDate.isBlank() || expected.captureDate.isBlank() || captureDate == expected.captureDate)

    fun requestThumbnail(photo: CameraObject) {
        val handle = photo.handle
        if (handle in mutableState.value.thumbnails) return
        synchronized(thumbnailRequests) {
            if (!thumbnailRequests.add(handle)) return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                galleryCache.readThumbnail(photo)?.let { cachedBytes ->
                    if (isDecodableImage(cachedBytes)) {
                        mutableState.update {
                            it.copy(thumbnails = it.thumbnails + (handle to cachedBytes))
                        }
                        return@launch
                    }
                    galleryCache.deleteThumbnail(photo)
                }

                if (!mutableState.value.connected) return@launch
                val cameraClient = client ?: return@launch
                var thumbnail: ByteArray? = null
                var lastError: Throwable? = null
                if (mutableState.value.cameraInfo?.supportsThumbnail != false) {
                    for (attempt in 1..THUMBNAIL_ATTEMPTS) {
                        val result = runCatching { cameraClient.getThumbnail(handle) }
                        lastError = result.exceptionOrNull()
                        thumbnail = result.getOrNull()
                            ?.takeIf(ByteArray::isNotEmpty)
                            ?.takeIf(::isDecodableImage)
                        if (thumbnail != null) break
                        if (attempt < THUMBNAIL_ATTEMPTS) delay(THUMBNAIL_RETRY_DELAY_MS)
                    }
                }
                if (thumbnail == null) {
                    val result = runCatching { createPreviewFromOriginal(photo, cameraClient) }
                    lastError = result.exceptionOrNull() ?: lastError
                    thumbnail = result.getOrNull()
                }
                thumbnail?.let { bytes ->
                    galleryCache.writeThumbnail(photo, bytes)
                    mutableState.update {
                        it.copy(
                            thumbnails = it.thumbnails + (handle to bytes),
                            thumbnailCacheBytes = galleryCache.thumbnailUsageBytes(),
                        )
                    }
                } ?: lastError?.let { error ->
                    if (error.isConnectionFailure()) markConnectionLost(cameraClient)
                }
            } finally {
                synchronized(thumbnailRequests) {
                    thumbnailRequests.remove(handle)
                }
            }
        }
    }

    private fun createPreviewFromOriginal(
        photo: CameraObject,
        cameraClient: PtpIpClient,
    ): ByteArray? {
        // Android's bitmap decoder does not reliably decode Nikon RAW originals. RAW
        // files use the camera-supplied thumbnail and the original remains untouched.
        if (!photo.isJpeg) return null
        if (photo.size <= 0L || photo.size > MAX_PREVIEW_FALLBACK_BYTES) return null
        val temporary = File.createTempFile(
            "nikon-preview-${photo.handle}-",
            ".jpg",
            getApplication<Application>().cacheDir,
        )
        return try {
            FileOutputStream(temporary).use { output ->
                cameraClient.downloadObject(photo.handle, photo.size, output)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > PREVIEW_MAX_DIMENSION * 2 ||
                bounds.outHeight / sampleSize > PREVIEW_MAX_DIMENSION * 2
            ) {
                sampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeFile(
                temporary.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return null
            try {
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output)) {
                        return null
                    }
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun isDecodableImage(bytes: ByteArray): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun markConnectionLost(failedClient: PtpIpClient) {
        synchronized(connectionStateLock) {
            if (client !== failedClient || !mutableState.value.connected) return
            failedClient.abort()
            client = null
            runCatching { networkLease?.close() }
            networkLease = null
            synchronized(thumbnailRequests) { thumbnailRequests.clear() }
            mutableState.update {
                it.copy(
                    connected = false,
                    busy = false,
                    transferProgress = null,
                    message = "Camera link was interrupted; reconnecting…",
                )
            }
        }
    }

    private fun Throwable.isConnectionFailure(): Boolean {
        if (generateSequence(this) { it.cause }.any { it is IOException }) return true
        val details = generateSequence(this) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" ")
            .lowercase()
        return details.contains("not connected") ||
            details.contains("disconnected") ||
            details.contains("connection reset") ||
            details.contains("broken pipe") ||
            details.contains("socket closed") ||
            details.contains("transfer ended early")
    }

    private fun synchronizeLibrary(
        cameraClient: PtpIpClient,
        forceMetadataRefresh: Boolean,
    ) {
        val handles = cameraClient.listObjectHandles()
        val cachedByHandle = cachedCameraObjects.associateBy(CameraObject::handle)
        val objects = ArrayList<CameraObject>(handles.size)
        handles.forEachIndexed { index, handle ->
            val cached = cachedByHandle[handle].takeUnless { forceMetadataRefresh }
            val cameraObject = cached ?: runCatching { cameraClient.getObjectInfo(handle) }.getOrNull()
            cameraObject?.let(objects::add)
            if ((index + 1) % 10 == 0 || index + 1 == handles.size) {
                mutableState.update {
                    it.copy(message = "Synchronizing media: ${index + 1} of ${handles.size}")
                }
            }
        }

        if (handles.isNotEmpty() && objects.isEmpty()) {
            throw PtpException("The camera listed ${handles.size} objects, but their details could not be read")
        }

        cachedCameraObjects = objects
        val media = objects
            .asSequence()
            .filter { it.isPhoto || it.isVideo }
            .sortedWith(compareByDescending<CameraObject> { it.captureDate }.thenByDescending { it.handle })
            .toList()
        val summary = inventorySummary(objects, handles.size)
        val syncedAt = System.currentTimeMillis()
        galleryCache.saveCatalog(objects, syncedAt)
        mutableState.update {
            it.copy(
                connected = true,
                photos = media,
                selected = it.selected.intersect(media.map(CameraObject::handle).toSet()),
                librarySummary = summary,
                message = summary,
                lastSyncedAtMillis = syncedAt,
                thumbnailCacheBytes = galleryCache.thumbnailUsageBytes(),
                thumbnailCacheLimitBytes = galleryCache.thumbnailLimitBytes,
            )
        }
    }

    private fun inventorySummary(
        cameraObjects: List<CameraObject>,
        reportedObjectCount: Int,
    ): String {
        val jpegCount = cameraObjects.count(CameraObject::isJpeg)
        val rawCount = cameraObjects.count(CameraObject::isRaw)
        val otherPhotoCount = cameraObjects.count { it.isPhoto && !it.isJpeg && !it.isRaw }
        val videoCount = cameraObjects.count(CameraObject::isVideo)
        val classifiedCount = jpegCount + rawCount + otherPhotoCount + videoCount
        val otherCount = (maxOf(reportedObjectCount, cameraObjects.size) - classifiedCount)
            .coerceAtLeast(0)
        return buildString {
            append(jpegCount + rawCount + otherPhotoCount)
            append(" photos found (")
            append(jpegCount)
            append(" JPEG")
            if (rawCount > 0) {
                append(", ")
                append(rawCount)
                append(" RAW")
            }
            if (otherPhotoCount > 0) {
                append(", ")
                append(otherPhotoCount)
                append(" other")
            }
            append(')')
            if (videoCount > 0) {
                append(" • ")
                append(videoCount)
                append(if (videoCount == 1) " video available" else " videos available")
            }
            if (otherCount > 0) {
                append(" • ")
                append(otherCount)
                append(if (otherCount == 1) " other object" else " other objects")
            }
        }
    }

    private fun formatPercent(bytes: Long, total: Long): String = if (total > 0L) {
        "${((bytes.toDouble() / total.toDouble()) * 100).roundToInt().coerceIn(0, 100)}%"
    } else {
        "partially"
    }

    private suspend fun runWork(startMessage: String, block: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true, message = startMessage) }
        try {
            block()
        } catch (error: Throwable) {
            if (transferCancellationRequested) {
                runCatching { client?.abort() }
                client = null
                runCatching { networkLease?.close() }
                networkLease = null
                mutableState.update {
                    it.copy(
                        connected = false,
                        transferProgress = null,
                        message = "Download cancelled; reconnecting…",
                    )
                }
            } else {
                runCatching { client?.close() }
                mutableState.update {
                    it.copy(
                        connected = false,
                        transferProgress = null,
                        message = friendlyMessage(error),
                    )
                }
            }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    private fun friendlyMessage(error: Throwable): String {
        val detail = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .firstOrNull()
            .orEmpty()
        return if (detail.isBlank()) {
            "Camera connection failed. Check the Nikon Wi-Fi connection and try again."
        } else {
            detail
        }
    }

    private fun String.isValidIpv4Address(): Boolean {
        val parts = split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                part.all(Char::isDigit) &&
                part.toIntOrNull() in 0..255
        }
    }

    override fun onCleared() {
        client?.close()
        networkLease?.close()
    }

    companion object {
        private const val CONNECTION_ATTEMPTS = 3
        private const val CONNECTION_RETRY_DELAY_MS = 1_000L
        private const val THUMBNAIL_ATTEMPTS = 2
        private const val THUMBNAIL_RETRY_DELAY_MS = 300L
        private const val MAX_PREVIEW_FALLBACK_BYTES = 100L * 1024L * 1024L
        private const val PREVIEW_MAX_DIMENSION = 640
        private const val PREVIEW_JPEG_QUALITY = 82
        private const val AUTO_CONNECT_POLL_MS = 250L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val BYTES_PER_MB = 1024L * 1024L
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 800L
        private const val MAX_TRANSFER_WAKE_LOCK_MS = 6L * 60L * 60L * 1_000L
    }
}
