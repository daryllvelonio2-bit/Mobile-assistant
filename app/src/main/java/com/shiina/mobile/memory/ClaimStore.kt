package com.shiina.mobile.memory

import android.content.Context
import java.io.File

/**
 * Claim log store: tiny on-device record for granted appeals (pass + log claim).
 * Persisted as plain JSON with manual escaping, 24h expiry.
 * Single file: {claims: [{timestampMs, appLabel, claimText, postClaimMin}, ...]}
 */
class ClaimStore(private val file: File) {

    /**
     * Secondary constructor taking Context resolves filesDir/claim_log.json.
     */
    constructor(context: Context) : this(File(context.filesDir, FILENAME))

    companion object {
        /** Claim record expires after 24 hours. */
        const val CLAIM_TTL_HOURS: Long = 24

        /** Default filename in app's private files directory. */
        private const val FILENAME = "claim_log.json"

        /**
         * Build JSON array string from list of ClaimRecord.
         * Manual escaping for safety (no external deps).
         */
        private fun buildJsonString(records: List<ClaimRecord>): String {
            val sb = StringBuilder()
            sb.append("{\"claims\":[")
            records.forEachIndexed { index, record ->
                if (index > 0) sb.append(",")
                sb.append("{\"timestampMs\":")
                sb.append(record.timestampMs)
                sb.append(",\"appLabel\":")
                sb.append('"')
                sb.append(escapeJsonString(record.appLabel))
                sb.append('"')
                sb.append(",\"claimText\":")
                sb.append('"')
                sb.append(escapeJsonString(record.claimText))
                sb.append('"')
                sb.append(",\"postClaimMin\":")
                sb.append(record.postClaimMin)
                sb.append("}")
            }
            sb.append("]}")
            return sb.toString()
        }

        /**
         * Simple JSON string escaping: backslash, quotes, control chars.
         */
        private fun escapeJsonString(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> {
                        if (c.code < 0x20) {
                            // Control chars: \uXXXX
                            sb.append(String.format("\\u%04x", c.code))
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            return sb.toString()
        }

        /**
         * Parse JSON string into list of ClaimRecord.
         * Returns empty list if parsing fails or file is empty/missing.
         */
        private fun parseJsonString(json: String): List<ClaimRecord> {
            return try {
                parseClaimsJson(json)
            } catch (e: Exception) {
                emptyList()
            }
        }

        /**
         * Low-level JSON parsing: extract claims array and each object's fields.
         * Handles minimal valid JSON only (no fancy features).
         */
        private fun parseClaimsJson(json: String): List<ClaimRecord> {
            val records = mutableListOf<ClaimRecord>()

            // Strip whitespace for easier parsing
            val trimmed = json.trim()
            if (trimmed.isEmpty()) return records

            // Expect top-level object: {"claims":[...]}
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return records

            // Find "claims" key and extract the array content
            val claimsKey = "\"claims\""
            val claimsIndex = trimmed.indexOf(claimsKey)
            if (claimsIndex == -1) return records

            // Find the opening bracket after "claims":
            val afterKey = trimmed.substring(claimsIndex + claimsKey.length).trim()
            if (!afterKey.startsWith(":")) return records
            val afterColon = afterKey.substring(1).trim()
            if (!afterColon.startsWith("[")) return records

            // Extract content inside the array brackets
            val arrayContent = extractArrayContent(afterColon)
            if (arrayContent.isEmpty()) return records

            // Split by objects: each { ... } is an object
            val objects = splitIntoObjects(arrayContent)
            for (obj in objects) {
                val record = parseClaimObject(obj)
                if (record != null) {
                    records.add(record)
                }
            }

            return records
        }

        /**
         * Extract content between [ and ] (handles nesting via counter).
         */
        private fun extractArrayContent(json: String): String {
            var depth = 0
            var inString = false
            var escape = false
            var start = -1

            for (i in json.indices) {
                val c = json[i]
                if (escape) {
                    escape = false
                    continue
                }
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = !inString
                    '[' -> {
                        if (!inString) {
                            if (depth == 0) start = i + 1
                            depth++
                        }
                    }
                    ']' -> {
                        if (!inString) {
                            depth--
                            if (depth == 0 && start != -1) {
                                return json.substring(start, i)
                            }
                        }
                    }
                }
            }
            return ""
        }

        /**
         * Split array content into individual objects.
         * Uses brace depth to identify object boundaries.
         */
        private fun splitIntoObjects(arrayContent: String): List<String> {
            val objects = mutableListOf<String>()
            var depth = 0
            var inString = false
            var escape = false
            var start = -1

            for (i in arrayContent.indices) {
                val c = arrayContent[i]
                if (escape) {
                    escape = false
                    continue
                }
                when (c) {
                    '\\' -> escape = true
                    '"' -> inString = !inString
                    '{' -> {
                        if (!inString) {
                            if (depth == 0) start = i
                            depth++
                        }
                    }
                    '}' -> {
                        if (!inString) {
                            depth--
                            if (depth == 0 && start != -1) {
                                objects.add(arrayContent.substring(start, i + 1))
                                start = -1
                            }
                        }
                    }
                }
            }
            return objects
        }

        /**
         * Parse a single claim object: {timestampMs, appLabel, claimText, postClaimMin}.
         */
        private fun parseClaimObject(obj: String): ClaimRecord? {
            var timestampMs: Long? = null
            var appLabel: String? = null
            var claimText: String? = null
            var postClaimMin: Int? = null

            // Extract fields using simple pattern matching
            timestampMs = extractLongField(obj, "timestampMs")
            appLabel = extractStringField(obj, "appLabel")
            claimText = extractStringField(obj, "claimText")
            postClaimMin = extractIntField(obj, "postClaimMin")

            if (timestampMs == null || appLabel == null || claimText == null || postClaimMin == null) {
                return null
            }

            return ClaimRecord(timestampMs, appLabel, claimText, postClaimMin)
        }

        /**
         * Extract a long field: "fieldName": 1234567890
         */
        private fun extractLongField(json: String, fieldName: String): Long? {
            val pattern = "\"$fieldName\""
            val idx = json.indexOf(pattern)
            if (idx == -1) return null
            val after = json.substring(idx + pattern.length).trim()
            if (!after.startsWith(":")) return null
            val valPart = after.substring(1).trim()
            return try {
                valPart.takeWhile { it.isDigit() || it == '-' }.toLongOrNull()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Extract an int field: "fieldName": 123
         */
        private fun extractIntField(json: String, fieldName: String): Int? {
            val pattern = "\"$fieldName\""
            val idx = json.indexOf(pattern)
            if (idx == -1) return null
            val after = json.substring(idx + pattern.length).trim()
            if (!after.startsWith(":")) return null
            val valPart = after.substring(1).trim()
            return try {
                valPart.takeWhile { it.isDigit() || it == '-' }.toIntOrNull()
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Extract a string field: "fieldName": "value"
         * Handles escaped quotes and basic escaping.
         */
        private fun extractStringField(json: String, fieldName: String): String? {
            val pattern = "\"$fieldName\""
            val idx = json.indexOf(pattern)
            if (idx == -1) return null
            val after = json.substring(idx + pattern.length).trim()
            if (!after.startsWith(":")) return null
            val valPart = after.substring(1).trim()
            if (!valPart.startsWith("\"")) return null

            // Extract string value with escape handling
            val sb = StringBuilder()
            var i = 1
            val len = valPart.length
            while (i < len) {
                val c = valPart[i]
                when (c) {
                    '\\' -> {
                        if (i + 1 < len) {
                            val next = valPart[i + 1]
                            when (next) {
                                '"' -> sb.append('"')
                                '\\' -> sb.append('\\')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    // \uXXXX
                                    if (i + 5 < len) {
                                        val hex = valPart.substring(i + 2, i + 6)
                                        try {
                                            sb.append(hex.toInt(16).toChar())
                                        } catch (e: Exception) {
                                            // ignore
                                        }
                                        i += 4
                                    }
                                }
                                else -> sb.append(next)
                            }
                            i += 2
                        } else {
                            i++
                        }
                    }
                    '"' -> return sb.toString()
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }
    }

    /** Load all claims from file, filtered to active ones. */
    private fun loadClaims(): MutableList<ClaimRecord> {
        if (!file.exists()) return mutableListOf()

        return try {
            val content = file.readText()
            parseJsonString(content).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /** Write claims to file atomically. */
    private fun saveClaims(records: List<ClaimRecord>) {
        try {
            val tempFile = File(file.absolutePath + ".tmp")
            tempFile.writeText(buildJsonString(records))
            tempFile.renameTo(file)
        } catch (e: Exception) {
            // Silent fail; log not available in this simple store
        }
    }

    /**
     * Save a claim record. Appends a new claim (multiple claims per session allowed).
     */
    fun saveClaim(appLabel: String, claimText: String, nowMs: Long = System.currentTimeMillis()) {
        val claims = loadClaims()
        val newRecord = ClaimRecord(
            timestampMs = nowMs,
            appLabel = appLabel,
            claimText = claimText,
            postClaimMin = 0
        )
        claims.add(newRecord)
        saveClaims(claims)
    }

    /**
     * Add post-claim minutes to the most recent claim (or a specific one if matched).
     * For simplicity, add to the most recent active claim.
     */
    fun addPostClaimMinutes(min: Int, nowMs: Long = System.currentTimeMillis()) {
        val claims = loadClaims()
        val cutoff = nowMs - (CLAIM_TTL_HOURS * 60 * 60 * 1000)
        var updated = false

        // Find the most recent active claim and add minutes
        for (i in claims.indices.reversed()) {
            val record = claims[i]
            if (record.timestampMs >= cutoff) {
                claims[i] = record.copy(postClaimMin = record.postClaimMin + min)
                updated = true
                break
            }
        }

        if (updated) {
            saveClaims(claims)
        }
    }

    /**
     * Get the most recent active claim (within 24h).
     * Returns null if no active claim exists.
     */
    fun getActiveClaim(nowMs: Long = System.currentTimeMillis()): ClaimRecord? {
        val claims = loadClaims()
        val cutoff = nowMs - (CLAIM_TTL_HOURS * 60 * 60 * 1000)

        // Return most recent active claim
        for (i in claims.indices.reversed()) {
            val record = claims[i]
            if (record.timestampMs >= cutoff) {
                return record
            }
        }
        return null
    }

    /**
     * Remove all expired claims (older than 24h).
     * Returns count of removed claims.
     */
    fun clearExpired(nowMs: Long = System.currentTimeMillis()): Int {
        val claims = loadClaims()
        val cutoff = nowMs - (CLAIM_TTL_HOURS * 60 * 60 * 1000)
        val originalSize = claims.size

        val activeClaims = claims.filter { it.timestampMs >= cutoff }
        if (activeClaims.size < claims.size) {
            saveClaims(activeClaims)
        }

        return originalSize - activeClaims.size
    }

    /**
     * Clear all claims (e.g., for testing or explicit reset).
     */
    fun clearAll() {
        saveClaims(emptyList())
    }

    /**
     * Get total count of active claims (within 24h).
     */
    fun activeClaimCount(nowMs: Long = System.currentTimeMillis()): Int {
        val claims = loadClaims()
        val cutoff = nowMs - (CLAIM_TTL_HOURS * 60 * 60 * 1000)
        return claims.count { it.timestampMs >= cutoff }
    }
}

/**
 * Single claim record persisted by ClaimStore.
 * @property timestampMs When the claim was granted (milliseconds since epoch)
 * @property appLabel Human-readable app name (e.g., "YouTube")
 * @property claimText The user-provided reason for the claim
 * @property postClaimMin Minutes accumulated after the claim (for grace period tracking)
 */
data class ClaimRecord(
    val timestampMs: Long,
    val appLabel: String,
    val claimText: String,
    val postClaimMin: Int
)
