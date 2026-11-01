package com.raza.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val bg = Color.rgb(5, 12, 28)
    private val panel = Color.rgb(12, 27, 52)
    private val cyan = Color.rgb(42, 220, 255)
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var log: LinearLayout
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var language = "English + Urdu"
    private var voiceReplies = true
    private var apiBase = "https://raza-ai-5rdfjt.v2.appdeploy.ai/api/v1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("raza", MODE_PRIVATE)
        language = prefs.getString("language", language) ?: language
        voiceReplies = prefs.getBoolean("voice", true)
        apiBase = prefs.getString("api", apiBase) ?: apiBase
        tts = TextToSpeech(this, this)
        buildUi()
        addLine("RAZA AI", "Assalam-o-Alaikum! I am ready. Tap the microphone or type a command.", false)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 28, 22, 18); setBackgroundColor(bg) }
        val title = TextView(this).apply { text = "R A Z A   A I"; textSize = 27f; setTextColor(cyan); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        val sub = TextView(this).apply { text = "PERSONAL VOICE ASSISTANT  •  SECURE MODE"; textSize = 10f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0, 4, 0, 18) }
        status = TextView(this).apply { text = "● ONLINE  |  LOCK SCREEN SECURITY ON"; setTextColor(cyan); textSize = 11f; gravity = Gravity.CENTER; setPadding(8, 12, 8, 12); setBackgroundColor(panel) }
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 12) }
        val scroll = ScrollView(this).apply { addView(log) }
        val fieldRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply { hint = "Ask RAZA anything…"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND; setBackgroundColor(panel); setPadding(14, 12, 14, 12) }
        val send = Button(this).apply { text = "SEND"; setTextColor(Color.BLACK); setBackgroundColor(cyan); setOnClickListener { sendMessage(input.text.toString()); input.setText("") } }
        val mic = Button(this).apply { text = "🎙 MIC"; setOnClickListener { startSpeechInput() } }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send.performClick(); true } else false }
        fieldRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f)); fieldRow.addView(send); fieldRow.addView(mic)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(smallButton("WAKE LISTENER") { startWake() }); actions.addView(smallButton("STOP") { stopWake() }); actions.addView(smallButton("SETTINGS") { showSettings() })
        outer.addView(title); outer.addView(sub); outer.addView(status); outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); outer.addView(fieldRow); outer.addView(actions)
        setContentView(outer)
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 10f; setOnClickListener { action() } }

    private fun addLine(who: String, message: String, speak: Boolean) {
        runOnUiThread {
            val tv = TextView(this).apply { text = "$who\n$message"; textSize = 15f; setTextColor(if (who == "RAZA") cyan else Color.WHITE); setPadding(12, 12, 12, 12); setBackgroundColor(panel) }
            val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 5, 0, 5); log.addView(tv, lp)
            (log.parent as? ScrollView)?.post { (log.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
            if (speak && voiceReplies) speak(message)
        }
    }

    private fun sendMessage(raw: String) {
        val message = raw.trim(); if (message.isEmpty()) return
        addLine("YOU", message, false)
        if (handleLocalCommand(message)) return
        status.text = "● THINKING…"
        worker.execute {
            try {
                val url = URL("${apiBase.trimEnd('/')}/assistant")
                val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 15000; readTimeout = 25000; doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
                val body = JSONObject().put("message", message).put("language", language).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = JSONObject(text)
                val answer = json.optString("reply", json.optString("response", json.optString("message", "I received your request, but the server response format was unexpected.")))
                addLine("RAZA", answer, true)
                runOnUiThread { status.text = "● ONLINE" }
                conn.disconnect()
            } catch (e: Exception) {
                addLine("RAZA", "I could not connect to the AI server. Check internet and API URL. Details: ${e.localizedMessage ?: "connection error"}", true)
                runOnUiThread { status.text = "● OFFLINE / CHECK CONNECTION" }
            }
        }
    }

    private fun handleLocalCommand(text: String): Boolean {
        val q = text.lowercase(Locale.ROOT)
        when {
            q.contains("open settings") || q.contains("settings kholo") || q.contains("سیٹنگ") -> { confirm("Open Android Settings?", "This will open your phone settings.") { startActivity(Intent(Settings.ACTION_SETTINGS)) }; return true }
            q.contains("open wi-fi") || q.contains("wifi settings") || q.contains("وائی فائی") -> { confirm("Open Wi-Fi settings?", "Android will show the Wi-Fi controls.") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }; return true }
            q.contains("bluetooth settings") || q.contains("open bluetooth") || q.contains("بلوٹوتھ") -> { confirm("Open Bluetooth settings?", "Android will show Bluetooth controls.") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }; return true }
            q.startsWith("open ") -> { val app = text.substringAfter("open ").trim(); confirm("Open $app?", "RAZA will look for an installed app with this name.") { launchApp(app) }; return true }
            q.contains("call ") || q.contains("send message") || q.contains("send sms") -> { addLine("RAZA", "For your safety, calls and messages are not sent automatically in this build. Open the Phone or Messages app yourself and confirm there.", true); return true }
        }
        return false
    }

    private fun confirm(title: String, msg: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> yes() }.show()
    }

    private fun launchApp(name: String) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(intent, 0)
        val found = matches.firstOrNull { it.loadLabel(pm).toString().contains(name, true) || it.activityInfo.packageName.contains(name, true) }
        if (found == null) { addLine("RAZA", "I could not find an installed app named '$name'. Try its exact display name.", true); return }
        val launch = pm.getLaunchIntentForPackage(found.activityInfo.packageName)
        if (launch != null) startActivity(launch) else addLine("RAZA", "Android did not provide a launch action for that app.", true)
    }

    private fun startSpeechInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { addLine("RAZA", "Speech recognition is not available on this phone.", true); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 40); return }
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == "Urdu") "ur-PK" else "en-US")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to RAZA AI")
            }
            @Suppress("DEPRECATION")
            startActivityForResult(i, 41)
        } catch (e: Exception) { addLine("RAZA", "Could not start voice input: ${e.localizedMessage}", true) }
    }

    @Deprecated("Android speech result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 41 && resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (heard.isNotBlank()) sendMessage(heard)
        }
    }

    private fun startWake() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 42); return }
        try { val i = Intent(this, WakeWordService::class.java); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i); status.text = "● WAKE LISTENER REQUESTED" }
        catch (e: Exception) { addLine("RAZA", "Android blocked background microphone startup. Open the app and try again: ${e.localizedMessage}", true) }
    }
    private fun stopWake() { stopService(Intent(this, WakeWordService::class.java)); status.text = "● WAKE LISTENER STOPPED" }

    private fun showSettings() {
        val choices = arrayOf("Language: English + Urdu", "Language: English", "Language: Urdu", "Toggle voice replies", "Change AI API URL", "Privacy & safety")
        AlertDialog.Builder(this).setTitle("RAZA AI Settings").setItems(choices) { _, which ->
            when (which) {
                0 -> savePrefs("English + Urdu", voiceReplies, apiBase)
                1 -> savePrefs("English", voiceReplies, apiBase)
                2 -> savePrefs("Urdu", voiceReplies, apiBase)
                3 -> savePrefs(language, !voiceReplies, apiBase)
                4 -> editApiUrl()
                5 -> AlertDialog.Builder(this).setTitle("Privacy & Safety").setMessage("RAZA never asks for, stores, or enters your phone PIN/password. Android controls lock-screen security. Sensitive actions require your confirmation. Microphone listening is shown by an ongoing notification.").setPositiveButton("OK", null).show()
            }
        }.show()
    }
    private fun savePrefs(lang: String, voice: Boolean, api: String) { language = lang; voiceReplies = voice; apiBase = api; getSharedPreferences("raza", MODE_PRIVATE).edit().putString("language", lang).putBoolean("voice", voice).putString("api", api).apply(); addLine("RAZA", "Settings saved: $language; voice replies ${if (voiceReplies) "on" else "off"}.", false) }
    private fun editApiUrl() {
        val box = EditText(this).apply { setText(apiBase); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("AI API base URL").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> val v = box.text.toString().trim(); if (v.startsWith("https://")) savePrefs(language, voiceReplies, v) else addLine("RAZA", "Use a valid HTTPS URL.", false) }.show()
    }

    private fun speak(text: String) { if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "raza-${System.currentTimeMillis()}") }
    override fun onInit(statusCode: Int) { ttsReady = statusCode == TextToSpeech.SUCCESS; if (ttsReady) tts.language = if (language == "Urdu") Locale("ur", "PK") else Locale.US }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 40 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeechInput()
        if (requestCode == 42 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startWake()
    }
    override fun onDestroy() { worker.shutdown(); if (::tts.isInitialized) tts.shutdown(); super.onDestroy() }
}
