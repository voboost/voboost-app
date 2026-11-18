package ru.voboost.ota

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import ru.voboost.AppFileIdentity
import ru.voboost.AppReadAuthority
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalSocketDaemonOtaGatewayTest {
    @Test
    fun androidConnectDeadlineCreatesSocketBeforeReadTimeoutAndClosesAStall() {
        val events = mutableListOf<String>()
        var connected = false
        connectWithDeadlineBeforeReadTimeout(
            timeoutMillis = 1_000,
            connect = {
                events += "connect"
                connected = true
            },
            close = { events += "close" },
            setReadTimeout = {
                check(connected) { "LocalSocket read timeout was configured before connect" }
                events += "read-timeout"
            },
        )
        assertEquals(listOf("connect", "read-timeout"), events)

        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        try {
            connectWithDeadlineBeforeReadTimeout(
                timeoutMillis = 20,
                connect = { release.await() },
                close = {
                    closed.countDown()
                    release.countDown()
                },
                setReadTimeout = { fail("read timeout configured after failed connect") },
            )
            fail("SocketTimeoutException expected")
        } catch (_: SocketTimeoutException) {
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun responseReadDeadlineClosesSocketWhenAndroidSoTimeoutDoesNotUnblockRead() {
        val releaseRead = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            socketOperationWithDeadline(
                operationName = "response-header",
                timeoutMillis = 20,
                close = {
                    closed.countDown()
                    releaseClose.await()
                },
                operation = {
                    releaseRead.await()
                    byteArrayOf()
                },
            )
            fail("SocketTimeoutException expected")
        } catch (_: SocketTimeoutException) {
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
        } finally {
            releaseClose.countDown()
            releaseRead.countDown()
        }
    }

    @Test
    fun nativeWireCorpusHasExactRecordedBytes() {
        java.io.File("src/test/resources/native-ota-wire/NATIVE-WIRE.sha256").readLines().forEach { line ->
            val expected = line.substringBefore("  ")
            val path = line.substringAfter("  ")
            assertEquals(expected, OtaHttpTransport.sha256(java.io.File(path).readBytes()))
        }
        assertArrayEquals("{\"status\":\"install-apk\"}\n".toByteArray(), fixture("result-install-apk.json"))
    }

    @Test
    fun terminalAcknowledgementRidesTheActiveReleaseWireAndReconciles() =
        kotlinx.coroutines.test.runTest {
            // Exact daemon canonical bytes for an active response carrying a
            // terminal acknowledgement of a previously proposed transaction.
            // The app parser must accept it (wire is forwards-compatible with
            // the daemon emitting terminals) and surface the exact tuple so
            // OtaTransactionStore.reconcile can retire the PROPOSED record.
            val identityJson =
                "\"identity\":{\"channel\":\"beta\",\"releaseIdentity\":\"1.0.0-beta1\"," +
                    "\"artifacts\":{\"voboost-daemon\":{\"size\":2," +
                    "\"sha256\":\"1111111111111111111111111111111111111111111111111111111111111111\"}}}"
            val terminalJson =
                "\"terminal\":{\"transactionId\":\"0123456789abcdef\"," +
                    "\"releaseIdentity\":\"1.0.0-beta1\"," +
                    "\"manifestSha256\":\"2222222222222222222222222222222222222222222222222222222222222222\"," +
                    "\"outcome\":\"complete\"}"
            val frame =
                (
                    "{\"response\":\"activeRelease\",\"result\":{\"status\":\"active\"," +
                        identityJson + "," + terminalJson + "}}\n"
                ).toByteArray()
            val gateway = LocalSocketDaemonOtaGateway(QueueFactory(RecordingSocket(frame(frame))))
            val active = gateway.activeRelease()
            require(active is DaemonActiveResult.Active)
            val terminal = active.terminal
            requireNotNull(terminal)
            assertEquals("0123456789abcdef", terminal.transactionId)
            assertEquals("1.0.0-beta1", terminal.releaseIdentity)
            assertEquals(
                "2222222222222222222222222222222222222222222222222222222222222222",
                terminal.manifestSha256,
            )
            assertEquals(DaemonTerminalOutcome.COMPLETE, terminal.outcome)

            // Rejected outcome parses too and never retires in the store —
            // only COMPLETE may retire an exact-identity record.
            val rejected =
                (
                    "{\"response\":\"activeRelease\",\"result\":{\"status\":\"active\"," +
                        identityJson + ",\"terminal\":{\"transactionId\":\"0123456789abcdef\"," +
                        "\"releaseIdentity\":\"1.0.0-beta1\"," +
                        "\"manifestSha256\":\"2222222222222222222222222222222222222222222222222222222222222222\"," +
                        "\"outcome\":\"rejected\"}}}\n"
                ).toByteArray()
            val rejectedGateway =
                LocalSocketDaemonOtaGateway(QueueFactory(RecordingSocket(frame(rejected))))
            val rejectedActive = rejectedGateway.activeRelease()
            require(rejectedActive is DaemonActiveResult.Active)
            assertEquals(DaemonTerminalOutcome.REJECTED, requireNotNull(rejectedActive.terminal).outcome)

            // An unknown outcome or malformed tuple must fail closed: the
            // whole activeRelease maps to Unavailable (the gateway never
            // surfaces a half-trusted active identity or terminal).
            val malformed =
                (
                    "{\"response\":\"activeRelease\",\"result\":{\"status\":\"active\"," +
                        identityJson + ",\"terminal\":{\"transactionId\":\"x\"," +
                        "\"releaseIdentity\":\"1.0.0-beta1\"," +
                        "\"manifestSha256\":\"2222222222222222222222222222222222222222222222222222222222222222\"," +
                        "\"outcome\":\"weird\"}}}\n"
                ).toByteArray()
            val malformedGateway =
                LocalSocketDaemonOtaGateway(QueueFactory(RecordingSocket(frame(malformed))))
            assertEquals(DaemonActiveResult.Unavailable, malformedGateway.activeRelease())
        }

    @Test
    fun goldenFramesAndDescriptorOrderMatchNativeFixtures() =
        kotlinx.coroutines.test.runTest {
            val activeSocket = RecordingSocket(frame(fixture("response-active.json")))
            val proposalSocket = RecordingSocket(frame(fixture("response-pending.json")))
            val installSocket = RecordingSocket(frame(fixture("response-install-apk.json")))
            val sockets = QueueFactory(activeSocket, proposalSocket, installSocket)
            val gateway = LocalSocketDaemonOtaGateway(sockets)

            val active = gateway.activeRelease()
            assertTrue(active is DaemonActiveResult.Active)
            assertArrayEquals(fixture("request-active.json"), activeSocket.payload())
            assertTrue(activeSocket.headerDescriptors.single().isEmpty())
            assertEquals(0, activeSocket.flushCount)

            val candidate = fixtureCandidate()
            val files = candidateFiles(candidate)
            assertEquals(DaemonProposalResult.Pending, gateway.propose(candidate, files))
            assertArrayEquals(fixture("request-proposal.json"), proposalSocket.payload())
            assertEquals(4, proposalSocket.headerDescriptors.single().size)
            assertEquals(1, proposalSocket.descriptorClearCount)

            assertEquals(DaemonProposalResult.InstallApk, gateway.propose(candidate, candidateFiles(candidate)))
            assertArrayEquals(fixture("request-proposal.json"), installSocket.payload())
        }

    @Test
    fun foreignPeerUidIsRejectedBeforeAnyPayloadIsSent() =
        kotlinx.coroutines.test.runTest {
            val hijacker =
                RecordingSocket(
                    frame(fixture("response-active.json")),
                    peerUid = 10_000 + LocalSocketDaemonOtaGateway.EXPECTED_DAEMON_UID,
                )
            assertEquals(
                DaemonActiveResult.Unavailable,
                LocalSocketDaemonOtaGateway(QueueFactory(hijacker)).activeRelease(),
            )
            assertEquals(0, hijacker.writtenBytes())
        }

    @Test
    fun disconnectTimeoutBadFrameFdCountAndReplayFailClosed() =
        kotlinx.coroutines.test.runTest {
            val disconnected = LocalSocketDaemonOtaGateway(QueueFactory(RecordingSocket(byteArrayOf())))
            assertEquals(DaemonActiveResult.Unavailable, disconnected.activeRelease())

            val timeout = LocalSocketDaemonOtaGateway(QueueFactory(RecordingSocket(input = TimeoutInputStream())))
            assertEquals(DaemonActiveResult.Unavailable, timeout.activeRelease())

            val oversized = byteArrayOf(0, 8, 0, 1)
            assertEquals(
                DaemonActiveResult.Unavailable,
                LocalSocketDaemonOtaGateway(QueueFactory(RecordingSocket(oversized))).activeRelease(),
            )

            val responseFds = RecordingSocket(frame(fixture("response-active.json")), inbound = arrayOf(FileDescriptor()))
            assertEquals(
                DaemonActiveResult.Unavailable,
                LocalSocketDaemonOtaGateway(QueueFactory(responseFds)).activeRelease(),
            )

            val candidate = fixtureCandidate()
            val wrongFiles = OtaCandidateFiles(candidateFiles(candidate).files.dropLast(1)) {}
            val unused = QueueFactory(RecordingSocket(frame(fixture("response-pending.json"))))
            assertEquals(DaemonProposalResult.Unavailable, LocalSocketDaemonOtaGateway(unused).propose(candidate, wrongFiles))
            assertEquals(0, unused.connectCount)

            val first = RecordingSocket(frame(fixture("response-pending.json")))
            val second = RecordingSocket(frame(fixture("response-pending.json")))
            val replayGateway = LocalSocketDaemonOtaGateway(QueueFactory(first, second))
            assertEquals(DaemonProposalResult.Pending, replayGateway.propose(candidate, candidateFiles(candidate)))
            assertEquals(DaemonProposalResult.Pending, replayGateway.propose(candidate, candidateFiles(candidate)))
            assertArrayEquals(first.payload(), second.payload())
        }

    @Test
    fun cancellationClosesStalledSocketsAndProposalDuplicatesWithoutParsing() =
        kotlinx.coroutines.runBlocking {
            val stalledConnect = StalledSocket(stallConnect = true)
            assertCancellation(stalledConnect) {
                LocalSocketDaemonOtaGateway(QueueFactory(stalledConnect)).activeRelease()
            }

            val stalledRead = StalledSocket()
            assertCancellation(stalledRead) {
                LocalSocketDaemonOtaGateway(QueueFactory(stalledRead)).activeRelease()
            }

            val candidate = fixtureCandidate()
            val duplicates = mutableListOf<ParcelFileDescriptor>()
            val proposalRead = StalledSocket()
            assertCancellation(proposalRead) {
                LocalSocketDaemonOtaGateway(QueueFactory(proposalRead)).propose(
                    candidate,
                    candidateFiles(candidate, duplicates),
                )
            }
            duplicates.forEach { Mockito.verify(it, Mockito.times(1)).close() }
        }

    private fun fixtureCandidate() =
        OtaCandidateHandle(
            "a".repeat(64),
            "1.0.0-beta1",
            org.json.JSONObject(fixture("proposal.json").toString(Charsets.UTF_8)).getString("manifestSha256"),
            listOf(
                CandidateArtifactIdentity("voboost-daemon", 2, "1".repeat(64)),
                CandidateArtifactIdentity(
                    "agent-settings.dex",
                    17,
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ),
            ),
        )

    private fun candidateFiles(
        candidate: OtaCandidateHandle,
        duplicates: MutableList<ParcelFileDescriptor>? = null,
    ): OtaCandidateFiles {
        val identities =
            listOf(
                CandidateArtifactIdentity("voboost.json", 1, "2".repeat(64)),
                CandidateArtifactIdentity("voboost.json.sig", 64, "3".repeat(64)),
            ) + candidate.artifacts
        return OtaCandidateFiles(
            identities.mapIndexed { index, value -> retained(value, index, duplicates) },
        ) {}
    }

    private fun retained(
        value: CandidateArtifactIdentity,
        index: Int,
        duplicates: MutableList<ParcelFileDescriptor>? = null,
    ): RetainedOtaFile {
        val parcel = Mockito.mock(ParcelFileDescriptor::class.java)
        duplicates?.add(parcel)
        Mockito.`when`(parcel.fileDescriptor).thenReturn(FileDescriptor())
        val authority =
            object : AppReadAuthority {
                override fun identity() = identity(index)

                override fun duplicateForTransfer(): ParcelFileDescriptor = parcel

                override fun seek(offset: Long) = Unit

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = -1

                override fun close() = Unit
            }
        return RetainedOtaFile(value.file, value.size, value.sha256, identity(index), authority)
    }

    private fun identity(index: Int) = AppFileIdentity(1, index.toLong() + 1, 1, 1, 0x8100, 1, 1, 0, 0, 0, 0)

    private fun fixture(name: String): ByteArray = java.io.File("src/test/resources/native-ota-wire/$name").readBytes()

    private fun frame(payload: ByteArray): ByteArray =
        byteArrayOf(
            (payload.size ushr 24).toByte(),
            (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte(),
        ) + payload

    private suspend fun assertCancellation(
        socket: StalledSocket,
        operation: suspend () -> Any,
    ) = coroutineScope {
        val call = async(kotlinx.coroutines.Dispatchers.Default) { operation() }
        assertTrue("operation did not reach the stalled socket", socket.entered.await(2, TimeUnit.SECONDS))
        call.cancel()
        try {
            call.await()
            fail("CancellationException expected")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: cancellation is a lifecycle signal, never an Unavailable response.
        }
        assertTrue("cancellation did not close the socket", socket.closed.await(2, TimeUnit.SECONDS))
        assertTrue(socket.closeCount > 0)
        assertEquals(0, socket.inboundCalls)
    }

    private class QueueFactory(vararg sockets: DaemonSocket) : DaemonSocketFactory {
        private val queue = ArrayDeque(sockets.toList())
        var connectCount = 0

        override fun create(): DaemonSocket {
            connectCount++
            return queue.removeFirst()
        }
    }

    private class RecordingSocket(
        bytes: ByteArray = byteArrayOf(),
        input: InputStream = ByteArrayInputStream(bytes),
        private val inbound: Array<FileDescriptor>? = null,
        private val peerUid: Int = LocalSocketDaemonOtaGateway.EXPECTED_DAEMON_UID,
    ) : DaemonSocket {
        override val input: InputStream = input
        private val written = ByteArrayOutputStream()
        private var outbound: Array<FileDescriptor>? = null
        val headerDescriptors = mutableListOf<List<FileDescriptor>>()
        var descriptorClearCount = 0
        var flushCount = 0

        override fun connect() = Unit

        override fun peerCredentials() = SocketPeerCredentials(peerUid)

        override val output: OutputStream =
            object : OutputStream() {
                override fun write(value: Int) = written.write(value)

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    if (written.size() == 0 && length == 4) headerDescriptors += outbound.orEmpty().toList()
                    written.write(bytes, offset, length)
                }

                override fun flush() {
                    flushCount++
                    throw IOException("LocalSocket transport must not wait for peer drain")
                }
            }

        override fun setOutboundFileDescriptors(descriptors: Array<FileDescriptor>?) {
            outbound = descriptors
            if (descriptors == null) descriptorClearCount++
        }

        override fun inboundFileDescriptors(): Array<FileDescriptor>? = inbound

        override fun close() = Unit

        fun payload(): ByteArray = written.toByteArray().copyOfRange(4, written.size())

        fun writtenBytes(): Int = written.size()
    }

    private class StalledSocket(private val stallConnect: Boolean = false) : DaemonSocket {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var closeCount = 0
        var inboundCalls = 0

        override val output: OutputStream = ByteArrayOutputStream()
        override val input: InputStream =
            object : InputStream() {
                override fun read(): Int = stall()

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = stall()
            }

        override fun connect() {
            if (stallConnect) stall()
        }

        override fun peerCredentials() = SocketPeerCredentials(LocalSocketDaemonOtaGateway.EXPECTED_DAEMON_UID)

        override fun setOutboundFileDescriptors(descriptors: Array<FileDescriptor>?) = Unit

        override fun inboundFileDescriptors(): Array<FileDescriptor>? {
            inboundCalls++
            return null
        }

        override fun close() {
            closeCount++
            closed.countDown()
        }

        private fun stall(): Nothing {
            entered.countDown()
            closed.await(2, TimeUnit.SECONDS)
            throw IOException("socket closed")
        }
    }

    private class TimeoutInputStream : InputStream() {
        override fun read(): Int = throw SocketTimeoutException("timeout")

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = throw SocketTimeoutException("timeout")
    }
}
