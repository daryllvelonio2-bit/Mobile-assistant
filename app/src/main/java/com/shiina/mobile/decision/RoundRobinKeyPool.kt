package com.shiina.mobile.decision

import java.util.concurrent.atomic.AtomicInteger

/** Round-robin over one provider's keys. Rotates on every failure. Thread-safe. */
class RoundRobinKeyPool(private val loadKeys: () -> List<String>) {

    private val cursor = AtomicInteger(0)
    private val cooldownUntil = mutableMapOf<String, Long>()
    private val lock = Any()

    fun next(): String? {
        val keys = loadKeys().ifEmpty { return null }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            repeat(keys.size) {
                val key = keys[cursor.getAndIncrement().mod(keys.size)]
                if ((cooldownUntil[key] ?: 0L) <= now) return key
            }
            return null
        }
    }

    fun reportFailure(key: String, cooldownMillis: Long = 60_000) {
        synchronized(lock) { cooldownUntil[key] = System.currentTimeMillis() + cooldownMillis }
    }
}
