package dev.ziqeno.android

import android.content.Context
import dev.ziqeno.routing.NetworkKind
import org.json.JSONArray
import org.json.JSONObject

internal class RouteLog(context: Context) {
    private val prefs = context.getSharedPreferences("route-changes", Context.MODE_PRIVATE)

    fun read(): List<RouteChange> {
        val encoded = runCatching { JSONArray(prefs.getString("events", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until minOf(encoded.length(), limit)).mapNotNull { index ->
            runCatching {
                val item = encoded.getJSONObject(index)
                RouteChange(
                    item.getLong("at"), item.getString("scope"), item.getString("route"),
                    NetworkKind.valueOf(item.getString("network")), if (item.isNull("previous")) null else item.getString("previous"),
                    item.getString("next"), RouteReason.valueOf(item.getString("reason")),
                )
            }.getOrNull()
        }
    }

    fun write(changes: List<RouteChange>) {
        val encoded = JSONArray(changes.take(limit).map { change ->
            json(
                "at" to change.at, "scope" to change.scope, "route" to change.route, "network" to change.network.name,
                "next" to change.next, "reason" to change.reason.name,
            ).apply { put("previous", change.previous ?: JSONObject.NULL) }
        })
        prefs.edit().putString("events", encoded.toString()).apply()
    }

    companion object {
        const val limit = 100
    }
}
