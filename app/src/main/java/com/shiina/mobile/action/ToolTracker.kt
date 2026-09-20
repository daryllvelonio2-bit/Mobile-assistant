package com.shiina.mobile.action

import com.shiina.mobile.data.db.ToolStat
import com.shiina.mobile.data.db.ToolStatDao

/**
 * Track C4 — records every tool attempt and outcome. NightlyReflection
 * reports weekly success rates, so the loop gets smarter without code
 * changes: failing tools surface in memory and she stops trusting them.
 */
class ToolTracker(private val dao: ToolStatDao) {

    suspend fun record(tool: String, success: Boolean) {
        runCatching {
            val cur = dao.get(tool)
            dao.upsert(
                ToolStat(
                    tool = tool,
                    attempts = (cur?.attempts ?: 0) + 1,
                    successes = (cur?.successes ?: 0) + if (success) 1 else 0,
                    updatedMillis = System.currentTimeMillis(),
                ),
            )
        }
    }
}