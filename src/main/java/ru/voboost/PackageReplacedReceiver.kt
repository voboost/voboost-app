package ru.voboost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the OTA schedule after PackageInstaller replaces this APK. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ClusterScheduler.schedule(context = context)
            OtaScheduler.schedule(context)
        }
    }
}
