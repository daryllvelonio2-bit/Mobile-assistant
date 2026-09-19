package com.shiina.mobile.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Deferrable maintenance (baseline recalculation lands in Phase 2). */
class BaselineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = Result.success()
}
