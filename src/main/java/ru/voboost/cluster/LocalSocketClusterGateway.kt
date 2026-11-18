package ru.voboost.cluster

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.voboost.AppSecureFiles
import ru.voboost.ota.AndroidDaemonSocketFactory
import ru.voboost.ota.DaemonSocketFactory
import ru.voboost.ota.LocalSocketDaemonOtaGateway
import ru.voboost.ota.socketOperationWithDeadline
import ru.voboost.platformAppFileOps
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Cluster operations extend the exact credential socket without weakening OTA parsing. */
internal class LocalSocketClusterGateway(
    private val context: Context,
    private val sockets: DaemonSocketFactory = AndroidDaemonSocketFactory(),
) : ClusterGateway {
    override suspend fun signPwChallenge(challenge: ByteArray): ByteArray? {
        val received = mutableListOf<FileDescriptor>()
        return try {
            val requestId = ClusterContracts.requestId()
            val bytes = exchange(ClusterPwContracts.request(requestId, challenge), emptyList(), received)
            require(received.isEmpty())
            ClusterPwContracts.response(bytes, requestId)
        } finally {
            received.asReversed().forEach { runCatching { Os.close(it) } }
        }
    }

    override suspend fun take(): ClusterTakeResult {
        val received = mutableListOf<FileDescriptor>()
        val retained = mutableListOf<ClusterFileSource>()
        var stage = "exchange"
        return try {
            val requestId = ClusterContracts.requestId()
            val response = exchange(ClusterContracts.takeRequest(requestId), emptyList(), received) { stage = "exchange.$it" }
            stage = "response"
            val (status, lease) = ClusterContracts.takeResponse(response, requestId)
            if (lease == null) {
                require(received.isEmpty())
                when (status) {
                    "empty" -> ClusterTakeResult.Empty
                    "busy" -> ClusterTakeResult.Busy
                    "setup-required" -> ClusterTakeResult.SetupRequired
                    else -> error("Unexpected lease-free cluster mailbox status")
                }
            } else {
                require(received.size == lease.files.size + 1)
                val metadata =
                    lease.files +
                        ClusterFile(
                            "credentials",
                            "transport.p12",
                            lease.transport.credentialSize,
                            lease.transport.credentialSha256,
                        )
                stage = "descriptors"
                for (file in metadata) {
                    retained += AndroidClusterFileSource(received.first(), file)
                    received.removeAt(0)
                }
                stage = "verify"
                retained.forEach { source -> verifyContents(source) }
                ClusterTakeResult.Ready(
                    ClusterLease(lease.leaseId, lease.transactionId, lease.transport, retained.dropLast(1), retained.last()),
                ).also {
                    retained.clear()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            coroutineContext.ensureActive()
            reportFailure("take.$stage", failure)
            ClusterTakeResult.Unavailable
        } finally {
            retained.asReversed().forEach { runCatching { it.close() } }
            received.asReversed().forEach { runCatching { Os.close(it) } }
        }
    }

    override suspend fun result(
        lease: ClusterLease,
        outcome: ClusterTransportOutcome,
        receipt: ByteArray?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val incoming = mutableListOf<FileDescriptor>()
            var stage = "request"
            try {
                val requestId = ClusterContracts.requestId()
                val request = ClusterContracts.resultRequest(requestId, lease, outcome, receipt)
                stage = "exchange"
                val response =
                    if (receipt == null) {
                        exchange(request, emptyList(), incoming) { stage = "exchange.$it" }
                    } else {
                        stage = "receipt"
                        val directory = File(context.noBackupFilesDir, "cluster-receipts")
                        val target = File(directory, "receipt.vbc")
                        AppSecureFiles.writeAtomically(target, receipt)
                        platformAppFileOps.openPrivateDirectory(directory).use { root ->
                            AppSecureFiles.requireBoundPrivateDirectory(platformAppFileOps, directory, root)
                            try {
                                root.openRead(target.name).use { file ->
                                    val initial = file.identity()
                                    require(initial == root.lstat(target.name) && initial.size == receipt.size.toLong())
                                    val exact = ByteArray(receipt.size)
                                    var offset = 0
                                    while (offset < exact.size) {
                                        val read = file.read(exact, offset, exact.size - offset)
                                        require(read > 0)
                                        offset += read
                                    }
                                    require(exact.contentEquals(receipt) && file.identity() == initial)
                                    file.seek(0)
                                    file.duplicateForTransfer().use {
                                            descriptor ->
                                        stage = "exchange"
                                        exchange(request, listOf(descriptor.fileDescriptor), incoming) { stage = "exchange.$it" }
                                    }
                                }
                            } finally {
                                root.remove(target.name)
                                root.sync()
                            }
                        }
                    }
                stage = "response"
                require(incoming.isEmpty())
                ClusterContracts.resultResponse(response, requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                reportFailure("result.$stage", failure)
                false
            } finally {
                incoming.forEach { runCatching { Os.close(it) } }
            }
        }

    private fun reportFailure(
        operation: String,
        failure: Exception,
    ) {
        val errno = (failure as? android.system.ErrnoException) ?: (failure.cause as? android.system.ErrnoException)
        val unavailable =
            failure is java.net.SocketException || failure is java.net.SocketTimeoutException ||
                errno?.errno == android.system.OsConstants.ENOENT || errno?.errno == android.system.OsConstants.ECONNREFUSED
        if (!unavailable) {
            val detail = errno?.let { "; errno=${it.errno}" }.orEmpty()
            ru.voboost.Logger.error("ClusterGateway", "$operation failed: ${failure.javaClass.simpleName}$detail")
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun exchange(
        payload: ByteArray,
        outgoing: List<FileDescriptor>,
        incoming: MutableList<FileDescriptor>,
        progress: (String) -> Unit = {},
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val socket = sockets.create()
            val cancellation =
                coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) abortAsync(socket::abort)
                }

            fun <T> bounded(
                name: String,
                action: () -> T,
            ): T {
                progress(name)
                return socketOperationWithDeadline("cluster-local-$name", if (name == "read") 8_000 else 2_000, socket::abort, action)
            }

            fun collect() {
                socket.inboundFileDescriptors()?.let { incoming.addAll(it) }
                require(incoming.size <= CLUSTER_MAX_FILES + 1)
            }

            fun exact(size: Int): ByteArray {
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    val count = bounded("read") { socket.input.read(bytes, offset, size - offset) }
                    collect()
                    require(count > 0)
                    offset += count
                }
                return bytes
            }
            try {
                progress("connect")
                socket.connect()
                progress("peer")
                require(socket.peerCredentials().uid == LocalSocketDaemonOtaGateway.EXPECTED_DAEMON_UID)
                val length = payload.size
                val header = byteArrayOf((length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte())
                socket.setOutboundFileDescriptors(outgoing.toTypedArray().takeIf { it.isNotEmpty() })
                try {
                    bounded("header") { socket.output.write(header) }
                } finally {
                    socket.setOutboundFileDescriptors(null)
                }
                bounded("body") { socket.output.write(payload) }
                val h = exact(4)
                val count =
                    ((h[0].toInt() and 255) shl 24) or
                        ((h[1].toInt() and 255) shl 16) or
                        ((h[2].toInt() and 255) shl 8) or
                        (h[3].toInt() and 255)
                require(count in 1..524_288)
                val headerDescriptorCount = incoming.size
                val body = exact(count)
                require(incoming.size == headerDescriptorCount) { "Cluster descriptors must accompany the response header" }
                val trailing = bounded("eof") { socket.input.read() }
                collect()
                require(trailing == -1 && incoming.size == headerDescriptorCount)
                body
            } finally {
                cancellation?.dispose()
                abortAsync(socket::abort)
            }
        }

    private suspend fun verifyContents(source: ClusterFileSource) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16_384)
        var offset = 0L
        source.verifyIdentity()
        while (offset < source.metadata.size) {
            coroutineContext.ensureActive()
            val read = source.readAt(offset, buffer, minOf(buffer.size.toLong(), source.metadata.size - offset).toInt())
            require(read > 0)
            hash.update(buffer, 0, read)
            offset += read
        }
        source.verifyIdentity()
        require(hash.digest().joinToString("") { "%02x".format(it) } == source.metadata.sha256)
    }
}

private fun abortAsync(close: () -> Unit) {
    Thread({ runCatching(close) }, "voboost-cluster-close").apply {
        isDaemon = true
        start()
    }
}

private class AndroidClusterFileSource(private val descriptor: FileDescriptor, override val metadata: ClusterFile) : ClusterFileSource {
    private val initial = identity()

    init {
        val stat = Os.fstat(descriptor)
        require(stat.st_uid == 0 && stat.st_mode and 0xf000 == 0x8000 && stat.st_size == metadata.size)
        ParcelFileDescriptor.dup(descriptor).use { retained ->
            val info =
                File("/proc/self/fdinfo/${retained.fd}").inputStream().use { input ->
                    val buffer = ByteArray(4_097)
                    var size = 0
                    while (size < buffer.size) {
                        val count = input.read(buffer, size, buffer.size - size)
                        if (count < 0) break
                        require(count > 0)
                        size += count
                    }
                    buffer.copyOf(size)
                }
            requireReadOnlyDescriptorFlags(info)
        }
    }

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int = Os.pread(descriptor, buffer, 0, length, offset)

    override fun verifyIdentity() {
        require(identity() == initial) { "Cluster descriptor identity changed" }
    }

    private fun identity(): List<Long> =
        Os.fstat(descriptor).let {
            listOf(
                it.st_dev,
                it.st_ino,
                it.st_uid.toLong(),
                it.st_mode.toLong(),
                it.st_size,
                it.st_mtime,
                it.st_ctime,
                it.st_mtim.tv_nsec,
                it.st_ctim.tv_nsec,
            )
        }

    override fun close() = Os.close(descriptor)
}

/** API 28 has no public fcntl getter; fdinfo belongs to the retained kernel descriptor. */
internal fun requireReadOnlyDescriptorFlags(bytes: ByteArray) {
    require(bytes.size in 1..4_096 && bytes.all { it.toInt() in 0..127 })
    val rows = bytes.toString(Charsets.US_ASCII).lineSequence().filter { it.startsWith("flags:") }.toList()
    require(rows.size == 1)
    val flags = rows.single().removePrefix("flags:").trim()
    require(flags.matches(Regex("^[0-7]{1,22}$")))
    require(flags.toLong(8) and 3L == 0L) { "Cluster descriptor is writable" }
}
