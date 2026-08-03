package io.github.noisetta.nikonpull.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraObjectClassificationTest {
    @Test
    fun recognizesAdditionalNikonRawAndVideoFormats() {
        val nrw = media("DSCN0001.NRW", 0x3800)
        val avi = media("DSCN0002.AVI", 0x300A)
        val quickTimeByPtpFormat = media("DSCN0003.BIN", 0x300D)

        assertTrue(nrw.isPhoto)
        assertTrue(nrw.isRaw)
        assertEquals("image/x-nikon-nrw", nrw.mimeType)
        assertTrue(avi.isVideo)
        assertEquals("video/x-msvideo", avi.mimeType)
        assertTrue(quickTimeByPtpFormat.isVideo)
        assertFalse(quickTimeByPtpFormat.isPhoto)
    }

    @Test
    fun recognizesMpoAndTiffAsPhotos() {
        assertTrue(media("DSCN0004.MPO", 0x3800).isPhoto)
        assertTrue(media("DSCN0005.TIF", 0x380D).isPhoto)
    }

    private fun media(filename: String, format: Int) = CameraObject(
        handle = 1L,
        storageId = 1L,
        format = format,
        size = 1L,
        thumbnailSize = 0L,
        filename = filename,
        captureDate = "",
    )
}
