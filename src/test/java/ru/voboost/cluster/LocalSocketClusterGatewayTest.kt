package ru.voboost.cluster

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import ru.voboost.ota.DaemonSocket
import ru.voboost.ota.DaemonSocketFactory
import ru.voboost.ota.SocketPeerCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.InputStream

class LocalSocketClusterGatewayTest {
    @Test
    fun foreignPeerAndMalformedMailboxFailBeforeDelivery() =
        runTest {
            val foreign = ReplySocket(uid = 10_001)
            assertEquals(ClusterTakeResult.Unavailable, gateway(foreign).take())
            assertEquals(0, foreign.output.size())
            val empty = ReplySocket()
            assertEquals(ClusterTakeResult.Empty, gateway(empty).take())
            val busy = ReplySocket(status = "busy")
            assertEquals(ClusterTakeResult.Busy, gateway(busy).take())
            val setup = ReplySocket(status = "setup-required")
            assertEquals(ClusterTakeResult.SetupRequired, gateway(setup).take())
            val unknown = ReplySocket(extra = ",\"unknown\":1")
            assertEquals(ClusterTakeResult.Unavailable, gateway(unknown).take())
            val wrongId = ReplySocket(echoId = false)
            assertEquals(ClusterTakeResult.Unavailable, gateway(wrongId).take())
            assertTrue(empty.output.size() > 4)
        }

    @Test
    fun malformedTakeRetriesWithoutDeliveryAndLaterEmptyTakeCompletes() =
        runTest {
            val replies = ArrayDeque(listOf(ReplySocket(extra = ",\"unknown\":1"), ReplySocket()))
            val gateway = LocalSocketClusterGateway(Mockito.mock(Context::class.java), DaemonSocketFactory { replies.removeFirst() })
            var deliveries = 0
            val courier =
                ClusterCourier(
                    gateway,
                    ClusterTransport {
                        deliveries++
                        byteArrayOf(1)
                    },
                )

            assertEquals(ClusterDeliveryResult.RETRY, courier.run())
            assertEquals(0, deliveries)
            assertEquals(ClusterDeliveryResult.COMPLETE, courier.run())
            assertEquals(0, deliveries)
            assertTrue(replies.isEmpty())
        }

    @Test
    fun authenticatedServerMayReplyAfterTheCacheReuseInterval() =
        runTest {
            val socket = ReplySocket(readDelayMillis = 2_200)
            assertEquals(ClusterTakeResult.Empty, gateway(socket).take())
        }

    @Test
    fun rejectsWritableDescriptorFlags() {
        requireReadOnlyDescriptorFlags("pos:\t0\nflags:\t0100000\nmnt_id:\t1\n".toByteArray())
        for (text in listOf(
            "flags:\t0100001\n",
            "flags:\t0100002\n",
            "flags:\t0\nflags:\t0\n",
            "flags:\t-1\n",
            "flags:\t0x0\n",
            "pos:\t0\n",
        )) {
            assertTrue(runCatching { requireReadOnlyDescriptorFlags(text.toByteArray()) }.isFailure)
        }
    }

    private fun gateway(socket: ReplySocket) = LocalSocketClusterGateway(Mockito.mock(Context::class.java), DaemonSocketFactory { socket })

    private class ReplySocket(
        private val uid: Int = 0,
        private val status: String = "empty",
        private val extra: String = "",
        private val echoId: Boolean = true,
        private val readDelayMillis: Long = 0,
    ) : DaemonSocket {
        override val output = ByteArrayOutputStream()
        private var response: InputStream? = null
        override val input: InputStream
            get() =
                response ?: run {
                    if (readDelayMillis > 0) Thread.sleep(readDelayMillis)
                    val request = JSONObject(output.toByteArray().drop(4).toByteArray().toString(Charsets.US_ASCII))
                    val id = if (echoId) request.getString("requestId") else "0".repeat(64)
                    val bytes =
                        (
                            "{\"response\":\"clusterDeliveryTake\",\"protocolVersion\":1,\"requestId\":\"$id\"," +
                                "\"result\":{\"status\":\"$status\"$extra}}\n"
                        ).toByteArray()
                    val frame = ByteArrayOutputStream()
                    DataOutputStream(frame).use {
                        it.writeInt(bytes.size)
                        it.write(bytes)
                    }
                    ByteArrayInputStream(frame.toByteArray()).also { response = it }
                }

        override fun connect() = Unit

        override fun peerCredentials() = SocketPeerCredentials(uid)

        override fun setOutboundFileDescriptors(descriptors: Array<FileDescriptor>?) = Unit

        override fun inboundFileDescriptors(): Array<FileDescriptor>? = null

        override fun close() = Unit
    }
}
