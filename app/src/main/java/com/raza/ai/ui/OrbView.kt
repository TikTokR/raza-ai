package com.raza.ai.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import com.raza.ai.AssistantSession.Phase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The holographic AI core. Idle: slow pulse. Listening: ripples that swell with the real microphone
 * level. Processing: spinning arcs. Speaking: radial bars. Offline: dimmed and grey-blue.
 */
class OrbView(context: Context) : View(context) {
    var phase: Phase = Phase.IDLE
        set(value) {
            field = value
            contentDescription = "Assistant is ${value.name.lowercase()}"
            invalidate()
        }

    var online: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var target = 0f
    private var smooth = 0f
    private var t = 0f
    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun setLevel(level: Float) {
        target = level.coerceIn(0f, 1f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            if (isAttachedToWindow && !animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.92f
        val accent = if (!online && phase == Phase.IDLE) Theme.muted else Theme.phaseColor(phase)
        val twoPi = (2 * PI).toFloat()
        smooth += (target - smooth) * 0.2f
        if (phase != Phase.LISTENING) target *= 0.9f
        val pulse = 0.5f + 0.5f * sin(twoPi * t)
        val level = if (phase == Phase.LISTENING) smooth else 0f

        // Soft glow behind everything.
        val glowR = r * 0.78f * (1f + 0.04f * pulse + 0.18f * level)
        fill.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(Theme.alpha(accent, 170 + (level * 70).toInt()), Theme.alpha(accent, 45), Theme.alpha(accent, 0)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, glowR, fill)
        fill.shader = null

        // Core disc.
        fill.color = Theme.alpha(accent, 230)
        canvas.drawCircle(cx, cy, r * 0.2f * (1f + 0.06f * pulse + 0.25f * level), fill)
        fill.color = Theme.bg
        canvas.drawCircle(cx, cy, r * 0.12f * (1f + 0.06f * pulse), fill)

        // Fixed rings.
        line.pathEffect = null
        line.strokeWidth = 1.5f * density
        line.color = Theme.alpha(accent, 110)
        canvas.drawCircle(cx, cy, r * 0.42f, line)
        line.color = Theme.alpha(accent, 70)
        canvas.drawCircle(cx, cy, r * 0.6f, line)

        // Rotating dashed outer ring.
        line.strokeWidth = 2f * density
        line.color = Theme.alpha(accent, 150)
        line.pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), t * 36f * density * 5f)
        canvas.drawCircle(cx, cy, r * 0.9f, line)
        line.pathEffect = null

        // Tick marks.
        line.strokeWidth = 1f * density
        line.color = Theme.alpha(accent, 90)
        for (i in 0 until 60) {
            val a = twoPi * i / 60f
            val inner = if (i % 5 == 0) r * 0.94f else r * 0.96f
            canvas.drawLine(cx + cos(a) * inner, cy + sin(a) * inner, cx + cos(a) * r, cy + sin(a) * r, line)
        }

        when (phase) {
            Phase.LISTENING -> {
                line.strokeWidth = 2f * density
                for (k in 0 until 3) {
                    val p = (t * 3f + k / 3f) % 1f
                    line.color = Theme.alpha(accent, ((1f - p) * (110 + level * 120)).toInt())
                    canvas.drawCircle(cx, cy, r * (0.25f + 0.62f * p) * (1f + 0.1f * level), line)
                }
            }
            Phase.PROCESSING -> {
                line.strokeWidth = 3f * density
                line.color = Theme.alpha(accent, 220)
                val radii = floatArrayOf(0.5f, 0.68f, 0.84f)
                val speeds = floatArrayOf(2f, -3f, 1.5f)
                for (i in radii.indices) {
                    val rr = r * radii[i]
                    rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                    canvas.drawArc(rect, t * 360f * speeds[i], 80f, false, line)
                    canvas.drawArc(rect, t * 360f * speeds[i] + 180f, 40f, false, line)
                }
            }
            Phase.SPEAKING -> {
                line.strokeWidth = 3f * density
                line.color = Theme.alpha(accent, 230)
                for (i in 0 until 56) {
                    val a = twoPi * i / 56f
                    val len = r * (0.03f + 0.11f * abs(sin(twoPi * t * 6f + i * 0.7f)) * (0.5f + 0.5f * pulse))
                    val base = r * 0.7f
                    canvas.drawLine(cx + cos(a) * base, cy + sin(a) * base, cx + cos(a) * (base + len), cy + sin(a) * (base + len), line)
                }
            }
            Phase.IDLE -> {
                line.strokeWidth = 2.5f * density
                line.color = Theme.alpha(accent, 160)
                val rr = r * 0.72f
                rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
                canvas.drawArc(rect, t * 360f, 50f, false, line)
            }
        }
    }
}
