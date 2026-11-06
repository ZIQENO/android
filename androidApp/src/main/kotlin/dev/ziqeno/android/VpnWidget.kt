package dev.ziqeno.android

import android.app.Application
import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.View
import android.widget.RemoteViews

class ZiqenoApplication : Application() {
    private val mainProcess by lazy {
        val name = if (Build.VERSION.SDK_INT >= 28) getProcessName() else {
            getSystemService(ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        name == packageName
    }

    override fun onCreate() {
        super.onCreate()
        if (!mainProcess) return
        var previous: VpnPhase? = null
        VpnSession.observe { status ->
            if (status.phase != previous) {
                previous = status.phase
                VpnWidget.refresh(this, status.phase)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mainProcess) VpnWidget.refresh(this, VpnSession.status.phase)
    }
}

class VpnWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, manager, appWidgetIds, VpnSession.status.phase)
    }

    companion object {
        internal fun refresh(context: Context, phase: VpnPhase) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidget::class.java))
            update(context, manager, ids, phase)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray, phase: VpnPhase) {
            if (ids.isEmpty()) return
            val status = when (phase) {
                VpnPhase.OFF -> context.getString(R.string.vpn_widget_off)
                VpnPhase.CHECKING -> "Подключаем VPN"
                VpnPhase.CONNECTED -> "VPN подключён"
                VpnPhase.STOPPING -> "Отключаем VPN"
                VpnPhase.ERROR -> "Ошибка VPN"
            }
            val action = when (phase) {
                VpnPhase.CHECKING -> "Отменить"
                VpnPhase.CONNECTED -> "Отключить"
                VpnPhase.STOPPING -> null
                else -> context.getString(R.string.vpn_widget_connect)
            }
            val click = if (action == null) null else {
                val disconnecting = phase == VpnPhase.CONNECTED || phase == VpnPhase.CHECKING
                val intent = Intent(context, TileConnectActivity::class.java)
                    .setAction(if (disconnecting) RouterVpnService.disconnect else RouterVpnService.connect)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            }
            val views = RemoteViews(context.packageName, R.layout.vpn_widget).apply {
                applyPalette(context)
                setTextViewText(R.id.widget_status, status)
                setTextViewText(R.id.widget_action, action.orEmpty())
                setViewVisibility(R.id.widget_action, if (action == null) View.GONE else View.VISIBLE)
                setContentDescription(android.R.id.background, listOfNotNull(status, action).joinToString(". "))
                setOnClickPendingIntent(android.R.id.background, click)
            }
            runCatching { manager.updateAppWidget(ids, views) }
                .onFailure { Log.w("ZiqenoWidget", "Widget update failed", it) }
        }

        private fun RemoteViews.applyPalette(context: Context) {
            val theme = context.getSharedPreferences("display", Context.MODE_PRIVATE).getString("theme", "system")
            if (Build.VERSION.SDK_INT >= 31 && theme == "system") {
                // The launcher selects the palette when the app process is in the background.
                val day = PixelUi(context, false)
                val night = PixelUi(context, true)
                setColorInt(android.R.id.background, "setBackgroundColor", day.paper, night.paper)
                setColorInt(R.id.widget_icon, "setColorFilter", day.accent, night.accent)
                setColorInt(R.id.widget_status, "setTextColor", day.ink, night.ink)
                setColorInt(R.id.widget_action, "setTextColor", day.accent, night.accent)
            } else {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
                val ui = PixelUi(context, theme == "dark" || theme == "system" && night)
                setInt(android.R.id.background, "setBackgroundColor", ui.paper)
                setInt(R.id.widget_icon, "setColorFilter", ui.accent)
                setTextColor(R.id.widget_status, ui.ink)
                setTextColor(R.id.widget_action, ui.accent)
            }
        }
    }
}
