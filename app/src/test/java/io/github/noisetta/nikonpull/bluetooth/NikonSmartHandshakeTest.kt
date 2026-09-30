package io.github.noisetta.nikonpull.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NikonSmartHandshakeTest {
    @Test fun messageWireFormatRoundTrips() {
        val original = NikonSmartHandshake.Message(1, 0x677da144ec13e1dbL, 0x3c3ae501L, 0x3fdaa451L)
        assertEquals(original, NikonSmartHandshake.Message.from(original.bytes()))
        assertEquals(17, original.bytes().size)
    }

    @Test fun capturedZ50iiChallengeProducesExpectedAnswer() {
        // Public Nikon Smart capture from the nsg protocol tests, originally based on furble research.
        val first = NikonSmartHandshake.Message(1, 0x677da144ec13e1dbL, 0x3c3ae501L, 0x3fdaa451L)
        val second = NikonSmartHandshake.Message(2, 0xb9943d5e8026fa29uL.toLong(), 0xa8b3f2e4L, 0x16d56a13L)
        val answer = NikonSmartHandshake().answer(first, second)
        assertEquals(3, answer.stage)
        assertArrayEquals(byteArrayOf(
            0x03, 0xdb.toByte(), 0xe1.toByte(), 0x13, 0xec.toByte(), 0x44, 0xa1.toByte(), 0x7d, 0x67,
            0x53, 0xad.toByte(), 0xf1.toByte(), 0x79, 0x35, 0x8a.toByte(), 0x83.toByte(), 0x23,
        ), answer.bytes())
    }
}
