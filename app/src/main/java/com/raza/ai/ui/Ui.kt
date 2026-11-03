package com.raza.ai.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.raza.ai.AssistantSession
import com.raza.ai.data.SecureTokenStore
import com.raza.ai.data.SettingsStore

object Theme {
    val bg = Color.rgb(3, 9, 20)
    val panel = Color.rgb(9, 22, 43)
    val panel2 = Color.rgb(14, 34, 64)
    val cyan = Color.rgb(42, 220, 255)
    val cyanDim = Color.rgb(24, 110, 140)
    val amber = Color.rgb(255, 196, 61)
    val green = Color.rgb(70, 235, 170)
    val red = Color.rgb(255, 92, 110)
    val text = Color.rgb(222, 241, 250)
    val muted = Color.rgb(125, 155, 175)

    fun alpha(color: Int, a: Int): Int = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Colour used for each assistant phase by the orb, waveform and status text. */
    fun phaseColor(p: AssistantSession.Phase): Int = when (p) {
        AssistantSession.Phase.IDLE -> cyan
        AssistantSession.Phase.LISTENING -> Color.rgb(90, 240, 255)
        AssistantSession.Phase.PROCESSING -> amber
        AssistantSession.Phase.SPEAKING -> green
    }
}

/** Small view-building helpers so screens stay readable. Everything is built in code (no XML layouts). */
class Ui(val ctx: Context) {
    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(v: Float): Float = v * ctx.resources.displayMetrics.density

    fun text(value: CharSequence, size: Float = 14f, color: Int = Theme.text, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    fun shape(fill: Int, stroke: Int, radiusDp: Float = 14f, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radiusDp)
        setStroke(dp(strokeDp), stroke)
    }

    fun button(label: String, accent: Boolean = false, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minHeight = dp(40)
        minimumHeight = dp(40)
        stateListAnimator = null
        setTextColor(if (accent) Theme.bg else Theme.cyan)
        background = shape(if (accent) Theme.cyan else Theme.panel2, if (accent) Theme.cyan else Theme.cyanDim, 12f)
        setOnClickListener { onClick() }
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f, margin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) }

    fun weighted(margin: Int = 2): LinearLayout.LayoutParams = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, margin)

    fun vertical(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** A rounded panel with a cyan title. Add content to the returned layout. */
    fun card(title: String?): LinearLayout = vertical().apply {
        background = shape(Theme.panel, Theme.cyanDim, 16f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (title != null) addView(text(title, 11f, Theme.cyan, true).apply { letterSpacing = 0.15f; setPadding(0, 0, 0, dp(8)) })
    }

    fun cardParams(): LinearLayout.LayoutParams = lp(margin = 0).apply { setMargins(dp(12), dp(6), dp(12), dp(6)) }

    fun row(label: String, value: String, valueColor: Int = Theme.text): LinearLayout = horizontal().apply {
        addView(text(label, 13f, Theme.muted), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(value, 13f, valueColor, true).apply { gravity = Gravity.END }, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        setPadding(0, dp(3), 0, dp(3))
    }

    fun note(value: String): TextView = text(value, 12f, Theme.muted).apply { setPadding(0, dp(6), 0, dp(2)) }
}

/** What a screen may ask the hosting activity to do (permissions, tabs, system screens). */
interface Host {
    val settings: SettingsStore
    val tokens: SecureTokenStore
    fun hasMic(): Boolean
    fun micBlocked(): Boolean
    fun notificationsGranted(): Boolean
    fun withMic(action: () -> Unit)
    fun has(permission: String): Boolean
    fun ask(permission: String, onDone: (Boolean) -> Unit)
    fun toggleWake()
    fun toggleWakePause()
    fun openTab(index: Int)
    fun openAppSettings()
    fun openBatterySettings()
    fun openSecuritySettings()
    fun openAccessibilitySettings()
    fun batteryUnrestricted(): Boolean
    fun screenLockSet(): Boolean
    fun toast(message: String)
}

abstract class Screen(protected val ui: Ui, protected val session: AssistantSession, protected val host: Host) {
    abstract val root: View

    /** Bring the visible content up to date. Called on every state change while this screen is showing. */
    abstract fun refresh()

    open fun onShown() = refresh()
}
