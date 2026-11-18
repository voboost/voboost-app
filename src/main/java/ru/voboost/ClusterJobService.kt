package ru.voboost

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.voboost.cluster.ClusterCourier
import ru.voboost.cluster.ClusterDeliveryResult
import ru.voboost.cluster.LocalSocketClusterGateway
import ru.voboost.cluster.PinnedClusterTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface ClusterJobRunner {
    suspend fun run(): ClusterDeliveryResult
}

internal object ClusterRuntime {
    @Volatile var testFactory: (() -> ClusterJobRunner)? = null
    private val delivering = AtomicBoolean(false)

    suspend fun run(context: Context): ClusterDeliveryResult {
        if (!delivering.compareAndSet(false, true)) return ClusterDeliveryResult.RETRY
        return try {
            withTimeout(25_000) {
                val runner =
                    testFactory?.invoke()
                        ?: ClusterJobRunner { ClusterCourier(LocalSocketClusterGateway(context), PinnedClusterTransport()).run() }
                runner.run()
            }
        } finally {
            delivering.set(false)
        }
    }
}

/** Owns one bounded asynchronous run per JobScheduler invocation. */
class ClusterJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<JobParameters, Job>()
    internal var jobFinisher: ((JobParameters, Boolean) -> Unit)? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val finished = AtomicBoolean(false)
        val retry = AtomicBoolean(true)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    retry.set(ClusterRuntime.run(applicationContext) == ClusterDeliveryResult.RETRY)
                } catch (_: CancellationException) {
                    Logger.debug("ClusterJobService", "Delivery cancelled")
                    retry.set(true)
                } catch (failure: Exception) {
                    Logger.error("ClusterJobService", "Delivery failed: ${failure.javaClass.simpleName}")
                    retry.set(true)
                }
            }
        ClusterScheduler.deliveryStarted(job)
        active.put(params, job)?.cancel()
        job.invokeOnCompletion {
            active.remove(params, job)
            try {
                if (finished.compareAndSet(false, true)) {
                    (jobFinisher ?: { value, again -> jobFinished(value, again) })(params, retry.get())
                }
            } finally {
                // JobScheduler has finished the original token before a
                // coalesced wake may replace its deferred retry schedule.
                ClusterScheduler.deliveryFinished(applicationContext, job)
            }
        }
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
