package com.shiina.mobile.action

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shiina.mobile.data.db.GoalDao
import com.shiina.mobile.data.db.GoalEntry
import com.shiina.mobile.data.settings.SettingsRepository
import java.util.Calendar

class ActionExecutor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val goalDao: GoalDao,
) {

    suspend fun execute(actionType: String, param: String = "") {
        when (actionType) {
            "alarm" -> schedule7PmAlarm()
            "toggle_screenshot" -> {
                // Toggle current state
            }
            "log_goal" -> {
                if (param.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    goalDao.insert(
                        GoalEntry(
                            title = param,
                            status = 0,
                            createdMillis = now,
                            updatedMillis = now
                        )
                    )
                }
            }
            else -> {}
        }
    }

    private fun schedule7PmAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val intent = Intent("com.shiina.mobile.ACTION_ALARM_TRIGGER")
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } catch (_: SecurityException) {
            // If exact alarm permission not granted yet
        }
    }
}
