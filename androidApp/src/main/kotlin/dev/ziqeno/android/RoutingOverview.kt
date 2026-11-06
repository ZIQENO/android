package dev.ziqeno.android

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
internal enum class RouteReason(val title: String) {
    CONNECTED("Первый выбор"),
    QUALITY("Лучший результат"),
    FAILURE("Узел перестал отвечать"),
    SERVICE_FAILURE("Не прошла проверка приложения"),
    DOMAIN_FAILURE("Не прошла проверка домена"),
}

internal data class RouteChange(
    val at: Long,
    val scope: String,
    val route: String,
    val network: NetworkKind,
    val previous: String?,
    val next: String,
    val reason: RouteReason,
)

internal data class RoutingOverview(
    val network: NetworkKind,
    val routes: List<RouteRow>,
    val backups: List<String>,
    val apps: Map<String, AppAssignment>,
    val changes: List<RouteChange>,
    val domainChecks: Map<String, DomainCheck> = emptyMap(),
    val domainRules: DomainRules? = null,
)

