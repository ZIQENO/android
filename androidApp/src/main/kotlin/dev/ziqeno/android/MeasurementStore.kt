package dev.ziqeno.android

import android.content.Context
import android.util.AtomicFile
import dev.ziqeno.routing.MeasurementHistory
import dev.ziqeno.routing.NetworkKind
import dev.ziqeno.routing.ProbeSample
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class MeasurementStore(context: Context, manual: Boolean = false) {
    private val file = AtomicFile(File(context.noBackupFilesDir, if (manual) "server-checks-v1.json" else "measurements-v1.json"))

    fun load(): MeasurementHistory = runCatching {
        val bytes = file.locked { file.readFully() }
        val data = JSONObject(bytes.decodeToString())
        check(data.getInt("profileVersion") == MeasurementHistory.profileVersion)
        val samples = NetworkKind.entries.flatMap { kind ->
            val entries = data.optJSONArray(kind.name) ?: return@flatMap emptyList()
            (0 until entries.length()).mapNotNull { index ->
                val item = entries.getJSONObject(index)
                val id = item.getString("node")
                if (!id.matches(nodeId)) return@mapNotNull null
                ProbeSample(
                    id, kind, item.getString("scope"), item.getString("endpoint"), item.getLong("time"),
                    item.optDouble("ttfbMs").takeIf(Double::isFinite),
                    item.optDouble("transportMs").takeIf(Double::isFinite),
                    item.optDouble("downloadMbps").takeIf(Double::isFinite),
                    item.optInt("bytes"),
                )
            }
        }
        MeasurementHistory(samples)
    }.getOrElse { MeasurementHistory() }

    fun save(history: MeasurementHistory) {
        val output = json("profileVersion" to MeasurementHistory.profileVersion)
        val observations = history.samples().groupBy(ProbeSample::network)
        for ((network, entries) in observations) {
            val encoded = entries.map { sample ->
                json(
                    "node" to sample.node, "scope" to sample.scope, "endpoint" to sample.endpoint,
                    "time" to sample.time, "bytes" to sample.bytes,
                ).apply {
                    put("ttfbMs", sample.ttfbMs)
                    put("transportMs", sample.transportMs)
                    put("downloadMbps", sample.downloadMbps)
                }
            }
            output.put(network.name, JSONArray(encoded))
        }
        val bytes = output.toString().encodeToByteArray()
        file.locked {
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        }
    }

    private companion object {
        val nodeId = Regex("node-[0-9a-f]{32}")
    }
}

