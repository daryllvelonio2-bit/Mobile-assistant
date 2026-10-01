package com.shiina.mobile.action

import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shiina.mobile.MainActivity
import com.shiina.mobile.R
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.ui.permissions.openOverlaySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R5 — Quick Settings tile "Talk to Shiina": guaranteed one-tap summon of the companion.
 *
 * Tap: brings the app forward on the Chat tab and raises the overlay bubble (MainActivity is in
 * the foreground at that point, so it owns the foreground-service start). If the overlay
 * permission has not been granted we route straight to the system grant screen instead of
 * crashing. Long-press is handled by the system (app info/settings).
 *
 * No listeners are registered, so onStartListening/onStopListening only refresh the tile; the
 * service holds no static state and cancels its scope in onDestroy to stay leak-free.
 */
class ShiinaTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onTileAdded() {
        super.onTileAdded()
        AppDebugServer.log("TILE", "onTileAdded")
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        // Deliberately stateless: nothing registered here to unregister.
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        AppDebugServer.log("TILE", "onTileRemoved")
    }

    override fun onClick() {
        super.onClick()
        AppDebugServer.log("TILE", "onClick")
        // canDrawOverlays() crosses into the system process: keep it off the main thread.
        scope.launch {
            val granted = withContext(Dispatchers.IO) {
                runCatching { Settings.canDrawOverlays(this@ShiinaTileService) }.getOrDefault(false)
            }
            if (!granted) {
                AppDebugServer.log("TILE", "Overlay permission missing — routing to grant screen")
                runCatching { openOverlaySettings(this@ShiinaTileService) }
                    .onFailure { AppDebugServer.log("ERROR", "TILE openOverlaySettings failed: ${it.message}") }
                return@launch
            }
            runCatching {
                startActivity(
                    Intent(this@ShiinaTileService, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                        putExtra(MainActivity.EXTRA_SUMMON, true)
                    },
                )
            }.onFailure { AppDebugServer.log("ERROR", "TILE startActivity failed: ${it.message}") }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        runCatching {
            tile.label = getString(R.string.tile_shiina_label)
            tile.state = Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_shiina)
        }.onFailure { AppDebugServer.log("ERROR", "TILE refresh failed: ${it.message}") }
        runCatching { tile.updateTile() }
            .onFailure { AppDebugServer.log("ERROR", "TILE updateTile failed: ${it.message}") }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
