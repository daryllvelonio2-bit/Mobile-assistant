package com.shiina.mobile.decision

/** One reasoning backend. Adding a provider means implementing this + registering it. */
interface DecisionProvider {
    val name: String
    val supportsVision: Boolean
    suspend fun generate(
        summary: DecisionSummary,
        memoryContext: String = "",
        senses: String = "",
    ): Decision
}