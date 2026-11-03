package com.raza.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.raza.ai.BuildConfig
import com.raza.ai.core.Languages

/** Non-secret preferences. Stored only on this phone, in app-private storage. */
class SettingsStore(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("raza_settings", Context.MODE_PRIVATE)

    var language: String
        get() = Languages.normalize(p.getString("language", null))
        set(v) = p.edit().putString("language", Languages.normalize(v)).apply()

    var voiceReplies: Boolean
        get() = p.getBoolean("voice_replies", true)
        set(v) = p.edit().putBoolean("voice_replies", v).apply()

    /** 0..100, applied to the spoken-reply volume. */
    var speechVolume: Int
        get() = p.getInt("speech_volume", 100).coerceIn(0, 100)
        set(v) = p.edit().putInt("speech_volume", v.coerceIn(0, 100)).apply()

    var wakePhrase: String
        get() = p.getString("wake_phrase", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WAKE_PHRASE
        set(v) = p.edit().putString("wake_phrase", v).apply()

    /** The user's last choice. The service never auto-starts after boot (Android forbids it for the microphone). */
    var wakeEnabled: Boolean
        get() = p.getBoolean("wake_enabled", false)
        set(v) = p.edit().putBoolean("wake_enabled", v).apply()

    var apiBaseUrl: String
        get() = p.getString("api_base_url", null) ?: BuildConfig.DEFAULT_API_BASE_URL
        set(v) = p.edit().putString("api_base_url", v).apply()

    /** User switch: when off the assistant never contacts the backend, even when online. */
    var cloudAiEnabled: Boolean
        get() = p.getBoolean("cloud_ai_enabled", true)
        set(v) = p.edit().putBoolean("cloud_ai_enabled", v).apply()

    var preferOfflineSpeech: Boolean
        get() = p.getBoolean("prefer_offline_speech", false)
        set(v) = p.edit().putBoolean("prefer_offline_speech", v).apply()

    /** Save chat history on this phone (and use it as context). Default on; off deletes the saved copy. */
    var rememberConversations: Boolean
        get() = p.getBoolean("remember_conversations", true)
        set(v) = p.edit().putBoolean("remember_conversations", v).apply()

    /** Place calls directly (needs the Phone permission). Off by default: the dialer opens and you press call. */
    var directCalls: Boolean
        get() = p.getBoolean("direct_calls", false)
        set(v) = p.edit().putBoolean("direct_calls", v).apply()

    /** Digits only, for numbers saved without a country code. Empty = use the SIM's country. */
    var defaultCountryCode: String
        get() = p.getString("default_cc", "") ?: ""
        set(v) = p.edit().putString("default_cc", v.filter { it.isDigit() }.take(4)).apply()

    fun clearAll() = p.edit().clear().apply()

    companion object {
        const val DEFAULT_WAKE_PHRASE = "hey raza"
    }
}

/**
 * Backend access token, kept in EncryptedSharedPreferences (Android Keystore backed).
 * If secure storage is unavailable on a device the token is NOT stored at all (never falls back to plain text).
 */
class SecureTokenStore(private val ctx: Context) {
    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "raza_secure",
                masterKey,
                ctx.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            null
        }
    }

    val available: Boolean get() = prefs != null

    fun get(): String = try {
        prefs?.getString("api_token", "").orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun set(token: String): Boolean {
        val p = prefs ?: return false
        return try {
            p.edit().putString("api_token", token.trim()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clear() {
        try {
            prefs?.edit()?.remove("api_token")?.apply()
        } catch (_: Exception) {
        }
    }
}
