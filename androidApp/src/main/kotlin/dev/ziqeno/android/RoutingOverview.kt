package dev.ziqeno.android

import android.content.Context
import dev.ziqeno.routing.NetworkKind

internal data class AppAssignment(
    val mode: AppRouteMode,
    val scope: String,
    val server: String?,
    val host: String? = null,
    val reachable: Boolean? = null,
    val fresh: Boolean = false,
    val protocol: AppProbeProtocol? = null,
)
internal data class RouteRow(val title: String, val server: String)
internal enum class RouteReason(val labelRes: Int) {
    CONNECTED(R.string.routing_reason_connected),
    QUALITY(R.string.routing_reason_quality),
    FAILURE(R.string.routing_reason_failure),
    SERVICE_FAILURE(R.string.routing_reason_service_failure),
    DOMAIN_FAILURE(R.string.routing_reason_domain_failure),
}

internal data class RouteChange(
    val at: Long,
    val scope: String,
    val route: String,
    val network: NetworkKind,
    val previous: String?,
    val next: String,
    val reason: RouteReason,
) {
    fun displayRoute(context: Context): String = when (scope) {
        XrayEngine.defaultRoute -> context.getString(R.string.routing_default_route)
        AppRouteMode.INTERACTIVE.presetScope -> AppRouteMode.INTERACTIVE.title(context)
        AppRouteMode.STREAMING.presetScope -> AppRouteMode.STREAMING.title(context)
        else -> route.ifEmpty { context.getString(R.string.routing_all_connections) }
    }
}

internal data class RoutingOverview(
    val network: NetworkKind,
    val routes: List<RouteRow>,
    val backups: List<String>,
    val apps: Map<String, AppAssignment>,
    val changes: List<RouteChange>,
    val domainChecks: Map<String, DomainCheck> = emptyMap(),
    val domainRules: DomainRules? = null,
)

