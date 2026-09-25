package com.shiina.mobile

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.debug.DebugTalkService
import com.shiina.mobile.di.AppContainer
import com.shiina.mobile.work.BaselineWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CompanionApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppDebugServer.start()
        AppDebugServer.log("SYSTEM", "CompanionApp onCreate started")
        container = AppContainer(this)
        AppDebugServer.log("SYSTEM", "AppContainer initialized successfully")
        runCatching { container.musicTracker.start() }
        runCatching { DebugTalkService.start(this) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.actionExecutor.execute("alarm") }
                .onFailure { e ->
                    AppDebugServer.log("ERROR", "Daily alarm schedule failed: ${e.message}")
                }
            runCatching { com.shiina.mobile.action.ProactiveLoop.restorePending(this@CompanionApp) }
                .onFailure { e ->
                    AppDebugServer.log("ERROR", "Proactive heartbeat schedule failed: ${e.message}")
                }
        }
        scheduleNightlyReflection()
    }

    /** Charger-idle nightly pass: memory adaptation, pruning, bedtime learning. */
    private fun scheduleNightlyReflection() {
        runCatching {
            val work = PeriodicWorkRequestBuilder<BaselineWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build(),
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "nightly_reflection",
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "NightlyReflection schedule failed: ${e.message}")
        }
    }
}
