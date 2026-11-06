package dev.ziqeno.android

import java.net.InetAddress

internal fun isTelegram(packageName: String) =
    packageName == "org.telegram.messenger" || packageName == "org.telegram.messenger.web" ||
        packageName == "org.telegram.messenger.beta"

internal object TelegramDataCenters {
    // https://core.telegram.org/resources/cidr.txt
    private val ranges = listOf(
        "91.108.56.0/22", "91.108.4.0/22", "91.108.8.0/22", "91.108.16.0/22", "91.108.12.0/22",
        "149.154.160.0/20", "91.105.192.0/23", "91.108.20.0/22", "185.76.151.0/24",
        "2001:b28:f23d::/48", "2001:b28:f23f::/48", "2001:67c:4e8::/48", "2001:b28:f23c::/48",
        "2a0a:f280::/32",
    ).map { InetAddress.getByName(it.substringBefore('/')).address to it.substringAfter('/').toInt() }

    fun contains(address: ByteArray): Boolean = ranges.any { (base, bits) ->
        if (address.size != base.size) return@any false
        val bytes = bits / 8
        val remainder = bits % 8
        (0 until bytes).all { address[it] == base[it] } &&
            (remainder == 0 || (address[bytes].toInt() xor base[bytes].toInt()) and (0xff shl (8 - remainder)) == 0)
    }
}
