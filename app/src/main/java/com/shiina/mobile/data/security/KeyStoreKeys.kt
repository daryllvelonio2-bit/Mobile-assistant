package com.shiina.mobile.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API keys live here, never in plain prefs or code. Comma-separated per provider. */
class KeyStoreKeys(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "api_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getKeys(provider: String): List<String> =
        prefs.getString(provider, "").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setKeys(provider: String, keys: List<String>) {
        prefs.edit().putString(provider, keys.joinToString(",")).apply()
    }
}
