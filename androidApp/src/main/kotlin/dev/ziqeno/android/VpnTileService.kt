package dev.ziqeno.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.Executors

class VpnTileService : TileService() {
    private val main = Handler(Looper.getMainLooper())
    private val observer: (VpnStatus) -> Unit = ::update
    private var destroyed = false

    override fun onStartListening() {
        super.onStartListening()
        VpnSession.observe(observer)
    }

    override fun onStopListening() {
        VpnSession.remove(observer)
        super.onStopListening()
    }

    override fun onDestroy() {
        destroyed = true
        VpnSession.remove(observer)
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::toggle) else toggle()
    }

    private fun toggle() {
        when (VpnSession.status.phase) {
            VpnPhase.CHECKING, VpnPhase.CONNECTED -> startService(vpnIntent(this, RouterVpnService.disconnect))
            VpnPhase.OFF, VpnPhase.ERROR -> {
                if (VpnService.prepare(this) != null) {
                    openSetup()
                    return
                }
                io.execute {
                    val hasSources = hasVpnTargets(applicationContext)
                    main.post {
                        if (destroyed || VpnSession.status.phase !in listOf(VpnPhase.OFF, VpnPhase.ERROR)) return@post
                        if (!hasSources) openSetup()
                        else runCatching { startForegroundService(vpnIntent(this, RouterVpnService.connect)) }
                            .onFailure { openSetup() }
                    }
                }
            }
            VpnPhase.STOPPING -> Unit
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSetup() {
        val intent = Intent(this, TileConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(intent)
    }

    private fun update(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.state = when (status.phase) {
            VpnPhase.CONNECTED, VpnPhase.CHECKING -> Tile.STATE_ACTIVE
            VpnPhase.STOPPING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (status.phase) {
                VpnPhase.CONNECTED -> getString(R.string.tile_connected)
                VpnPhase.CHECKING -> getString(R.string.vpn_state_connecting_short)
                VpnPhase.STOPPING -> getString(R.string.vpn_state_stopping_short)
                VpnPhase.ERROR -> getString(R.string.tile_error)
                VpnPhase.OFF -> getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }

    companion object {
        internal val io = Executors.newSingleThreadExecutor()
    }
}

class TileConnectActivity : Activity() {
    private var awaitingConsent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingConsent = savedInstanceState?.getBoolean("awaitingConsent") ?: false
        val fromWidget = intent.action != null
        if (intent.action == RouterVpnService.disconnect) {
            if (VpnSession.status.phase in listOf(VpnPhase.CONNECTED, VpnPhase.CHECKING)) {
                startService(vpnIntent(this, RouterVpnService.disconnect))
            }
            finish()
            return
        }
        if (!fromWidget) {
            val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val ui = PixelUi(this, dark)
            setContentView(ui.text(getString(R.string.vpn_permission_title), 22).apply {
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(ui.paper)
            })
        }
        if (awaitingConsent) return
        VpnTileService.io.execute {
            val hasSources = hasVpnTargets(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (VpnSession.status.phase !in listOf(VpnPhase.OFF, VpnPhase.ERROR)) {
                    finish()
                } else if (!hasSources) {
                    val destination = if (fromWidget) MainActivity::class.java else SourcesActivity::class.java
                    val setup = Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (fromWidget) setup.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(setup)
                    finish()
                } else {
                    val consent = VpnService.prepare(this)
                    if (consent == null) connect() else {
                        awaitingConsent = true
                        startActivityForResult(consent, requestVpn)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("awaitingConsent", awaitingConsent)
    }

    @Deprecated("Uses the platform VPN consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestVpn && resultCode == RESULT_OK) connect() else finish()
    }

    private fun connect() {
        if (VpnSession.status.phase == VpnPhase.OFF || VpnSession.status.phase == VpnPhase.ERROR) {
            startForegroundService(vpnIntent(this, RouterVpnService.connect))
        }
        finish()
    }

    companion object {
        private const val requestVpn = 44
    }
}

private fun vpnIntent(context: Context, action: String) = Intent(context, RouterVpnService::class.java).setAction(action)

private fun hasVpnTargets(context: Context) = DomainRuleStore(context).load().active.isNotEmpty() ||
    runCatching { SourceStore(context).load().isNotEmpty() }.getOrDefault(false)
