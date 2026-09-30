package io.github.noisetta.nikonpull.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.noisetta.nikonpull.diagnostics.DiagnosticLog
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NikonBleDevice(val name: String, val address: String, val rssi: Int)
data class NikonBleState(
    val status: String = "未连接蓝牙",
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val devices: List<NikonBleDevice> = emptyList(),
    val authenticated: Boolean = false,
    val bonded: Boolean = false,
    val wifiRequested: Boolean = false,
)

/** Nikon Smart BLE pairing and the optional Wi-Fi establishment characteristic. */
@SuppressLint("MissingPermission")
class NikonBleController(private val context: Context) : BluetoothGattCallback() {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("nikon_smart_ble", Context.MODE_PRIVATE)
    private val handshake = NikonSmartHandshake()
    private val mutableState = MutableStateFlow(NikonBleState())
    val state = mutableState.asStateFlow()
    private val discovered = mutableMapOf<String, BluetoothDevice>()
    private var rawScanResults = 0
    private var serviceScanResults = 0
    private var manufacturerScanResults = 0
    private var scannerCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var cameraName: String? = null
    private var classicDevice: BluetoothDevice? = null
    private var classicScanning = false
    private var firstMessage: NikonSmartHandshake.Message? = null
    private var identity: NikonSmartHandshake.Identity? = null
    private var phase = 0
    private var gattConnected = false
    private var receiverRegistered = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND && classicScanning) {
                val found = intent.bluetoothDevice() ?: return
                val name = runCatching { found.name }.getOrNull().orEmpty()
                DiagnosticLog.info("BLE", "classic candidate name=$name address=${found.address}")
                if (!matchesCamera(name)) return
                classicScanning = false
                runCatching { adapter?.cancelDiscovery() }
                classicDevice = found
                DiagnosticLog.info("BLE", "classic camera selected address=${found.address}")
                if (found.bondState == BluetoothDevice.BOND_BONDED) onClassicBonded(found)
                else if (!found.createBond()) fail("无法启动系统蓝牙配对；请导出日志")
                else mutableState.update { it.copy(status = "请在 Zf 和手机确认蓝牙配对码") }
                return
            }
            if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED && classicScanning) {
                classicScanning = false
                fail("未发现 Zf 的传统蓝牙设备；请保持相机配对界面并导出日志")
                return
            }
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed = intent.bluetoothDevice() ?: return
            if (changed.address != classicDevice?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> onClassicBonded(changed)
                BluetoothDevice.BOND_NONE -> if (phase >= 3) {
                    fail("系统蓝牙配对未完成；请在相机和手机上确认配对码")
                }
            }
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private fun matchesCamera(candidate: String): Boolean {
        val expected = cameraName.orEmpty().uppercase().filter(Char::isLetterOrDigit).removePrefix("NIKON")
        val actual = candidate.uppercase().filter(Char::isLetterOrDigit).removePrefix("NIKON")
        return expected.isNotEmpty() && actual.isNotEmpty() &&
            (actual.contains(expected) || expected.contains(actual))
    }

    private fun onClassicBonded(bondedDevice: BluetoothDevice) {
        DiagnosticLog.info("BLE", "classic bond complete address=${bondedDevice.address}")
        val bleAddress = device?.address ?: run { fail("BLE 设备信息已丢失"); return }
        preferences.edit().putString("classic_$bleAddress", bondedDevice.address).apply()
        mutableState.update { it.copy(bonded = true, status = "系统蓝牙配对完成；重连 Nikon BLE…") }
        handler.postDelayed({ connect(bleAddress) }, 1_000)
    }

    private fun startClassicDiscovery() {
        val bluetooth = adapter ?: run { fail("蓝牙不可用"); return }
        classicScanning = true
        mutableState.update { it.copy(status = "正在寻找 Zf 的传统蓝牙地址…") }
        DiagnosticLog.info("BLE", "classic discovery started after BLE authentication")
        if (!bluetooth.startDiscovery()) {
            classicScanning = false
            fail("传统蓝牙扫描未能启动；请导出日志")
        }
    }

    fun scan() {
        stopScan()
        if (adapter?.isEnabled != true) {
            fail("请先打开手机蓝牙")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { fail("蓝牙扫描器不可用"); return }
        discovered.clear()
        rawScanResults = 0
        serviceScanResults = 0
        manufacturerScanResults = 0
        mutableState.update { it.copy(scanning = true, devices = emptyList(), status = "扫描 Nikon 相机…") }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                rawScanResults++
                val servicePresent = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
                val nikonManufacturer = result.scanRecord?.manufacturerSpecificData?.get(0x0399) != null
                if (servicePresent) serviceScanResults++
                if (nikonManufacturer) manufacturerScanResults++
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val zfName = name?.matches(Regex("(?i)^Z[_ -]?f(?:[_ -].*)?$")) == true
                if (!servicePresent && !nikonManufacturer && name?.contains("Nikon", true) != true && !zfName) return
                val address = result.device.address
                discovered[address] = result.device
                mutableState.update { old ->
                    old.copy(devices = (old.devices.filterNot { it.address == address } +
                        NikonBleDevice(name ?: "Nikon ($address)", address, result.rssi))
                        .sortedByDescending { it.rssi })
                }
            }

            override fun onScanFailed(errorCode: Int) = fail("蓝牙扫描失败：$errorCode")
        }
        scannerCallback = callback
        runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.postDelayed({
                if (scannerCallback === callback) {
                    stopScan()
                    if (mutableState.value.devices.isEmpty()) {
                        val status = if (rawScanResults == 0) "未收到蓝牙广播；请确认 Zf 处于蓝牙配对界面"
                            else "收到 $rawScanResults 条蓝牙广播，但未识别 Zf；请导出诊断日志"
                        mutableState.update { it.copy(status = status) }
                    }
                }
            }, 15_000)
            DiagnosticLog.info("BLE", "scan started")
        }.onFailure { fail("蓝牙扫描启动失败：${it.message}") }
    }

    fun stopScan() {
        scannerCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
            scannerCallback = null
            mutableState.update { it.copy(scanning = false) }
            DiagnosticLog.info("BLE", "scan stopped raw=$rawScanResults service=$serviceScanResults " +
                "nikonManufacturer=$manufacturerScanResults candidates=${discovered.size}")
        }
    }

    fun connect(address: String) {
        stopScan()
        closeGatt()
        val target = discovered[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (target == null) { fail("蓝牙设备已失效，请重新扫描"); return }
        device = target
        cameraName = discovered[address]?.let { selected ->
            mutableState.value.devices.firstOrNull { it.address == selected.address }?.name
        } ?: cameraName
        phase = 0
        firstMessage = null
        val saved = preferences.getString("identity_$address", null)?.split(':')
        identity = if (saved?.size == 2) {
            runCatching { NikonSmartHandshake.Identity(saved[0].toLong(), saved[1].toLong()) }.getOrNull()
        } else null
        if (identity == null) identity = handshake.newIdentity()
        classicDevice = preferences.getString("classic_$address", null)?.let { savedAddress ->
            runCatching { adapter?.getRemoteDevice(savedAddress) }.getOrNull()
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(context, bondReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        mutableState.update { it.copy(busy = true, authenticated = false,
            bonded = classicDevice?.bondState == BluetoothDevice.BOND_BONDED,
            wifiRequested = false, status = "正在建立 Nikon 蓝牙连接…") }
        DiagnosticLog.info("BLE", "GATT connect name=${runCatching { target.name }.getOrNull()} address=$address")
        runCatching { gatt = target.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { fail("蓝牙连接失败：${it.message}") }
    }

    fun requestWifi() {
        val current = gatt ?: run { fail("蓝牙连接已断开"); return }
        if (!mutableState.value.authenticated) { fail("尼康蓝牙认证尚未完成"); return }
        val characteristic = current.getService(SERVICE_UUID)?.getCharacteristic(WIFI_ESTABLISHMENT_UUID)
        if (characteristic == null) {
            fail("相机没有公开的 Wi-Fi 唤起特征值 0x2005；请导出日志")
            return
        }
        DiagnosticLog.info("BLE", "write Wi-Fi establishment 0x2005 value=01")
        write(current, characteristic, byteArrayOf(1))
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        DiagnosticLog.info("BLE", "GATT state=$newState status=$status")
        if (status != BluetoothGatt.GATT_SUCCESS && !mutableState.value.authenticated) {
            fail("GATT 连接错误：$status"); return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gattConnected = true
            mutableState.update { it.copy(status = "读取相机蓝牙服务…") }
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            gattConnected = false
            if (mutableState.value.authenticated && !mutableState.value.bonded) {
                mutableState.update { it.copy(status = "等待系统蓝牙配对确认…") }
            } else {
                mutableState.update { it.copy(busy = false, authenticated = false, status = "蓝牙已断开") }
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("读取蓝牙服务失败：$status"); return }
        val service = gatt.getService(SERVICE_UUID) ?: run { fail("未找到 Nikon Smart 蓝牙服务"); return }
        val pair = service.getCharacteristic(PAIR_UUID) ?: run { fail("未找到 Nikon 配对特征值"); return }
        DiagnosticLog.info("BLE", "service discovered characteristics=${service.characteristics.map { it.uuid }}")
        phase = 1
        subscribe(gatt, pair, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("蓝牙通知订阅失败：$status"); return }
        if (phase == 1) {
            phase = 2
            val not1 = gatt.getService(SERVICE_UUID)?.getCharacteristic(NOT1_UUID)
            if (not1 != null) subscribe(gatt, not1, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else sendFirst(gatt)
        } else if (phase == 2) sendFirst(gatt)
    }

    private fun sendFirst(gatt: BluetoothGatt) {
        val id = identity ?: run { fail("蓝牙身份未生成"); return }
        val message = handshake.first(id)
        firstMessage = message
        phase = 3
        mutableState.update { it.copy(status = "尼康蓝牙认证：阶段 1/4") }
        DiagnosticLog.info("BLE", "pairing stage 1 sent")
        write(gatt, gatt.getService(SERVICE_UUID).getCharacteristic(PAIR_UUID), message.bytes())
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        changed(gatt, characteristic.uuid, value)
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        @Suppress("DEPRECATION")
        changed(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf())
    }

    private fun changed(gatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (uuid == PAIR_UUID && phase == 3) {
            runCatching {
                val response = handshake.answer(firstMessage ?: error("Missing stage 1"),
                    NikonSmartHandshake.Message.from(value))
                phase = 4
                mutableState.update { it.copy(status = "尼康蓝牙认证：阶段 3/4") }
                DiagnosticLog.info("BLE", "pairing stage 2 verified; stage 3 sent")
                write(gatt, gatt.getService(SERVICE_UUID).getCharacteristic(PAIR_UUID), response.bytes())
            }.onFailure { fail("尼康蓝牙认证失败：${it.message}") }
        } else if (uuid == PAIR_UUID && phase == 4) {
            val fourth = runCatching { NikonSmartHandshake.Message.from(value) }.getOrNull()
            if (fourth?.stage != 4) { fail("尼康认证阶段 4 数据无效"); return }
            phase = 5
            mutableState.update { it.copy(status = "认证完成，登记手机名称…") }
            DiagnosticLog.info("BLE", "pairing stage 4 received")
            handler.postDelayed({ if (phase == 5) writeControllerName(gatt) }, 1_000)
        } else if (uuid == NOT1_UUID && phase == 5 && value.size >= 2 && value[0] == 1.toByte() && value[1] == 0.toByte()) {
            writeControllerName(gatt)
        }
    }

    private fun writeControllerName(gatt: BluetoothGatt) {
        if (phase != 5) return
        val id = gatt.getService(SERVICE_UUID)?.getCharacteristic(ID_UUID)
            ?: run { fail("未找到手机名称特征值"); return }
        phase = 6
        val name = "Android_${Build.MODEL.filter { it.code in 33..126 }.take(16)}_ZfTransfer"
        val padded = ByteArray(32)
        name.toByteArray(Charsets.US_ASCII).copyInto(padded, endIndex = minOf(name.length, 32))
        write(gatt, id, padded)
        DiagnosticLog.info("BLE", "controller name sent")
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        DiagnosticLog.info("BLE", "write uuid=${characteristic.uuid} status=$status phase=$phase")
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("蓝牙写入被相机拒绝：$status"); return }
        if (characteristic.uuid == ID_UUID && phase == 6) {
            phase = 7
            val id = identity ?: return
            device?.address?.let { address ->
                preferences.edit().putString("identity_$address", "${id.device}:${id.nonce}").apply()
            }
            mutableState.update { it.copy(authenticated = true, status = "尼康认证成功；等待系统蓝牙配对…") }
            if (classicDevice?.bondState == BluetoothDevice.BOND_BONDED) {
                mutableState.update { it.copy(bonded = true, status = "请求相机打开 Wi-Fi…") }
                requestWifi()
            } else {
                closeGatt()
                handler.postDelayed({ if (phase == 7) startClassicDiscovery() }, 1_200)
            }
        } else if (characteristic.uuid == WIFI_ESTABLISHMENT_UUID) {
            mutableState.update { it.copy(busy = false, wifiRequested = true,
                status = "Wi-Fi 唤起指令已发送；请检查 Zf 是否开启热点") }
            DiagnosticLog.info("BLE", "Wi-Fi establishment write acknowledged")
        }
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run { fail("缺少蓝牙通知描述符"); return }
        if (!gatt.setCharacteristicNotification(characteristic, true)) { fail("无法启用蓝牙通知"); return }
        if (Build.VERSION.SDK_INT >= 33) {
            val result = gatt.writeDescriptor(descriptor, value)
            if (result != BluetoothGatt.GATT_SUCCESS) fail("蓝牙通知订阅启动失败：$result")
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            if (!gatt.writeDescriptor(descriptor)) fail("蓝牙通知订阅启动失败")
        }
    }

    private fun write(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            val result = gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (result != BluetoothGatt.GATT_SUCCESS) fail("蓝牙写入启动失败：$result")
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            if (!gatt.writeCharacteristic(characteristic)) fail("蓝牙写入启动失败")
        }
    }

    private fun fail(message: String) {
        DiagnosticLog.warn("BLE", message)
        mutableState.update { it.copy(scanning = false, busy = false, status = message) }
    }

    private fun closeGatt() {
        gattConnected = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    fun close() {
        stopScan()
        if (classicScanning) runCatching { adapter?.cancelDiscovery() }
        classicScanning = false
        closeGatt()
        if (receiverRegistered) runCatching { context.unregisterReceiver(bondReceiver) }
        receiverRegistered = false
    }

    companion object {
        private val SERVICE_UUID = UUID.fromString("0000de00-3dd4-4255-8d62-6dc7b9bd5561")
        private val PAIR_UUID = UUID.fromString("00002000-3dd4-4255-8d62-6dc7b9bd5561")
        private val ID_UUID = UUID.fromString("00002002-3dd4-4255-8d62-6dc7b9bd5561")
        private val WIFI_ESTABLISHMENT_UUID = UUID.fromString("00002005-3dd4-4255-8d62-6dc7b9bd5561")
        private val NOT1_UUID = UUID.fromString("00002008-3dd4-4255-8d62-6dc7b9bd5561")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
