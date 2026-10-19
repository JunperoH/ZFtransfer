package io.github.noisetta.nikonpull

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import io.github.noisetta.nikonpull.ptp.CameraObject
import io.github.noisetta.nikonpull.bluetooth.NikonBleState
import io.github.noisetta.nikonpull.data.CameraConnectionMode
import io.github.noisetta.nikonpull.data.CameraEndpointSettings
import io.github.noisetta.nikonpull.diagnostics.DiagnosticLog
import io.github.noisetta.nikonpull.ui.MainViewModel
import io.github.noisetta.nikonpull.ui.DownloadResultSummary
import io.github.noisetta.nikonpull.ui.ZfTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            ZfTheme {
                NikonPullScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NikonPullScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bleState by viewModel.bleState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.startHeartbeat()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                Lifecycle.Event.ON_START -> viewModel.onAppForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    DisposableEffect(view, state.downloadActive) {
        view.keepScreenOn = state.downloadActive
        onDispose { view.keepScreenOn = false }
    }
    var selectedFormat by rememberSaveable { mutableStateOf(PhotoFormatFilter.ALL_PHOTOS) }
    var selectedStatus by rememberSaveable { mutableStateOf(PhotoStatusFilter.ALL) }
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
    val datedPhotos = remember(photosForFormat, selectedDateKey) {
        selectedDateKey?.let { selected ->
            photosForFormat.filter { photo -> photo.captureDate.takeValidDateKey() == selected }
        } ?: photosForFormat
    }
    val filteredPhotos = remember(datedPhotos, selectedStatus, state.downloadedHandles, state.newHandles) {
        datedPhotos.filter { photo -> selectedStatus.matches(photo.handle, state.downloadedHandles, state.newHandles) }
    }
    val statusCounts = remember(datedPhotos, state.downloadedHandles, state.newHandles) {
        PhotoStatusFilter.entries.associateWith { filter ->
            datedPhotos.count { photo -> filter.matches(photo.handle, state.downloadedHandles, state.newHandles) }
        }
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
    val selectedOrdinals = remember(selectedFiles) {
        selectedFiles.mapIndexed { index, photo -> photo.handle to index + 1 }.toMap()
    }
    var showDownloadReview by rememberSaveable { mutableStateOf(false) }
    var showCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showCameraDialog by rememberSaveable { mutableStateOf(false) }
    var showBluetoothDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            val flags = result.data?.flags ?: 0
            val success = runCatching {
                val canRead = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                val canWrite = flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                when {
                    canRead && canWrite -> context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    canRead -> context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    canWrite -> context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    else -> error("Folder picker did not grant persistent access")
                }
                viewModel.chooseDownloadFolder(uri)
            }.getOrDefault(false)
            Toast.makeText(context, if (success) "保存位置：${viewModel.state.value.downloadLocationLabel}" else "无法使用此文件夹，或下载队列仍在进行", Toast.LENGTH_LONG).show()
        }
    }
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) viewModel.scanBluetooth()
        else Toast.makeText(context, "需要蓝牙权限才能扫描 Zf", Toast.LENGTH_SHORT).show()
    }
    val scanBluetooth = {
        if (bluetoothPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.scanBluetooth()
        } else bluetoothPermissionLauncher.launch(bluetoothPermissions)
    }
    LaunchedEffect(selectedFiles.isEmpty(), state.downloadActive) {
        if (selectedFiles.isEmpty() || state.downloadActive) showDownloadReview = false
    }

    if (showDownloadReview) {
        DownloadReviewDialog(
            files = selectedFiles,
            destinationLabel = state.downloadLocationLabel,
            confirmEnabled = state.connected && !state.busy,
            onDismiss = { showDownloadReview = false },
            onConfirm = {
                showDownloadReview = false
                selectionMode = false
                viewModel.downloadSelected()
            },
        )
    }
    if (showCacheDialog) {
        CacheDialog(
            lastSyncedAtMillis = state.lastSyncedAtMillis,
            cacheBytes = state.thumbnailCacheBytes,
            cacheLimitBytes = state.thumbnailCacheLimitBytes,
            newCount = state.newHandles.count { it !in state.downloadedHandles },
            refreshEnabled = state.connected && !state.busy,
            onLimitSelected = viewModel::setThumbnailCacheLimit,
            onClearPreviews = viewModel::clearThumbnailCache,
            onMarkNewSeen = viewModel::markNewAsSeen,
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
            supportsPartialObject = state.cameraInfo?.supportsPartialObject,
            initialHost = state.manualCameraHost,
            initialMode = state.connectionMode,
            initialPort = state.cameraPort,
            initialStaFirstRegistration = state.staFirstRegistration,
            downloadLocationLabel = state.downloadLocationLabel,
            canChangeDownloadLocation = !state.downloadActive && state.downloadQueueRemaining == 0,
            onChooseDownloadFolder = {
                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary"))
                })
            },
            onResetDownloadFolder = viewModel::resetDownloadFolder,
            onSelectMode = viewModel::savedCameraEndpoint,
            onSaveEndpoint = viewModel::saveCameraEndpoint,
            onShareReport = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Zf AP Transfer compatibility report")
                    putExtra(Intent.EXTRA_TEXT, viewModel.compatibilityReport())
                }
                context.startActivity(Intent.createChooser(share, "Share compatibility report"))
            },
            onDismiss = { showCameraDialog = false },
        )
    }
    if (showBluetoothDialog) {
        BluetoothDialog(
            state = bleState,
            onScan = scanBluetooth,
            onConnect = viewModel::connectBluetooth,
            onWake = viewModel::wakeCameraWifi,
            onDismiss = {
                viewModel.stopBluetoothScan()
                showBluetoothDialog = false
            },
        )
    }
    if (showAboutDialog) {
        AboutDialog(
            onExportLog = {
                DiagnosticLog.info("EXPORT", "user requested diagnostic log export")
                val file = DiagnosticLog.createExport()
                if (file == null) {
                    Toast.makeText(context, "诊断日志导出失败", Toast.LENGTH_SHORT).show()
                } else {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "导出诊断日志"))
                    }.onFailure { error ->
                        DiagnosticLog.error("EXPORT", "share dialog failed", error)
                        Toast.makeText(context, "无法打开分享界面", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showAboutDialog = false },
        )
    }
    state.downloadSummary?.let { summary ->
        DownloadSummaryDialog(
            summary = summary,
            onDismiss = viewModel::dismissDownloadSummary,
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Zf 传图", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                        Text(
                            "从相机导入，留住精彩",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showCameraDialog = true }) {
                        Text("⚙", fontSize = 25.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Text("⋮", fontSize = 27.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(text = { Text("缓存与刷新") }, onClick = {
                                showMoreMenu = false
                                showCacheDialog = true
                            })
                            DropdownMenuItem(text = { Text("蓝牙实验") }, onClick = {
                                showMoreMenu = false
                                showBluetoothDialog = true
                            })
                            DropdownMenuItem(text = { Text("关于与诊断日志") }, onClick = {
                                showMoreMenu = false
                                showAboutDialog = true
                            })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            if (state.photos.isNotEmpty() || state.downloadQueueRemaining > 0) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (selectionMode) "上下滑动浏览 · 横滑或长按拖选" else "长按照片开始多选",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            if (selectionMode) {
                                TextButton(
                                    onClick = {
                                        selectionMode = false
                                        viewModel.clearSelection()
                                    },
                                    enabled = !state.busy && state.downloadQueueRemaining == 0,
                                ) { Text("退出多选") }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.selected.isEmpty()) "未选择照片" else "已选 ${state.selected.size} 项",
                            modifier = Modifier.weight(1f),
                            style = if (state.selected.isEmpty()) MaterialTheme.typography.labelMedium
                            else MaterialTheme.typography.titleSmall,
                            color = if (state.selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        TextButton(
                            onClick = {
                                if (state.selected.isEmpty()) {
                                    selectionMode = true
                                    viewModel.select(filteredPhotos.map(CameraObject::handle))
                                }
                                else viewModel.clearSelection()
                            },
                            enabled = !state.busy && state.downloadQueueRemaining == 0,
                        ) { Text(if (state.selected.isEmpty()) "全选" else "清空") }
                        if (state.downloadActive || state.downloadQueueRemaining > 0) {
                            Button(onClick = viewModel::cancelDownload) { Text("取消队列") }
                        } else {
                            Button(
                                onClick = { showDownloadReview = true },
                                enabled = state.connected && state.selected.isNotEmpty() && !state.busy,
                                shape = RoundedCornerShape(22.dp),
                            ) { Text("↓  下载原图") }
                        }
                        }
                    }
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
                cameraName = state.cameraName,
                connectionMode = state.connectionMode,
                onConnect = viewModel::connect,
                onCameraSettings = { showCameraDialog = true },
            )

            if (state.photos.isEmpty()) {
                EmptyGallery(busy = state.busy, mode = state.connectionMode)
            } else {
                GalleryFilters(
                    availableDates = availableDates,
                    selectedDateKey = selectedDateKey,
                    onDateSelected = { selectedDateKey = it },
                    counts = formatCounts,
                    selectedFormat = selectedFormat,
                    onFormatSelected = { selectedFormat = it },
                    statusCounts = statusCounts,
                    selectedStatus = selectedStatus,
                    onStatusSelected = { selectedStatus = it },
                    visibleCount = filteredPhotos.size,
                )
                PhotoGrid(
                    photos = filteredPhotos,
                    thumbnails = state.thumbnails,
                    selected = state.selected,
                    selectedOrdinals = selectedOrdinals,
                    newHandles = state.newHandles,
                    downloadedHandles = state.downloadedHandles,
                    enabled = !state.busy && !state.downloadActive && state.downloadQueueRemaining == 0,
                    canFetchThumbnailFromCamera = state.connected && !state.busy && !state.downloadActive &&
                        state.downloadQueueRemaining == 0,
                    thumbnailCacheRevision = state.thumbnailCacheRevision,
                    selectionMode = selectionMode,
                    onEnterSelectionMode = { selectionMode = true },
                    onToggle = { handle ->
                        selectionMode = true
                        viewModel.toggle(handle)
                    },
                    onSelect = viewModel::select,
                    onThumbnailNeeded = viewModel::requestThumbnail,
                    resetKey = "${selectedDateKey.orEmpty()}|${selectedFormat.name}|${selectedStatus.name}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BluetoothDialog(
    state: NikonBleState,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onWake: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("蓝牙配对与唤起 Wi-Fi") },
        text = {
            Column {
                Text("在 Zf 上选择“连接到智能设备 → 配对（蓝牙）→ 开始配对”，然后扫描。首次配对需在相机和手机确认配对码。")
                Spacer(Modifier.height(10.dp))
                Text(state.status, color = MaterialTheme.colorScheme.primary)
                Text("认证：${if (state.authenticated) "完成" else "未完成"} · 系统配对：${if (state.bonded) "完成" else "未完成"}")
                Spacer(Modifier.height(8.dp))
                state.devices.take(8).forEach { camera ->
                    TextButton(
                        onClick = { onConnect(camera.address) },
                        enabled = !state.busy,
                    ) { Text("${camera.name}  (${camera.rssi} dBm)") }
                }
                TextButton(onClick = onScan, enabled = !state.busy) {
                    Text(if (state.scanning) "重新扫描" else "扫描 Nikon 相机")
                }
                if (state.authenticated) {
                    TextButton(onClick = onWake, enabled = !state.busy || state.bonded) {
                        Text("再次请求开启相机 Wi-Fi")
                    }
                }
                Text(
                    "蓝牙唤起指令尚未通过 Zf 真机验证。可在“关于”导出阶段日志；AP 手动连接仍可独立使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun AboutDialog(onExportLog: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 Zf 传图") },
        text = {
            Column {
                Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Spacer(Modifier.height(12.dp))
                Text(
                    "基于 GPL-3.0-or-later 开源项目 Nikon Pull 修改。Zf 的 AP 与 STA 原图传输均已实测；蓝牙仍未配对成功。本应用与尼康公司无关。",
                )
                Spacer(Modifier.height(12.dp))
                Text("隐私", style = MaterialTheme.typography.titleSmall)
                Text(
                    "无广告、账号和遥测。相机通信及缓存信息保存在本机；只有主动分享时才会导出兼容性报告或诊断日志。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("采用 GPL-3.0-or-later 许可证。")
                Spacer(Modifier.height(12.dp))
                Text("诊断日志保存在本机，最多约 3 MB。记录网络地址、相机型号、PTP 操作、文件大小及错误；错误可能含文件名。不记录 Wi-Fi 密码和照片内容。")
                TextButton(onClick = onExportLog) { Text("导出诊断日志") }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
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
    supportsPartialObject: Boolean?,
    initialHost: String,
    initialMode: CameraConnectionMode,
    initialPort: Int,
    initialStaFirstRegistration: Boolean,
    downloadLocationLabel: String,
    canChangeDownloadLocation: Boolean,
    onChooseDownloadFolder: () -> Unit,
    onResetDownloadFolder: () -> Boolean,
    onSelectMode: (CameraConnectionMode) -> CameraEndpointSettings,
    onSaveEndpoint: (String, String, CameraConnectionMode, Boolean) -> Unit,
    onShareReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var host by rememberSaveable(initialHost) { mutableStateOf(initialHost) }
    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var port by rememberSaveable(initialPort) { mutableStateOf(initialPort.toString()) }
    var staFirstRegistration by remember(initialStaFirstRegistration) { mutableStateOf(initialStaFirstRegistration) }
    fun selectMode(nextMode: CameraConnectionMode) {
        if (nextMode == mode) return
        val saved = onSelectMode(nextMode)
        mode = nextMode
        host = saved.host
        port = saved.port.toString()
        staFirstRegistration = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("相机连接") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(cameraName, style = MaterialTheme.typography.titleMedium)
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (firmware.isNotBlank()) Text("固件：$firmware")
                if (connectedEndpoint.isNotBlank()) Text("连接地址：$connectedEndpoint")
                if (supportsThumbnail != null) {
                    Text("相机缩略图：${if (supportsThumbnail) "支持" else "未报告"}")
                }
                if (supportsPartialObject != null) {
                    Text("分段传输：${if (supportsPartialObject) "支持" else "整文件传输"}")
                }
                Spacer(Modifier.height(16.dp))
                Text("Wi-Fi 连接模式", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = { selectMode(CameraConnectionMode.AP) }) {
                        Text(if (mode == CameraConnectionMode.AP) "● AP 直连" else "○ AP 直连")
                    }
                    TextButton(onClick = { selectMode(CameraConnectionMode.STA) }) {
                        Text(if (mode == CameraConnectionMode.STA) "● STA 局域网" else "○ STA 局域网")
                    }
                }
                Text("相机地址", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (mode == CameraConnectionMode.AP) {
                        "手机连接 Zf 热点；地址留空将使用相机网关。"
                    } else {
                        "Zf 和手机连接同一 Wi-Fi；地址留空将搜索 PTP/IP 服务。搜索不到时填 Zf 的 IPv4 地址。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("相机 IPv4 地址（可留空）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("PTP/IP 端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (mode == CameraConnectionMode.STA) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = staFirstRegistration,
                            onCheckedChange = { staFirstRegistration = it },
                        )
                        Text("首次登记新的 STA 配置")
                    }
                    Text(
                        "若自动搜索不到相机，填入 IPv4 地址并勾选此项；保存后点连接，再到 Zf 上按 J 确认。已登记的配置无需勾选。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("原图保存位置", style = MaterialTheme.typography.titleSmall)
                Text(downloadLocationLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "照片和视频都会保存到所选文件夹；未指定时分别保存到系统相册的 Pictures / Movies。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    OutlinedButton(onClick = onChooseDownloadFolder, enabled = canChangeDownloadLocation) {
                        Text("选择文件夹")
                    }
                    TextButton(
                        onClick = { onResetDownloadFolder() },
                        enabled = canChangeDownloadLocation && downloadLocationLabel != "系统相册 · Zf AP Transfer",
                    ) { Text("恢复默认") }
                }
                if (!canChangeDownloadLocation) {
                    Text("下载队列完成或取消后可修改保存位置", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onShareReport) {
                    Text("分享兼容性报告")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveEndpoint(host, port, mode, staFirstRegistration)
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun CacheDialog(
    lastSyncedAtMillis: Long,
    cacheBytes: Long,
    cacheLimitBytes: Long,
    newCount: Int,
    refreshEnabled: Boolean,
    onLimitSelected: (Int) -> Unit,
    onClearPreviews: () -> Unit,
    onMarkNewSeen: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var limitExpanded by remember { mutableStateOf(false) }
    val currentLimitMb = (cacheLimitBytes / BYTES_PER_MB).toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("离线相册缓存") },
        text = {
            Column {
                Text("上次同步：${lastSyncedAtMillis.toSyncTime()}")
                Spacer(Modifier.height(12.dp))
                Text("预览缓存：${cacheBytes.toReadableSize()} / ${cacheLimitBytes.toReadableSize()}")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("缓存上限")
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
                    Text("清除预览缓存")
                }
                if (newCount > 0) {
                    TextButton(onClick = onMarkNewSeen) {
                        Text("将 $newCount 张新增照片标为已看")
                    }
                }
                Text(
                    "断开相机后仍可浏览已缓存的目录和预览；清除预览后需连接相机重新加载。原图不会缓存在应用内。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onRefresh, enabled = refreshEnabled) { Text("刷新相机目录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun DownloadReviewDialog(
    files: List<CameraObject>,
    destinationLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val jpegCount = files.count(CameraObject::isJpeg)
    val rawCount = files.count(CameraObject::isRaw)
    val videoCount = files.count(CameraObject::isVideo)
    val totalSize = files.sumOf(CameraObject::size)
    val details = buildString {
        append("${files.size} 个原始文件 · ${totalSize.toReadableSize()}\n\n")
        if (jpegCount > 0) append("JPEG: $jpegCount\n")
        if (rawCount > 0) append("RAW: $rawCount\n")
        val otherPhotoCount = files.count { it.isPhoto && !it.isJpeg && !it.isRaw }
        if (otherPhotoCount > 0) append("其他照片: $otherPhotoCount\n")
        if (videoCount > 0) append("视频: $videoCount\n")
        append("\n保存位置：$destinationLabel。保留原始文件。")
        if (videoCount > 0 || totalSize >= LARGE_DOWNLOAD_WARNING_BYTES) {
            append("\n\n大文件传输可能需要数分钟，请保持相机和手机开启。")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认下载") },
        text = { Text(details) },
        confirmButton = {
            Button(onClick = onConfirm, enabled = confirmEnabled) {
                Text("开始下载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("返回") }
        },
    )
}

@Composable
private fun DownloadSummaryDialog(
    summary: DownloadResultSummary,
    onDismiss: () -> Unit,
) {
    val text = buildString {
        append("共处理 ${summary.totalCount} 个文件。\n\n")
        append("已保存并校验：${summary.downloadedCount}\n")
        append("已存在，跳过：${summary.skippedCount}\n")
        append("失败：${summary.failedFilenames.size}")
        if (summary.failedFilenames.isNotEmpty()) {
            append("\n\n失败文件：\n")
            val details = summary.failedDetails.ifEmpty { summary.failedFilenames }
            append(details.take(8).joinToString("\n"))
            if (summary.failedFilenames.size > 8) {
                append("\n…还有 ${summary.failedFilenames.size - 8} 个")
            }
            append("\n\n可重新选择失败文件再次下载。")
        }
        append("\n\n已保存的照片可在系统相册查看。")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载结果") },
        text = { Text(text) },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成") }
        },
    )
}

@Composable
private fun GalleryFilters(
    availableDates: List<Pair<String, Int>>,
    selectedDateKey: String?,
    onDateSelected: (String?) -> Unit,
    counts: Map<PhotoFormatFilter, Int>,
    selectedFormat: PhotoFormatFilter,
    onFormatSelected: (PhotoFormatFilter) -> Unit,
    statusCounts: Map<PhotoStatusFilter, Int>,
    selectedStatus: PhotoStatusFilter,
    onStatusSelected: (PhotoStatusFilter) -> Unit,
    visibleCount: Int,
) {
    var dateMenuExpanded by remember { mutableStateOf(false) }
    val dateTitle = selectedDateKey?.toDisplayDate()
        ?: availableDates.singleOrNull()?.first?.toDisplayDate()
        ?: "全部日期"
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                TextButton(onClick = { dateMenuExpanded = true }) {
                    Text(dateTitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(5.dp))
                    Text("⌄", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = dateMenuExpanded, onDismissRequest = { dateMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("全部日期") }, onClick = {
                        onDateSelected(null)
                        dateMenuExpanded = false
                    })
                    availableDates.forEach { (dateKey, count) ->
                        DropdownMenuItem(
                            text = { Text("${dateKey.toCompactDisplayDate()} · $count") },
                            onClick = {
                                onDateSelected(dateKey)
                                dateMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("$visibleCount 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoFormatFilter.entries.forEach { format ->
                FilterChip(
                    selected = selectedFormat == format,
                    onClick = { onFormatSelected(format) },
                    label = { Text("${format.label} ${counts[format] ?: 0}") },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (selectedFormat == format) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoStatusFilter.entries.forEach { status ->
                FilterChip(
                    selected = selectedStatus == status,
                    onClick = { onStatusSelected(status) },
                    label = { Text("${status.label} ${statusCounts[status] ?: 0}") },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (selectedStatus == status) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
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
    cameraName: String,
    connectionMode: CameraConnectionMode,
    onConnect: () -> Unit,
    onCameraSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.camera_front),
                    contentDescription = null,
                    modifier = Modifier.width(82.dp).height(56.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.size(10.dp).background(
                        if (connected) Color(0xFF8CD95A) else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (connected) "已连接 ${cameraName.ifBlank { "Zf" }}"
                        else if (busy) "正在连接相机" else "相机未连接",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (connected && !busy) {
                            "${if (connectionMode == CameraConnectionMode.STA) "STA 局域网" else "AP 直连"} · 已可浏览照片"
                        } else message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                when {
                    !connected -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = onCameraSettings, enabled = !busy) {
                            Text("${if (connectionMode == CameraConnectionMode.STA) "STA" else "AP"} 模式 ▾")
                        }
                        if (busy && progress == null) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Button(onClick = onConnect, enabled = !busy) { Text("连接") }
                        }
                    }
                    else -> IconButton(onClick = onCameraSettings) {
                        Text("›", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (progress != null) {
                if (queueTotal > 0) {
                    Text(
                        text = "总进度 · 已处理 ${queueTotal - queueRemaining}/$queueTotal",
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
                    text = "当前文件 · ${(fileProgress * 100).toInt().coerceIn(0, 100)}%",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                LinearProgressIndicator(
                    progress = { fileProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun EmptyGallery(busy: Boolean, mode: CameraConnectionMode) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("相机照片会显示在这里", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (mode == CameraConnectionMode.AP) {
                        "请先连接相机的 Wi-Fi AP；若 Android 提示没有互联网，请保持连接。"
                    } else {
                        "让 Zf 和手机连接同一 Wi-Fi，在“相机”中设置 STA 后点连接。"
                    },
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
    selectedOrdinals: Map<Long, Int>,
    newHandles: Set<Long>,
    downloadedHandles: Set<Long>,
    enabled: Boolean,
    canFetchThumbnailFromCamera: Boolean,
    thumbnailCacheRevision: Int,
    selectionMode: Boolean,
    onEnterSelectionMode: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelect: (Collection<Long>) -> Unit,
    onThumbnailNeeded: (CameraObject, Boolean) -> Unit,
    resetKey: String,
    modifier: Modifier = Modifier,
) {
    val photosByDate = remember(photos) {
        photos.groupBy { photo -> photo.captureDate.takeValidDateKey() }
    }
    val gridState = rememberLazyGridState()
    val orderedHandles = remember(photos) { photos.map(CameraObject::handle) }
    val currentSelection by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnToggle by rememberUpdatedState(onToggle)
    val currentSelectionMode by rememberUpdatedState(selectionMode)
    val currentOnEnterSelectionMode by rememberUpdatedState(onEnterSelectionMode)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(resetKey) {
        gridState.scrollToItem(0)
    }
    LazyVerticalGrid(
        modifier = modifier.pointerInput(enabled, orderedHandles) {
            if (!enabled) return@pointerInput
            coroutineScope {
                var anchor: Long? = null
                var baseline = emptySet<Long>()
                var lastHandle: Long? = null
                var pointer = Offset.Zero
                var scrollJob: Job? = null
                val edge = 48.dp.toPx()
                val step = 6.dp.toPx()

                fun handleAt(position: Offset): Long? = gridState.layoutInfo.visibleItemsInfo
                    .firstOrNull { item ->
                        position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
                            position.y >= item.offset.y && position.y < item.offset.y + item.size.height
                    }
                    ?.key as? Long

                fun selectThrough(position: Offset) {
                    val start = anchor ?: return
                    val end = handleAt(position) ?: return
                    if (end == lastHandle) return
                    lastHandle = end
                    currentOnSelect(dragSelection(orderedHandles, baseline, start, end))
                }

                fun tapAt(position: Offset) {
                    handleAt(position)?.let(currentOnToggle)
                }

                fun updateEdgeScroll() {
                    val direction = when {
                        pointer.y < edge -> -1
                        pointer.y > size.height - edge -> 1
                        else -> 0
                    }
                    if (direction == 0) {
                        scrollJob?.cancel()
                        scrollJob = null
                    } else if (scrollJob?.isActive != true) {
                        scrollJob = launch {
                            delay(180)
                            while (isActive) {
                                val currentDirection = when {
                                    pointer.y < edge -> -1
                                    pointer.y > size.height - edge -> 1
                                    else -> 0
                                }
                                if (currentDirection == 0) break
                                gridState.scrollBy(step * currentDirection)
                                selectThrough(pointer)
                                delay(32)
                            }
                        }
                    }
                }

                val onStart: (Offset, Boolean) -> Unit = { position, longPress ->
                    anchor = handleAt(position)
                    if (anchor != null) {
                        baseline = currentSelection
                        lastHandle = null
                        pointer = position
                        if (longPress) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnEnterSelectionMode()
                        }
                        selectThrough(position)
                    }
                }
                val onDragMove: (androidx.compose.ui.input.pointer.PointerInputChange, Offset) -> Unit = { change, _ ->
                    if (anchor != null) {
                        change.consume()
                        pointer = change.position
                        selectThrough(pointer)
                        updateEdgeScroll()
                    }
                }
                val onEnd: () -> Unit = {
                    scrollJob?.cancel()
                    scrollJob = null
                    anchor = null
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val longPress = awaitLongPressOrCancellation(down.id)
                    val current = currentEvent.changes.firstOrNull { it.id == down.id }
                    val horizontalDrag = currentSelectionMode && current != null && current.pressed &&
                        !current.isConsumed && abs(current.position.x - down.position.x) > viewConfiguration.touchSlop &&
                        abs(current.position.x - down.position.x) >
                        abs(current.position.y - down.position.y) * 1.3f
                    if (longPress != null || horizontalDrag) {
                        (longPress ?: current)?.consume()
                        onStart(down.position, longPress != null)
                        if (horizontalDrag) onDragMove(current, Offset.Zero)
                        try {
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null && change.pressed) onDragMove(change, Offset.Zero)
                            } while (change?.pressed == true)
                        } finally { onEnd() }
                    } else if (current != null && !current.pressed && !current.isConsumed) {
                        tapAt(down.position)
                    }
                }
            }
        },
        state = gridState,
        userScrollEnabled = true,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        photosByDate.forEach { (dateKey, datedPhotos) ->
            if (photosByDate.size > 1) {
                item(
                    key = "date:$dateKey",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    Text(
                        text = dateKey.toDisplayDate(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(datedPhotos, key = CameraObject::handle) { photo ->
                val thumbnail = thumbnails[photo.handle]
                LaunchedEffect(photo.handle, thumbnail, canFetchThumbnailFromCamera, thumbnailCacheRevision) {
                    if (thumbnail != null) return@LaunchedEffect
                    delay(THUMBNAIL_VISIBILITY_DEBOUNCE_MS)
                    onThumbnailNeeded(photo, canFetchThumbnailFromCamera)
                }
                val isSelected = photo.handle in selected
                val isDownloaded = photo.handle in downloadedHandles
                val isNew = photo.handle in newHandles && !isDownloaded
                val statusLabel = when {
                    isDownloaded -> "已下载"
                    isNew -> "新增"
                    else -> "未下载"
                }
                val shape = RoundedCornerShape(6.dp)
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                        .semantics {
                            contentDescription = "${photo.filename}，$statusLabel，${if (isSelected) "已选择第 ${selectedOrdinals[photo.handle]} 项" else "未选择"}"
                            onClick(label = if (isSelected) "取消选择" else "选择照片") {
                                if (enabled) {
                                    onToggle(photo.handle)
                                    true
                                } else false
                            }
                        },
                ) {
                    Thumbnail(
                        bytes = thumbnail,
                        photo = photo,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isSelected) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.24f)))
                        Box(
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                .size(25.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                        selectedOrdinals[photo.handle]?.let { ordinal ->
                            Box(
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                    .size(25.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (ordinal > 99) "99+" else "$ordinal",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    } else if (photo.isRaw || photo.isVideo) {
                        Text(
                            if (photo.isRaw) "RAW" else "视频",
                            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                    Text(
                        statusLabel,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                            .background(
                                if (isNew) MaterialTheme.colorScheme.primary
                                else Color.Black.copy(alpha = 0.74f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isNew) MaterialTheme.colorScheme.onPrimary else Color.White,
                    )
                }
            }
        }
    }
}

private fun dragSelection(
    orderedHandles: List<Long>,
    baseline: Set<Long>,
    anchor: Long,
    end: Long,
): Set<Long> {
    val startIndex = orderedHandles.indexOf(anchor)
    val endIndex = orderedHandles.indexOf(end)
    if (startIndex < 0 || endIndex < 0) return baseline
    val range = orderedHandles.subList(minOf(startIndex, endIndex), maxOf(startIndex, endIndex) + 1)
    return if (anchor in baseline) baseline - range.toSet() else baseline + range
}

private enum class PhotoStatusFilter(val label: String) {
    ALL("全部"),
    NEW("新增"),
    NOT_DOWNLOADED("未下载"),
    DOWNLOADED("已下载"),
    ;

    fun matches(handle: Long, downloaded: Set<Long>, new: Set<Long>): Boolean = when (this) {
        ALL -> true
        DOWNLOADED -> handle in downloaded
        NEW -> handle !in downloaded && handle in new
        NOT_DOWNLOADED -> handle !in downloaded
    }
}

private enum class PhotoFormatFilter(val label: String) {
    ALL_PHOTOS("照片"),
    JPEG("JPEG"),
    RAW("RAW"),
    VIDEO("视频"),
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
    if (isEmpty()) return "日期未知"
    return runCatching {
        LocalDate.parse(this, DateTimeFormatter.BASIC_ISO_DATE)
            .format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))
    }.getOrDefault("日期未知")
}

private fun String.toCompactDisplayDate(): String {
    if (isEmpty()) return "日期未知"
    return runCatching {
        LocalDate.parse(this, DateTimeFormatter.BASIC_ISO_DATE)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.SIMPLIFIED_CHINESE))
    }.getOrDefault("日期未知")
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
    if (this <= 0L) return "尚未同步"
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
    photo: CameraObject,
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        photo.isVideo -> "▶"
                        photo.isRaw -> "RAW"
                        else -> "JPG"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    photo.filename.substringBeforeLast('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (photo.isVideo) {
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
    }
}
