package ru.voboost

import android.app.job.JobParameters
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.voboost.ota.OtaRunResult
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OtaJobServiceTest {
    @After
    fun resetRuntime() {
        OtaRuntime.testFactory = null
        Logger.shutdown()
    }

    @Test
    fun typedFailuresRemainErrorRecordsForEveryAttempt() {
        val reason = "signature verification failed\ntoken=private-token password=private-password Authorization: Bearer private-auth"
        val records =
            loggedOutcomes("error") {
                repeat(2) {
                    assertCompletion(expectedRetry = true) { OtaRunResult.Failed(reason) }
                }
            }

        assertEquals(2, records.size)
        records.forEach { record ->
            assertEquals("error", record.getString("level"))
            assertEquals("OtaJobService", record.getString("component"))
            val message = record.getString("message")
            assertTrue(message.contains("signature verification failed\n"))
            assertTrue(message.contains("[REDACTED]"))
            assertFalse(message.contains("private-token"))
            assertFalse(message.contains("private-password"))
            assertFalse(message.contains("private-auth"))
        }
    }

    @Test
    fun expectedOutcomesLogTransitionsAcrossJobsAndOnlyMilestonesAtInfo() {
        assertCompletion(expectedRetry = false) { OtaRunResult.CandidateAccepted }
        val records =
            loggedOutcomes("debug") {
                repeat(2) { assertCompletion(expectedRetry = false) { OtaRunResult.NoUpdate } }
                repeat(2) { assertCompletion(expectedRetry = true) { OtaRunResult.Retry } }
                repeat(2) { assertCompletion(expectedRetry = false) { OtaRunResult.CandidatePending } }
                assertCompletion(expectedRetry = false) { OtaRunResult.CandidateAccepted }
                assertCompletion(expectedRetry = false) { OtaRunResult.NoUpdate }
                assertCompletion(expectedRetry = false) { OtaRunResult.SelfUpdateRequested }
            }

        val info = records.filter { it.getString("level") == "info" }
        assertEquals(
            listOf("OTA candidate accepted", "OTA app update requested"),
            info.map { it.getString("message") },
        )
        val waits = records.filter { it.getString("message").startsWith("waiting:") }
        assertEquals(
            listOf("waiting: up-to-date", "waiting: retry-required", "waiting: candidate-pending", "waiting: up-to-date"),
            waits.map { it.getString("message") },
        )
        assertTrue(waits.all { it.getString("level") == "debug" })
        assertTrue(records.none { it.getString("level") == "error" })
    }

    private fun loggedOutcomes(
        level: String,
        run: () -> Unit,
    ): List<JSONObject> {
        val parent = File("build/test-work").absoluteFile.apply { mkdirs() }
        val directory = Files.createTempDirectory(parent.toPath(), "ota-job-logging-").toFile()
        try {
            Logger.init(directory, level)
            run()
            Logger.shutdown()
            return directory.listFiles().orEmpty().filter { it.extension == "jsonl" }
                .flatMap { it.readLines() }.map(::JSONObject)
                .filter { it.getString("component") == "OtaJobService" }
        } finally {
            Logger.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun jobRunsOneInjectedBoundedAttempt() {
        val called = CountDownLatch(1)
        OtaRuntime.testFactory = {
            OtaJobRunner {
                called.countDown()
                OtaRunResult.NoUpdate
            }
        }
        val service = Robolectric.buildService(OtaJobService::class.java).create().get()

        assertTrue(service.onStartJob(Mockito.mock(JobParameters::class.java)))
        assertTrue(called.await(5, TimeUnit.SECONDS))
        service.onDestroy()
    }

    @Test
    fun disabledJobCompletesSynchronouslyWithoutAsyncOwnership() {
        OtaRuntime.testFactory = { null }
        val service = Robolectric.buildService(OtaJobService::class.java).create().get()

        assertFalse(service.onStartJob(Mockito.mock(JobParameters::class.java)))
        service.onDestroy()
    }

    @Test
    fun asyncOutcomesAndExceptionsFinishExactlyOnce() {
        assertCompletion(expectedRetry = false) { OtaRunResult.NoUpdate }
        assertCompletion(expectedRetry = true) { OtaRunResult.Failed("malformed transaction") }
        assertCompletion(expectedRetry = true) { throw IOException("daemon IPC") }
        assertCompletion(expectedRetry = true, cancel = true) { awaitCancellation() }
    }

    private fun assertCompletion(
        expectedRetry: Boolean,
        cancel: Boolean = false,
        run: suspend () -> OtaRunResult,
    ) {
        OtaRuntime.testFactory = { OtaJobRunner(run) }
        val service = Robolectric.buildService(OtaJobService::class.java).create().get()
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
        if (cancel) service.onStopJob(params)
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(1, count)
        assertEquals(expectedRetry, retry)
        service.onDestroy()
    }

    /** A2 regression: each run owns its completion latch — a run completing
     * around a fresh start must not consume or suppress the next run's
     * jobFinished. Robolectric drives UNDISPATCHED coroutines through one
     * main thread, so the concurrent-overlap ordering stays a device gate;
     * this test proves the per-run ownership on the sequential projection:
     * the old run's completion must not suppress the new run's completion. */
    @Test
    fun aStoppedRunsCompletionDoesNotConsumeTheNextRunsJobParams() {
        val oldDone = CountDownLatch(1)
        val oldRunner =
            OtaJobRunner {
                oldDone.countDown()
                OtaRunResult.NoUpdate
            }
        OtaRuntime.testFactory = { oldRunner }
        val service = Robolectric.buildService(OtaJobService::class.java).create().get()
        val completions = java.util.concurrent.ConcurrentHashMap<JobParameters, Boolean>()
        service.jobFinisher = { params, retry -> completions[params] = retry }

        val oldParams = Mockito.mock(JobParameters::class.java)
        val newParams = Mockito.mock(JobParameters::class.java)
        assertTrue(service.onStartJob(oldParams))
        assertTrue(oldDone.await(5, TimeUnit.SECONDS))
        service.onStopJob(oldParams)
        // Fresh run with a fresh factory: the next completed latch must be
        // its own — the previous completion must not have set finished.
        val newDone = CountDownLatch(1)
        OtaRuntime.testFactory = {
            OtaJobRunner {
                newDone.countDown()
                OtaRunResult.NoUpdate
            }
        }
        assertTrue(service.onStartJob(newParams))
        assertTrue(newDone.await(5, TimeUnit.SECONDS))
        // Both runs complete their own params with NoUpdate's retry=false.
        assertEquals(2, completions.size)
        assertEquals(java.lang.Boolean.FALSE, completions[oldParams])
        assertEquals(java.lang.Boolean.FALSE, completions[newParams])
        service.onDestroy()
    }

    /** A3 regression: an exception while building the runner completes the
     * job with a defined retry outcome instead of escaping the lifecycle. */
    @Test
    fun runnerConstructionFailureCompletesAsRetry() {
        OtaRuntime.testFactory = {
            OtaJobRunner {
                throw IllegalStateException("must not be reached")
            }
        }
        // Force the PRODUCTION construction path by clearing the test factory
        // inside a runner-less deployment? No: instead assert the bounded
        // outcome directly — construction exceptions map to retry, verified
        // through the injected-null branch of the same try/catch.
        OtaRuntime.testFactory = { null }
        val service = Robolectric.buildService(OtaJobService::class.java).create().get()
        // testFactory returning null means "disabled": synchronous false.
        assertFalse(service.onStartJob(Mockito.mock(JobParameters::class.java)))

        // A throwing factory simulates construction failure after the
        // disabled check: the run must finish with retry=true, not lose
        // completion.
        OtaRuntime.testFactory = { throw IOException("runner build failed") }
        val completed = CountDownLatch(1)
        var retry: Boolean? = null
        service.jobFinisher = { _, value ->
            retry = value
            completed.countDown()
        }
        assertTrue(service.onStartJob(Mockito.mock(JobParameters::class.java)))
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(true, retry)
        service.onDestroy()
    }

    @Test
    fun runnerConstructionCancellationFinishesExactlyOnceAsRetry() {
        var calls = 0
        OtaRuntime.testFactory = {
            if (++calls == 1) {
                OtaJobRunner { error("runner must not execute") }
            } else {
                throw kotlinx.coroutines.CancellationException("construction cancelled")
            }
        }
        val service = Robolectric.buildService(OtaJobService::class.java).create().get()
        val completed = CountDownLatch(1)
        var count = 0
        var retry: Boolean? = null
        service.jobFinisher = { _, value ->
            count++
            retry = value
            completed.countDown()
        }
        assertTrue(service.onStartJob(Mockito.mock(JobParameters::class.java)))
        assertTrue("construction cancellation must finish the job", completed.await(2, TimeUnit.SECONDS))
        service.onStopJob(Mockito.mock(JobParameters::class.java))
        service.onDestroy()
        assertEquals(1, count)
        assertEquals(true, retry)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationWhileConstructionIsSuspendedFinishesExactlyOnceAsRetry() {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        var calls = 0
        OtaRuntime.testFactory = {
            calls++
            OtaJobRunner { error("runner must not execute") }
        }
        val service = Robolectric.buildService(OtaJobService::class.java).create().get()
        service.constructionDispatcher = dispatcher
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
        assertEquals("only the synchronous enabled probe ran", 1, calls)
        assertTrue(service.onStopJob(params))
        dispatcher.scheduler.runCurrent()
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(1, count)
        assertEquals(true, retry)
        assertEquals("cancelled construction must not invoke the factory", 1, calls)
        service.onDestroy()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, count)
    }
}
