package com.raza.ai.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import com.raza.ai.AssistantSession.Phase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Bar waveform. While listening it shows the REAL microphone level reported by Android's
 * recogniser. While speaking it is a decorative animation (Android gives no audio level for
 * text-to-speech). When idle it is a flat line.
 */
class WaveformView(context: Context) : View(context) {
    var phase: Phase = Phase.IDLE
        set(value) {
            field = value
            invalidate()
        }

    private val bars = 48
    private val history = FloatArray(bars)
    private var head = 0
    private var t = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun pushLevel(level: Float) {
        history[head] = level.coerceIn(0f, 1f)
        head = (head + 1) % bars
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
        val slot = w / bars
        val barW = slot * 0.55f
        val twoPi = (2 * PI).toFloat()
        paint.color = Theme.alpha(Theme.phaseColor(phase), if (phase == Phase.IDLE) 90 else 230)
        for (i in 0 until bars) {
            val v = when (phase) {
                Phase.LISTENING -> history[(head + i) % bars]
                Phase.SPEAKING -> 0.2f + 0.7f * abs(sin(twoPi * (t * 3f) + i * 0.45f)) * (0.55f + 0.45f * sin(twoPi * t + i * 0.2f))
                Phase.PROCESSING -> 0.1f + 0.25f * (0.5f + 0.5f * sin(twoPi * (t * 4f) - i * 0.5f))
                Phase.IDLE -> 0.03f
            }.coerceIn(0.03f, 1f)
            val bh = h * v
            val x = i * slot + (slot - barW) / 2f
            rect.set(x, (h - bh) / 2f, x + barW, (h + bh) / 2f)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
        }
    }
}
