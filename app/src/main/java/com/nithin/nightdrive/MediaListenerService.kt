package com.nithin.nightdrive

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Holding notification access is what unlocks media sessions for Now Playing. The only
 * notifications actually read are navigation ones, for the Next Turn widget.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        try {
            activeNotifications?.forEach(::consider)
        } catch (e: SecurityException) {
            // Access was revoked while connecting.
        }
    }

    override fun onListenerDisconnected() = Navigation.publish(null, null)

    override fun onNotificationPosted(sbn: StatusBarNotification) = consider(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == Navigation.key) Navigation.publish(null, null)
    }

    private fun consider(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val navigating = notification.category == Notification.CATEGORY_NAVIGATION ||
            (sbn.packageName == MAPS_PACKAGE && sbn.isOngoing)
        if (!navigating) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        // Google Maps puts "350 m - Turn right onto MG Road" in the title, the destination in
        // the text and "12 min · 5.4 km · 10:48 PM ETA" in the sub text.
        val parts = title.split(" - ", limit = 2)
        val destination = text.substringBefore(" - ").trim().takeIf { it.isNotEmpty() && sub.isNotEmpty() }
        // Without a distance ("Head southwest", "Rerouting…") the title is the whole instruction.
        val hasDistance = parts.size == 2 && parts[0].firstOrNull()?.isDigit() == true
        val distance = if (hasDistance) parts[0] else ""
        val instruction = if (hasDistance) parts[1] else title.ifEmpty { text }
        val eta = sub.ifEmpty { text }
        // Nothing new to show: skip redrawing the widget and decoding the arrow again.
        if (Navigation.shows(sbn.key, distance, instruction, eta)) return
        val step = Navigation.Step(distance, instruction, eta, arrow(notification), destination)
        Navigation.publish(sbn.key, step)
    }

    /** The turn arrow most navigation apps supply as the notification's large icon. */
    private fun arrow(notification: Notification): Bitmap? = try {
        notification.getLargeIcon()?.loadDrawable(this)?.let { drawable ->
            val bitmap = Bitmap.createBitmap(ARROW_PX, ARROW_PX, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, ARROW_PX, ARROW_PX)
            drawable.draw(Canvas(bitmap))
            bitmap
        }
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val ARROW_PX = 144
    }
}
