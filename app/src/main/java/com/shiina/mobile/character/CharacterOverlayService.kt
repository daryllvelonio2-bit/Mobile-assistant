package com.shiina.mobile.character

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.shiina.mobile.decision.Decision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Overlay bubble driven by decision output. Drag to move; WANDER drifts on its own. */
class CharacterOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var tone by mutableStateOf("calm")
    private var mode: CharacterMode = CharacterMode.STAY
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wanderJob: Job? = null
    private var overlayOwner: OverlayLifecycleOwner? = null

    /** Retained Lifecycle/ViewModelStore/SavedState owner for the WindowManager-hosted ComposeView. */
    private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun attach() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            runCatching { registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
            runCatching { registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
            runCatching { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            runCatching { store.clear() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Character", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        com.shiina.mobile.debug.AppDebugServer.log("SERVICE", "onStartCommand action=${intent?.action} tone=${intent?.getStringExtra(EXTRA_TONE)}")
        runCatching {
            startForeground(NOTIFICATION_ID, notification())
        }.onFailure { e ->
            com.shiina.mobile.debug.AppDebugServer.log("ERROR", "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            when (intent?.action) {
                ACTION_HIDE -> removeBubble()
                else -> {
                    tone = intent?.getStringExtra(EXTRA_TONE) ?: tone
                    val requested = intent?.getStringExtra(EXTRA_MODE)
                        ?.let { runCatching { CharacterMode.valueOf(it) }.getOrNull() }
                    if (requested != null) {
                        applyMode(requested)
                    } else {
                        val decision = Decision(
                            tone = tone,
                            interrupt = intent?.getBooleanExtra(EXTRA_INTERRUPT, false) ?: false,
                            action = intent?.getStringExtra(EXTRA_ACTION) ?: "none",
                        )
                        applyMode(CharacterController.modeFor(decision))
                    }
                }
            }
        }.onFailure { e ->
            com.shiina.mobile.debug.AppDebugServer.log("ERROR", "onStartCommand body failed: ${e.message}")
            removeBubble()
            stopSelf()
        }
        return START_STICKY
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("Shiina character")
        .setContentText("Overlay active")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureBubble() {
        if (bubble != null) {
            com.shiina.mobile.debug.AppDebugServer.log("SERVICE", "ensureBubble: bubble already exists")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            com.shiina.mobile.debug.AppDebugServer.log("ERROR", "ensureBubble: Overlay permission (SYSTEM_ALERT_WINDOW) not granted!")
            stopSelf()
            return
        }
        val wm = getSystemService(WindowManager::class.java) ?: run {
            com.shiina.mobile.debug.AppDebugServer.log("ERROR", "ensureBubble: WindowManager is null")
            return
        }
        windowManager = wm
        val owner = OverlayLifecycleOwner()
        owner.attach()
        overlayOwner = owner
        val view = ComposeView(this)
        view.setTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner, owner)
        view.setTag(androidx.lifecycle.viewmodel.R.id.view_tree_view_model_store_owner, owner)
        view.setTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner, owner)
        bubble = view
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 300
        }
        view.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                params?.let { p ->
                                    p.x += drag.x.toInt()
                                    p.y += drag.y.toInt()
                                    windowManager?.updateViewLayout(view, p)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tone.take(4),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        runCatching { wm.addView(view, params) }.onFailure { e ->
            com.shiina.mobile.debug.AppDebugServer.log("ERROR", "wm.addView failed: ${e.message}\n${e.stackTraceToString()}")
            overlayOwner?.destroy()
            overlayOwner = null
            bubble = null
            windowManager = null
            params = null
            stopSelf()
        }.onSuccess {
            com.shiina.mobile.debug.AppDebugServer.log("SERVICE", "wm.addView SUCCESS! Overlay bubble rendered on screen.")
            overlayOwner?.resume()
        }
    }

    private fun applyMode(next: CharacterMode) {
        mode = next
        when (next) {
            CharacterMode.VANISH -> removeBubble()
            CharacterMode.STAY -> {
                wanderJob?.cancel()
                ensureBubble()
            }
            CharacterMode.WANDER -> {
                ensureBubble()
                startWander()
            }
        }
    }

    private fun startWander() {
        wanderJob?.cancel()
        wanderJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(4_000)
                val wm = windowManager ?: continue
                val view = bubble ?: continue
                val p = params ?: continue
                val (maxX, maxY) = displaySize()
                val (dx, dy) = CharacterController.wanderOffset(maxX, maxY)
                p.x = dx
                p.y = dy + 100
                runCatching { wm.updateViewLayout(view, p) }
            }
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val wm = windowManager ?: return 0 to 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return (bounds.width() - 200).coerceAtLeast(0) to
                (bounds.height() - 400).coerceAtLeast(0)
        }
        @Suppress("DEPRECATION")
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getSize(size)
        return (size.x - 200).coerceAtLeast(0) to (size.y - 400).coerceAtLeast(0)
    }

    private fun removeBubble() {
        wanderJob?.cancel()
        runCatching {
            val wm = windowManager
            val view = bubble
            if (wm != null && view != null) wm.removeView(view)
        }
        runCatching { overlayOwner?.destroy() }
        overlayOwner = null
        bubble = null
        windowManager = null
        params = null
    }

    override fun onDestroy() {
        removeBubble()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SHOW = "com.shiina.mobile.character.SHOW"
        const val ACTION_HIDE = "com.shiina.mobile.character.HIDE"
        const val EXTRA_TONE = "tone"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INTERRUPT = "interrupt"
        const val EXTRA_ACTION = "action"
        private const val CHANNEL = "character"
        private const val NOTIFICATION_ID = 2
    }
}
