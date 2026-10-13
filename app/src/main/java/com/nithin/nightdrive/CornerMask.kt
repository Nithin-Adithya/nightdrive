package com.nithin.nightdrive

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Paints the four screen corners black, so a square display looks like it has rounded corners. */
class CornerMask @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val corners = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = 24 * resources.displayMetrics.density
        corners.reset()
        corners.fillType = Path.FillType.EVEN_ODD
        corners.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        corners.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) = canvas.drawPath(corners, paint)
}
