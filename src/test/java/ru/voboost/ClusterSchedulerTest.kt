package ru.voboost

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClusterSchedulerTest {
    private fun deferWake(): JobScheduler {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        scheduler.schedule(
            JobInfo.Builder(ClusterScheduler.WAKE_JOB_ID, ComponentName(context, ClusterJobService::class.java))
                .setMinimumLatency(120_000)
                .build(),
        )
        return scheduler
    }

    @Test fun activeWakeWaitsForTheLastOriginalTokenAndIgnoresOldCompletion() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = deferWake()
        val deferred = scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!
        val old = Any()
        val current = Any()
        ClusterScheduler.deliveryStarted(old)
        ClusterScheduler.deliveryStarted(current)
        try {
            repeat(5) { assertEquals(JobScheduler.RESULT_SUCCESS, ClusterScheduler.wake(context)) }
            assertSame(deferred, scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID))
            ClusterScheduler.deliveryFinished(context, old)
            ClusterScheduler.deliveryFinished(context, old)
            assertSame(deferred, scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID))
            ClusterScheduler.deliveryFinished(context, current)
            val replacement = scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!
            assertEquals(0L, replacement.minLatencyMillis)
            ClusterScheduler.deliveryFinished(context, current)
            assertSame(replacement, scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID))
        } finally {
            ClusterScheduler.deliveryFinished(context, old)
            ClusterScheduler.deliveryFinished(context, current)
        }
    }

    @Test fun racingWakeAndCompletionAlwaysLeaveOneImmediateDeliveryJob() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = deferWake()
        val token = Any()
        ClusterScheduler.deliveryStarted(token)
        val start = CyclicBarrier(2)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val wake =
                workers.submit {
                    start.await(5, TimeUnit.SECONDS)
                    ClusterScheduler.wake(context)
                }
            val completion =
                workers.submit {
                    start.await(5, TimeUnit.SECONDS)
                    ClusterScheduler.deliveryFinished(context, token)
                }
            wake.get(5, TimeUnit.SECONDS)
            completion.get(5, TimeUnit.SECONDS)
            assertEquals(0L, scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!.minLatencyMillis)
            assertEquals(1, scheduler.allPendingJobs.size)
        } finally {
            workers.shutdownNow()
            ClusterScheduler.deliveryFinished(context, token)
        }
    }

    @Test fun explicitWakeReplacesAnAlreadyDeferredDeliveryJob() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        val deferred =
            JobInfo.Builder(
                ClusterScheduler.WAKE_JOB_ID,
                ComponentName(context, ClusterJobService::class.java),
            ).setMinimumLatency(120_000)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
        scheduler.schedule(deferred)
        assertEquals(JobScheduler.RESULT_SUCCESS, ClusterScheduler.wake(context))
        // The fixture models deferred eligibility rather than an internal
        // JobScheduler retry clock; the new immediate oneoff replaces it.
        assertEquals(0L, scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!.minLatencyMillis)
        assertEquals(1, scheduler.allPendingJobs.size)
    }

    @Test fun wakeAndBootScheduleIndependentCoalescedJobs() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        repeat(5) { ClusterWakeReceiver().onReceive(context, Intent(ClusterScheduler.WAKE_ACTION).putExtra("agentId", "ignored")) }
        assertEquals(
            setOf(
                OtaScheduler.JOB_ID,
                ClusterScheduler.REPAIR_JOB_ID,
                ClusterScheduler.WAKE_JOB_ID,
            ),
            scheduler.allPendingJobs.map {
                it.id
            }.toSet(),
        )
        val periodic = scheduler.getPendingJob(ClusterScheduler.REPAIR_JOB_ID)!!
        assertTrue(periodic.isPersisted)
        assertTrue(periodic.isPeriodic)
        assertEquals(ClusterScheduler.REPAIR_PERIOD_MS, periodic.intervalMillis)
        val wake = scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!
        assertEquals(ClusterJobService::class.java.name, wake.service.className)
        assertEquals(3, scheduler.allPendingJobs.size)
    }

    @Suppress("DEPRECATION") // Assert the API 28 network contract against the compile-SDK surface.
    @Test
    fun localDeliverySchedulesWithoutAnInternetNetwork() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        assertNull(connectivity.activeNetworkInfo)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        repeat(5) { ClusterWakeReceiver().onReceive(context, Intent(ClusterScheduler.WAKE_ACTION)) }
        val repair = scheduler.getPendingJob(ClusterScheduler.REPAIR_JOB_ID)!!
        val wake = scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!
        for (job in listOf(repair, wake)) {
            assertNull(job.requiredNetwork)
            assertEquals(JobInfo.NETWORK_TYPE_NONE, job.networkType)
            assertTrue(job.isPersisted)
            assertEquals(30_000L, job.initialBackoffMillis)
            assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
        }
        assertTrue(repair.isPeriodic)
        assertEquals(ClusterScheduler.REPAIR_PERIOD_MS, repair.intervalMillis)
        assertEquals(3, scheduler.allPendingJobs.size)
        val ota = scheduler.getPendingJob(OtaScheduler.JOB_ID)!!
        assertNotNull(ota.requiredNetwork)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, ota.networkType)
    }
}
