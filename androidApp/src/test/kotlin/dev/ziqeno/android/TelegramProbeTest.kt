package dev.ziqeno.android

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramProbeTest {
    @Test
    fun usesAbridgedUnauthenticatedHandshakeAndValidatesTheNonce() {
        val nonce = ByteArray(16) { it.toByte() }
        val request = ByteBuffer.wrap(TelegramProbe.request(nonce, 1_800_000_000_125L)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10, request.get().toInt())
        assertEquals(0L, request.long)
        assertEquals((1_800_000_000L shl 32) + (1L shl 29), request.long)
        assertEquals(20, request.int)
        assertEquals(0xbe7e8ef1.toInt(), request.int)

        val boundary = ByteBuffer.wrap(TelegramProbe.request(nonce, 1_800_000_000_000L)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((1_800_000_000L shl 32) + 4, boundary.getLong(9))
        val response = ByteBuffer.allocate(85).order(ByteOrder.LITTLE_ENDIAN)
            .put(21).putLong(0).putLong(1).putInt(64).putInt(0x05162463).put(nonce).put(ByteArray(16))
            .put(8).putLong(301).put(ByteArray(3)).putInt(0x1cb5c415).putInt(1).putLong(234).array()
        assertTrue(TelegramProbe.validate(DataInputStream(response.inputStream()), nonce))
        assertFalse(TelegramProbe.validate(DataInputStream(response.inputStream()), ByteArray(16)))
        response[69] = 0
        assertFalse(TelegramProbe.validate(DataInputStream(response.inputStream()), nonce))
        assertFalse(TelegramProbe.validate(DataInputStream(byteArrayOf(0x7f, -1, -1, -1).inputStream()), nonce))
    }
}
