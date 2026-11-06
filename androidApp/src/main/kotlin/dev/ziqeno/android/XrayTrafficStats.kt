package dev.ziqeno.android

import java.io.ByteArrayInputStream

internal object XrayTrafficStats {
    const val prefix = "inbound>>>vpn>>>traffic>>>"
    const val directPrefix = "outbound>>>direct>>>traffic>>>"

    // QueryStatsResponse has repeated Stat messages containing a name and an int64 value.
    fun decode(response: ByteArray): TunnelBytes {
        val input = response.inputStream()
        var received: Long? = null
        var sent: Long? = null
        var directReceived = 0L
        var directSent = 0L
        while (input.available() > 0) {
            require(input.read() == 10)
            val stat = input.message().inputStream()
            var name = ""
            var value = 0L
            while (stat.available() > 0) {
                when (stat.read()) {
                    10 -> name = stat.message().decodeToString()
                    16 -> value = stat.varint()
                    else -> error("Unknown Xray stat field")
                }
            }
            require(value >= 0)
            when (name) {
                prefix + "downlink" -> received = value
                prefix + "uplink" -> sent = value
                directPrefix + "downlink" -> directReceived = value
                directPrefix + "uplink" -> directSent = value
            }
        }
        return TunnelBytes(checkNotNull(received), checkNotNull(sent), directReceived, directSent)
    }

    private fun ByteArrayInputStream.message(): ByteArray {
        val length = varint()
        require(length in 0..available().toLong())
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) check(read(bytes, 0, bytes.size) == bytes.size)
        return bytes
    }

    private fun ByteArrayInputStream.varint(): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            val next = read()
            require(next >= 0 && (shift < 63 || next <= 1))
            value = value or ((next and 127).toLong() shl shift)
            if (next < 128) return value
        }
        error("Invalid Xray stat varint")
    }
}
