package ru.voboost

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** Schedules the bounded OTA courier entry point without a resident process. */
object OtaScheduler {
    internal const val JOB_ID = 0x5642
    internal const val PERIOD_MS = 12 * 60 * 60 * 1000L

    fun schedule(context: Context): Int {
        val job =
            JobInfo.Builder(JOB_ID, ComponentName(context, OtaJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(PERIOD_MS)
                .build()
        return context.getSystemService(JobScheduler::class.java).schedule(job)
    }
}
