package com.shiina.mobile.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API keys live here, never in plain prefs or code. Comma-separated per provider. */
class KeyStoreKeys(context: Context) {

    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                "api_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { error ->
            android.util.Log.e("KeyStoreKeys", "EncryptedSharedPreferences failed, falling back to private prefs", error)
            context.getSharedPreferences("api_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    fun getKeys(provider: String = "gemini"): List<String> =
        prefs.getString(provider, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setKeys(provider: String = "gemini", keys: List<String>) {
        prefs.edit().putString(provider, keys.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")).apply()
    }

    fun addKey(provider: String = "gemini", key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        val current = getKeys(provider).toMutableList()
        if (current.contains(trimmed)) return false
        current.add(trimmed)
        setKeys(provider, current)
        return true
    }

    fun removeKey(provider: String = "gemini", key: String): Boolean {
        val trimmed = key.trim()
        val current = getKeys(provider).toMutableList()
        val removed = current.remove(trimmed)
        if (removed) {
            setKeys(provider, current)
        }
        return removed
    }
}
