package com.shiina.mobile.decision

/** Input to one decision round: today vs the rolling baseline. */
data class DecisionSummary(
    val entertainmentMinutes: Int,
    val entertainmentBaseline: Double,
    val sleepBedMillis: Long,
    val sleepBaselineMillis: Long,
    val goalsOpen: Int,
    val goalsDone: Int,
    val goalsMissed: Int,
)

/** Output of one decision round. */
data class Decision(
    val tone: String,
    val interrupt: Boolean,
    val action: String,
)
