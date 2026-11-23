package com.neverhide.empire.noor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The NOOR gate screen — drawn by hand so it can live in a system overlay.
 *
 * V2.13.8 (2026-11-23): the owner wanted a gate that is impossible to ignore
 * and beautiful at the same time: a deep gradient, slow breathing light rings,
 * the dhikr in Arabic with its transliteration, and three dots that fill as the
 * taps land.
 *
 * Deliberately a plain View: a ComposeView inside a TYPE_APPLICATION_OVERLAY
 * needs its own saved-state owner and crashes on some One UI builds. Canvas
 * drawing has no lifecycle to get wrong.
 */
class NoorGateView(
    context: Context,
    private val onComplete: () -> Unit
) : View(context) {

    companion object {
        const val REQUIRED_TAPS = 3
        private const val HOLD_TO_ESCAPE_MS = 4000L
    }

    private var taps = 0
    private var pulse = 0f
    private var tapFlash = 0f
    private var holdProgress = 0f
    private var holdStart = 0L
    private var finished = false

    private val main = Handler(Looper.getMainLooper())

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arabicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F3E9D2")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.BOLD)
    }
    private val latinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9FB8AD")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5E7A70")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#C9A227")
    }

    private val breath = object : Runnable {
        override fun run() {
            pulse += 0.012f
            if (pulse > 1f) pulse = 0f
            if (tapFlash > 0f) tapFlash -= 0.05f
            invalidate()
            main.postDelayed(this, 33L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        main.post(breath)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgPaint.shader = RadialGradient(
            w / 2f, h * 0.42f, (maxOf(w, h) * 0.75f),
            intArrayOf(
                Color.parseColor("#12352C"),
                Color.parseColor("#0B1F1A"),
                Color.parseColor("#050B0A")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        arabicPaint.textSize = w * 0.155f
        latinPaint.textSize = w * 0.052f
        hintPaint.textSize = w * 0.036f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cx = w / 2f
        val cy = h * 0.40f

        // Breathing light rings: three, offset so they never line up.
        for (i in 0 until 3) {
            val phase = (pulse + i * 0.33f) % 1f
            val radius = w * (0.22f + phase * 0.46f)
            val alpha = ((1f - phase) * 90).toInt().coerceIn(0, 255)
            ringPaint.color = Color.argb(alpha, 201, 162, 39)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        // Soft core glow that brightens with every tap.
        val core = w * (0.20f + taps * 0.012f)
        glowPaint.color = Color.argb(
            (26 + taps * 22 + (tapFlash * 60).toInt()).coerceIn(0, 120), 201, 162, 39
        )
        canvas.drawCircle(cx, cy, core, glowPaint)

        // The dhikr.
        canvas.drawText("أستغفر الله", cx, cy + arabicPaint.textSize * 0.35f, arabicPaint)
        canvas.drawText("ASTAGHFIRULLAH", cx, cy + arabicPaint.textSize * 0.95f, latinPaint)

        // Three dots, filled as taps land.
        val dotR = w * 0.016f
        val gap = w * 0.075f
        val startX = cx - gap
        val dotY = cy + arabicPaint.textSize * 1.55f
        for (i in 0 until REQUIRED_TAPS) {
            val filled = i < taps
            dotPaint.color = if (filled) Color.parseColor("#C9A227") else Color.parseColor("#24413A")
            canvas.drawCircle(startX + i * gap, dotY, if (filled) dotR * 1.25f else dotR, dotPaint)
        }

        // Instruction, changes once you start.
        val hint = when {
            taps == 0 -> "Tap three times to begin your day"
            taps < REQUIRED_TAPS -> "${REQUIRED_TAPS - taps} more"
            else -> "Alhamdulillah"
        }
        canvas.drawText(hint, cx, dotY + w * 0.13f, hintPaint)

        // Hold-to-escape ring, for emergencies only.
        if (holdProgress > 0f) {
            canvas.drawArc(
                cx - w * 0.09f, dotY + w * 0.20f, cx + w * 0.09f, dotY + w * 0.38f,
                -90f, 360f * holdProgress, false, holdPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (finished) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdStart = System.currentTimeMillis()
                holdTick()
                return true
            }
            MotionEvent.ACTION_UP -> {
                holdProgress = 0f
                invalidate()
                if (System.currentTimeMillis() - holdStart < HOLD_TO_ESCAPE_MS) {
                    registerTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                holdProgress = 0f
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun registerTap() {
        if (finished) return
        taps++
        tapFlash = 1f
        animate().cancel()
        animate().scaleX(1.02f).scaleY(1.02f).setDuration(70).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(140)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
        invalidate()
        if (taps >= REQUIRED_TAPS) {
            finished = true
            animate().alpha(0f).setDuration(420).withEndAction { onComplete() }.start()
        }
    }

    /** Long press anywhere for four seconds releases the gate. Safety valve. */
    private fun holdTick() {
        if (finished) return
        val held = System.currentTimeMillis() - holdStart
        holdProgress = (held.toFloat() / HOLD_TO_ESCAPE_MS).coerceIn(0f, 1f)
        invalidate()
        if (held >= HOLD_TO_ESCAPE_MS) {
            finished = true
            onComplete()
            return
        }
        main.postDelayed({ holdTick() }, 60L)
    }
}
