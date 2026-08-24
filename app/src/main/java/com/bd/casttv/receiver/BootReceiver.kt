package com.bd.casttv.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bd.casttv.settings.Settings

/**
 * Starts the background cast receiver after device boot when the user has
 * explicitly enabled auto-start in Settings.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) return
        val settings = Settings(context)
        if (!settings.bootAutoStart) return
        CastReceiverService.start(context)
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
