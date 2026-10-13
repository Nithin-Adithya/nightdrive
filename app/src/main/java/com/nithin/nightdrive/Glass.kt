package com.nithin.nightdrive

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Frosted glass without live blur (which this Android version lacks): the wallpaper is blurred
 * once into a small bitmap, and each glass surface paints the part of it that lies behind itself.
 */
object Frost {

    /** Draws [wallpaper] at [width] x [height], blurs it by [radius] pixels and lifts the colour. */
    fun blur(wallpaper: Drawable, width: Int, height: Int, radius: Int): Bitmap {
        val small = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        wallpaper.setBounds(0, 0, width, height)
        wallpaper.draw(Canvas(small))

        val pixels = IntArray(width * height)
        val scratch = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        // Three box blurs come very close to a true gaussian.
        repeat(3) {
            boxPass(pixels, scratch, width, height, 1, width, radius)
            boxPass(scratch, pixels, height, width, width, 1, radius)
        }
        small.setPixels(pixels, 0, width, 0, 0, width, height)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val vivid = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.35f) })
        }
        Canvas(out).drawBitmap(small, 0f, 0f, vivid)
        small.recycle()
        return out
    }

    /** One sliding-window blur along lines of [count] pixels spaced [step] apart. */
    private fun boxPass(
        src: IntArray, dst: IntArray, count: Int, lines: Int, step: Int, lineStep: Int, radius: Int
    ) {
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val base = line * lineStep
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[base + i.coerceIn(0, count - 1) * step]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until count) {
                dst[base + i * step] =
                    (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = src[base + (i - radius).coerceAtLeast(0) * step]
                val entering = src[base + (i + radius + 1).coerceAtMost(count - 1) * step]
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}

/**
 * Background for a glass surface: the blurred wallpaper behind it, a translucent [veil] and a
 * hairline edge. [place] must fill in where the surface sits: x, y, screen width, screen height.
 */
class GlassDrawable(
    private val radius: Float,
    private val frost: () -> Bitmap?,
    private val place: (FloatArray) -> Unit
) : Drawable() {

    var veil = 0xA61C1C1E.toInt()
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val spot = FloatArray(4)
    private val rect = RectF()
    private var shaderSource: Bitmap? = null
    private var shader: BitmapShader? = null

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        paint.style = Paint.Style.FILL
        val blurred = frost()
        place(spot)
        if (blurred != null && spot[2] > 0f && spot[3] > 0f) {
            if (blurred !== shaderSource) {
                shaderSource = blurred
                shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            matrix.setScale(spot[2] / blurred.width, spot[3] / blurred.height)
            matrix.postTranslate(-spot[0], -spot[1])
            shader?.setLocalMatrix(matrix)
            paint.shader = shader
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }
        paint.color = veil
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (radius > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = 0x26FFFFFF
            rect.inset(0.5f, 0.5f)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    /** Lets the host view clip its children to the same rounded shape. */
    override fun getOutline(outline: Outline) = outline.setRoundRect(bounds, radius)

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
