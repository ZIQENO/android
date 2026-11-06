package dev.ziqeno.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.ziqeno.routing.ScoreProfile
import java.security.MessageDigest

internal data class LaunchableApp(val packageName: String, val label: String, val uid: Int, val isBrowser: Boolean = false) {
    val scope by lazy { "app-${routeDigest(packageName)}" }
}

internal enum class AppRouteMode(val titleRes: Int, val profile: ScoreProfile? = null) {
    AUTO(R.string.apps_route_auto),
    INTERACTIVE(R.string.apps_route_interactive, ScoreProfile.INTERACTIVE),
    STREAMING(R.string.apps_route_streaming, ScoreProfile.STREAMING);

    fun title(context: Context): String = context.getString(titleRes)

    val presetScope get() = "app-${name.lowercase()}"
}

internal data class TrackedApp(val app: LaunchableApp, val mode: AppRouteMode) {
    val scope get() = if (mode == AppRouteMode.AUTO) app.scope else mode.presetScope
}

internal fun routeDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).take(16)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

internal class AppTrackingStore(context: Context) {
    private val prefs = context.getSharedPreferences("app-routes", Context.MODE_PRIVATE)

    fun choices(): Map<String, AppRouteMode> = prefs.all.mapNotNull { (name, value) ->
        AppRouteMode.entries.firstOrNull { it.name == value }?.let { name to it }
    }.toMap()

    fun save(packageName: String, mode: AppRouteMode?) {
        val current = choices()
        require(mode == null || packageName in current || current.size < maxApps)
        prefs.edit().apply {
            if (mode == null) remove(packageName) else putString(packageName, mode.name)
        }.apply()
    }

    fun configured(context: Context): List<TrackedApp> {
        if (!supported) return emptyList()
        val choices = choices()
        if (choices.isEmpty()) return emptyList()
        return launchableApps(context).mapNotNull { app ->
            choices[app.packageName]?.let { TrackedApp(app, it) }
        }.distinctBy { it.app.uid }
    }

    companion object {
        const val maxApps = 24
        val supported get() = Build.VERSION.SDK_INT >= 29

        @Suppress("DEPRECATION")
        fun launchableApps(context: Context, includeBrowsers: Boolean = false): List<LaunchableApp> {
            val manager = context.packageManager
            val browsers = if (includeBrowsers) browserPackages(manager) else emptySet()
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            return manager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .mapNotNull { match ->
                    val info = match.activityInfo ?: return@mapNotNull null
                    val name = info.packageName
                    val uid = info.applicationInfo.uid
                    if (name == context.packageName) return@mapNotNull null
                    if (manager.getPackagesForUid(uid).orEmpty().any { it != name }) return@mapNotNull null
                    val label = match.loadLabel(manager).toString().filterNot(Char::isISOControl).take(80).ifBlank { name }
                    LaunchableApp(name, label, uid, name in browsers)
                }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }

        @Suppress("DEPRECATION")
        private fun browserPackages(manager: PackageManager): Set<String> = buildSet {
            val category = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            manager.queryIntentActivities(category, PackageManager.MATCH_ALL).mapNotNullTo(this) { it.activityInfo?.packageName }

            for (scheme in listOf("https", "http")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme:"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.GET_RESOLVED_FILTER
                manager.queryIntentActivities(intent, flags).mapNotNullTo(this) { match ->
                    // Android's browser role expects a handler with no host restriction.
                    match.activityInfo?.packageName?.takeIf { match.filter?.countDataAuthorities() == 0 }
                }
            }
        }
    }
}


