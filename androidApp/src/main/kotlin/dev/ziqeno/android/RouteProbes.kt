package dev.ziqeno.android

import android.os.SystemClock
import dev.ziqeno.routing.MeasurementHistory
import dev.ziqeno.routing.NetworkKind
import dev.ziqeno.routing.ProbeSample
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class ProbeEndpoint(val id: String, val url: String, val status: Set<Int>)
internal data class ProbeJob(val node: String, val scope: String, val endpoint: ProbeEndpoint, val size: Int = 0)
internal data class AppProbeJob(val node: String, val scope: String, val target: AppTarget)

internal class RouteProbes(private val port: Int, private val password: String) : AutoCloseable {
    private val pool = Executors.newFixedThreadPool(4)
    private val quickPool = Executors.newFixedThreadPool(6)
    private val http = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val quickHttp = http.newBuilder().callTimeout(1_700, TimeUnit.MILLISECONDS).build()

    fun run(jobs: List<ProbeJob>, network: NetworkKind, parallelism: Int, valid: () -> Boolean): List<ProbeSample> =
        run(jobs, parallelism, pool, valid) { measure(it, network, http) }

    fun quick(jobs: List<ProbeJob>, network: NetworkKind, valid: () -> Boolean): List<ProbeSample> =
        run(jobs, 6, quickPool, valid) { measure(it, network, quickHttp) }

    fun apps(jobs: List<AppProbeJob>, network: NetworkKind, parallelism: Int, valid: () -> Boolean): List<ProbeSample> =
        run(jobs, parallelism, pool, valid) { handshake(it, network) }

    fun quickApps(jobs: List<AppProbeJob>, network: NetworkKind, valid: () -> Boolean): List<ProbeSample> =
        run(jobs, 6, quickPool, valid) { handshake(it, network) }

    fun firstReachable(nodes: List<String>, network: NetworkKind, valid: () -> Boolean): String? {
        val completed = ExecutorCompletionService<ProbeSample>(quickPool)
        val pending = nodes.map { node ->
            completed.submit(Callable {
                val first = measure(ProbeJob(node, XrayEngine.defaultRoute, global[0]), network, quickHttp)
                if (first.ttfbMs != null || !valid() || Thread.currentThread().isInterrupted) first
                else measure(ProbeJob(node, XrayEngine.defaultRoute, global[1]), network, quickHttp)
            })
        }
        try {
            var remaining = pending.size
            while (remaining > 0 && valid()) {
                val sample = completed.poll(100, TimeUnit.MILLISECONDS)?.get() ?: continue
                if (sample.ttfbMs != null && valid()) return sample.node
                remaining--
            }
            return null
        } finally {
            pending.forEach { it.cancel(true) }
        }
    }

    private fun <T> run(
        jobs: List<T>, parallelism: Int, executor: java.util.concurrent.ExecutorService, valid: () -> Boolean,
        measure: (T) -> ProbeSample,
    ): List<ProbeSample> {
        val results = mutableListOf<ProbeSample>()
        for (batch in jobs.chunked(parallelism)) {
            if (!valid()) return emptyList()
            results += executor.invokeAll(batch.map { job -> Callable { measure(job) } }).map { it.get() }
        }
        return results.takeIf { valid() }.orEmpty()
    }

