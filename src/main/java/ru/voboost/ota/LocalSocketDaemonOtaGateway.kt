package ru.voboost.ota

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.voboost.Logger
import java.io.Closeable
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

internal interface DaemonSocket : Closeable {
    val input: InputStream
    val output: OutputStream

    fun connect()

    /** Effective peer credentials bound by the kernel to this connection. */
    fun peerCredentials(): SocketPeerCredentials

    fun setOutboundFileDescriptors(descriptors: Array<FileDescriptor>?)

    fun inboundFileDescriptors(): Array<FileDescriptor>?

    fun abort() = close()
}

/** Kernel-verified SO_PEERCRED identity of the connected daemon peer. */
internal data class SocketPeerCredentials(val uid: Int)

internal fun interface DaemonSocketFactory {
    fun create(): DaemonSocket
}

private data class SocketOperationOutcome<T>(
    val value: T? = null,
    val failure: Throwable? = null,
)

private fun closeSocketAsynchronously(
    operationName: String,
    close: () -> Unit,
) {
    Thread({
        runCatching(close)
    }, "voboost-local-$operationName-close").apply {
        isDaemon = true
        start()
    }
}

internal fun <T> socketOperationWithDeadline(
    operationName: String,
    timeoutMillis: Int,
    close: () -> Unit,
    operation: () -> T,
): T {
    require(timeoutMillis > 0)
    val completed = CountDownLatch(1)
    val outcome = AtomicReference<SocketOperationOutcome<T>?>()
    val worker =
        Thread({
            try {
                outcome.set(SocketOperationOutcome(value = operation()))
            } catch (error: Throwable) {
                outcome.set(SocketOperationOutcome(failure = error))
            } finally {
                completed.countDown()
            }
        }, "voboost-local-$operationName").apply { isDaemon = true }
    worker.start()
    if (!completed.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
        closeSocketAsynchronously(operationName, close)
        completed.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        throw SocketTimeoutException("Daemon LocalSocket $operationName timed out")
    }
    val result = requireNotNull(outcome.get()) { "Daemon LocalSocket $operationName produced no result" }
    result.failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result.value as T
}

internal fun connectWithDeadlineBeforeReadTimeout(
    timeoutMillis: Int,
    connect: () -> Unit,
    close: () -> Unit,
    setReadTimeout: () -> Unit,
) {
    socketOperationWithDeadline("connect", timeoutMillis, close, connect)
    setReadTimeout()
}

