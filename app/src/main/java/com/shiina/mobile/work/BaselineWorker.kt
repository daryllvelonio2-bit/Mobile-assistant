package com.shiina.mobile.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.debug.AppDebugServer

/**
 * Nightly maintenance: runs memory/NightlyReflection once a day (charger-idle).
 * Audit C12: worker success/failure feeds ToolStat("nightly_reflection") so
 * nightly health shows up beside the Talk-loop tools.
 */
class BaselineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as CompanionApp).container
        return runCatching {
            container.nightlyReflection.run()
            runCatching { container.toolTracker.record("nightly_reflection", true) }
            Result.success()
        }.getOrElse { e ->
            AppDebugServer.log("ERROR", "NightlyReflection failed: ${e.message}")
            runCatching { container.toolTracker.record("nightly_reflection", false) }
            Result.retry()
        }
    }
}