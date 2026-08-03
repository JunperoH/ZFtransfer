package io.github.noisetta.nikonpull.ptp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlin.concurrent.thread

class PtpIpClient(
    private val host: String = DEFAULT_HOST,
    private val cameraPort: Int = DEFAULT_PORT,
    private val clientName: String = "Nikon Pull",
    private val clientGuid: UUID = UUID.randomUUID(),
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : Closeable {
    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var commandInput: BufferedInputStream? = null
    private var commandOutput: BufferedOutputStream? = null
    private var eventThread: Thread? = null
    private val eventRunning = AtomicBoolean(false)
    private val nextTransactionId = AtomicLong(0)
    private var responderGuid: String = ""

    @Synchronized
    fun connect() {
        close()
        var stage = "opening the command socket"

        try {
            val command = openSocket(COMMAND_TIMEOUT_MS)
            commandSocket = command
            commandInput = BufferedInputStream(command.getInputStream(), IO_BUFFER_SIZE)
            commandOutput = BufferedOutputStream(command.getOutputStream(), IO_BUFFER_SIZE)

            sendCommandPacket(TYPE_INIT_COMMAND_REQUEST, initCommandPayload())
            stage = "waiting for the Nikon command acknowledgement"
            val commandAck = readSmallPacket(commandInput())
            if (commandAck.type == TYPE_INIT_FAIL) {
                throw PtpException("The camera rejected this phone. Disable and re-enable camera Wi-Fi, then try again.")
            }
            if (commandAck.type != TYPE_INIT_COMMAND_ACK || commandAck.payload.size < 4) {
                throw PtpException("Camera returned an invalid PTP/IP connection response")
            }
            val connectionNumber = commandAck.payload.littleEndianBuffer().int
            responderGuid = commandAck.payload
                .takeIf { it.size >= 20 }
                ?.copyOfRange(4, 20)
                ?.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .orEmpty()

            stage = "opening the event socket"
            val event = openSocket(COMMAND_TIMEOUT_MS)
            eventSocket = event
            val eventInput = BufferedInputStream(event.getInputStream(), EVENT_BUFFER_SIZE)
            val eventOutput = BufferedOutputStream(event.getOutputStream(), EVENT_BUFFER_SIZE)
            eventOutput.write(
                packet(
                    TYPE_INIT_EVENT_REQUEST,
                    littleEndianBuffer(4).putInt(connectionNumber).array(),
                ),
            )
            eventOutput.flush()
            stage = "waiting for the Nikon event acknowledgement"
            val eventAck = readSmallPacket(eventInput)
            if (eventAck.type != TYPE_INIT_EVENT_ACK) {
                throw PtpException("Camera event channel could not be initialized")
            }
            event.soTimeout = EVENT_READ_TIMEOUT_MS
            startEventLoop(eventInput, eventOutput)

            nextTransactionId.set(0)
            stage = "opening the PTP session"
            execute(
                operationCode = OP_OPEN_SESSION,
                parameters = listOf(SESSION_ID),
                transactionId = 0,
            )
        } catch (error: Throwable) {
            closeSockets()
            if (error is PtpException) throw error
            val cause = generateSequence(error) { it.cause }.last()
            val causeName = cause::class.simpleName ?: "network error"
            val detail = cause.message?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
            throw PtpException(
                "Connection failed while $stage at $host:$cameraPort ($causeName$detail)",
                error,
            )
        }
    }

    fun listObjectHandles(): List<Long> {
        val storageIds = PtpDataParser.parseU32Array(
            executeForBytes(OP_GET_STORAGE_IDS),
        )
        return storageIds.flatMap { storageId ->
            PtpDataParser.parseU32Array(
                executeForBytes(
                    OP_GET_OBJECT_HANDLES,
                    listOf(storageId, FORMAT_UNDEFINED, ALL_ASSOCIATIONS),
                ),
            )
        }.distinct()
    }

    fun getObjectInfo(handle: Long): CameraObject = PtpDataParser.parseObjectInfo(
        handle,
        executeForBytes(OP_GET_OBJECT_INFO, listOf(handle)),
    )

    fun listObjects(onProgress: (loaded: Int, total: Int) -> Unit = { _, _ -> }): List<CameraObject> {
        val handles = listObjectHandles()

        val objects = ArrayList<CameraObject>(handles.size)
        handles.forEachIndexed { index, handle ->
            runCatching { getObjectInfo(handle) }.getOrNull()?.let(objects::add)
            onProgress(index + 1, handles.size)
        }

        if (handles.isNotEmpty() && objects.isEmpty()) {
            throw PtpException("The camera listed ${handles.size} objects, but none of their details could be read")
        }

        return objects
            .asSequence()
            .sortedWith(compareByDescending<CameraObject> { it.captureDate }.thenByDescending { it.handle })
            .toList()
    }

    fun getThumbnail(handle: Long): ByteArray = executeForBytes(
        operationCode = OP_GET_THUMB,
        parameters = listOf(handle),
        maximumSize = MAX_THUMBNAIL_BYTES,
    )

    fun getDeviceInfo(): CameraDeviceInfo = PtpDataParser.parseDeviceInfo(
        executeForBytes(OP_GET_DEVICE_INFO),
        responderGuid,
    )

    /** A harmless metadata request that keeps the Nikon PTP/IP session active. */
    fun ping() {
        executeForBytes(OP_GET_DEVICE_INFO)
    }

    fun downloadObject(
        handle: Long,
        destination: OutputStream,
        onProgress: (TransferProgress) -> Unit = {},
    ) {
        execute(
            operationCode = OP_GET_OBJECT,
            parameters = listOf(handle),
            destination = destination,
            onProgress = onProgress,
        )
    }

    @Synchronized
    private fun executeForBytes(
        operationCode: Int,
        parameters: List<Long> = emptyList(),
        maximumSize: Int = MAX_METADATA_BYTES,
    ): ByteArray {
        val destination = LimitedByteArrayOutputStream(maximumSize)
        execute(operationCode, parameters, destination = destination)
        return destination.toByteArray()
    }

    @Synchronized
    private fun execute(
        operationCode: Int,
        parameters: List<Long> = emptyList(),
        transactionId: Long = nextTransactionId.incrementAndGet(),
        destination: OutputStream? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ) {
        checkConnected()
        val request = operationRequestPayload(operationCode, transactionId, parameters)
        sendCommandPacket(TYPE_OPERATION_REQUEST, request)

        var expectedLength: Long? = null
        var bytesTransferred = 0L
        var sawEndData = false

        while (true) {
            val header = readHeader(commandInput())
            when (header.type) {
                TYPE_START_DATA -> {
                    val payload = readPayload(commandInput(), header.payloadLength, MAX_SMALL_PACKET_BYTES)
                    val reader = LittleEndianReader(payload)
                    requireTransaction(reader.u32(), transactionId)
                    expectedLength = reader.u64().takeUnless { it == UNKNOWN_DATA_LENGTH }
                    onProgress(TransferProgress(bytesTransferred, expectedLength))
                }

                TYPE_DATA, TYPE_END_DATA -> {
                    if (header.payloadLength < 4) throw PtpException("Camera returned an invalid data packet")
                    val packetTransaction = readU32(commandInput())
                    requireTransaction(packetTransaction, transactionId)
                    val dataLength = header.payloadLength - 4
                    if (destination == null && dataLength > 0) {
                        throw PtpException("Camera returned unexpected data for operation 0x${operationCode.toString(16)}")
                    }
                    if (destination != null) {
                        copyExactly(commandInput(), destination, dataLength) { copied ->
                            onProgress(TransferProgress(bytesTransferred + copied, expectedLength))
                        }
                    } else {
                        skipExactly(commandInput(), dataLength)
                    }
                    bytesTransferred += dataLength
                    sawEndData = sawEndData || header.type == TYPE_END_DATA
                }

                TYPE_OPERATION_RESPONSE -> {
                    val payload = readPayload(commandInput(), header.payloadLength, MAX_SMALL_PACKET_BYTES)
                    val reader = LittleEndianReader(payload)
                    val responseCode = reader.u16()
                    requireTransaction(reader.u32(), transactionId)
                    if (responseCode != RESPONSE_OK) {
                        throw PtpException(
                            "Camera returned PTP error 0x${responseCode.toString(16).uppercase()} " +
                                "for operation 0x${operationCode.toString(16).uppercase()}",
                        )
                    }
                    if (expectedLength != null && bytesTransferred != expectedLength) {
                        throw PtpException(
                            "Camera transfer ended early ($bytesTransferred of $expectedLength bytes)",
                        )
                    }
                    if (expectedLength != null && !sawEndData) {
                        throw PtpException("Camera transfer ended without an End Data packet")
                    }
                    destination?.flush()
                    onProgress(TransferProgress(bytesTransferred, expectedLength))
                    return
                }

                TYPE_CANCEL -> throw PtpException("Camera cancelled the transfer")
                else -> {
                    skipExactly(commandInput(), header.payloadLength)
                    throw PtpException("Unexpected PTP/IP packet type ${header.type}")
                }
            }
        }
    }

    private fun startEventLoop(
        eventInput: BufferedInputStream,
        eventOutput: BufferedOutputStream,
    ) {
        eventRunning.set(true)
        eventThread = thread(name = "nikon-pull-events", isDaemon = true) {
            while (eventRunning.get()) {
                try {
                    val header = readHeader(eventInput)
                    skipExactly(eventInput, header.payloadLength)
                    if (header.type == TYPE_PROBE_REQUEST) {
                        synchronized(eventOutput) {
                            eventOutput.write(packet(TYPE_PROBE_RESPONSE, ByteArray(0)))
                            eventOutput.flush()
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Periodically wake so close() can stop this thread.
                } catch (_: Throwable) {
                    if (eventRunning.get()) closeSockets()
                    return@thread
                }
            }
        }
    }

    private fun initCommandPayload(): ByteArray {
        val guid = littleEndianBuffer(16)
            .putLong(clientGuid.mostSignificantBits)
            .putLong(clientGuid.leastSignificantBits)
            .array()
        return LittleEndianWriter().apply {
            bytes(guid)
            utf16NullTerminated(clientName)
            u32(PTP_IP_VERSION)
        }.toByteArray()
    }

    private fun openSocket(readTimeoutMs: Int): Socket = socketFactory.createSocket().apply {
        reuseAddress = true
        tcpNoDelay = true
        connect(InetSocketAddress(host, cameraPort), CONNECT_TIMEOUT_MS)
        soTimeout = readTimeoutMs
        keepAlive = true
    }

    private fun checkConnected() {
        if (commandSocket?.isConnected != true || commandSocket?.isClosed != false) {
            throw PtpException("Not connected to the camera")
        }
    }

    private fun sendCommandPacket(type: Int, payload: ByteArray) {
        val output = commandOutput ?: throw PtpException("Not connected to the camera")
        output.write(packet(type, payload))
        output.flush()
    }

    private fun commandInput(): BufferedInputStream =
        commandInput ?: throw PtpException("Not connected to the camera")

    private fun packet(type: Int, payload: ByteArray): ByteArray =
        littleEndianBuffer(8 + payload.size)
            .putInt(8 + payload.size)
            .putInt(type)
            .put(payload)
            .array()

    private fun readSmallPacket(source: BufferedInputStream): Packet {
        val header = readHeader(source)
        return Packet(header.type, readPayload(source, header.payloadLength, MAX_SMALL_PACKET_BYTES))
    }

    private fun readHeader(source: BufferedInputStream): PacketHeader {
        val rawHeader = ByteArray(8)
        source.readExactly(rawHeader, 0, rawHeader.size)
        val header = rawHeader.littleEndianBuffer()
        val length = header.int.toLong() and 0xffffffffL
        val type = header.int
        if (length < 8 || length > MAX_PACKET_BYTES) {
            throw PtpException("Camera returned invalid PTP/IP packet length $length")
        }
        return PacketHeader(type, length - 8)
    }

    private fun readPayload(source: BufferedInputStream, length: Long, maximumSize: Int): ByteArray {
        if (length > maximumSize) throw PtpException("Camera packet is too large ($length bytes)")
        return ByteArray(length.toInt()).also { source.readExactly(it, 0, it.size) }
    }

    private fun readU32(source: BufferedInputStream): Long {
        val bytes = ByteArray(4)
        source.readExactly(bytes, 0, bytes.size)
        return bytes.littleEndianBuffer().int.toLong() and 0xffffffffL
    }

    private fun requireTransaction(actual: Long, expected: Long) {
        if (actual != expected) {
            throw PtpException("Unexpected transaction $actual; expected $expected")
        }
    }

    private fun BufferedInputStream.readExactly(destination: ByteArray, offset: Int, length: Int) {
        var readTotal = 0
        while (readTotal < length) {
            val count = read(destination, offset + readTotal, length - readTotal)
            if (count < 0) throw PtpException("Camera disconnected during transfer")
            readTotal += count
        }
    }

    private fun copyExactly(
        source: BufferedInputStream,
        destination: OutputStream,
        length: Long,
        onProgress: (Long) -> Unit,
    ) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var copied = 0L
        while (copied < length) {
            val requested = minOf(buffer.size.toLong(), length - copied).toInt()
            val count = source.read(buffer, 0, requested)
            if (count < 0) throw PtpException("Camera disconnected during transfer")
            destination.write(buffer, 0, count)
            copied += count
            onProgress(copied)
        }
    }

    private fun skipExactly(source: BufferedInputStream, length: Long) {
        val discard = ByteArray(IO_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val requested = minOf(discard.size.toLong(), remaining).toInt()
            val count = source.read(discard, 0, requested)
            if (count < 0) throw PtpException("Camera disconnected while reading a packet")
            remaining -= count
        }
    }

    @Synchronized
    override fun close() {
        if (commandSocket?.isConnected == true && commandSocket?.isClosed == false) {
            runCatching { execute(OP_CLOSE_SESSION, transactionId = nextTransactionId.incrementAndGet()) }
        }
        closeSockets()
    }

    /** Immediately tears down a broken session without attempting a PTP close handshake. */
    fun abort() {
        closeSockets()
    }

    private fun closeSockets() {
        eventRunning.set(false)
        runCatching { eventSocket?.close() }
        runCatching { commandSocket?.close() }
        eventSocket = null
        commandSocket = null
        commandInput = null
        commandOutput = null
        eventThread = null
    }

    private data class PacketHeader(val type: Int, val payloadLength: Long)
    private data class Packet(val type: Int, val payload: ByteArray)

    private class LimitedByteArrayOutputStream(private val maximumSize: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            ensureCapacityFor(1)
            super.write(value)
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            ensureCapacityFor(length)
            super.write(value, offset, length)
        }

        private fun ensureCapacityFor(additionalBytes: Int) {
            if (size().toLong() + additionalBytes > maximumSize) {
                throw PtpException("Camera data exceeded the safe in-memory limit")
            }
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.1.1"
        const val DEFAULT_PORT = 15740

        private const val SESSION_ID = 1L
        private const val PTP_IP_VERSION = 0x0001_0000L
        private const val UNKNOWN_DATA_LENGTH = -1L
        private const val FORMAT_UNDEFINED = 0L
        private const val ALL_ASSOCIATIONS = 0L

        private const val TYPE_INIT_COMMAND_REQUEST = 1
        private const val TYPE_INIT_COMMAND_ACK = 2
        private const val TYPE_INIT_EVENT_REQUEST = 3
        private const val TYPE_INIT_EVENT_ACK = 4
        private const val TYPE_INIT_FAIL = 5
        private const val TYPE_OPERATION_REQUEST = 6
        private const val TYPE_OPERATION_RESPONSE = 7
        private const val TYPE_START_DATA = 9
        private const val TYPE_DATA = 10
        private const val TYPE_CANCEL = 11
        private const val TYPE_END_DATA = 12
        private const val TYPE_PROBE_REQUEST = 13
        private const val TYPE_PROBE_RESPONSE = 14

        private const val RESPONSE_OK = 0x2001
        private const val OP_GET_DEVICE_INFO = 0x1001
        private const val OP_OPEN_SESSION = 0x1002
        private const val OP_CLOSE_SESSION = 0x1003
        private const val OP_GET_STORAGE_IDS = 0x1004
        private const val OP_GET_OBJECT_HANDLES = 0x1007
        private const val OP_GET_OBJECT_INFO = 0x1008
        private const val OP_GET_OBJECT = 0x1009
        private const val OP_GET_THUMB = 0x100A

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val COMMAND_TIMEOUT_MS = 60_000
        private const val EVENT_READ_TIMEOUT_MS = 2_000
        private const val IO_BUFFER_SIZE = 128 * 1024
        private const val EVENT_BUFFER_SIZE = 8 * 1024
        private const val MAX_THUMBNAIL_BYTES = 16 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 32 * 1024 * 1024
        private const val MAX_SMALL_PACKET_BYTES = 64 * 1024
        private const val MAX_PACKET_BYTES = 2L * 1024 * 1024 * 1024
    }
}
