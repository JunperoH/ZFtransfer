package io.github.noisetta.nikonpull

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.noisetta.nikonpull.ptp.CameraObject
import io.github.noisetta.nikonpull.ui.MainViewModel
import io.github.noisetta.nikonpull.ui.DownloadResultSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                NikonPullScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NikonPullScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.startAutoConnect()
    }
    val view = LocalView.current
    DisposableEffect(view, state.downloadActive) {
        view.keepScreenOn = state.downloadActive
        onDispose { view.keepScreenOn = false }
    }
    var selectedFormat by rememberSaveable { mutableStateOf(PhotoFormatFilter.ALL_PHOTOS) }
    val photosForFormat = remember(state.photos, selectedFormat) {
        state.photos.filter(selectedFormat::matches)
    }
    val availableDates = remember(photosForFormat) {
        photosForFormat
            .groupingBy { photo -> photo.captureDate.takeValidDateKey() }
            .eachCount()
            .entries
            .map { entry -> entry.key to entry.value }
    }
    var selectedDateKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(availableDates) {
        if (selectedDateKey != null && availableDates.none { it.first == selectedDateKey }) {
            selectedDateKey = null
        }
    }
    val filteredPhotos = remember(photosForFormat, selectedDateKey) {
        selectedDateKey?.let { selected ->
            photosForFormat.filter { photo -> photo.captureDate.takeValidDateKey() == selected }
        } ?: photosForFormat
    }
    val formatCounts = remember(state.photos) {
        mapOf(
            PhotoFormatFilter.ALL_PHOTOS to state.photos.count(CameraObject::isPhoto),
            PhotoFormatFilter.JPEG to state.photos.count(CameraObject::isJpeg),
            PhotoFormatFilter.RAW to state.photos.count(CameraObject::isRaw),
            PhotoFormatFilter.VIDEO to state.photos.count(CameraObject::isVideo),
        )
    }
    val selectedFiles = remember(state.photos, state.selected) {
        state.photos.filter { it.handle in state.selected }
    }
    var showDownloadReview by rememberSaveable { mutableStateOf(false) }
    var showCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showCameraDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(selectedFiles.isEmpty(), state.downloadActive) {
        if (selectedFiles.isEmpty() || state.downloadActive) showDownloadReview = false
    }

    if (showDownloadReview) {
        DownloadReviewDialog(
            files = selectedFiles,
            confirmEnabled = state.connected && !state.busy,
            onDismiss = { showDownloadReview = false },
            onConfirm = {
                showDownloadReview = false
                viewModel.downloadSelected()
            },
        )
    }
    if (showCacheDialog) {
        CacheDialog(
            lastSyncedAtMillis = state.lastSyncedAtMillis,
            cacheBytes = state.thumbnailCacheBytes,
            cacheLimitBytes = state.thumbnailCacheLimitBytes,
            refreshEnabled = state.connected && !state.busy,
            onLimitSelected = viewModel::setThumbnailCacheLimit,
            onClearPreviews = viewModel::clearThumbnailCache,
            onRefresh = {
                showCacheDialog = false
                viewModel.refreshLibrary()
            },
            onDismiss = { showCacheDialog = false },
        )
    }
    if (showCameraDialog) {
        CameraCompatibilityDialog(
            cameraName = state.cameraName,
            firmware = state.cameraInfo?.deviceVersion.orEmpty(),
            status = state.compatibilityStatus,
            connectedEndpoint = state.connectedEndpoint,
            supportsThumbnail = state.cameraInfo?.supportsThumbnail,
            initialHost = state.manualCameraHost,
            initialPort = state.cameraPort,
            onSaveEndpoint = viewModel::saveCameraEndpoint,
            onShareReport = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Nikon Pull compatibility report")
                    putExtra(Intent.EXTRA_TEXT, viewModel.compatibilityReport())
                }
                context.startActivity(Intent.createChooser(share, "Share compatibility report"))
            },
            onDismiss = { showCameraDialog = false },
        )
    }
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
    state.downloadSummary?.let { summary ->
        DownloadSummaryDialog(
            summary = summary,
            onDismiss = viewModel::dismissDownloadSummary,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Nikon Pull", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (state.connected) {
                                "${state.cameraName} connected"
                            } else {
                                "Nikon WMU camera transfer"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showCameraDialog = true }) {
                        Text("Camera")
                    }
                    TextButton(onClick = { showCacheDialog = true }) {
                        Text("Cache")
                    }
                    TextButton(onClick = { showAboutDialog = true }) {
                        Text("About")
                    }
                },
            )
        },
        bottomBar = {
            if (state.connected || state.downloadQueueRemaining > 0) {
                BottomAppBar {
                    TextButton(
                        onClick = { viewModel.select(filteredPhotos.map(CameraObject::handle)) },
                        enabled = state.connected && !state.busy && state.downloadQueueRemaining == 0,
                    ) {
                        Text("Select all")
                    }
                    TextButton(
                        onClick = viewModel::clearSelection,
                        enabled = state.connected && !state.busy && state.downloadQueueRemaining == 0,
                    ) {
                        Text("Clear")
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.downloadActive || state.downloadQueueRemaining > 0) {
                        Button(onClick = viewModel::cancelDownload) {
                            Text("Cancel queue")
                        }
                    } else {
                        Button(
                            onClick = { showDownloadReview = true },
                            enabled = state.selected.isNotEmpty() && !state.busy,
                        ) {
                            Text("Review (${state.selected.size})")
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .padding(scaffoldPadding)
                .fillMaxSize(),
        ) {
            ConnectionStatus(
                message = state.message,
                connected = state.connected,
                busy = state.busy,
                progress = state.transferProgress,
                fileProgress = state.currentFileProgress,
                queueRemaining = state.downloadQueueRemaining,
                queueTotal = state.downloadQueueTotal,
                onConnect = viewModel::connect,
            )

            if (state.photos.isEmpty()) {
                EmptyGallery(busy = state.busy)
            } else {
                DateFilterBar(
                    availableDates = availableDates,
                    selectedDateKey = selectedDateKey,
                    onDateSelected = { selectedDateKey = it },
                )
                FormatFilterBar(
                    counts = formatCounts,
                    selectedFormat = selectedFormat,
                    onFormatSelected = { selectedFormat = it },
                )
                PhotoGrid(
                    photos = filteredPhotos,
                    thumbnails = state.thumbnails,
                    selected = state.selected,
                    enabled = !state.busy && !state.downloadActive && state.downloadQueueRemaining == 0,
                    onToggle = viewModel::toggle,
                    onThumbnailNeeded = viewModel::requestThumbnail,
                    resetKey = "${selectedDateKey.orEmpty()}|${selectedFormat.name}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Nikon Pull") },
        text = {
            Column {
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nikon Pull is an unofficial open-source app and is not affiliated with or endorsed by Nikon Corporation.",
                )
                Spacer(Modifier.height(12.dp))
                Text("Privacy", style = MaterialTheme.typography.titleSmall)
                Text(
                    "No ads, analytics, accounts, telemetry, or developer-operated online service. Camera communication and cached metadata remain on this device. Compatibility reports are shared only when you choose a recipient.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("Licensed under GPL-3.0-or-later.")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun CameraCompatibilityDialog(
    cameraName: String,
    firmware: String,
    status: String,
    connectedEndpoint: String,
    supportsThumbnail: Boolean?,
    initialHost: String,
    initialPort: Int,
    onSaveEndpoint: (String, String) -> Unit,
    onShareReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var host by rememberSaveable(initialHost) { mutableStateOf(initialHost) }
    var port by rememberSaveable(initialPort) { mutableStateOf(initialPort.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera compatibility") },
        text = {
            Column {
                Text(cameraName, style = MaterialTheme.typography.titleMedium)
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (firmware.isNotBlank()) Text("Firmware: $firmware")
                if (connectedEndpoint.isNotBlank()) Text("Connected at $connectedEndpoint")
                if (supportsThumbnail != null) {
                    Text("Camera thumbnails: ${if (supportsThumbnail) "supported" else "not reported"}")
                }
                Spacer(Modifier.height(16.dp))
                Text("Connection endpoint", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Leave the address blank to use the Wi-Fi gateway automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Camera IPv4 address (optional)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("PTP/IP port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                TextButton(onClick = onShareReport) {
                    Text("Share privacy-safe compatibility report")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveEndpoint(host, port)
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun CacheDialog(
    lastSyncedAtMillis: Long,
    cacheBytes: Long,
    cacheLimitBytes: Long,
    refreshEnabled: Boolean,
    onLimitSelected: (Int) -> Unit,
    onClearPreviews: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var limitExpanded by remember { mutableStateOf(false) }
    val currentLimitMb = (cacheLimitBytes / BYTES_PER_MB).toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline gallery cache") },
        text = {
            Column {
                Text("Last synced: ${lastSyncedAtMillis.toSyncTime()}")
                Spacer(Modifier.height(12.dp))
                Text("Preview cache: ${cacheBytes.toReadableSize()} of ${cacheLimitBytes.toReadableSize()}")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cache limit")
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { limitExpanded = true }) {
                            Text("$currentLimitMb MB")
                        }
                        DropdownMenu(
                            expanded = limitExpanded,
                            onDismissRequest = { limitExpanded = false },
                        ) {
                            CACHE_LIMIT_OPTIONS_MB.forEach { megabytes ->
                                DropdownMenuItem(
                                    text = { Text("$megabytes MB") },
                                    onClick = {
                                        onLimitSelected(megabytes)
                                        limitExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onClearPreviews) {
                    Text("Clear preview cache")
                }
                Text(
                    "The catalog and previews are available without the camera. Originals are never cached.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onRefresh, enabled = refreshEnabled) { Text("Refresh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DownloadReviewDialog(
    files: List<CameraObject>,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val jpegCount = files.count(CameraObject::isJpeg)
    val rawCount = files.count(CameraObject::isRaw)
    val videoCount = files.count(CameraObject::isVideo)
    val totalSize = files.sumOf(CameraObject::size)
    val details = buildString {
        append(files.size)
        append(if (files.size == 1) " original file" else " original files")
        append(" • ")
        append(totalSize.toReadableSize())
        append("\n\n")
        if (jpegCount > 0) append("JPEG: $jpegCount\n")
        if (rawCount > 0) append("RAW: $rawCount\n")
        val otherPhotoCount = files.count { it.isPhoto && !it.isJpeg && !it.isRaw }
        if (otherPhotoCount > 0) append("Other photos: $otherPhotoCount\n")
        if (videoCount > 0) append("Video: $videoCount\n")
        append("\nPhotos → Pictures/Nikon Pull")
        if (videoCount > 0) append("\nVideos → Movies/Nikon Pull")
        if (videoCount > 0 || totalSize >= LARGE_DOWNLOAD_WARNING_BYTES) {
            append("\n\nLarge files may take several minutes over camera Wi-Fi. Keep the camera and phone awake.")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review download") },
        text = { Text(details) },
        confirmButton = {
            Button(onClick = onConfirm, enabled = confirmEnabled) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Back") }
        },
    )
}

@Composable
private fun DownloadSummaryDialog(
    summary: DownloadResultSummary,
    onDismiss: () -> Unit,
) {
    val text = buildString {
        append("Processed ${summary.totalCount} files.\n\n")
        append("Saved and verified: ${summary.downloadedCount}\n")
        append("Already downloaded: ${summary.skippedCount}\n")
        append("Failed: ${summary.failedFilenames.size}")
        if (summary.failedFilenames.isNotEmpty()) {
            append("\n\nFailed files:\n")
            append(summary.failedFilenames.take(8).joinToString("\n"))
            if (summary.failedFilenames.size > 8) {
                append("\n…and ${summary.failedFilenames.size - 8} more")
            }
            append("\n\nSelect failed files again to retry them.")
        }
        append("\n\nPhotos → Pictures/Nikon Pull")
        append("\nVideos → Movies/Nikon Pull")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download summary") },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun FormatFilterBar(
    counts: Map<PhotoFormatFilter, Int>,
    selectedFormat: PhotoFormatFilter,
    onFormatSelected: (PhotoFormatFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Format", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "${selectedFormat.label} (${counts[selectedFormat] ?: 0})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    PhotoFormatFilter.entries.forEach { format ->
                        DropdownMenuItem(
                            text = { Text("${format.label} (${counts[format] ?: 0})") },
                            onClick = {
                                onFormatSelected(format)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateFilterBar(
    availableDates: List<Pair<String, Int>>,
    selectedDateKey: String?,
    onDateSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Date", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = selectedDateKey?.toCompactDisplayDate() ?: "All dates",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All dates") },
                        onClick = {
                            onDateSelected(null)
                            expanded = false
                        },
                    )
                    availableDates.forEach { (dateKey, count) ->
                        DropdownMenuItem(
                            text = { Text("${dateKey.toCompactDisplayDate()} ($count)") },
                            onClick = {
                                onDateSelected(dateKey)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatus(
    message: String,
    connected: Boolean,
    busy: Boolean,
    progress: Float?,
    fileProgress: Float?,
    queueRemaining: Int,
    queueTotal: Int,
    onConnect: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(12.dp))
                when {
                    busy && progress == null -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )

                    !connected -> Button(onClick = onConnect) { Text("Connect") }
                }
            }
            if (progress != null) {
                if (queueTotal > 0) {
                    Text(
                        text = "Overall • ${queueTotal - queueRemaining} of $queueTotal processed",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (fileProgress != null) {
                Text(
                    text = "Current file • ${(fileProgress * 100).toInt().coerceIn(0, 100)}%",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    progress = { fileProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyGallery(busy: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!busy) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera photos will appear here", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Join the camera's Wi-Fi network and stay connected when Android says there is no internet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<CameraObject>,
    thumbnails: Map<Long, ByteArray>,
    selected: Set<Long>,
    enabled: Boolean,
    onToggle: (Long) -> Unit,
    onThumbnailNeeded: (CameraObject) -> Unit,
    resetKey: String,
    modifier: Modifier = Modifier,
) {
    val photosByDate = remember(photos) {
        photos.groupBy { photo -> photo.captureDate.takeValidDateKey() }
    }
    val gridState = rememberLazyGridState()
    LaunchedEffect(resetKey) {
        gridState.scrollToItem(0)
    }
    LazyVerticalGrid(
        modifier = modifier,
        state = gridState,
        columns = GridCells.Adaptive(132.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        photosByDate.forEach { (dateKey, datedPhotos) ->
            item(
                key = "date:$dateKey",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Text(
                    text = dateKey.toDisplayDate(),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(datedPhotos, key = CameraObject::handle) { photo ->
                val thumbnail = thumbnails[photo.handle]
                LaunchedEffect(photo.handle, thumbnail, enabled) {
                    if (thumbnail != null || !enabled) return@LaunchedEffect
                    delay(THUMBNAIL_VISIBILITY_DEBOUNCE_MS)
                    onThumbnailNeeded(photo)
                }
                val isSelected = photo.handle in selected
                Card(
                    modifier = Modifier
                        .aspectRatio(0.86f)
                        .clickable(enabled = enabled) { onToggle(photo.handle) },
                    shape = RoundedCornerShape(12.dp),
                    border = if (isSelected) {
                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        null
                    },
                ) {
                    Column {
                        Thumbnail(
                            bytes = thumbnail,
                            selected = isSelected,
                            isVideo = photo.isVideo,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                        Text(
                            text = photo.filename,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

private enum class PhotoFormatFilter(val label: String) {
    ALL_PHOTOS("All photos"),
    JPEG("JPEG"),
    RAW("RAW"),
    VIDEO("Video"),
    ;

    fun matches(photo: CameraObject): Boolean = when (this) {
        ALL_PHOTOS -> photo.isPhoto
        JPEG -> photo.isJpeg
        RAW -> photo.isRaw
        VIDEO -> photo.isVideo
    }
}

private fun String.takeValidDateKey(): String = take(8)
    .takeIf { value -> value.length == 8 && value.all(Char::isDigit) }
    .orEmpty()

private fun String.toDisplayDate(): String {
    if (isEmpty()) return "Date unknown"
    return runCatching {
        LocalDate.parse(this, DateTimeFormatter.BASIC_ISO_DATE)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()))
    }.getOrDefault("Date unknown")
}

private fun String.toCompactDisplayDate(): String {
    if (isEmpty()) return "Date unknown"
    return runCatching {
        LocalDate.parse(this, DateTimeFormatter.BASIC_ISO_DATE)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
    }.getOrDefault("Date unknown")
}

private fun Long.toReadableSize(): String {
    if (this < 1024L) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}

private fun Long.toSyncTime(): String {
    if (this <= 0L) return "Not synced yet"
    return DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))
}

private const val THUMBNAIL_VISIBILITY_DEBOUNCE_MS = 250L
private const val LARGE_DOWNLOAD_WARNING_BYTES = 500L * 1024L * 1024L
private const val BYTES_PER_MB = 1024L * 1024L
private val CACHE_LIMIT_OPTIONS_MB = listOf(100, 250, 500)

@Composable
private fun Thumbnail(
    bytes: ByteArray?,
    selected: Boolean,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            if (isVideo) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", style = MaterialTheme.typography.headlineMedium)
                    Text("Video", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Preview", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (isVideo) {
                Text(
                    text = "▶",
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
