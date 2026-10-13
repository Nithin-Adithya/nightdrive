package com.nithin.nightdrive

import android.graphics.Bitmap

/**
 * The current turn-by-turn step, as read from the navigation app's ongoing notification by
 * [MediaListenerService]. Stays on the device. Main thread only.
 */
object Navigation {

    class Step(
        val distance: String, val instruction: String, val eta: String, val icon: Bitmap?,
        /** Name of the place being navigated to, when the notification gives one. */
        val destination: String? = null
    )

    var step: Step? = null
        private set
    var key: String? = null
        private set
    var onChange: (() -> Unit)? = null

    fun publish(key: String?, step: Step?) {
        this.key = key
        this.step = step
        onChange?.invoke()
    }

    /** True when [key] already shows exactly this text; navigation apps re-post it every second. */
    fun shows(key: String, distance: String, instruction: String, eta: String): Boolean {
        val current = step ?: return false
        return this.key == key && current.distance == distance &&
            current.instruction == instruction && current.eta == eta
    }
}
