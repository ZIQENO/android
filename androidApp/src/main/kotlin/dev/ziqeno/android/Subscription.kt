package dev.ziqeno.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal const val clientVersion = "0.4.8"

internal data class VpnSource(
    val id: String,
    val kind: SourceKind,
    val name: String,
    val reference: String,
    val cachedBody: String?,
    val nodeCount: Int?,
    val hwid: Boolean = true,
) {
    val config get() = cachedBody ?: reference
    val title get() = name.ifBlank {
        if (kind == SourceKind.SUBSCRIPTION) "Подписка" else "Ручной сервер"
    }
}

internal class SourceFailure(message: String) : Exception(message)

internal class SourceStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "subscription.bin"))

    fun load(): List<VpnSource> = file.locked { read() }

    fun save(source: VpnSource, uniqueDefaultName: Boolean): List<VpnSource> = file.locked {
        val previous = read()
        if (previous.any { it.id != source.id && it.kind == source.kind && it.reference == source.reference }) {
            throw SourceFailure("Этот источник уже добавлен.")
        }
        val entry = if (uniqueDefaultName) {
            val used = previous.filter { it.id != source.id && it.kind == source.kind }.map(VpnSource::name).toSet()
            if (source.name !in used) source else {
                val label = source.name.take(58)
                val next = (2..maxSources + 1).first { "$label ($it)" !in used }
                source.copy(name = "$label ($next)")
            }
        } else source
        val index = previous.indexOfFirst { it.id == source.id }
        val sources = previous.toMutableList()
        if (index < 0) {
            if (sources.size >= maxSources) throw SourceFailure("Можно сохранить до $maxSources источников.")
            sources.add(entry)
        } else sources[index] = entry
        write(sources)
        sources
    }

    fun delete(id: String): List<VpnSource> = file.locked {
        read().filterNot { it.id == id }.also(::write)
    }

    private fun read(): List<VpnSource> {
        val blob = try {
            file.openRead().use {
                require(it.channel.size() <= maxCatalogBytes + ivLength + 16L)
                it.readBytes()
            }
        } catch (_: java.io.FileNotFoundException) {
            return emptyList()
        }
        require(blob.size >= ivLength + 16)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, ivLength))
        val data = JSONObject(cipher.doFinal(blob, ivLength, blob.size - ivLength).decodeToString())
        val entries = data.optJSONArray("entries")
        if (entries == null) {
            val url = data.getString("url")
            val label = runCatching { subscriptionUri(url).host }.getOrNull().orEmpty()
            return listOf(VpnSource(
                "legacy", SourceKind.SUBSCRIPTION, label, url, data.getString("body"),
                data.optInt("nodeCount").takeIf { it > 0 },
            ))
        }
        require(data.getInt("version") == 2 && entries.length() <= maxSources)
        return List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            VpnSource(
                item.getString("id"), SourceKind.valueOf(item.getString("kind")), item.getString("name"),
                item.getString("reference"), item.optString("body").takeIf { item.has("body") },
                item.optInt("nodeCount").takeIf { it > 0 }, item.optBoolean("hwid", true),
            )
        }
    }

    private fun write(sources: List<VpnSource>) {
        val entries = JSONArray(sources.map { source ->
            json(
                "id" to source.id, "kind" to source.kind.name, "name" to source.name,
                "reference" to source.reference, "hwid" to source.hwid,
            ).apply {
                source.cachedBody?.let { put("body", it) }
                source.nodeCount?.let { put("nodeCount", it) }
            }
        })
        val payload = json("version" to 2, "entries" to entries).toString().encodeToByteArray()
        if (payload.size > maxCatalogBytes) throw SourceFailure("Подписки занимают слишком много места. Удалите один из источников.")
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == ivLength)
        val bytes = cipher.iv + cipher.doFinal(payload)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val maxSources = 32
        private const val maxCatalogBytes = 16 * 1024 * 1024
        private const val keyAlias = "ziqeno.subscription.v1"
        private const val transformation = "AES/GCM/NoPadding"
        private const val ivLength = 12
    }
}

internal object SubscriptionDownload {
    private const val maxBytes = 2 * 1024 * 1024
    private const val maxRedirects = 3
    private val identityLock = Any()

    fun fetch(context: Context, input: String, hwid: Boolean): String {
        val original = subscriptionUri(input.trim())
        val origin = subscriptionOrigin(original.toASCIIString())
        val identity = if (hwid) deviceIdFor(deviceSeed(context), origin) else null
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var uri = original
        repeat(maxRedirects + 1) { attempt ->
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(8_000).toInt()
            check(remaining > 0)
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = remaining
                connection.readTimeout = remaining
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "v2rayNG/1.10.0 ZIQENO/$clientVersion")
                connection.setRequestProperty("Accept", "text/plain, application/octet-stream, application/json")
                val sameOrigin = subscriptionOrigin(uri.toASCIIString()) == origin
                if (identity != null && sameOrigin) {
                    connection.setRequestProperty("x-hwid", identity)
                    connection.setRequestProperty("x-device-os", "Android")
                    connection.setRequestProperty("x-ver-os", headerValue(Build.VERSION.RELEASE))
                    connection.setRequestProperty("x-device-model", headerValue(Build.MODEL))
                }
                val status = connection.responseCode
                when {
                    connection.flag("x-hwid-max-devices-reached") || connection.flag("x-hwid-limit") ->
                        throw SourceFailure("Провайдер сообщил, что лимит устройств исчерпан.")
                    connection.flag("x-hwid-not-supported") ->
                        throw SourceFailure(if (sameOrigin) "Провайдер требует HWID. Проверьте настройку подписки."
                            else "Провайдер требует HWID на другом адресе. Добавьте конечную ссылку подписки.")
                    status in listOf(301, 302, 303, 307, 308) -> {
                        check(attempt < maxRedirects)
                        uri = subscriptionUri(uri.resolve(requireNotNull(connection.getHeaderField("Location"))).toASCIIString())
                    }
                    else -> {
                        if (status !in 200..299) throw SourceFailure("Провайдер вернул HTTP $status.")
                        check(connection.contentLength <= maxBytes)
                        return connection.inputStream.use { stream ->
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                check(SystemClock.elapsedRealtime() < deadline)
                                val count = stream.read(buffer)
                                if (count == -1) break
                                check(bytes.size() + count <= maxBytes)
                                bytes.write(buffer, 0, count)
                            }
                            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Subscription redirect limit")
    }

    @SuppressLint("HardwareIds")
    private fun deviceSeed(context: Context): String {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return synchronized(identityLock) {
            val prefs = context.getSharedPreferences("hwid", Context.MODE_PRIVATE)
            prefs.getString("seed", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("seed", it).commit())
            }
        }
    }

    private fun headerValue(value: String) = value.filter { it in ' '..'~' }.trim().take(80).ifEmpty { "Android" }

    private fun HttpsURLConnection.flag(name: String) = getHeaderField(name).equals("true", ignoreCase = true)
}
