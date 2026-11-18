package ru.voboost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** A wake carries no authority; the job reads only the authenticated root mailbox. */
class ClusterWakeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ClusterScheduler.WAKE_ACTION) ClusterScheduler.wake(context)
    }
}
