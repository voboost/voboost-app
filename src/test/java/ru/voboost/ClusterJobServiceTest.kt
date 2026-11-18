package ru.voboost

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.voboost.cluster.ClusterDeliveryResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClusterJobServiceTest {
    @After fun reset() {
        ClusterRuntime.testFactory = null
    }

    @Test fun activeDeliveryIsNotCancelledAndRetryCompletionSchedulesTheLatchedWake() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        scheduler.schedule(
            JobInfo.Builder(ClusterScheduler.WAKE_JOB_ID, ComponentName(context, ClusterJobService::class.java))
                .setMinimumLatency(120_000).build(),
        )
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val retryObserved = AtomicBoolean(false)
        val deferredAtFinish = AtomicBoolean(false)
        ClusterRuntime.testFactory = {
            ClusterJobRunner {
                started.countDown()
                try {
                    release.await()
                    throw java.io.IOException("offline")
                } catch (error: CancellationException) {
                    cancelled.set(true)
                    throw error
                }
            }
        }
        val service = Robolectric.buildService(ClusterJobService::class.java).create().get()
        service.jobFinisher = { _, retry ->
            // The pending job may be replaced only after jobFinished returns.
            retryObserved.set(retry)
            deferredAtFinish.set(scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)?.minLatencyMillis == 120_000L)
            finished.countDown()
        }
        try {
            assertTrue(service.onStartJob(Mockito.mock(JobParameters::class.java)))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(JobScheduler.RESULT_SUCCESS, ClusterScheduler.wake(context))
            assertFalse(cancelled.get())
            assertEquals(120_000L, scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!.minLatencyMillis)
            release.complete(Unit)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(retryObserved.get())
            assertTrue(deferredAtFinish.get())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)?.minLatencyMillis != 0L && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertEquals(0L, scheduler.getPendingJob(ClusterScheduler.WAKE_JOB_ID)!!.minLatencyMillis)
            assertFalse(cancelled.get())
        } finally {
            release.complete(Unit)
            service.onDestroy()
        }
    }

    @Test fun asyncOutcomesFinishOnceAndCancellationRetries() {
        complete(false) { ClusterDeliveryResult.COMPLETE }
        complete(true) { ClusterDeliveryResult.RETRY }
        complete(true) { throw java.io.IOException("offline") }
        complete(true, true) { awaitCancellation() }
    }

    @Test fun immediateStopAlwaysFinishesTheOwnedJob() {
        repeat(10) {
            ClusterRuntime.testFactory = { ClusterJobRunner { awaitCancellation() } }
            val service = Robolectric.buildService(ClusterJobService::class.java).create().get()
            val completed = CountDownLatch(1)
            var count = 0
            service.jobFinisher = { _, retry ->
                assertTrue(retry)
                count++
                completed.countDown()
            }
            val params = Mockito.mock(JobParameters::class.java)
            assertTrue(service.onStartJob(params))
            service.onStopJob(params)
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, count)
            service.onDestroy()
        }
    }

    private fun complete(
        expected: Boolean,
        cancel: Boolean = false,
        run: suspend () -> ClusterDeliveryResult,
    ) {
        val started = CountDownLatch(1)
        ClusterRuntime.testFactory = {
            ClusterJobRunner {
                started.countDown()
                run()
            }
        }
        val service = Robolectric.buildService(ClusterJobService::class.java).create().get()
        val completed = CountDownLatch(1)
        var count = 0
        var retry: Boolean? = null
        service.jobFinisher = { _, value ->
            count++
            retry = value
            completed.countDown()
        }
        val params = Mockito.mock(JobParameters::class.java)
        assertTrue(service.onStartJob(params))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        if (cancel) service.onStopJob(params)
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(1, count)
        assertEquals(expected, retry)
        service.onDestroy()
    }
}
