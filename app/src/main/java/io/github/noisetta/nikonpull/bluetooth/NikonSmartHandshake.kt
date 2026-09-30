package io.github.noisetta.nikonpull.bluetooth

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Nikon Smart BLE challenge/response, based on the published furble protocol research. */
internal class NikonSmartHandshake {
    data class Identity(val device: Long, val nonce: Long)

    data class Message(val stage: Int, val timestamp: Long, val device: Long, val nonce: Long) {
        fun bytes(): ByteArray = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
            .put(stage.toByte()).putLong(timestamp).putInt(device.toInt()).putInt(nonce.toInt()).array()

        companion object {
            fun from(bytes: ByteArray): Message {
                require(bytes.size == 17) { "Expected 17-byte Nikon pairing message" }
                val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                return Message(input.get().toInt() and 0xff, input.long,
                    input.int.toLong() and MASK, input.int.toLong() and MASK)
            }
        }
    }

    private val random = SecureRandom()
    // Nikon Smart defines a raw 8-byte Blowfish block transform for this handshake.
    @SuppressLint("GetInstance")
    private val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "Blowfish"))
    }

    fun newIdentity(): Identity = Identity(
        ((random.nextInt().toLong() and MASK) and 0xffffff00L) or 1L,
        random.nextInt().toLong() and MASK,
    )

    fun first(identity: Identity): Message = Message(1, random.nextLong(), identity.device, identity.nonce)

    fun answer(first: Message, second: Message): Message {
        require(second.stage == 2) { "Expected Nikon pairing stage 2" }
        val camera = words(second.timestamp)
        val phone = words(first.timestamp)
        val salt = SALTS.firstOrNull { candidate ->
            val challenge = hash(intArrayOf(candidate[0], candidate[1], camera.first, camera.second,
                phone.first, phone.second))
            challenge.first == Integer.reverseBytes(second.device.toInt()) &&
                challenge.second == Integer.reverseBytes(second.nonce.toInt())
        } ?: throw IllegalArgumentException("Nikon pairing challenge did not match")
        val response = hash(intArrayOf(salt[0], salt[1], phone.first, phone.second,
            camera.first, camera.second))
        return Message(3, first.timestamp,
            Integer.reverseBytes(response.first).toLong() and MASK,
            Integer.reverseBytes(response.second).toLong() and MASK)
    }

    private fun words(value: Long): Pair<Int, Int> =
        Integer.reverseBytes(value.toInt()) to Integer.reverseBytes((value ushr 32).toInt())

    private fun hash(blocks: IntArray): Pair<Int, Int> {
        var left = 0x01020304
        var right = 0x05060708
        for (offset in blocks.indices step 2) {
            val input = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putInt(blocks[offset] xor left).putInt(blocks[offset + 1] xor right).array()
            val output = ByteBuffer.wrap(cipher.doFinal(input)).order(ByteOrder.BIG_ENDIAN)
            left = output.int
            right = output.int
        }
        return left to right
    }

    companion object {
        private const val MASK = 0xffffffffL
        private val KEY = byteArrayOf(-1, -1, -86, 85, 17, 34, 51, 0)
        private val SALTS = arrayOf(
            intArrayOf(0x704066e4, 0x0433d552),
            intArrayOf(0xed4b8fac.toInt(), 0x15f7e47b),
            intArrayOf(0x24471f11, 0x8b5ea1fc.toInt()),
            intArrayOf(0x05960c31, 0x2b8c7f41),
            intArrayOf(0xfda588c1.toInt(), 0xeba8b1f3.toInt()),
            intArrayOf(0x99166056.toInt(), 0x1bd3d550),
            intArrayOf(0xcd32687f.toInt(), 0xa9e28a30.toInt()),
            intArrayOf(0x2a8fe834, 0xdec7ebf4.toInt()),
        )
    }
}
