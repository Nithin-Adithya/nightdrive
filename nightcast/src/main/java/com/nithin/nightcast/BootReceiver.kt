package com.nithin.nightcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver when the head unit boots, so the phone can connect without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReceiverService.start(context)
    }
}
