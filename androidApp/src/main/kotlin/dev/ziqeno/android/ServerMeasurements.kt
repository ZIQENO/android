package dev.ziqeno.android

import dev.ziqeno.routing.MeasurementHistory
import dev.ziqeno.routing.NetworkKind
import dev.ziqeno.routing.ProbeSample

internal data class ServerMeasurements(val latencyMs: Double?, val downloadMbps: Double?)

internal fun latestServerMeasurements(samples: List<ProbeSample>, network: NetworkKind): Map<String, ServerMeasurements> {
    val latency = mutableMapOf<String, ProbeSample>()
    val speed = mutableMapOf<String, ProbeSample>()
    for (sample in samples) {
        if (sample.network != network) continue
        val series = when (sample.scope) {
            XrayEngine.defaultRoute -> latency
            MeasurementHistory.speedScope -> speed
            else -> continue
        }
        if (sample.time >= (series[sample.node]?.time ?: Long.MIN_VALUE)) series[sample.node] = sample
    }
    return (latency.keys + speed.keys).associateWith { id ->
        ServerMeasurements(latency[id]?.ttfbMs, speed[id]?.downloadMbps)
    }
}
