package ru.voboost

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.voboost.ota.AndroidPackageUpdater
import ru.voboost.ota.LocalSocketDaemonOtaGateway
import ru.voboost.ota.OtaCoordinator
import ru.voboost.ota.OtaHttpTransport
import ru.voboost.ota.OtaRunResult
import ru.voboost.ota.OtaTransactionStore
import ru.voboost.ota.ReleaseSignatureVerifier
import ru.voboost.ota.parseHttpsUrl
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

internal fun interface OtaJobRunner {
    suspend fun run(): OtaRunResult
}

internal object OtaRuntime {
    /** One production runner per process; built off the main thread, reused for every job. */
    @Volatile
    private var cached: OtaJobRunner? = null

    @Volatile
    var testFactory: ((Context) -> OtaJobRunner?)? = null

    /** Static disabled state; never performs I/O and never calls the test factory. */
    fun isDisabled(): Boolean = testFactory == null && BuildConfig.OTA_DISABLED

    fun runner(context: Context): OtaJobRunner? {
        testFactory?.let { return it(context) }
        if (BuildConfig.OTA_DISABLED) return null
        return cached ?: synchronized(this) {
            cached ?: buildRunner(context.applicationContext).also { cached = it }
        }
    }

    private fun buildRunner(context: Context): OtaJobRunner {
        val publicKey =
            context.assets.open("config/release-public.pem").bufferedReader().use { it.readText() }
        val coordinator =
            OtaCoordinator(
                parseHttpsUrl(BuildConfig.CHANNEL_URL),
                ReleaseSignatureVerifier.fromPem(publicKey),
                OtaHttpTransport(),
                LocalSocketDaemonOtaGateway(),
                OtaTransactionStore(Paths(context)),
                AndroidPackageUpdater(context),
            )
        return OtaJobRunner(coordinator::run)
    }
}

/** JobScheduler owner for one bounded OTA attempt. */
class OtaJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeLock = Any()
    private var active: Job? = null
    internal var constructionDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var jobFinisher: ((JobParameters, Boolean) -> Unit)? = null

    /**
     * Per-run completion ownership: each onStartJob run owns its own
     * finished latch. A late finally of a previous (cancelled) run can
     * never consume the completion of a newer run on a reused service
     * instance, and a newer run never suppresses an in-flight old one.
     */
    private class RunState(val params: JobParameters) {
        val finished = AtomicBoolean(false)
        var retry = true
    }

    override fun onStartJob(params: JobParameters): Boolean {
        // Deployed-disabled maps to a synchronous no-op job; a present test
        // factory that yields null means the same. Everything else — including
        // a THROWing factory or a failing production build — resolves inside
        // the run's failure lifecycle so construction errors always get a
        // defined job completion instead of escaping onStartJob.
        val hasFactory = OtaRuntime.testFactory != null
        if (!hasFactory && OtaRuntime.isDisabled()) {
            deploymentLog.waiting("channel-not-configured")
            return false
        }
        if (hasFactory) {
            // Probe ONLY distinguishes "the factory yields a runner" from
            // "the deployment is disabled". A THROWING factory is not the
            // disabled path: the run below re-invokes the factory inside
            // the failure lifecycle, so the throw resolves to a defined
            // retry completion instead of escaping onStartJob.
            val probesRunner =
                try {
                    val factory = OtaRuntime.testFactory
                    factory != null && factory(applicationContext) != null
                } catch (error: Exception) {
                    true // a throwing factory is an active deployment failure
                }
            if (!probesRunner) {
                deploymentLog.waiting("runner-not-configured")
                return false
            }
        }
        deploymentLog.recovered()
        val run = RunState(params)
        val launched: Job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Runner construction lives INSIDE the run's failure
                // lifecycle: a construction error or cancellation during
                // the build resolves to a defined retry outcome instead
                // of escaping the finally-based job completion.
                try {
                    val boundRunner =
                        withContext(constructionDispatcher) {
                            OtaRuntime.runner(applicationContext)
                        } ?: error("OTA runner is not available")
                    val result = boundRunner.run()
                    run.retry = result is OtaRunResult.Retry || result is OtaRunResult.Failed
                    when (result) {
                        OtaRunResult.NoUpdate -> outcomeLog.waiting("up-to-date")
                        OtaRunResult.Retry -> outcomeLog.waiting("retry-required")
                        OtaRunResult.CandidatePending -> outcomeLog.waiting("candidate-pending")
                        OtaRunResult.CandidateAccepted -> {
                            outcomeLog.recovered()
                            Logger.info(LOG, "OTA candidate accepted")
                        }
                        OtaRunResult.SelfUpdateRequested -> {
                            outcomeLog.recovered()
                            Logger.info(LOG, "OTA app update requested")
                        }
                        is OtaRunResult.Failed -> {
                            outcomeLog.recovered()
                            Logger.error(LOG, "OTA job failed: ${result.reason}")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    Logger.debug(LOG, "OTA job cancelled")
                    run.retry = true
                } catch (error: Exception) {
                    outcomeLog.recovered()
                    Logger.error(LOG, "OTA job failed: ${error.message}")
                    run.retry = true
                } finally {
                    completeRun(run)
                    synchronized(activeLock) {
                        // Losing the race to a reused service instance is
                        // harmless: a fresh onStartJob has already published
                        // its own active job.
                        if (active === coroutineContext[Job]) active = null
                    }
                }
            }
        synchronized(activeLock) {
            if (launched.isActive) active = launched
        }
        return true
    }

    /** Exactly-once jobFinished per run, owned by that run's own latch. */
    private fun completeRun(run: RunState) {
        if (run.finished.compareAndSet(false, true)) {
            (jobFinisher ?: { value, reschedule -> jobFinished(value, reschedule) })(
                run.params,
                run.retry,
            )
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val job =
            synchronized(activeLock) {
                active.also { active = null }
            }
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val LOG = "OtaJobService"
        val deploymentLog = LogEpisode { Logger.debug(LOG, it) }
        val outcomeLog = LogEpisode { Logger.debug(LOG, it) }
    }
}
