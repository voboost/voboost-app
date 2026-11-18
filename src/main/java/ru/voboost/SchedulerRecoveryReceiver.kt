package ru.voboost

import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the OTA schedule after root-owned package execution recovery. */
class SchedulerRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION) {
            val ota = OtaScheduler.schedule(context)
            val cluster = ClusterScheduler.schedule(context)
            val scheduled =
                if (ota == JobScheduler.RESULT_SUCCESS && cluster == JobScheduler.RESULT_SUCCESS) {
                    JobScheduler.RESULT_SUCCESS
                } else {
                    JobScheduler.RESULT_FAILURE
                }
            if (isOrderedBroadcast) {
                resultCode = scheduled
                resultData = if (scheduled == JobScheduler.RESULT_SUCCESS) "scheduler-ready" else "scheduler-failed"
            }
        }
    }

    companion object {
        const val ACTION = "ru.voboost.action.RECONCILE_SCHEDULER"
    }
}
