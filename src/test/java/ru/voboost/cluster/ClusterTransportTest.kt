package ru.voboost.cluster

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ClusterTransportTest {
    @get:Rule val temporary = TemporaryFolder(File("build/cluster-test-tmp").apply { mkdirs() })

    @Test fun pairedTlsRelaysExactBytesAndRejectsForeignCertificate() =
        runBlocking {
            val serverIdentity = identity("server")
            val clientIdentity = identity("client")
            val serverPin = pin(serverIdentity)
            val clientPin = pin(clientIdentity)
            val emptyPasswordIdentity =
                KeyStore.getInstance("PKCS12").apply {
                    load(null, charArrayOf())
                    val alias = clientIdentity.aliases().nextElement()
                    setKeyEntry(
                        alias,
                        clientIdentity.getKey(alias, "fixture-password".toCharArray()),
                        charArrayOf(),
                        clientIdentity.getCertificateChain(alias),
                    )
                }
            val credential = ByteArrayOutputStream().also { emptyPasswordIdentity.store(it, charArrayOf()) }.toByteArray()
            val context = serverContext(serverIdentity, clientPin)
            val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            server.needClientAuth = true
            server.soTimeout = 3_000
            val captured = AtomicReference<Pair<ByteArray, ByteArray>>()
            val failure = AtomicReference<Throwable>()
            val receipt = byteArrayOf(11, 12, 13)
            val thread =
                Thread {
                    try {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 2_000
                            val input = DataInputStream(socket.inputStream)
                            val header = ByteArray(input.readInt())
                            input.readFully(header)
                            val bodies = ByteArray(5)
                            input.readFully(bodies)
                            socket.soTimeout = 100
                            try {
                                input.read()
                                error("Unexpected bytes after declared file bodies")
                            } catch (_: SocketTimeoutException) {
                            }
                            captured.set(header to bodies)
                            val output = DataOutputStream(socket.outputStream)
                            output.writeInt(receipt.size)
                            output.write(receipt)
                            output.flush()
                        }
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            val config =
                ClusterTransportConfig(
                    "127.0.0.1",
                    server.localPort,
                    serverPin,
                    clientPin,
                    credential.size.toLong(),
                    clusterSha256(credential),
                )
            testLease(credential, config).use { lease ->
                assertArrayEquals(receipt, PinnedClusterTransport().deliver(lease))
                thread.join(5_000)
                assertTrue(!thread.isAlive)
                failure.get()?.let { throw it }
                assertArrayEquals(ClusterContracts.networkHeader(lease.files.map { it.metadata }), captured.get().first)
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), captured.get().second)
            }
            server.close()

            val foreign = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            foreign.needClientAuth = true
            foreign.soTimeout = 2_000
            val acceptedBodies = AtomicBoolean(false)
            val hostile =
                Thread {
                    try {
                        (foreign.accept() as SSLSocket).use {
                            it.soTimeout = 2_000
                            if (it.inputStream.read() >= 0) acceptedBodies.set(true)
                        }
                    } catch (
                        _: Exception,
                    ) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            testLease(credential, config.copy(port = foreign.localPort, serverCertificateSha256 = "0".repeat(64))).use { lease ->
                assertTrue(runCatching { PinnedClusterTransport().deliver(lease) }.isFailure)
            }
            hostile.join(3_000)
            foreign.close()
            assertTrue(!acceptedBodies.get())
            assertTrue(
                runCatching {
                    PinnedClusterTransport().tlsContext(
                        credential,
                        config.copy(clientCertificateSha256 = "0".repeat(64)),
                    )
                }.isFailure,
            )
            credential.fill(0)
        }

    @Test fun cancellationClosesAStalledHandshake() =
        runBlocking {
            val client = identity("cancel-client")
            val empty =
                KeyStore.getInstance("PKCS12").apply {
                    load(null, charArrayOf())
                    val alias = client.aliases().nextElement()
                    setKeyEntry(
                        alias,
                        client.getKey(alias, "fixture-password".toCharArray()),
                        charArrayOf(),
                        client.getCertificateChain(alias),
                    )
                }
            val credential = ByteArrayOutputStream().also { empty.store(it, charArrayOf()) }.toByteArray()
            val server = java.net.ServerSocket(0)
            val closed = java.util.concurrent.CountDownLatch(1)
            val peer =
                Thread {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 3_000
                            val input = socket.inputStream
                            while (input.read() >= 0) { }
                            closed.countDown()
                        }
                    } catch (_: Exception) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            val config =
                ClusterTransportConfig(
                    "127.0.0.1",
                    server.localPort,
                    "0".repeat(64),
                    pin(client),
                    credential.size.toLong(),
                    clusterSha256(credential),
                )
            val started = System.nanoTime()
            testLease(credential, config).use { lease ->
                val failure =
                    runCatching {
                        kotlinx.coroutines.withTimeout(
                            150,
                        ) { PinnedClusterTransport().deliver(lease) }
                    }.exceptionOrNull()
                assertTrue(
                    "Cancellation became ${failure?.javaClass?.name}: ${failure?.message}",
                    failure is kotlinx.coroutines.TimeoutCancellationException,
                )
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
            assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            server.close()
            peer.join(2_000)
            credential.fill(0)
        }

    private fun identity(name: String): KeyStore {
        val path = File(temporary.root, "$name.p12")
        val executable = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val process =
            ProcessBuilder(
                executable,
                "-genkeypair",
                "-alias",
                name,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=$name",
                "-keystore",
                path.path,
                "-storetype",
                "PKCS12",
                "-storepass",
                "fixture-password",
                "-keypass",
                "fixture-password",
            ).redirectErrorStream(
                true,
            ).start()
        process.inputStream.use { it.readBytes() }
        assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        return KeyStore.getInstance("PKCS12").apply { path.inputStream().use { load(it, "fixture-password".toCharArray()) } }
    }

    private fun pin(store: KeyStore): String = clusterSha256(store.getCertificate(store.aliases().nextElement()).encoded)

    private fun serverContext(
        store: KeyStore,
        clientPin: String,
    ): SSLContext {
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        manager.init(store, "fixture-password".toCharArray())
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String,
                ) {
                    require(clusterSha256(chain.first().encoded) == clientPin)
                }

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String,
                ) = error("Server-only fixture")
            }
        return SSLContext.getInstance("TLS").apply { init(manager.keyManagers, arrayOf<TrustManager>(trust), SecureRandom()) }
    }
}
