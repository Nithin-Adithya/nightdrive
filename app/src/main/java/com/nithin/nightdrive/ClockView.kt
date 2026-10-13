package com.nithin.nightdrive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The clock faces the user can pick. Analog faces have a second hand, so they redraw every second. */
enum class ClockStyle(val label: Int, val seconds: Boolean) {
    DIGITAL_LIGHT(R.string.clock_digital_light, false),
    DIGITAL_BOLD(R.string.clock_digital_bold, false),
    DIGITAL_STACKED(R.string.clock_digital_stacked, false),
    DIGITAL_FLIP(R.string.clock_digital_flip, false),
    ANALOG_CLASSIC(R.string.clock_analog_classic, true),
    ANALOG_MINIMAL(R.string.clock_analog_minimal, true),
    ANALOG_CHRONO(R.string.clock_analog_chrono, true)
}

/**
 * Draws the clock widget in the chosen [style]. Everything is sized from the view's own bounds,
 * so the same face works in a small or a large home screen slot.
 */
class ClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var style = ClockStyle.DIGITAL_LIGHT
        set(value) {
            field = value
            invalidate()
        }

    /** Light appearance: text and hands drawn dark for a light card. */
    var light = false
        set(value) {
            field = value
            invalidate()
        }

    /** Analog second hand glides instead of ticking. Redraws about 20 times a second. */
    var sweep = false
        set(value) {
            field = value
            invalidate()
        }

    private val extraLight = resources.getFont(R.font.inter_extralight)
    private val regular = resources.getFont(R.font.inter_regular)
    private val semibold = resources.getFont(R.font.inter_semibold)
    private val bold = resources.getFont(R.font.inter_bold)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val now = Calendar.getInstance()
    private val tick = Runnable { invalidate() }
    private var cx = 0f
    private var cy = 0f

    private val primary get() = if (light) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val secondary get() = if (light) 0x993C3C43.toInt() else 0x99EBEBF5.toInt()

    override fun onDraw(canvas: Canvas) {
        drawClock(canvas, width.toFloat(), height.toFloat())
        // Redraw on the next second (analog) or minute (digital) boundary.
        val period = when {
            !style.seconds -> 60_000L
            sweep -> 50L
            else -> 1000L
        }
        removeCallbacks(tick)
        postDelayed(tick, period - System.currentTimeMillis() % period + 10)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate() else removeCallbacks(tick)
    }

    private fun drawClock(c: Canvas, w: Float, h: Float) {
        now.timeZone = java.util.TimeZone.getDefault()
        now.timeInMillis = System.currentTimeMillis()
        cx = w / 2
        cy = h / 2
        when (style) {
            ClockStyle.DIGITAL_LIGHT -> digitalLight(c, w, h)
            ClockStyle.DIGITAL_BOLD -> digitalBold(c, w, h)
            ClockStyle.DIGITAL_STACKED -> digitalStacked(c, w, h)
            ClockStyle.DIGITAL_FLIP -> digitalFlip(c, w, h)
            ClockStyle.ANALOG_CLASSIC -> analogClassic(c, min(w, h) * 0.43f)
            ClockStyle.ANALOG_MINIMAL -> analogMinimal(c, min(w, h) * 0.43f)
            ClockStyle.ANALOG_CHRONO -> analogChrono(c, min(w, h) * 0.43f)
        }
    }

    // ---- Digital ----------------------------------------------------------------------------

    private fun hourText(): String {
        if (DateFormat.is24HourFormat(context)) return "%02d".format(now.get(Calendar.HOUR_OF_DAY))
        val hour = now.get(Calendar.HOUR)
        return (if (hour == 0) 12 else hour).toString()
    }

    private fun minuteText() = "%02d".format(now.get(Calendar.MINUTE))

    private fun date(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(now.time)

    private fun text(
        c: Canvas, value: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int,
        align: Paint.Align = Paint.Align.CENTER, spacing: Float = 0f
    ) {
        paint.style = Paint.Style.FILL
        paint.typeface = face
        paint.textSize = size
        paint.color = color
        paint.textAlign = align
        paint.letterSpacing = spacing
        c.drawText(value, x, baseline, paint)
    }

    private fun width(value: String, size: Float, face: Typeface): Float {
        paint.typeface = face
        paint.textSize = size
        paint.letterSpacing = 0f
        return paint.measureText(value)
    }

    /** Small red date above a very thin, very large time. */
    private fun digitalLight(c: Canvas, w: Float, h: Float) {
        val size = min(h * 0.46f, w * 0.36f)
        val dateSize = size * 0.16f
        val gap = size * 0.16f
        val top = cy - (dateSize * CAP + gap + size * CAP) / 2
        val dateBase = top + dateSize * CAP
        text(c, date("EEE d MMM").uppercase(), cx, dateBase, dateSize, semibold, RED, spacing = 0.1f)
        text(c, "${hourText()}:${minuteText()}", cx, dateBase + gap + size * CAP, size, extraLight, primary, spacing = -0.02f)
    }

    /** Heavy numerals with the minutes in orange, date underneath. */
    private fun digitalBold(c: Canvas, w: Float, h: Float) {
        val size = min(h * 0.4f, w * 0.31f)
        val dateSize = size * 0.19f
        val gap = size * 0.2f
        val parts = listOf(hourText() to primary, ":" to secondary, minuteText() to ORANGE)
        val total = parts.sumOf { width(it.first, size, bold).toDouble() }.toFloat()
        val base = cy - (size * CAP + gap + dateSize * CAP) / 2 + size * CAP
        var x = cx - total / 2
        for ((value, color) in parts) {
            text(c, value, x, base, size, bold, color, Paint.Align.LEFT)
            x += width(value, size, bold)
        }
        text(c, date("EEEE, d MMMM"), cx, base + gap + dateSize * CAP, dateSize, regular, secondary)
    }

    /** Hours over minutes, with the day and date in a column beside them. */
    private fun digitalStacked(c: Canvas, w: Float, h: Float) {
        val size = min(h * 0.4f, w * 0.34f)
        val gap = size * 0.14f
        val small = size * 0.2f
        val top = cy - (2 * size * CAP + gap) / 2
        val hourBase = top + size * CAP
        val minuteBase = hourBase + gap + size * CAP
        val digitsRight = cx + size * 0.2f
        text(c, hourText(), digitsRight, hourBase, size, bold, primary, Paint.Align.RIGHT, -0.02f)
        text(c, minuteText(), digitsRight, minuteBase, size, bold, ORANGE, Paint.Align.RIGHT, -0.02f)
        val side = digitsRight + size * 0.22f
        text(c, date("EEE").uppercase(), side, top + small * CAP, small, semibold, RED, Paint.Align.LEFT, 0.08f)
        text(c, date("d"), side, top + small * CAP + small * 1.9f, small * 1.7f, extraLight, primary, Paint.Align.LEFT)
        text(c, date("MMM").uppercase(), side, minuteBase, small, semibold, secondary, Paint.Align.LEFT, 0.08f)
    }

    /** Two flip-clock tiles. */
    private fun digitalFlip(c: Canvas, w: Float, h: Float) {
        val tileW = min(w * 0.37f, h * 0.5f)
        val tileH = tileW * 1.08f
        val gap = tileW * 0.09f
        val dateSize = tileH * 0.12f
        val top = cy - (tileH + gap * 1.6f + dateSize * CAP) / 2
        val left = cx - tileW - gap / 2
        val size = tileH * 0.62f
        listOf(hourText(), minuteText()).forEachIndexed { i, value ->
            val x = left + i * (tileW + gap)
            paint.style = Paint.Style.FILL
            paint.color = if (light) 0xFF1C1C1E.toInt() else 0xFF3A3A3C.toInt()
            c.drawRoundRect(x, top, x + tileW, top + tileH, tileW * 0.13f, tileW * 0.13f, paint)
            text(c, value, x + tileW / 2, top + tileH / 2 + size * CAP / 2, size, bold, 0xFFFFFFFF.toInt(), spacing = -0.02f)
            // The hinge line across the middle of each tile.
            paint.color = 0xB3000000.toInt()
            c.drawRect(x, top + tileH / 2 - tileH * 0.008f, x + tileW, top + tileH / 2 + tileH * 0.008f, paint)
        }
        text(c, date("EEEE, d MMMM"), cx, top + tileH + gap * 1.6f + dateSize * CAP, dateSize, regular, secondary)
    }

    // ---- Analog -----------------------------------------------------------------------------

    private val hourAngle get() = (now.get(Calendar.HOUR) + now.get(Calendar.MINUTE) / 60f) * 30f
    private val minuteAngle get() = (now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f) * 6f
    private val secondAngle: Float
        get() {
            val fraction = if (sweep) now.get(Calendar.MILLISECOND) / 1000f else 0f
            return (now.get(Calendar.SECOND) + fraction) * 6f
        }

    /** A line from [from] to [to] (distances from the centre) at [degrees] clockwise from 12. */
    private fun hand(c: Canvas, degrees: Float, from: Float, to: Float, width: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = width
        paint.color = color
        c.save()
        c.rotate(degrees, cx, cy)
        c.drawLine(cx, cy - from, cx, cy - to, paint)
        c.restore()
    }

    private fun dot(c: Canvas, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawCircle(cx, cy, radius, paint)
    }

    private fun numeral(c: Canvas, value: String, hour: Int, distance: Float, size: Float, color: Int) {
        val angle = Math.toRadians(hour * 30.0)
        val x = cx + sin(angle).toFloat() * distance
        val y = cy - cos(angle).toFloat() * distance + size * CAP / 2
        text(c, value, x, y, size, semibold, color)
    }

    private fun secondHand(c: Canvas, r: Float, color: Int) {
        hand(c, secondAngle, -r * 0.16f, r * 0.84f, r * 0.02f, color)
        dot(c, r * 0.05f, color)
    }

    /** White dial with all twelve numerals and dark hands. */
    private fun analogClassic(c: Canvas, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        paint.setShadowLayer(r * 0.08f, 0f, r * 0.03f, 0x40000000)
        c.drawCircle(cx, cy, r, paint)
        paint.clearShadowLayer()

        val ink = 0xFF1C1C1E.toInt()
        for (i in 0 until 60) {
            if (i % 5 != 0) hand(c, i * 6f, r * 0.93f, r * 0.96f, r * 0.012f, 0x661C1C1E)
        }
        for (hour in 1..12) numeral(c, hour.toString(), hour, r * 0.78f, r * 0.2f, ink)
        hand(c, hourAngle, 0f, r * 0.47f, r * 0.075f, ink)
        hand(c, minuteAngle, 0f, r * 0.72f, r * 0.055f, ink)
        secondHand(c, r, ORANGE)
        dot(c, r * 0.02f, 0xFFFFFFFF.toInt())
    }

    /** No dial at all: twelve marks and three hands straight on the card. */
    private fun analogMinimal(c: Canvas, r: Float) {
        val faint = (primary and 0x00FFFFFF) or 0x66000000
        for (hour in 0 until 12) {
            val quarter = hour % 3 == 0
            hand(c, hour * 30f, r * (if (quarter) 0.8f else 0.86f), r * 0.97f, r * 0.03f, if (quarter) primary else faint)
        }
        hand(c, hourAngle, 0f, r * 0.5f, r * 0.085f, primary)
        hand(c, minuteAngle, 0f, r * 0.78f, r * 0.06f, primary)
        secondHand(c, r, ORANGE)
    }

    /** Black instrument dial: minute track, bold hour marks, date window, lume-style hands. */
    private fun analogChrono(c: Canvas, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFF000000.toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.03f
        paint.color = 0xFF3A3A3C.toInt()
        c.drawCircle(cx, cy, r - r * 0.015f, paint)

        val white = 0xFFFFFFFF.toInt()
        for (i in 0 until 60) {
            if (i % 5 == 0) {
                hand(c, i * 6f, r * 0.82f, r * 0.92f, r * 0.03f, white)
            } else {
                hand(c, i * 6f, r * 0.88f, r * 0.92f, r * 0.012f, 0x80FFFFFF.toInt())
            }
        }
        for (hour in intArrayOf(12, 6, 9)) numeral(c, hour.toString(), hour, r * 0.62f, r * 0.22f, white)

        // Date window where the 3 would be.
        val boxW = r * 0.3f
        val boxH = r * 0.22f
        val boxX = cx + r * 0.6f
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1C1C1E.toInt()
        c.drawRoundRect(boxX - boxW / 2, cy - boxH / 2, boxX + boxW / 2, cy + boxH / 2, boxH * 0.25f, boxH * 0.25f, paint)
        val daySize = boxH * 0.72f
        text(c, date("d"), boxX, cy + daySize * CAP / 2, daySize, bold, ORANGE)

        // Each hand is a thin stem that widens into a white blade.
        hand(c, hourAngle, 0f, r * 0.16f, r * 0.03f, white)
        hand(c, hourAngle, r * 0.16f, r * 0.5f, r * 0.09f, white)
        hand(c, minuteAngle, 0f, r * 0.16f, r * 0.03f, white)
        hand(c, minuteAngle, r * 0.16f, r * 0.78f, r * 0.07f, white)
        secondHand(c, r, RED)
        dot(c, r * 0.02f, 0xFF000000.toInt())
    }

    companion object {
        /** Cap height of Inter as a fraction of the text size; used to centre numerals. */
        private const val CAP = 0.727f
        private const val ORANGE = 0xFFFF9F0A.toInt()
        private const val RED = 0xFFFF5A4F.toInt()

        /** A small picture of [style] for the style chooser. */
        fun preview(context: Context, style: ClockStyle, sizePx: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val size = sizePx.toFloat()
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
            canvas.drawRoundRect(0f, 0f, size, size, size * 0.22f, size * 0.22f, background)
            ClockView(context).also { it.style = style }.drawClock(canvas, size, size)
            return bitmap
        }
    }
}
