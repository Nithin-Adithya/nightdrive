package com.nithin.nightdrive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Turns any app icon into a uniform tile: a smooth "squircle" with a soft drop shadow and a
 * hairline edge. Common system apps (phone, messages, settings…) get a drawn icon in one
 * consistent style; everything else keeps its own artwork inside the same shape.
 *
 * Call off the main thread when rendering many icons.
 */
object IconRenderer {

    private const val SHADOW_FRACTION = 0.085f

    /** [viewPx] is the whole bitmap; the tile sits in the centre with room for its shadow. */
    fun render(
        context: Context, packageName: String, icon: Drawable, viewPx: Int,
        label: String = "", system: Boolean = false
    ): Bitmap =
        tile(viewPx) { canvas, size ->
            val pkg = packageName.lowercase(Locale.ROOT)
            val name = label.lowercase(Locale.ROOT)
            if (!drawThemed(context, canvas, pkg, name, system, size.toFloat())) {
                drawOwnIcon(canvas, icon, size)
            }
        }

    /** Icon for the launcher's own "Themes" entry in the app grid. */
    fun renderThemes(viewPx: Int): Bitmap = tile(viewPx) { canvas, size ->
        val s = size.toFloat()
        gradient(canvas, s, 0xFF34363C.toInt(), 0xFF0E0F12.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val discs = listOf(
            Triple(0.37f, 0.39f, 0xFF3B8FE0.toInt() to 0xFF6CC0EA.toInt()),
            Triple(0.63f, 0.41f, 0xFFF6B85C.toInt() to 0xFFF28B4B.toInt()),
            Triple(0.5f, 0.63f, 0xFF58C3D9.toInt() to 0xFF9ADFC6.toInt())
        )
        for ((x, y, colors) in discs) {
            paint.shader = LinearGradient(
                0f, s * (y - 0.23f), 0f, s * (y + 0.23f), colors.first, colors.second,
                Shader.TileMode.CLAMP
            )
            paint.alpha = 235
            canvas.drawCircle(s * x, s * y, s * 0.23f, paint)
        }
    }

    private fun tile(viewPx: Int, drawFace: (Canvas, Int) -> Unit): Bitmap {
        val pad = (viewPx * SHADOW_FRACTION).toInt()
        val size = viewPx - 2 * pad

        val face = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawFace(Canvas(face), size)

        val out = Bitmap.createBitmap(viewPx, viewPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shape = squircle(size.toFloat()).apply { offset(pad.toFloat(), pad.toFloat()) }

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(pad * 0.85f, 0f, pad * 0.45f, 0x59000000)
        }
        canvas.drawPath(shape, shadow)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(pad.toFloat(), pad.toFloat()) })
            }
        }
        canvas.drawPath(shape, fill)

        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0x24FFFFFF
        }
        canvas.drawPath(shape, edge)
        face.recycle()
        return out
    }

    /** Superellipse: corners that curve continuously instead of a circle arc meeting a line. */
    private fun squircle(size: Float): Path {
        val r = size / 2f
        val exponent = 2.0 / 5.0
        val path = Path()
        for (deg in 0 until 360 step 3) {
            val t = Math.toRadians(deg.toDouble())
            val x = r + r * (sign(cos(t)) * abs(cos(t)).pow(exponent)).toFloat()
            val y = r + r * (sign(sin(t)) * abs(sin(t)).pow(exponent)).toFloat()
            if (deg == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    // ---- Third-party icons ------------------------------------------------------------------

    private fun drawOwnIcon(canvas: Canvas, icon: Drawable, size: Int) {
        if (icon is AdaptiveIconDrawable) {
            // Adaptive layers are 108dp with a 72dp visible centre, so overdraw by a quarter each side.
            val bleed = size / 4
            canvas.drawColor(Color.WHITE)
            for (layer in arrayOf(icon.background, icon.foreground)) {
                layer?.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                layer?.draw(canvas)
            }
            return
        }
        // Legacy icon: square artwork goes edge to edge, anything else sits on a light tile.
        val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(raw))
        val near = (size * 0.06f).toInt()
        val far = size - 1 - near
        val square = listOf(near to near, far to near, near to far, far to far)
            .all { (x, y) -> Color.alpha(raw.getPixel(x, y)) > 240 }
        raw.recycle()
        if (square) {
            icon.draw(canvas)
        } else {
            gradient(canvas, size.toFloat(), 0xFFFFFFFF.toInt(), 0xFFE6E8EC.toInt())
            val inset = (size * 0.13f).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(canvas)
        }
    }

    // ---- Drawn icons ------------------------------------------------------------------------

    private fun drawThemed(
        context: Context, c: Canvas, pkg: String, label: String, system: Boolean, s: Float
    ): Boolean {
        when {
            pkg.contains("dialer") || pkg == "com.android.phone" ->
                glyphTile(context, c, s, 0xFF6BEA7A.toInt(), 0xFF17B537.toInt(), R.drawable.ic_phone)
            pkg.contains("messaging") || pkg.endsWith(".mms") ->
                glyphTile(context, c, s, 0xFF6BEA7A.toInt(), 0xFF17B537.toInt(), R.drawable.ic_glyph_message)
            pkg.contains("contacts") ->
                glyphTile(context, c, s, 0xFFC2C6CE.toInt(), 0xFF868C96.toInt(), R.drawable.ic_glyph_person)
            pkg.contains("documentsui") || pkg.contains("filemanager") || pkg.contains("fileexplorer") ->
                glyphTile(context, c, s, 0xFF5CB8FF.toInt(), 0xFF1672EE.toInt(), R.drawable.ic_glyph_folder)
            pkg.contains("calendar") -> calendar(context, c, s)
            pkg.contains("deskclock") || pkg.endsWith(".clock") -> clock(c, s)
            pkg.contains("camera") -> camera(c, s)
            pkg == "com.android.settings" -> gear(c, s)
            else -> if (!drawByKind(context, c, pkg, label, system, s)) return false
        }
        // Soft light from above, shared by every drawn icon.
        val gloss = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, s * 0.6f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, s, s, gloss)
        return true
    }

    private fun gradient(c: Canvas, s: Float, top: Int, bottom: Int) {
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, s, top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, s, s, paint)
    }

    private fun glyphTile(context: Context, c: Canvas, s: Float, top: Int, bottom: Int, glyph: Int) {
        gradient(c, s, top, bottom)
        val drawable = context.getDrawable(glyph)?.mutate() ?: return
        val inset = (s * 0.2f).toInt()
        drawable.setTint(Color.WHITE)
        drawable.setBounds(inset, inset, s.toInt() - inset, s.toInt() - inset)
        drawable.draw(c)
    }

    private fun calendar(context: Context, c: Canvas, s: Float) {
        c.drawColor(Color.WHITE)
        val now = Calendar.getInstance()
        val weekday = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF4A3D.toInt()
            textAlign = Paint.Align.CENTER
            textSize = s * 0.2f
            typeface = context.resources.getFont(R.font.inter_semibold)
        }
        val day = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1C1C1E.toInt()
            textAlign = Paint.Align.CENTER
            textSize = s * 0.52f
            typeface = context.resources.getFont(R.font.inter_light)
        }
        val name = SimpleDateFormat("EEE", Locale.getDefault()).format(now.time).uppercase()
        c.drawText(name, s / 2, s * 0.29f, weekday)
        c.drawText(now.get(Calendar.DAY_OF_MONTH).toString(), s / 2, s * 0.8f, day)
    }

    private fun clock(c: Canvas, s: Float) {
        gradient(c, s, 0xFF2C2C30.toInt(), 0xFF050506.toInt())
        val mid = s / 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        c.drawCircle(mid, mid, s * 0.4f, paint)

        paint.color = 0xFF1C1C1E.toInt()
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = s * 0.018f
        for (i in 0 until 12) {
            c.save()
            c.rotate(i * 30f, mid, mid)
            c.drawLine(mid, s * 0.14f, mid, s * 0.19f, paint)
            c.restore()
        }
        hand(c, mid, 305f, s * 0.2f, s * 0.04f, paint)   // hour, just past ten
        hand(c, mid, 54f, s * 0.31f, s * 0.03f, paint)   // minute
        paint.color = 0xFFFF7A2E.toInt()
        hand(c, mid, 180f, s * 0.33f, s * 0.012f, paint) // second
        c.drawCircle(mid, mid, s * 0.03f, paint)
    }

    private fun hand(c: Canvas, mid: Float, degrees: Float, length: Float, width: Float, paint: Paint) {
        paint.strokeWidth = width
        c.save()
        c.rotate(degrees, mid, mid)
        c.drawLine(mid, mid, mid, mid - length, paint)
        c.restore()
    }

    private fun camera(c: Canvas, s: Float) {
        gradient(c, s, 0xFFE9EBEF.toInt(), 0xFFAEB2BB.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF2A2C31.toInt()
        c.drawRoundRect(s * 0.39f, s * 0.26f, s * 0.61f, s * 0.42f, s * 0.04f, s * 0.04f, paint)
        c.drawRoundRect(s * 0.18f, s * 0.33f, s * 0.82f, s * 0.73f, s * 0.085f, s * 0.085f, paint)
        paint.color = 0xFFD5D8DE.toInt()
        c.drawCircle(s / 2, s * 0.53f, s * 0.14f, paint)
        paint.color = 0xFF2A2C31.toInt()
        c.drawCircle(s / 2, s * 0.53f, s * 0.095f, paint)
        paint.color = 0xFF6F7682.toInt()
        c.drawCircle(s * 0.47f, s * 0.5f, s * 0.03f, paint)
    }

    /**
     * Built-in head unit apps (radio, Bluetooth, video…) vary by maker, so these are matched by
     * keywords in the package or label. Only apps that came with the unit are redrawn; anything
     * the user installed keeps its own icon.
     */
    private fun drawByKind(
        context: Context, c: Canvas, pkg: String, label: String, system: Boolean, s: Float
    ): Boolean {
        if (!system || BRANDS.any { pkg.startsWith(it) }) return false
        fun has(vararg words: String) = words.any { pkg.contains(it) || label.contains(it) }
        when {
            has("bluetooth") || label == "bt" || label.startsWith("bt ") -> bluetooth(c, s)
            has("radio") || label == "fm" -> radio(c, s)
            has("equalizer", "equaliser", "amplifier") || label == "eq" || label == "dsp" -> equalizer(c, s)
            has("calculator") -> calculator(c, s)
            has("gallery") -> gallery(c, s)
            has("steering") || label == "swc" -> wheel(c, s)
            has("video", "movie") -> video(c, s)
            has("music") ->
                glyphTile(context, c, s, 0xFFFF6B7D.toInt(), 0xFFE0214F.toInt(), R.drawable.ic_music)
            else -> return false
        }
        return true
    }

    private fun stroke(width: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun bluetooth(c: Canvas, s: Float) {
        gradient(c, s, 0xFF4DA3FF.toInt(), 0xFF0A60FF.toInt())
        val rune = Path().apply {
            moveTo(s * 0.34f, s * 0.36f)
            lineTo(s * 0.66f, s * 0.64f)
            lineTo(s * 0.5f, s * 0.8f)
            lineTo(s * 0.5f, s * 0.2f)
            lineTo(s * 0.66f, s * 0.36f)
            lineTo(s * 0.34f, s * 0.64f)
        }
        c.drawPath(rune, stroke(s * 0.065f, Color.WHITE))
    }

    private fun radio(c: Canvas, s: Float) {
        gradient(c, s, 0xFFFF9F45.toInt(), 0xFFFF4A2E.toInt())
        val x = s / 2
        val y = s * 0.46f
        val wave = stroke(s * 0.055f, Color.WHITE)
        for (r in floatArrayOf(s * 0.16f, s * 0.28f)) {
            c.drawArc(x - r, y - r, x + r, y + r, -45f, 90f, false, wave)
            c.drawArc(x - r, y - r, x + r, y + r, 135f, 90f, false, wave)
        }
        c.drawLine(x, y, x, s * 0.8f, wave)
        c.drawCircle(x, y, s * 0.065f, fill(Color.WHITE))
    }

    private fun equalizer(c: Canvas, s: Float) {
        gradient(c, s, 0xFF3A3A3F.toInt(), 0xFF121214.toInt())
        val track = stroke(s * 0.045f, 0x66FFFFFF)
        val knobs = floatArrayOf(0.4f, 0.62f, 0.34f)
        floatArrayOf(0.3f, 0.5f, 0.7f).forEachIndexed { i, x ->
            c.drawLine(s * x, s * 0.24f, s * x, s * 0.76f, track)
            c.drawCircle(s * x, s * knobs[i], s * 0.08f, fill(if (i == 1) 0xFFFF9F0A.toInt() else Color.WHITE))
        }
    }

    private fun calculator(c: Canvas, s: Float) {
        gradient(c, s, 0xFF3A3A3F.toInt(), 0xFF121214.toInt())
        floatArrayOf(0.31f, 0.5f, 0.69f).forEachIndexed { row, y ->
            floatArrayOf(0.29f, 0.5f, 0.71f).forEachIndexed { column, x ->
                val color = when {
                    column == 2 -> 0xFFFF9F0A.toInt()
                    row == 0 -> 0xFFA5A5AA.toInt()
                    else -> 0xFF5A5A5F.toInt()
                }
                c.drawCircle(s * x, s * y, s * 0.085f, fill(color))
            }
        }
    }

    private fun gallery(c: Canvas, s: Float) {
        gradient(c, s, 0xFF8FD3FF.toInt(), 0xFFE8F6FF.toInt())
        c.drawCircle(s * 0.69f, s * 0.31f, s * 0.085f, fill(0xFFFFCC00.toInt()))
        val back = Path().apply {
            moveTo(s * 0.4f, s)
            lineTo(s * 0.68f, s * 0.5f)
            lineTo(s, s * 0.86f)
            lineTo(s, s)
            close()
        }
        c.drawPath(back, fill(0xFF30B0C7.toInt()))
        val front = Path().apply {
            moveTo(0f, s * 0.9f)
            lineTo(s * 0.36f, s * 0.44f)
            lineTo(s * 0.78f, s)
            lineTo(0f, s)
            close()
        }
        c.drawPath(front, fill(0xFF34C759.toInt()))
    }

    private fun wheel(c: Canvas, s: Float) {
        gradient(c, s, 0xFFB9BDC5.toInt(), 0xFF63686F.toInt())
        val mid = s / 2
        val dark = 0xFF2B2D32.toInt()
        c.drawCircle(mid, mid, s * 0.29f, stroke(s * 0.075f, dark))
        val spoke = stroke(s * 0.07f, dark)
        c.drawLine(mid - s * 0.27f, mid, mid + s * 0.27f, mid, spoke)
        c.drawLine(mid, mid, mid, mid + s * 0.27f, spoke)
        c.drawCircle(mid, mid, s * 0.085f, fill(dark))
    }

    private fun video(c: Canvas, s: Float) {
        gradient(c, s, 0xFFB36BFF.toInt(), 0xFF6A2CF0.toInt())
        c.drawRoundRect(s * 0.2f, s * 0.29f, s * 0.8f, s * 0.71f, s * 0.09f, s * 0.09f, fill(Color.WHITE))
        val play = Path().apply {
            moveTo(s * 0.44f, s * 0.4f)
            lineTo(s * 0.61f, s * 0.5f)
            lineTo(s * 0.44f, s * 0.6f)
            close()
        }
        c.drawPath(play, fill(0xFF7A3CF5.toInt()))
    }

    /** Well-known apps keep their own artwork even when preinstalled. */
    private val BRANDS = listOf(
        "com.google.", "com.spotify.", "com.netflix.", "com.amazon.", "com.whatsapp",
        "com.facebook.", "com.instagram.", "org.videolan."
    )

    private fun gear(c: Canvas, s: Float) {
        gradient(c, s, 0xFFB9BDC5.toInt(), 0xFF63686F.toInt())
        val mid = s / 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF2B2D32.toInt()
        c.drawCircle(mid, mid, s * 0.39f, paint)

        paint.color = 0xFFE4E6EA.toInt()
        for (i in 0 until 12) {
            c.save()
            c.rotate(i * 30f, mid, mid)
            c.drawRoundRect(
                mid - s * 0.038f, s * 0.14f, mid + s * 0.038f, s * 0.26f, s * 0.015f, s * 0.015f, paint
            )
            c.restore()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.07f
        c.drawCircle(mid, mid, s * 0.265f, paint)
        paint.strokeWidth = s * 0.035f
        for (i in 0 until 3) {
            c.save()
            c.rotate(i * 120f, mid, mid)
            c.drawLine(mid, mid, mid, mid - s * 0.25f, paint)
            c.restore()
        }
        paint.style = Paint.Style.FILL
        c.drawCircle(mid, mid, s * 0.07f, paint)
    }
}
