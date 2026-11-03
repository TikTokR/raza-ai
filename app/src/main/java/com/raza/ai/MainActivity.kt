package com.raza.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.raza.ai.core.WakeState
import com.raza.ai.data.SecureTokenStore
import com.raza.ai.data.SettingsStore
import com.raza.ai.ui.AssistantScreen
import com.raza.ai.ui.DashboardScreen
import com.raza.ai.ui.Host
import com.raza.ai.ui.Screen
import com.raza.ai.ui.SettingsScreen
import com.raza.ai.ui.TasksScreen
import com.raza.ai.ui.Theme
import com.raza.ai.ui.Ui
import com.raza.ai.voice.WakeHub

/** Tabbed host for the four sections: Assistant, Dashboard, Tasks, Settings. Also owns permissions, wake control and the owner check. */
class MainActivity : Activity(), Host {
    private lateinit var razaApp: RazaApp
    private val session get() = razaApp.session
    override val settings: SettingsStore get() = razaApp.settings
    override val tokens: SecureTokenStore get() = razaApp.tokens

    private lateinit var ui: Ui
    private lateinit var screens: List<Screen>
    private lateinit var frame: FrameLayout
    private val tabLabels = listOf("ASSISTANT", "DASHBOARD", "TASKS", "SETTINGS")
    private val tabViews = ArrayList<TextView>()
    private var tab = 0

    private val main = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { refreshCurrent() }
    private val tick = object : Runnable {
        override fun run() {
            refreshCurrent()
            if (session.pending() != null) main.postDelayed(this, 1000)
        }
    }
    private var afterPermission: (() -> Unit)? = null
    private var micPermanentlyDenied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        razaApp = application as RazaApp
        ui = Ui(this)
        window.statusBarColor = Theme.bg
        window.navigationBarColor = Theme.bg
        screens = listOf(
            AssistantScreen(ui, session, this),
            DashboardScreen(ui, session, this),
            TasksScreen(ui, session, this),
            SettingsScreen(ui, session, this),
        )
        buildUi()
        tab = savedInstanceState?.getInt(KEY_TAB, 0)?.coerceIn(0, 3) ?: 0
        showTab(tab)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(WakeWordService.EXTRA_FROM_WAKE, false) == true) {
            i.removeExtra(WakeWordService.EXTRA_FROM_WAKE)
            showTab(0)
            withMic { session.startListening() }
        }
    }

    override fun onStart() {
        super.onStart()
        session.addListener(onChange)
        WakeHub.addListener(onChange)
        WakeHub.wakeHandler = { cmd -> session.handleWake(cmd) }
        session.authLauncher = { launchOwnerCheck() }
        session.refreshConnection()
        refreshCurrent()
    }

    override fun onStop() {
        session.removeListener(onChange)
        WakeHub.removeListener(onChange)
        WakeHub.wakeHandler = null
        session.authLauncher = null
        main.removeCallbacks(tick)
        super.onStop()
    }

    // ---------------- layout ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.bg)
        }
        frame = FrameLayout(this)
        screens.forEach { frame.addView(it.root, FrameLayout.LayoutParams(-1, -1)) }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Theme.panel)
        }
        tabLabels.forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                letterSpacing = 0.1f
                setPadding(0, ui.dp(14), 0, ui.dp(14))
                setOnClickListener { showTab(i) }
            }
            tabViews.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        // Android 15 draws edge to edge: keep content clear of the system bars and the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun showTab(index: Int) {
        tab = index
        screens.forEachIndexed { i, s -> s.root.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { i, t ->
            t.setTextColor(if (i == index) Theme.cyan else Theme.muted)
            t.setBackgroundColor(if (i == index) Theme.panel2 else Theme.panel)
        }
        screens[index].onShown()
    }

    private fun refreshCurrent() {
        // Do not rebuild a form while the user is typing in it.
        if (tab != 0 && currentFocus is EditText) return
        screens[tab].refresh()
        main.removeCallbacks(tick)
        if (session.pending() != null) main.postDelayed(tick, 1000)
    }

    // ---------------- Host ----------------

    override fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun micBlocked() = micPermanentlyDenied && !hasMic()
    override fun notificationsGranted() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    override fun openTab(index: Int) = showTab(index.coerceIn(0, 3))
    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    override fun openSecuritySettings() {
        try {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (e: Exception) {
            toast("Open Android Settings > Security to set a screen lock.")
        }
    }

    override fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    override fun screenLockSet(): Boolean = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override fun withMic(action: () -> Unit) {
        if (hasMic()) {
            action()
            return
        }
        afterPermission = action
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (!notificationsGranted()) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ASK) {
            val cb = askCallback
            askCallback = null
            cb?.invoke(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            refreshCurrent()
            return
        }
        if (requestCode != REQ_PERMS) return
        val granted = hasMic()
        micPermanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val action = afterPermission
        afterPermission = null
        when {
            granted -> action?.invoke()
            micPermanentlyDenied -> AlertDialog.Builder(this)
                .setTitle("Microphone blocked")
                .setMessage("Android will not ask again. To use voice, open App settings > Permissions > Microphone and allow it.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open App settings") { _, _ -> openAppSettings() }
                .show()
            else -> toast("Microphone permission is needed for voice.")
        }
        refreshCurrent()
    }

    private var askCallback: ((Boolean) -> Unit)? = null

    override fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun ask(permission: String, onDone: (Boolean) -> Unit) {
        if (has(permission)) {
            onDone(true)
            return
        }
        askCallback = onDone
        requestPermissions(arrayOf(permission), REQ_ASK)
    }

    override fun toggleWake() {
        val s = WakeHub.state
        if (s == WakeState.OFF || s == WakeState.ERROR) {
            withMic {
                try {
                    settings.wakeEnabled = true
                    WakeWordService.start(this)
                } catch (e: Exception) {
                    toast("Android blocked the background microphone: ${e.javaClass.simpleName}")
                }
            }
        } else {
            settings.wakeEnabled = false
            WakeWordService.stop(this)
        }
    }

    override fun toggleWakePause() {
        when (WakeHub.state) {
            WakeState.PAUSED -> WakeWordService.userResume(this)
            WakeState.LISTENING, WakeState.COMMAND_LISTENING -> WakeWordService.userPause(this)
            else -> toast("Turn voice activation on first.")
        }
    }

    // ---------------- owner check (Android's own screen lock) ----------------

    @Suppress("DEPRECATION")
    private fun launchOwnerCheck() {
        val i = getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent("RAZA AI", "Confirm it's you to continue.")
        if (i == null) {
            session.onAuthResult(false)
            return
        }
        startActivityForResult(i, REQ_AUTH)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUTH) session.onAuthResult(resultCode == RESULT_OK)
    }

    companion object {
        private const val REQ_PERMS = 40
        private const val REQ_AUTH = 41
        private const val REQ_ASK = 42
        private const val KEY_TAB = "tab"
    }
}
