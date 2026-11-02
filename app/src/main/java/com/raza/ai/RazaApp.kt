package com.raza.ai

import android.app.Application
import com.raza.ai.data.SecureTokenStore
import com.raza.ai.data.SettingsStore

class RazaApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var tokens: SecureTokenStore
        private set
    lateinit var session: AssistantSession
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        tokens = SecureTokenStore(this)
        session = AssistantSession(this, settings, tokens)
    }
}
