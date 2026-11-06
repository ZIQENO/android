package dev.ziqeno.android

import java.io.DataInputStream
import java.io.FilterInputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object TelegramProbe {
    private const val requestPq = 0xbe7e8ef1.toInt()
    private const val responsePq = 0x05162463
    private const val vector = 0x1cb5c415
    private val random by lazy { SecureRandom() }
    private val reserved = setOf(0x44414548, 0x54534f50, 0x20544547, 0x4954504f, 0xeeeeeeee.toInt(), 0xdddddddd.toInt(), 0x02010316)

    fun handshake(socket: Socket): Boolean {
        val nonce = ByteArray(16).also(random::nextBytes)
        val header = header()
        val outgoing = cipher(header, Cipher.ENCRYPT_MODE)
        val incoming = cipher(header.reversedArray(), Cipher.DECRYPT_MODE)
        val encryptedHeader = outgoing.update(header)
        val frame = outgoing.doFinal(request(nonce, System.currentTimeMillis()))
        val deadline = System.nanoTime() + socket.soTimeout * 1_000_000L
        socket.getOutputStream().write(header.copyOf(56) + encryptedHeader.copyOfRange(56, 64) + frame)
        val bounded = object : FilterInputStream(socket.getInputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val left = deadline - System.nanoTime()
                if (left <= 0) throw SocketTimeoutException()
                socket.soTimeout = ((left + 999_999) / 1_000_000).toInt()
                return super.read(bytes, offset, length)
            }
        }
        return validate(DataInputStream(CipherInputStream(bounded, incoming)), nonce)
    }

    // Direct obfuscated abridged transport uses public, per-connection key material and no account key.
    private fun header(): ByteArray {
        val bytes = ByteArray(64)
        val ints = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        do {
            random.nextBytes(bytes)
        } while (bytes[0] == 0xef.toByte() || ints.getInt(0) in reserved || ints.getInt(4) == 0)
        bytes.fill(0xef.toByte(), 56, 60)
        return bytes
    }

    private fun cipher(header: ByteArray, mode: Int): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(mode, SecretKeySpec(header.copyOfRange(8, 40), "AES"), IvParameterSpec(header.copyOfRange(40, 56)))
    }

    fun request(nonce: ByteArray, timeMillis: Long): ByteArray {
        require(nonce.size == 16)
        val fraction = (((timeMillis % 1000 shl 32) / 1000) and -4L).coerceAtLeast(4L)
        val id = (timeMillis / 1000 shl 32) + fraction
        return ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN).put(10)
            .putLong(0).putLong(id).putInt(20).putInt(requestPq).put(nonce).array()
    }

    fun validate(input: DataInputStream, nonce: ByteArray): Boolean {
        val prefix = input.readUnsignedByte()
        if (prefix > 0x7f) return false
        val words = if (prefix < 0x7f) prefix else
            input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or (input.readUnsignedByte() shl 16)
        val size = words * 4
        if (size !in 76..512) return false
        val payload = ByteBuffer.wrap(ByteArray(size).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        if (payload.long != 0L || payload.long and 3L != 1L || payload.int != size - 20 || payload.int != responsePq) return false
        if (!ByteArray(16).also(payload::get).contentEquals(nonce)) return false
        payload.position(payload.position() + 16) // server nonce
        val pqLength = payload.get().toInt() and 255
        if (pqLength !in 1..16) return false
        val padding = (4 - (1 + pqLength) % 4) % 4
        if (payload.remaining() < pqLength + padding + 16) return false
        payload.position(payload.position() + pqLength)
        repeat(padding) { if (payload.get() != 0.toByte()) return false }
        if (payload.int != vector) return false
        val keys = payload.int
        return keys > 0 && payload.remaining() % 8 == 0 && keys == payload.remaining() / 8
    }
}