    private fun handshake(job: AppProbeJob, network: NetworkKind): ProbeSample {
        val sample = ProbeSample(job.node, network, job.scope, job.target.historyKey, System.currentTimeMillis())
        val start = SystemClock.elapsedRealtimeNanos()
        return try {
            Socket().use { proxy ->
                proxy.connect(InetSocketAddress("127.0.0.1", port), 1_700)
                proxy.soTimeout = 1_700
                val authority = job.target.authority
                val auth = Credentials.basic(job.node, password)
                proxy.getOutputStream().write(
                    "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\nProxy-Authorization: $auth\r\n\r\n".encodeToByteArray(),
                )
                // Xray acknowledges CONNECT before dialing. Measure the remote protocol response.
                if (!readConnectResponse(proxy)) return sample
                if (job.target.protocol == AppProbeProtocol.MTPROTO) {
                    return if (TelegramProbe.handshake(proxy)) sample.copy(ttfbMs = elapsedMs(start)) else sample
                }
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(proxy, job.target.host, 443, true) as SSLSocket).use { tls ->
                    tls.soTimeout = 1_700
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        serverNames = listOf(SNIHostName(job.target.host))
                    }
                    tls.startHandshake()
                    sample.copy(ttfbMs = elapsedMs(start))
                }
            }
        } catch (_: IOException) {
            sample
        }
    }

    private fun readConnectResponse(proxy: Socket): Boolean {
        val input = proxy.getInputStream()
        val header = StringBuilder()
        repeat(2_048) {
            val byte = input.read()
            if (byte == -1) return false
            header.append(byte.toChar())
            if (header.endsWith("\r\n\r\n")) {
                return header.toString().substringBefore("\r\n").split(' ').getOrNull(1) == "200"
            }
        }
        return false
    }

    private fun measure(job: ProbeJob, network: NetworkKind, transportClient: OkHttpClient): ProbeSample {
        val observedAt = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtimeNanos()
        var transport: Double? = null
        val client = transportClient.newBuilder().proxyAuthenticator { _, response ->
            val request = response.request
            if (request.header("Proxy-Authorization") != null) null else request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(job.node, password)).build()
        }.eventListener(object : EventListener() {
            override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                transport = elapsedMs(started)
            }
        }).build()
        val request = Request.Builder().url(job.endpoint.url)
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .header("Connection", "close")
            .apply { if (job.size == 0) head() }.build()
        val failed = ProbeSample(job.node, network, job.scope, job.endpoint.id, observedAt)
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code !in job.endpoint.status) return failed
                val ttfb = elapsedMs(started)
                if (job.size == 0) return failed.copy(ttfbMs = ttfb, transportMs = transport)
                val body = response.body
                if (body.contentLength() > job.size) return failed
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                if (input.read() < 0) return failed
                val firstByte = SystemClock.elapsedRealtimeNanos()
                var bytes = 1
                while (bytes < job.size) {
                    val read = input.read(buffer, 0, minOf(buffer.size, job.size - bytes))
                    if (read < 0) break
                    bytes += read
                }
                val duration = elapsedMs(firstByte)
                val rate = if (bytes == job.size) (bytes - 1) * 8.0 / (maxOf(duration, 10.0) * 1000) else null
                failed.copy(ttfbMs = ttfb, transportMs = transport, downloadMbps = rate, bytes = bytes)
            }
        } catch (_: java.io.IOException) {
            failed
        }
    }

    fun cancelCalls() {
        http.dispatcher.cancelAll()
    }

    override fun close() {
        cancelCalls()
        pool.shutdownNow()
        quickPool.shutdownNow()
        http.connectionPool.evictAll()
    }

    private fun elapsedMs(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    companion object {
        val global = listOf(
            ProbeEndpoint("cloudflare-204", "https://cp.cloudflare.com/", setOf(204)),
            ProbeEndpoint("gstatic-204", "https://www.gstatic.com/generate_204", setOf(204)),
        )
        val pages = mapOf(
            "github" to ProbeEndpoint("github", "https://github.com/", setOf(200, 301, 302, 307, 308)),
            "youtube" to ProbeEndpoint("youtube", "https://www.youtube.com/", setOf(200, 301, 302, 303, 307, 308)),
        )

        fun speed(node: String, bytes: Int) = ProbeJob(
            node, MeasurementHistory.speedScope,
            ProbeEndpoint("cloudflare-download", "https://speed.cloudflare.com/__down?bytes=$bytes", setOf(200)),
            bytes,
        )
    }
}


