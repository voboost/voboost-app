package ru.voboost.cluster

import java.io.EOFException
import java.security.MessageDigest

internal const val CLUSTER_MAX_DOCUMENT_BYTES = 65_536
internal const val CLUSTER_MAX_FILE_BYTES = 64L * 1024 * 1024
internal const val CLUSTER_MAX_TOTAL_BYTES = 256L * 1024 * 1024
internal const val CLUSTER_MAX_FILES = 252
internal const val CLUSTER_LEASE_MILLIS = 30_000L

internal data class ClusterFile(val role: String, val file: String, val size: Long, val sha256: String)

/** Retained positional reads never reopen a daemon pathname or share a seek offset. */
internal interface ClusterFileSource : AutoCloseable {
    val metadata: ClusterFile

    fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int

    fun verifyIdentity()
}

internal data class ClusterTransportConfig(
    val host: String,
    val port: Int,
    val serverCertificateSha256: String,
    val clientCertificateSha256: String,
    val credentialSize: Long,
    val credentialSha256: String,
)

internal class ClusterLease(
    val leaseId: String,
    val transactionId: String,
    val transport: ClusterTransportConfig,
    val files: List<ClusterFileSource>,
    val credentials: ClusterFileSource,
) : AutoCloseable {
    override fun close() {
        (files + credentials).asReversed().forEach { runCatching { it.close() } }
    }
}

internal sealed interface ClusterTakeResult {
    data object Empty : ClusterTakeResult

    data object Busy : ClusterTakeResult

    data object SetupRequired : ClusterTakeResult

    data object Unavailable : ClusterTakeResult

    data class Ready(val lease: ClusterLease) : ClusterTakeResult
}

internal enum class ClusterTransportOutcome(val wire: String) { DELIVERED("delivered"), UNAVAILABLE("unavailable"), FAILED("failed") }

internal enum class ClusterDeliveryResult { COMPLETE, RETRY }

internal interface ClusterGateway {
    suspend fun signPwChallenge(challenge: ByteArray): ByteArray? = null

    suspend fun take(): ClusterTakeResult

    suspend fun result(
        lease: ClusterLease,
        outcome: ClusterTransportOutcome,
        receipt: ByteArray?,
    ): Boolean
}

internal fun interface ClusterTransport {
    suspend fun deliver(lease: ClusterLease): ByteArray
}

internal fun clusterSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun readClusterFile(
    source: ClusterFileSource,
    maximum: Int,
): ByteArray {
    require(source.metadata.size in 1..maximum.toLong())
    val output = ByteArray(source.metadata.size.toInt())
    var offset = 0
    source.verifyIdentity()
    while (offset < output.size) {
        val chunk = ByteArray(minOf(16_384, output.size - offset))
        val count = source.readAt(offset.toLong(), chunk, chunk.size)
        if (count <= 0) throw EOFException("Cluster descriptor made no progress")
        require(count <= chunk.size)
        chunk.copyInto(output, offset, 0, count)
        offset += count
    }
    source.verifyIdentity()
    require(clusterSha256(output) == source.metadata.sha256) { "Cluster descriptor digest differs" }
    return output
}
