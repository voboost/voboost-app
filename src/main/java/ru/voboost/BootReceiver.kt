package ru.voboost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the persistent OTA schedule after device boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ClusterScheduler.schedule(context = context)
            OtaScheduler.schedule(context)
        }
    }
}
