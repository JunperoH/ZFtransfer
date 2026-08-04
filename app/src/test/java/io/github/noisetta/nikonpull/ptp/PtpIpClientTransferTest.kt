package io.github.noisetta.nikonpull.ptp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import javax.net.SocketFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpIpClientTransferTest {
    @Test
    fun eventChannelEofDoesNotAbortActiveCommandChannel() {
        val objectBytes = "complete-video-data".toByteArray()
        val commandSocket = ScriptedSocket(commandConversation(objectBytes))
        val eventSocket = ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))
        val client = PtpIpClient(
            socketFactory = QueuedSocketFactory(commandSocket, eventSocket),
        )

        client.connect()
        assertTrue(waitUntil { eventSocket.closed })
        assertFalse(commandSocket.closed)

        val destination = ByteArrayOutputStream()
        client.downloadObject(handle = 42L, destination = destination)

        assertArrayEquals(objectBytes, destination.toByteArray())
        assertFalse(commandSocket.closed)
        client.abort()
    }

    @Test
    fun partialObjectDownloadConcatenatesBoundedTransactions() {
        val objectBytes = "a-video-split-into-several-small-ptp-responses".toByteArray()
        val chunkSize = 11
        val commandSocket = ScriptedSocket(partialObjectConversation(objectBytes, chunkSize))
        val eventSocket = ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))
        val client = PtpIpClient(
            socketFactory = QueuedSocketFactory(commandSocket, eventSocket),
        )

        client.connect()
        val destination = ByteArrayOutputStream()
        var lastProgress = TransferProgress(0, null)
        client.downloadObjectInChunks(
            handle = 42L,
            objectSize = objectBytes.size.toLong(),
            destination = destination,
            onProgress = { lastProgress = it },
            chunkSize = chunkSize,
        )

        assertArrayEquals(objectBytes, destination.toByteArray())
        assertEquals(objectBytes.size.toLong(), lastProgress.bytesTransferred)
        assertEquals(objectBytes.size.toLong(), lastProgress.totalBytes)
        assertFalse(commandSocket.closed)
        client.abort()
    }

    @Test
    fun partialObjectDownloadResumesFromCompletedOffset() {
        val objectBytes = "prefix-already-saved-and-the-remaining-video-data".toByteArray()
        val startOffset = 21
        val remainingBytes = objectBytes.copyOfRange(startOffset, objectBytes.size)
        val commandSocket = ScriptedSocket(partialObjectConversation(remainingBytes, chunkSize = 8))
        val eventSocket = ScriptedSocket(packet(TYPE_INIT_EVENT_ACK))
        val client = PtpIpClient(
            socketFactory = QueuedSocketFactory(commandSocket, eventSocket),
        )

        client.connect()
        val destination = ByteArrayOutputStream().apply {
            write(objectBytes, 0, startOffset)
        }
        val checkpoints = mutableListOf<Long>()
        client.downloadObjectInChunks(
            handle = 42L,
            objectSize = objectBytes.size.toLong(),
            destination = destination,
            startOffset = startOffset.toLong(),
            onChunkCompleted = checkpoints::add,
            chunkSize = 8,
        )

        assertArrayEquals(objectBytes, destination.toByteArray())
        assertTrue(checkpoints.all { it > startOffset })
        assertEquals(objectBytes.size.toLong(), checkpoints.last())
        client.abort()
    }

    private fun commandConversation(objectBytes: ByteArray): ByteArray = join(
        packet(
            TYPE_INIT_COMMAND_ACK,
            littleEndian(20).putInt(7).put(ByteArray(16)).array(),
        ),
        operationResponse(transactionId = 0),
        packet(
            TYPE_START_DATA,
            littleEndian(12).putInt(1).putLong(objectBytes.size.toLong()).array(),
        ),
        packet(
            TYPE_END_DATA,
            littleEndian(4 + objectBytes.size).putInt(1).put(objectBytes).array(),
        ),
        operationResponse(transactionId = 1),
    )

    private fun partialObjectConversation(objectBytes: ByteArray, chunkSize: Int): ByteArray {
        val responses = mutableListOf(
            packet(
                TYPE_INIT_COMMAND_ACK,
                littleEndian(20).putInt(7).put(ByteArray(16)).array(),
            ),
            operationResponse(transactionId = 0),
        )
        objectBytes.asList().chunked(chunkSize).forEachIndexed { index, values ->
            val transactionId = index + 1
            val chunk = values.toByteArray()
            responses += packet(
                TYPE_START_DATA,
                littleEndian(12).putInt(transactionId).putLong(chunk.size.toLong()).array(),
            )
            responses += packet(
                TYPE_END_DATA,
                littleEndian(4 + chunk.size).putInt(transactionId).put(chunk).array(),
            )
            responses += operationResponse(transactionId)
        }
        return join(*responses.toTypedArray())
    }

    private fun operationResponse(transactionId: Int): ByteArray = packet(
        TYPE_OPERATION_RESPONSE,
        littleEndian(6).putShort(RESPONSE_OK.toShort()).putInt(transactionId).array(),
    )

    private fun packet(type: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        littleEndian(8 + payload.size)
            .putInt(8 + payload.size)
            .putInt(type)
            .put(payload)
            .array()

    private fun littleEndian(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun join(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        repeat(100) {
            if (predicate()) return true
            Thread.sleep(10)
        }
        return predicate()
    }

    private class QueuedSocketFactory(vararg sockets: Socket) : SocketFactory() {
        private val remaining = ArrayDeque(sockets.toList())

        override fun createSocket(): Socket = remaining.removeFirst()
        override fun createSocket(host: String?, port: Int): Socket = createSocket()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket()
        override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket()
        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = createSocket()
    }

    private class ScriptedSocket(input: ByteArray) : Socket() {
        private val scriptedInput = ByteArrayInputStream(input)
        private val capturedOutput = ByteArrayOutputStream()
        @Volatile var closed: Boolean = false
            private set

        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun getInputStream(): InputStream = scriptedInput
        override fun getOutputStream(): OutputStream = capturedOutput
        override fun isConnected(): Boolean = true
        override fun isClosed(): Boolean = closed
        override fun close() {
            closed = true
            scriptedInput.close()
            capturedOutput.close()
        }
    }

    private companion object {
        const val TYPE_INIT_COMMAND_ACK = 2
        const val TYPE_INIT_EVENT_ACK = 4
        const val TYPE_OPERATION_RESPONSE = 7
        const val TYPE_START_DATA = 9
        const val TYPE_END_DATA = 12
        const val RESPONSE_OK = 0x2001
    }
}
