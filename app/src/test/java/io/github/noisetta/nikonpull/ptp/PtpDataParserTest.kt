package io.github.noisetta.nikonpull.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpDataParserTest {
    @Test
    fun parsesDeviceIdentityAndCapabilities() {
        val bytes = LittleEndianWriter().apply {
            u16(100)
            u32(0x0000_000AL)
            u16(100)
            ptpString("Nikon extension")
            u16(0)
            u16Array(listOf(0x1001, 0x1004, 0x1007, 0x1008, 0x1009, 0x100A))
            u16Array(emptyList())
            u16Array(emptyList())
            u16Array(emptyList())
            u16Array(listOf(CameraObject.FORMAT_EXIF_JPEG))
            ptpString("Nikon Corporation")
            ptpString("D5300")
            ptpString("1.03")
            ptpString("secret-serial")
        }.toByteArray()

        val info = PtpDataParser.parseDeviceInfo(bytes, "responder")

        assertEquals("Nikon D5300", info.displayName)
        assertEquals("1.03", info.deviceVersion)
        assertTrue(info.isBrowseCompatible)
        assertTrue(info.supportsThumbnail)
        assertEquals(setOf(CameraObject.FORMAT_EXIF_JPEG), info.imageFormatsSupported)
    }

    @Test
    fun parsesObjectInfoWithoutOffsetDrift() {
        val bytes = LittleEndianWriter().apply {
            u32(0x0001_0001)
            u16(CameraObject.FORMAT_EXIF_JPEG)
            u16(0)
            u32(12_345_678)
            u16(0x3801)
            u32(32_768)
            repeat(6) { u32(0) }
            u16(0)
            u32(0)
            u32(42)
            ptpString("DSC_1234.JPG")
            ptpString("20260731T143015")
            ptpString("20260731T143015")
            ptpString("")
        }.toByteArray()

        val photo = PtpDataParser.parseObjectInfo(99, bytes)

        assertEquals(99L, photo.handle)
        assertEquals("DSC_1234.JPG", photo.filename)
        assertEquals("20260731T143015", photo.captureDate)
        assertEquals(12_345_678L, photo.size)
        assertTrue(photo.isJpeg)
    }

    @Test
    fun parsesUnsignedHandleArray() {
        val bytes = LittleEndianWriter().apply {
            u32(3)
            u32(1)
            u32(0x8000_0000L)
            u32(0xffff_ffffL)
        }.toByteArray()

        assertEquals(listOf(1L, 0x8000_0000L, 0xffff_ffffL), PtpDataParser.parseU32Array(bytes))
    }

    @Test
    fun operationRequestUsesNoDataOrDataInPhase() {
        val payload = operationRequestPayload(
            operationCode = 0x1002,
            transactionId = 0,
            parameters = listOf(1),
        )
        val reader = LittleEndianReader(payload)

        assertEquals(1L, reader.u32())
        assertEquals(0x1002, reader.u16())
        assertEquals(0L, reader.u32())
        assertEquals(1L, reader.u32())
        assertEquals(0, reader.remaining())
    }

    private fun LittleEndianWriter.ptpString(value: String) {
        if (value.isEmpty()) {
            bytes(byteArrayOf(0))
        } else {
            bytes(byteArrayOf((value.length + 1).toByte()))
            value.forEach { u16(it.code) }
            u16(0)
        }
    }

    private fun LittleEndianWriter.u16Array(values: List<Int>) {
        u32(values.size.toLong())
        values.forEach(::u16)
    }
}
