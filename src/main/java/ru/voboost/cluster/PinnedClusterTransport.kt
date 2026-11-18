package ru.voboost.cluster

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.voboost.ota.socketOperationWithDeadline
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/** A pinned, mutually authenticated stream without redirects or alternate endpoints. */
internal class PinnedClusterTransport : ClusterTransport {
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun deliver(lease: ClusterLease): ByteArray =
        withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + 22_000_000_000L
            val credential = readClusterFile(lease.credentials, CLUSTER_MAX_DOCUMENT_BYTES)
            val context =
                try {
                    tlsContext(credential, lease.transport)
                } finally {
                    credential.fill(0)
                }
            val raw = Socket()
            val abort = {
                runCatching { raw.close() }
                Unit
            }
            val cancellation =
                coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) abort()
                }

            fun <T> bounded(
                name: String,
                action: () -> T,
            ): T {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                require(remaining > 0) { "Cluster transport deadline exceeded" }
                return socketOperationWithDeadline("cluster-$name", minOf(2_000L, remaining).toInt(), abort, action)
            }
            try {
                bounded("connect") { raw.connect(InetSocketAddress(lease.transport.host, lease.transport.port), 2_000) }
                val socket = context.socketFactory.createSocket(raw, lease.transport.host, lease.transport.port, false) as SSLSocket
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                require(socket.enabledProtocols.isNotEmpty())
                socket.soTimeout = 2_000
                bounded("handshake") { socket.startHandshake() }
                val output = DataOutputStream(socket.outputStream)
                val header = ClusterContracts.networkHeader(lease.files.map { it.metadata })
                bounded("header") {
                    output.writeInt(header.size)
                    output.write(header)
                }
                val buffer = ByteArray(16_384)
                for (source in lease.files) {
                    coroutineContext.ensureActive()
                    source.verifyIdentity()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var offset = 0L
                    while (offset < source.metadata.size) {
                        coroutineContext.ensureActive()
                        val count = source.readAt(offset, buffer, minOf(buffer.size.toLong(), source.metadata.size - offset).toInt())
                        require(count in 1..minOf(buffer.size.toLong(), source.metadata.size - offset).toInt())
                        bounded("body") { output.write(buffer, 0, count) }
                        digest.update(buffer, 0, count)
                        offset += count
                    }
                    source.verifyIdentity()
                    require(digest.digest().joinToString("") { "%02x".format(it) } == source.metadata.sha256)
                }
                bounded("flush") { output.flush() }
                val input = DataInputStream(socket.inputStream)
                val size = bounded("receipt-header") { input.readInt() }
                require(size in 1..CLUSTER_MAX_DOCUMENT_BYTES)
                val receipt = ByteArray(size)
                bounded("receipt") { input.readFully(receipt) }
                require(bounded("eof") { input.read() } == -1) { "Cluster receipt has trailing bytes" }
                receipt
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                throw failure
            } finally {
                cancellation?.dispose()
                abort()
            }
        }

    // The exact provisioned leaf pin is checked before platform trust-anchor validation.
    @SuppressLint("CustomX509TrustManager")
    internal fun tlsContext(
        bytes: ByteArray,
        config: ClusterTransportConfig,
    ): SSLContext {
        require(bytes.size.toLong() == config.credentialSize && clusterSha256(bytes) == config.credentialSha256)
        val store = KeyStore.getInstance("PKCS12")
        store.load(bytes.inputStream(), charArrayOf())
        val keys = store.aliases().asSequence().filter(store::isKeyEntry).toList()
        require(keys.size == 1) { "Cluster transport needs one provisioned private key" }
        val client = store.getCertificate(keys.single()) as X509Certificate
        require(clusterSha256(client.encoded) == config.clientCertificateSha256) { "Cluster client certificate pin differs" }
        client.checkValidity()
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        managers.init(store, charArrayOf())
        val pin = config.serverCertificateSha256
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = error(
                    "Client validation is unavailable",
                )

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) {
                    val server = requireNotNull(chain?.firstOrNull()) { "Cluster server certificate absent" }
                    require(clusterSha256(server.encoded) == pin) { "Cluster server certificate pin differs" }
                    server.checkValidity()
                    val roots = KeyStore.getInstance(KeyStore.getDefaultType())
                    roots.load(null, null)
                    roots.setCertificateEntry("paired-server", server)
                    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    factory.init(roots)
                    val verifier = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
                    verifier.checkServerTrusted(requireNotNull(chain), requireNotNull(authType))
                }
            }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, arrayOf<TrustManager>(trust), SecureRandom()) }
    }
}
