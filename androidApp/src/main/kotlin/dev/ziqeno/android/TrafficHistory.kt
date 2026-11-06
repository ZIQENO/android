package dev.ziqeno.android

import android.content.Context
import android.util.AtomicFile
import java.io.File

internal const val trafficWindowMillis = 600_000L
internal const val trafficIntervalMillis = 2_000L
internal const val trafficGapMillis = 6_000L

internal data class TunnelBytes(val received: Long, val sent: Long, val directReceived: Long = 0, val directSent: Long = 0)
internal data class TrafficPoint(val at: Long, val download: Double, val upload: Double)

internal class TrafficSampler {
    private data class Reading(val elapsed: Long, val at: Long, val bytes: TunnelBytes)

    private var previous: Reading? = null

    fun record(elapsed: Long, at: Long, bytes: TunnelBytes): TrafficPoint? {
        val last = previous
        previous = Reading(elapsed, at, bytes)
        if (last == null) return null
        val duration = elapsed - last.elapsed
        val wallDuration = at - last.at
        if (duration !in 1..trafficGapMillis || wallDuration !in 1..trafficGapMillis) return null
        if (bytes.received < last.bytes.received || bytes.sent < last.bytes.sent ||
            bytes.directReceived < last.bytes.directReceived || bytes.directSent < last.bytes.directSent) return null
        val received = (bytes.received - last.bytes.received - (bytes.directReceived - last.bytes.directReceived)).coerceAtLeast(0)
        val sent = (bytes.sent - last.bytes.sent - (bytes.directSent - last.bytes.directSent)).coerceAtLeast(0)
        return TrafficPoint(
            at,
            received * 8.0 / (duration * 1000),
            sent * 8.0 / (duration * 1000),
        )
    }
}

internal class TrafficWindow {
    private val points = ArrayDeque<TrafficPoint>()

    fun add(point: TrafficPoint) {
        if (points.lastOrNull()?.let { it.at >= point.at } == true) points.clear()
        points.addLast(point)
        prune(point.at)
    }

    fun recent(now: Long): List<TrafficPoint> {
        prune(now)
        return points.toList()
    }

    private fun prune(now: Long) {
        while (points.lastOrNull()?.let { it.at > now } == true) points.removeLast()
        while (points.firstOrNull()?.let { now - it.at >= trafficWindowMillis } == true) points.removeFirst()
    }
}

internal object TrafficHistory {
    private val window = TrafficWindow()
    private var file: AtomicFile? = null
    private var savedAt: Long = 0
    @Volatile var sessionStartedAt: Long = Long.MAX_VALUE
        private set

    @Synchronized
    fun load(context: Context) {
        if (file != null) return
        val stored = AtomicFile(File(context.noBackupFilesDir, "vpn-traffic-v1.tsv"))
        val now = System.currentTimeMillis()
        runCatching {
            stored.openRead().bufferedReader().useLines { lines ->
                lines.take(400).forEach { row ->
                    val parts = row.split('\t')
                    if (parts.size != 3) return@forEach
                    val at = parts[0].toLongOrNull() ?: return@forEach
                    val down = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@forEach
                    val up = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@forEach
                    if (at in (now - trafficWindowMillis)..now) window.add(TrafficPoint(at, down, up))
                }
            }
        }
        file = stored
    }

    fun startSession(at: Long) {
        sessionStartedAt = at
    }

    fun add(point: TrafficPoint, elapsed: Long) {
        val due = synchronized(this) {
            window.add(point)
            elapsed - savedAt >= 15_000
        }
        if (due) save(elapsed)
    }

    @Synchronized
    fun recent(now: Long) = window.recent(now)

    fun save(elapsed: Long) {
        val (stored, snapshot) = synchronized(this) {
            val ready = file ?: return
            savedAt = elapsed
            ready to window.recent(System.currentTimeMillis())
        }
        val stream = runCatching { stored.startWrite() }.getOrNull() ?: return
        try {
            stream.write(snapshot.joinToString("\n") { "${it.at}\t${it.download}\t${it.upload}" }.encodeToByteArray())
            stored.finishWrite(stream)
        } catch (_: java.io.IOException) {
            stored.failWrite(stream)
        }
    }
}
