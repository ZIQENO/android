package dev.ziqeno.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import dev.ziqeno.routing.MeasurementHistory
import dev.ziqeno.routing.NetworkKind
import dev.ziqeno.routing.ProbeSample
import java.net.DatagramSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SubscriptionCheckService : VpnService() {
    private class CheckRun(val reply: Messenger) {
        val active = AtomicBoolean(true)
        val probes = AtomicReference<RouteProbes?>()

        fun cancel() {
            active.set(false)
            probes.get()?.cancelCalls()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var running: CheckRun? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.sendingUid == Process.myUid()) {
            val reply = request.replyTo
            when (request.what) {
                startCheck -> request.data.getString("source")?.let { if (reply != null) start(it, reply) }
                cancelCheck -> if (running?.reply == reply && reply != null) cancel()
            }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        cancel()
        return false
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun cancel() {
        running?.cancel()
        running = null
    }

    private fun start(source: String, reply: Messenger) {
        if (running != null) {
            reply.deliver(checkFailed, message = "Уже выполняется другая проверка.")
            return
        }
        val run = CheckRun(reply)
        running = run
        worker.execute {
            val result = runCatching { checkServers(source, run) }
            main.post {
                if (running !== run) return@post
                running = null
                if (!run.active.get()) return@post
                val failure = result.exceptionOrNull()
                reply.deliver(if (failure == null) checkFinished else checkFailed,
                    message = (failure as? SourceFailure)?.message ?: "Не удалось завершить проверку.")
            }
        }
    }

    private fun checkServers(id: String, run: CheckRun) {
        if (!run.active.get()) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val link = connectivity.linkContext() ?: throw SourceFailure("Нет сети для проверки.")
        val tunnel = link.vpn
        if (tunnel && !DatagramSocket().use { protect(it) }) {
            throw SourceFailure("Текущий VPN не разрешает отдельную проверку.")
        }
        val valid = { run.active.get() && connectivity.linkContext() == link }
        val body = SourceStore(this).load().firstOrNull { it.id == id && it.kind == SourceKind.SUBSCRIPTION }?.cachedBody
            ?: throw SourceFailure("Сначала обновите список узлов подписки.")
        val nodes = XrayEngine.decodeNodes(body, all = true)
        val store = MeasurementStore(this, manual = true)
        val history = store.load()
        val metered = tunnel || link.metered || link.kind != NetworkKind.WIFI
        val size = if (metered) 65_536 else 262_144
        var checked = 0
        for (batch in nodes.chunked(12)) {
            if (!valid()) break
            val supported = XrayEngine.validated(batch, valid)
            val measured = if (supported.isEmpty() || !valid()) emptyList() else {
                XrayEngine.withBenchmarks(supported, { fd -> valid() && (!tunnel || protect(fd)) }, tunnel) { probes ->
                    run.probes.set(probes)
                    try {
                        measure(probes, supported, link.kind, size, if (metered) 1 else 2, valid)
                    } finally {
                        run.probes.set(null)
                    }
                }
            }
            if (!valid()) break
            val time = System.currentTimeMillis()
            val byNode = measured.groupBy(ProbeSample::node)
            val samples = batch.flatMap { node ->
                val readings = byNode[node.id].orEmpty()
                listOf(XrayEngine.defaultRoute, MeasurementHistory.speedScope).map { scope ->
                    readings.firstOrNull { it.scope == scope } ?: ProbeSample(node.id, link.kind, scope, "manual", time)
                }
            }
            history.record(samples, time)
            store.save(history)
            checked += batch.size
            run.reply.deliver(checkProgress, checked, nodes.size)
        }
        if (run.active.get() && !valid()) throw SourceFailure("Сеть изменилась. Запустите проверку ещё раз.")
    }

    private fun measure(
        probes: RouteProbes, nodes: List<XrayNode>, network: NetworkKind, size: Int, parallelism: Int, valid: () -> Boolean,
    ): List<ProbeSample> {
        val primary = probes.quick(nodes.map { ProbeJob(it.id, XrayEngine.defaultRoute, RouteProbes.global[0]) }, network, valid)
        val retry = probes.quick(primary.filter { it.ttfbMs == null }.map {
            ProbeJob(it.node, XrayEngine.defaultRoute, RouteProbes.global[1])
        }, network, valid)
        val latency = (retry + primary).distinctBy(ProbeSample::node)
        val speed = probes.run(latency.filter { it.ttfbMs != null }.map { RouteProbes.speed(it.node, size) }, network, parallelism, valid)
        return latency + speed
    }

    private companion object {
        val worker = Executors.newSingleThreadExecutor()
    }
}

internal data class ServerCheckProgress(val checked: Int = 0, val total: Int = 0, val finished: Boolean = false, val error: String? = null)

internal class SubscriptionCheck(
    private val context: Context, private val source: String, private val update: (ServerCheckProgress) -> Unit,
) : ServiceConnection, AutoCloseable {
    private var bound = false
    private var remote: Messenger? = null
    private val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
        if (bound) when (response.what) {
            checkProgress -> update(ServerCheckProgress(response.arg1, response.arg2))
            checkFinished -> finish()
            checkFailed -> finish(response.data.getString("message") ?: genericError)
        }
        true
    })

    fun start(): Boolean {
        bound = runCatching {
            context.bindService(Intent(context, SubscriptionCheckService::class.java), this, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        return bound
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        if (!bound) return
        val server = Messenger(binder)
        remote = server
        val request = Message.obtain(null, startCheck).apply {
            replyTo = reply
            data = Bundle().apply { putString("source", source) }
        }
        try {
            server.send(request)
        } catch (_: RemoteException) {
            finish(genericError)
        }
    }

    override fun onServiceDisconnected(name: ComponentName) = finish(genericError)
    override fun onNullBinding(name: ComponentName) = finish(genericError)
    override fun onBindingDied(name: ComponentName) = finish(genericError)

    private fun finish(error: String? = null) {
        if (!bound) return
        close()
        update(ServerCheckProgress(finished = true, error = error))
    }

    override fun close() {
        if (!bound) return
        bound = false
        try {
            remote?.send(Message.obtain(null, cancelCheck).apply { replyTo = reply })
        } catch (_: RemoteException) {
            // The service process already went away.
        }
        remote = null
        context.unbindService(this)
    }
}

private fun Messenger.deliver(what: Int, checked: Int = 0, total: Int = 0, message: String? = null) {
    val response = Message.obtain(null, what, checked, total)
    if (message != null) response.data = Bundle().apply { putString("message", message) }
    try {
        send(response)
    } catch (_: RemoteException) {
        // Android unbinds the service when its client goes away.
    }
}

private const val startCheck = 1
private const val checkProgress = 2
private const val checkFinished = 3
private const val checkFailed = 4
private const val cancelCheck = 5
private const val genericError = "Не удалось запустить проверку."
