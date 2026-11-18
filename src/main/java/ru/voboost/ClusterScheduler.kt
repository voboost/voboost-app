package ru.voboost

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** Independent bounded delivery triggers; the daemon retains the durable outbox. */
object ClusterScheduler {
    internal const val REPAIR_JOB_ID = 0x5643
    internal const val WAKE_JOB_ID = 0x5644
    internal const val REPAIR_PERIOD_MS = 15 * 60 * 1000L
    const val WAKE_ACTION = "ru.voboost.CLUSTER_DELIVERY_WAKE"
    private val active = mutableSetOf<Any>()
    private var rerun = false

    fun schedule(context: Context): Int =
        scheduler(context).schedule(
            builder(context, REPAIR_JOB_ID)
                .setPersisted(true)
                .setPeriodic(REPAIR_PERIOD_MS)
                .build(),
        )

    @Synchronized
    fun wake(context: Context): Int {
        if (active.isNotEmpty()) {
            rerun = true
            return JobScheduler.RESULT_SUCCESS
        }
        val scheduler = scheduler(context)
        // A pending job can be in exponential retry backoff. A new explicit
        // wake must restore immediate eligibility without interrupting delivery.
        scheduler.cancel(WAKE_JOB_ID)
        val result =
            scheduler.schedule(
                builder(context, WAKE_JOB_ID)
                    .setPersisted(true)
                    .setMinimumLatency(0)
                    .build(),
            )
        rerun = result != JobScheduler.RESULT_SUCCESS
        return result
    }

    @Synchronized
    internal fun deliveryStarted(token: Any) {
        active.add(token)
    }

    @Synchronized
    internal fun deliveryFinished(
        context: Context,
        token: Any,
    ) {
        if (!active.remove(token)) return
        if (active.isEmpty() && rerun) wake(context)
    }

    private fun builder(
        context: Context,
        id: Int,
    ): JobInfo.Builder =
        // Android 9 NETWORK_TYPE_ANY requires validated Internet; this peer is local.
        JobInfo.Builder(id, ComponentName(context, ClusterJobService::class.java))
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)

    private fun scheduler(context: Context): JobScheduler = context.getSystemService(JobScheduler::class.java)
}