internal class AndroidDaemonSocketFactory(
    private val address: LocalSocketAddress =
        LocalSocketAddress(LocalSocketDaemonOtaGateway.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
    private val timeoutMillis: Int = LocalSocketDaemonOtaGateway.TIMEOUT_MS,
) : DaemonSocketFactory {
    override fun create(): DaemonSocket = AndroidDaemonSocket(LocalSocket(LocalSocket.SOCKET_STREAM), address, timeoutMillis)

    private class AndroidDaemonSocket(
        private val socket: LocalSocket,
        private val address: LocalSocketAddress,
        private val timeoutMillis: Int,
    ) : DaemonSocket {
        private val abortStarted = AtomicBoolean(false)

        override val input: InputStream
            get() = socket.inputStream
        override val output: OutputStream
            get() = socket.outputStream

        override fun connect() {
            connectWithDeadlineBeforeReadTimeout(
                timeoutMillis = timeoutMillis,
                connect = {
                    socket.connect(address)
                    // Abstract socket names are unclaimable: any app may bind
                    // voboost.ota.v1 before the daemon starts. The kernel-bound
                    // SO_PEERCRED identity is the only trustworthy evidence that
                    // this endpoint belongs to the expected daemon before any
                    // byte or file descriptor is sent.
                    val peer = socket.getPeerCredentials()
                    check(peer.uid == LocalSocketDaemonOtaGateway.EXPECTED_DAEMON_UID) {
                        "Daemon socket peer uid ${peer.uid} is not the expected ${LocalSocketDaemonOtaGateway.EXPECTED_DAEMON_UID}"
                    }
                },
                close = this::abort,
                setReadTimeout = { socket.soTimeout = timeoutMillis },
            )
        }

        override fun peerCredentials(): SocketPeerCredentials = SocketPeerCredentials(socket.getPeerCredentials().uid)

        override fun setOutboundFileDescriptors(descriptors: Array<FileDescriptor>?) {
            socket.setFileDescriptorsForSend(descriptors)
        }

        override fun inboundFileDescriptors(): Array<FileDescriptor>? = socket.ancillaryFileDescriptors

        override fun abort() {
            if (!abortStarted.compareAndSet(false, true)) return
            runCatching { Os.shutdown(socket.fileDescriptor, OsConstants.SHUT_RDWR) }
            socket.close()
        }

        override fun close() = socket.close()
    }
}

internal class LocalSocketDaemonOtaGateway(
    private val sockets: DaemonSocketFactory = AndroidDaemonSocketFactory(),
) : DaemonOtaGateway {
    override suspend fun activeRelease(): DaemonActiveResult =
        try {
            parseActive(exchange(ACTIVE_REQUEST, emptyList()))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            DaemonActiveResult.Unavailable
        }

    override suspend fun propose(
        candidate: OtaCandidateHandle,
        files: OtaCandidateFiles,
    ): DaemonProposalResult {
        if (candidate.artifacts.size > MAX_CHANGED_DESCRIPTORS) return DaemonProposalResult.Unavailable
        val expectedNames = listOf(MANIFEST, MANIFEST_SIGNATURE) + candidate.artifacts.map { it.file }
        if (files.files.map { it.file } != expectedNames || files.files.size !in 2..MAX_TOTAL_DESCRIPTORS) {
            return DaemonProposalResult.Unavailable
        }
        val duplicates = mutableListOf<android.os.ParcelFileDescriptor>()
        return try {
            files.files.forEach { duplicates += it.duplicateForTransport() }
            val request = proposalRequest(candidate)
            parseProposal(exchange(request, duplicates.map { it.fileDescriptor }))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            coroutineContext.ensureActive()
            Logger.debug(LOG, "propose unavailable: ${error.javaClass.simpleName}: ${error.message}")
            DaemonProposalResult.Unavailable
        } finally {
            duplicates.asReversed().forEach { runCatching { it.close() } }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun exchange(
        payload: ByteArray,
        descriptors: List<FileDescriptor>,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require(payload.size in 1..MAX_PAYLOAD_BYTES)
            val socket = sockets.create()
            var exchangeCompleted = false
            val cancellation =
                coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause is kotlinx.coroutines.CancellationException) {
                        closeSocketAsynchronously("cancel", socket::abort)
                    }
                }
            try {
                socket.connect()
                val peer = socket.peerCredentials()
                require(peer.uid == EXPECTED_DAEMON_UID) { "Daemon socket peer uid ${peer.uid} is not trusted" }
                val header =
                    payload.size.toUInt().toInt().let {
                        byteArrayOf(
                            (it ushr 24).toByte(),
                            (it ushr 16).toByte(),
                            (it ushr 8).toByte(),
                            it.toByte(),
                        )
                    }
                socket.setOutboundFileDescriptors(descriptors.toTypedArray().takeIf { it.isNotEmpty() })
                try {
                    socketOperationWithDeadline("request-header", TIMEOUT_MS, socket::abort) {
                        socket.output.write(header)
                    }
                } finally {
                    socket.setOutboundFileDescriptors(null)
                }
                socketOperationWithDeadline("request-body", TIMEOUT_MS, socket::abort) {
                    socket.output.write(payload)
                }

                val responseHeader =
                    socketOperationWithDeadline("response-header", TIMEOUT_MS, socket::abort) {
                        readExact(socket.input, 4)
                    }
                val length =
                    ((responseHeader[0].toInt() and 0xff) shl 24) or
                        ((responseHeader[1].toInt() and 0xff) shl 16) or
                        ((responseHeader[2].toInt() and 0xff) shl 8) or
                        (responseHeader[3].toInt() and 0xff)
                require(length in 1..MAX_PAYLOAD_BYTES) { "Daemon OTA response length is invalid" }
                val response =
                    socketOperationWithDeadline("response-body", TIMEOUT_MS, socket::abort) {
                        readExact(socket.input, length)
                    }
                require(socket.inboundFileDescriptors().isNullOrEmpty()) { "Daemon OTA response carries descriptors" }
                val trailing =
                    socketOperationWithDeadline("response-eof", TIMEOUT_MS, socket::abort) {
                        socket.input.read()
                    }
                require(trailing == -1) { "Daemon OTA response has trailing bytes" }
                exchangeCompleted = true
                response
            } finally {
                cancellation?.dispose()
                if (exchangeCompleted) {
                    socket.close()
                } else {
                    closeSocketAsynchronously("exchange", socket::abort)
                }
            }
        }

    companion object {
        private const val LOG = "LocalSocketDaemonOtaGateway"
        const val SOCKET_NAME = "voboost.ota.v1"
        const val TIMEOUT_MS = 2000

        /** The root daemon owns the abstract OTA socket; SO_PEERCRED must match. */
        const val EXPECTED_DAEMON_UID = 0
        const val MAX_PAYLOAD_BYTES = 524_288
        const val MAX_CHANGED_DESCRIPTORS = 251
        const val MAX_TOTAL_DESCRIPTORS = 253
        private const val MANIFEST = "voboost.json"
        private const val MANIFEST_SIGNATURE = "voboost.json.sig"
        val ACTIVE_REQUEST = "{\"request\":\"activeRelease\"}\n".toByteArray(Charsets.UTF_8)

        fun proposalRequest(candidate: OtaCandidateHandle): ByteArray =
            buildString {
                append("{\"request\":\"propose\",\"candidate\":{")
                append("\"transactionId\":${jsonString(candidate.transactionId)},")
                append("\"releaseIdentity\":${jsonString(candidate.releaseIdentity)},")
                append("\"manifestSha256\":${jsonString(candidate.manifestSha256)},")
                append("\"artifacts\":[")
                append(
                    candidate.artifacts.joinToString(",") {
                        "{\"file\":${jsonString(it.file)},\"size\":${it.size},\"sha256\":${jsonString(it.sha256)}}"
                    },
                )
                append("]}}\n")
            }.toByteArray(Charsets.UTF_8)

        private fun parseActive(bytes: ByteArray): DaemonActiveResult {
            val json = strictResponse(bytes, "activeRelease")
            val result = json.getJSONObject("result")
            return when (result.getString("status")) {
                "unavailable" -> {
                    require(result.keys().asSequence().toSet() == setOf("status"))
                    require(String(bytes, Charsets.UTF_8) == "{\"response\":\"activeRelease\",\"result\":{\"status\":\"unavailable\"}}\n")
                    DaemonActiveResult.Unavailable
                }
                "active" -> {
                    // The terminal block is OPTIONAL: absent while no update
                    // has been proposed (pre-first-OTA installs and the
                    // unavailable/none states). When present it is the
                    // replayed terminal acknowledgement of the previously
                    // proposed transaction and must carry the exact match
                    // tuple the durable store records.
                    val keys = result.keys().asSequence().toSet()
                    require(keys == setOf("status", "identity") || keys == setOf("status", "identity", "terminal"))
                    val terminal =
                        if (keys.contains("terminal")) {
                            val block = result.getJSONObject("terminal")
                            require(
                                block.keys().asSequence().toSet() ==
                                    setOf(
                                        "transactionId", "releaseIdentity", "manifestSha256", "outcome",
                                    ),
                            )
                            val transactionId = block.getString("transactionId")
                            require(transactionId.isNotEmpty() && transactionId.length <= 128)
                            // The terminal identity must survive the same
                            // canonical semver check the daemon-side contract
                            // applies (contract/status.rs canonical_semver).
                            ProductSemVer.parse(block.getString("releaseIdentity"))
                            val outcome = block.getString("outcome")
                            require(outcome == "complete" || outcome == "rejected")
                            DaemonTerminalAcknowledgement(
                                transactionId,
                                block.getString("releaseIdentity"),
                                block.getString("manifestSha256"),
                                if (outcome == "complete") {
                                    DaemonTerminalOutcome.COMPLETE
                                } else {
                                    DaemonTerminalOutcome.REJECTED
                                },
                            )
                        } else {
                            null
                        }
                    val identity = result.getJSONObject("identity")
                    require(identity.keys().asSequence().toSet() == setOf("channel", "releaseIdentity", "artifacts"))
                    val artifactsJson = identity.getJSONObject("artifacts")
                    val artifacts =
                        artifactsJson.keys().asSequence().toList().sorted().associateWith { file ->
                            val value = artifactsJson.getJSONObject(file)
                            require(value.keys().asSequence().toSet() == setOf("size", "sha256"))
                            val rawSize = value.get("size")
                            require(rawSize is Int || rawSize is Long)
                            val size = (rawSize as Number).toLong()
                            val sha256 = value.getString("sha256")
                            require(file.isNotEmpty() && file.length <= 255 && file.none { it == '/' || it == '\\' || it == '\u0000' })
                            require(size > 0 && sha256.matches(Regex("^[0-9a-f]{64}$")))
                            ActiveArtifactIdentity(size, sha256)
                        }
                    require(artifacts.isNotEmpty() && artifacts.size <= 1024)
                    val channel = identity.getString("channel")
                    val releaseIdentity = ProductSemVer.parse(identity.getString("releaseIdentity"))
                    require(
                        channel == "stable" && releaseIdentity.prerelease.isEmpty() ||
                            channel == "beta" && releaseIdentity.prerelease.firstOrNull()?.startsWith("beta") == true,
                    )
                    val active =
                        ActiveReleaseIdentity(
                            channel,
                            releaseIdentity,
                            artifacts,
                        )
                    val canonical =
                        "{\"response\":\"activeRelease\",\"result\":{\"status\":\"active\",\"identity\":{" +
                            "\"channel\":${jsonString(active.channel)}," +
                            "\"releaseIdentity\":${jsonString(active.releaseIdentity.source)},\"artifacts\":{" +
                            artifacts.entries.joinToString(",") {
                                "${jsonString(it.key)}:{\"size\":${it.value.size},\"sha256\":${jsonString(it.value.sha256)}}"
                            } + "}}" +
                            (
                                terminal?.let { ack ->
                                    ",\"terminal\":{\"transactionId\":${jsonString(ack.transactionId)}," +
                                        "\"releaseIdentity\":${jsonString(ack.releaseIdentity)}," +
                                        "\"manifestSha256\":${jsonString(ack.manifestSha256)}," +
                                        "\"outcome\":\"${if (ack.outcome == DaemonTerminalOutcome.COMPLETE) "complete" else "rejected"}\"}"
                                } ?: ""
                            ) +
                            "}}\n"
                    require(canonical.toByteArray().contentEquals(bytes))
                    DaemonActiveResult.Active(active, terminal)
                }
                else -> error("Unknown active release status")
            }
        }

        private fun parseProposal(bytes: ByteArray): DaemonProposalResult {
            val json = strictResponse(bytes, "propose")
            val result = json.getJSONObject("result")
            val parsed =
                when (result.getString("status")) {
                    "accepted" -> DaemonProposalResult.Accepted
                    "pending" -> DaemonProposalResult.Pending
                    "install-apk" -> DaemonProposalResult.InstallApk
                    "unavailable" -> DaemonProposalResult.Unavailable
                    "rejected" -> {
                        val reason = result.getString("reason")
                        require(reason.isNotEmpty() && reason.length <= 512 && reason.none(Char::isISOControl))
                        DaemonProposalResult.Rejected(reason)
                    }
                    else -> error("Unknown proposal status")
                }
            val canonicalResult =
                when (parsed) {
                    DaemonProposalResult.Accepted -> "{\"status\":\"accepted\"}"
                    DaemonProposalResult.Pending -> "{\"status\":\"pending\"}"
                    DaemonProposalResult.InstallApk -> "{\"status\":\"install-apk\"}"
                    DaemonProposalResult.Unavailable -> "{\"status\":\"unavailable\"}"
                    is DaemonProposalResult.Rejected -> "{\"status\":\"rejected\",\"reason\":${jsonString(parsed.reason)}}"
                }
            require("{\"response\":\"propose\",\"result\":$canonicalResult}\n".toByteArray().contentEquals(bytes))
            return parsed
        }

        private fun strictResponse(
            bytes: ByteArray,
            response: String,
        ): org.json.JSONObject {
            require(bytes.size in 1..MAX_PAYLOAD_BYTES && bytes.last() == '\n'.code.toByte())
            DuplicateKeyJsonValidator.validate(String(bytes, Charsets.UTF_8))
            val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            require(json.keys().asSequence().toSet() == setOf("response", "result"))
            require(json.getString("response") == response)
            return json
        }

        private fun readExact(
            input: InputStream,
            size: Int,
        ): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(bytes, offset, size - offset)
                if (read < 0) throw java.io.EOFException("Daemon OTA frame disconnected")
                if (read == 0) throw SocketTimeoutException("Daemon OTA frame made no progress")
                offset += read
            }
            return bytes
        }
    }
}
