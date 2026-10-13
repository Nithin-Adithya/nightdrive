package com.nithin.nightdrive

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Small, cheap animations shared across the launcher. */
object Motion {

    private val settle = DecelerateInterpolator()
    private val spring = OvershootInterpolator(2.5f)

    /**
     * Press feedback: the view shrinks (and, with [dim], darkens) under the finger and springs
     * back on release. Clicks and long-presses still reach the view's own listeners.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(view: View, dim: Boolean = true) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val press = v.animate().scaleX(0.9f).scaleY(0.9f)
                    if (dim) press.alpha(0.7f)
                    press.setDuration(90).setInterpolator(settle).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val release = v.animate().scaleX(1f).scaleY(1f)
                    if (dim) release.alpha(1f)
                    release.setDuration(280).setInterpolator(spring).start()
                }
            }
            false
        }
    }

    /** Fades an overlay in. */
    fun reveal(view: View) {
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(200).setInterpolator(settle).start()
    }

    /** Fades an overlay out, then removes it. */
    fun dismiss(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).setDuration(150).setInterpolator(settle).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }
}
