package io.github.noisetta.nikonpull.ptp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun littleEndianBuffer(size: Int): ByteBuffer =
    ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

internal fun ByteArray.littleEndianBuffer(): ByteBuffer =
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

internal class LittleEndianWriter {
    private val output = ByteArrayOutputStream()

    fun u16(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    fun u32(value: Long) {
        repeat(4) { byteIndex ->
            output.write(((value ushr (byteIndex * 8)) and 0xff).toInt())
        }
    }

    fun bytes(value: ByteArray) {
        output.write(value)
    }

    fun utf16NullTerminated(value: String) {
        bytes(value.toByteArray(Charsets.UTF_16LE))
        u16(0)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

internal class LittleEndianReader(private val bytes: ByteArray) {
    private val buffer = bytes.littleEndianBuffer()

    fun remaining(): Int = buffer.remaining()

    fun u8(): Int {
        requireRemaining(1)
        return buffer.get().toInt() and 0xff
    }

    fun u16(): Int {
        requireRemaining(2)
        return buffer.short.toInt() and 0xffff
    }

    fun u32(): Long {
        requireRemaining(4)
        return buffer.int.toLong() and 0xffffffffL
    }

    fun u64(): Long {
        requireRemaining(8)
        return buffer.long
    }

    fun bytes(count: Int): ByteArray {
        requireRemaining(count)
        return ByteArray(count).also(buffer::get)
    }

    fun ptpString(): String {
        val codeUnitCountIncludingNull = u8()
        if (codeUnitCountIncludingNull == 0) return ""

        val textLength = codeUnitCountIncludingNull - 1
        requireRemaining(codeUnitCountIncludingNull * 2)
        val value = CharArray(textLength) { u16().toChar() }.concatToString()
        u16()
        return value
    }

    private fun requireRemaining(count: Int) {
        if (buffer.remaining() < count) {
            throw PtpException("Camera returned a truncated PTP data set")
        }
    }
}
