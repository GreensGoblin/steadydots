package uk.co.steadydots

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Full-screen transparent view that draws the motion-cue dots.
 *
 * The dots sit on a virtual spring. The vehicle's acceleration (already converted to
 * screen directions by OverlayService) pushes them around, so they move the way your
 * inner ear feels the motion:
 *   - braking      -> dots slide up   (towards the road ahead)
 *   - accelerating -> dots slide down
 *   - turning left -> dots slide right (and vice versa)
 * When the motion stops the spring brings them smoothly back home.
 */
@SuppressLint("ViewConstructor")
class DotsOverlayView(context: Context) : View(context) {

    private companion object {
        // Spring stiffness range (1/s²), picked by the Responsiveness slider.
        // Higher = dots react faster. Default (60%) ≈ 52, about twice as quick as v1.
        const val MIN_STIFFNESS = 10f
        const val MAX_STIFFNESS = 80f
        const val DAMPING_RATIO = 0.8f                  // slightly under-damped: smooth, no wobble
        const val DISPLACE_DP_PER_MS2 = 12f             // steady offset per 1 m/s² (at 100% sensitivity)
        const val MAX_OFFSET_DP = 90f                   // soft limit on sideways travel
        const val MAX_STEP = 1f / 120f                  // physics sub-step (s)
    }

    private val density = resources.displayMetrics.density
    private var cfg = Prefs.load(context)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // Input acceleration in screen coordinates (m/s², +x = right, +y = down)
    private var inX = 0f
    private var inY = 0f

    // Spring state in pixels
    private var posX = 0f
    private var posY = 0f
    private var velX = 0f
    private var velY = 0f
    private var lastFrameNanos = 0L

    init {
        applyConfig(cfg)
    }

    fun applyConfig(c: Prefs.Config) {
        cfg = c
        // Dark dot with a light ring: visible on both light and dark apps
        fill.color = Color.argb((c.opacity * 255).toInt(), 20, 20, 20)
        ring.color = Color.argb((c.opacity * 230).toInt(), 255, 255, 255)
        ring.strokeWidth = 1.5f * density
        invalidate()
    }

    /** Called from the sensor listener (main thread) with screen-space acceleration. */
    fun setAcceleration(x: Float, y: Float) {
        inX = x
        inY = y
        postInvalidateOnAnimation()
    }

    private fun integrate(dt: Float) {
        val k = MIN_STIFFNESS + (MAX_STIFFNESS - MIN_STIFFNESS) * cfg.response
        val c = 2f * sqrt(k) * DAMPING_RATIO
        // Gain scales with stiffness so the resting offset for a given acceleration
        // stays the same; only the speed of reaction changes.
        val gain = k * DISPLACE_DP_PER_MS2 * density * cfg.sensitivity
        var remaining = dt
        while (remaining > 0f) {
            val h = minOf(remaining, MAX_STEP)
            val ax = -k * posX - c * velX + inX * gain
            val ay = -k * posY - c * velY + inY * gain
            velX += ax * h
            velY += ay * h
            posX += velX * h
            posY += velY * h
            remaining -= h
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f
                 else ((now - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = now
        integrate(dt)

        val spacing = cfg.spacingDp * density
        val radius = cfg.sizeDp * density / 2f

        // Sideways: soft-clamped so edge dots never wander into the middle of the screen
        val maxOff = MAX_OFFSET_DP * density
        val offX = maxOff * tanh(posX / maxOff)
        // Up/down: the grid repeats, so wrap the offset and it looks like endless flow
        val offY = ((posY % spacing) + spacing) % spacing

        val columnsX = ArrayList<Float>()
        if (cfg.edgesOnly) {
            val margin = spacing * 0.6f
            for (i in 0 until cfg.columns) {
                columnsX += margin + i * spacing
                columnsX += width - margin - i * spacing
            }
        } else {
            var x = spacing / 2f
            while (x < width) { columnsX += x; x += spacing }
        }

        var y = -spacing + offY
        while (y < height + spacing) {
            for (bx in columnsX) drawMark(canvas, bx + offX, y, radius)
            y += spacing
        }

        // Keep animating until the spring has settled, then sleep until the next sensor event
        val moving = abs(velX) > 1f || abs(velY) > 1f ||
                     abs(posX) > 0.5f || abs(posY) > 0.5f ||
                     abs(inX) > 0.05f || abs(inY) > 0.05f
        if (moving) postInvalidateOnAnimation() else lastFrameNanos = 0L
    }

    private fun drawMark(canvas: Canvas, x: Float, y: Float, r: Float) {
        if (cfg.lines) {
            val halfW = r * 2f
            val halfH = r * 0.45f
            canvas.drawRoundRect(x - halfW, y - halfH, x + halfW, y + halfH, halfH, halfH, fill)
            canvas.drawRoundRect(x - halfW, y - halfH, x + halfW, y + halfH, halfH, halfH, ring)
        } else {
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, ring)
        }
    }
}
