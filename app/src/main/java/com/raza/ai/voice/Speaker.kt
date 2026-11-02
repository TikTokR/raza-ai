package com.raza.ai.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.raza.ai.core.Script
import com.raza.ai.core.VoiceScore
import com.raza.ai.data.SettingsStore
import java.util.Locale

/**
 * Spoken replies through Android TextToSpeech. The assistant asks for a deep, masculine voice
 * (lower pitch + a male-named voice when the engine has one), but which voices exist depends on
 * the phone's installed TTS engine. It never claims a specific character or celebrity voice.
 */
class Speaker(ctx: Context, private val settings: SettingsStore, private val onFinished: () -> Unit) {

    sealed class Result {
        data object Started : Result()
        data object NotReady : Result()
        data class LanguageMissing(val tag: String) : Result()
    }

    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                ready = true
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                engine.setPitch(DEEP_PITCH)
                engine.setSpeechRate(SPEECH_RATE)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = onFinished()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onFinished()
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = onFinished()
                })
            }
        }
    }

    val isSpeaking: Boolean get() = try { tts?.isSpeaking == true } catch (_: Exception) { false }

    fun speak(text: String, preferOffline: Boolean = false): Result {
        val engine = tts
        if (!ready || engine == null) return Result.NotReady
        val urdu = Script.isUrdu(text)
        val locale = if (urdu) Locale("ur", "PK") else Locale.US
        val availability = engine.isLanguageAvailable(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            return Result.LanguageMissing(locale.toLanguageTag())
        }
        engine.setLanguage(locale)
        pickVoice(engine, locale, preferOffline)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.speechVolume / 100f)
        }
        val clipped = text.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        val rc = engine.speak(clipped, TextToSpeech.QUEUE_FLUSH, params, "raza-${System.nanoTime()}")
        return if (rc == TextToSpeech.SUCCESS) Result.Started else Result.NotReady
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }

    private fun pickVoice(engine: TextToSpeech, locale: Locale, preferOffline: Boolean) {
        try {
            val best = engine.voices
                ?.filter { it.locale.language == locale.language }
                ?.maxByOrNull { VoiceScore.score(it.name, it.isNetworkConnectionRequired, preferOffline) }
            if (best != null) engine.setVoice(best)
        } catch (_: Exception) {
            // Keep the engine default voice.
        }
    }

    companion object {
        private const val DEEP_PITCH = 0.8f
        private const val SPEECH_RATE = 0.95f
    }
}
