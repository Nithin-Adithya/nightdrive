package com.nithin.nightdrive

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that always takes exactly the space it is offered.
 *
 * The pager inside it lays its pages out during measurement whenever it is given an "at most"
 * size, first at a trial size and then again at the real one. That made the whole home screen,
 * live map included, visibly jump on every re-layout. With an exact size the pager skips that.
 */
class ExactFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(exact(widthMeasureSpec), exact(heightMeasureSpec))
    }

    private fun exact(spec: Int): Int =
        if (MeasureSpec.getMode(spec) == MeasureSpec.AT_MOST) {
            MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(spec), MeasureSpec.EXACTLY)
        } else {
            spec
        }
}
