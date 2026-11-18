package ru.voboost

import android.net.Credentials
import android.net.LocalSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReviewAndroidLogSocketTest {
    @Test
    fun nonRootPeerReceivesNoLogBytes() {
        val sent = ByteArrayOutputStream()
        Mockito.mockConstruction(LocalSocket::class.java) { socket, _ ->
            Mockito.`when`(socket.peerCredentials).thenReturn(Credentials(7, 2000, 2000))
            Mockito.`when`(socket.outputStream).thenReturn(sent)
        }.use {
            assertThrows(IllegalStateException::class.java) { DaemonAppLogBatchSender().send("private".toByteArray()) }
        }
        assertEquals(0, sent.size())
    }

    @Test
    fun malformedAcknowledgementCannotConfirmDelivery() {
        val frame =
            ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeInt(32)
                    write("A".repeat(32).toByteArray())
                }
            }.toByteArray()
        Mockito.mockConstruction(LocalSocket::class.java) { socket, _ ->
            Mockito.`when`(socket.peerCredentials).thenReturn(Credentials(7, 0, 0))
            Mockito.`when`(socket.inputStream).thenReturn(ByteArrayInputStream(frame))
            Mockito.`when`(socket.outputStream).thenReturn(ByteArrayOutputStream())
        }.use {
            assertThrows(IllegalArgumentException::class.java) { DaemonAppLogBatchSender().send("batch".toByteArray()) }
        }
    }

    @Test
    fun sdk28LogSocketConnectsBeforeOptionsAndTransfersExactBatch() {
        val connected = AtomicBoolean(false)
        val body = "batch".toByteArray()
        val id = "a".repeat(32)
        val frame =
            ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeInt(32)
                    write(id.toByteArray())
                }
            }.toByteArray()
        val sent = ByteArrayOutputStream()
        Mockito.mockConstruction(LocalSocket::class.java) { socket, _ ->
            Mockito.doAnswer {
                connected.set(true)
                null
            }.`when`(socket).connect(Mockito.any())
            Mockito.doAnswer {
                assertTrue("timeout must follow connect", connected.get())
                null
            }
                .`when`(socket).setSoTimeout(Mockito.anyInt())
            Mockito.`when`(socket.peerCredentials).thenReturn(Credentials(7, 0, 0))
            Mockito.`when`(socket.inputStream).thenReturn(ByteArrayInputStream(frame))
            Mockito.`when`(socket.outputStream).thenReturn(sent)
        }.use { construction ->
            assertEquals(id, DaemonAppLogBatchSender().send(body))
            assertEquals(1, construction.constructed().size)
            Mockito.verify(construction.constructed().single()).setSoTimeout(APP_LOG_SOCKET_TIMEOUT_MS)
        }
        DataInputStream(ByteArrayInputStream(sent.toByteArray())).use { input ->
            assertEquals(body.size, input.readInt())
            assertArrayEquals(body, ByteArray(body.size).also(input::readFully))
            assertEquals(-1, input.read())
        }
    }
}
