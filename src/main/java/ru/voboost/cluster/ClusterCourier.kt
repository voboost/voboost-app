package ru.voboost.cluster

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.voboost.LogEpisode
import ru.voboost.Logger
import kotlin.coroutines.coroutineContext

/** Transfers root-approved bytes only; receipts acquire authority in the daemon. */
internal class ClusterCourier(private val gateway: ClusterGateway, private val transport: ClusterTransport) {
    suspend fun run(): ClusterDeliveryResult {
        repeat(4) {
            coroutineContext.ensureActive()
            when (val taken = gateway.take()) {
                ClusterTakeResult.Empty -> {
                    deliveryLog.recovered()
                    return ClusterDeliveryResult.COMPLETE
                }
                ClusterTakeResult.Busy -> return ClusterDeliveryResult.RETRY
                ClusterTakeResult.SetupRequired -> {
                    deliveryLog.waiting("setup-required")
                    return ClusterDeliveryResult.COMPLETE
                }
                ClusterTakeResult.Unavailable -> {
                    deliveryLog.waiting("daemon-unavailable")
                    return ClusterDeliveryResult.RETRY
                }
                is ClusterTakeResult.Ready ->
                    taken.lease.use { lease ->
                        var deliveredReceipt: ByteArray? = null
                        try {
                            val receipt =
                                try {
                                    transport.deliver(lease)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    if (failure is java.net.SocketException ||
                                        failure is java.net.SocketTimeoutException ||
                                        failure is java.net.UnknownHostException
                                    ) {
                                        deliveryLog.waiting(
                                            "peer-unavailable",
                                            lease.transactionId + ":" + lease.transport.host + ":" + lease.transport.port,
                                        )
                                    } else {
                                        Logger.error(
                                            "ClusterCourier",
                                            "Delivery failed: ${failure.javaClass.simpleName}; ${tlsFailureCategory(failure)}",
                                        )
                                    }
                                    gateway.result(lease, ClusterTransportOutcome.UNAVAILABLE, null)
                                    return ClusterDeliveryResult.RETRY
                                }
                            deliveredReceipt = receipt
                            deliveryLog.recovered()
                            require(receipt.size in 1..CLUSTER_MAX_DOCUMENT_BYTES)
                            if (!gateway.result(lease, ClusterTransportOutcome.DELIVERED, receipt)) return ClusterDeliveryResult.RETRY
                        } catch (cancelled: CancellationException) {
                            settleCancelled(lease, deliveredReceipt, cancelled)
                            throw cancelled
                        }
                    }
            }
        }
        return ClusterDeliveryResult.RETRY
    }

    private suspend fun settleCancelled(
        lease: ClusterLease,
        receipt: ByteArray?,
        cancelled: CancellationException,
    ) {
        try {
            withContext(NonCancellable) {
                withTimeout(8_000) {
                    val outcome = if (receipt == null) ClusterTransportOutcome.UNAVAILABLE else ClusterTransportOutcome.DELIVERED
                    if (!gateway.result(lease, outcome, receipt)) {
                        Logger.debug("ClusterCourier", "Cancelled delivery lease was not accepted")
                    }
                }
            }
        } catch (failure: Exception) {
            cancelled.addSuppressed(failure)
            Logger.error("ClusterCourier", "Cancelled delivery settlement failed: ${failure.javaClass.simpleName}")
        }
    }

    private companion object {
        val deliveryLog = LogEpisode { Logger.debug("ClusterCourier", it) }
    }
}

internal fun tlsFailureCategory(failure: Throwable): String {
    var current: Throwable? = failure
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        when (current) {
            is java.security.cert.CertificateExpiredException -> return "certificate-expired"
            is java.security.cert.CertificateNotYetValidException -> return "certificate-not-yet-valid"
            is java.security.cert.CertPathValidatorException -> return "certificate-path-invalid"
            is javax.net.ssl.SSLPeerUnverifiedException -> return "peer-unverified"
        }
        current = current.cause
    }
    return "cause-unclassified"
}
