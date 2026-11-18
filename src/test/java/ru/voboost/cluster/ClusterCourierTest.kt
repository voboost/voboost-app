package ru.voboost.cluster

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterCourierTest {
    @Test fun tlsFailureCategoryReportsTypesWithoutExceptionContents() {
        val handshake = javax.net.ssl.SSLHandshakeException("private-payload")
        handshake.initCause(java.security.cert.CertificateExpiredException("private-certificate"))
        assertEquals("certificate-expired", tlsFailureCategory(handshake))
        assertEquals("cause-unclassified", tlsFailureCategory(javax.net.ssl.SSLException("private-payload")))
    }

    @Test fun expectedNetworkWaitIsDebugButMalformedAndTlsFailuresAreErrors() =
        runTest {
            val parent = java.nio.file.Files.createDirectories(java.nio.file.Paths.get("build", "test-work").toAbsolutePath())
            val directory = java.nio.file.Files.createTempDirectory(parent, "cluster-log-severity-").toFile()
            ru.voboost.Logger.shutdown()
            ru.voboost.Logger.init(directory, "debug")
            try {
                for (failure in listOf(
                    java.net.SocketTimeoutException("private-payload"),
                    java.io.EOFException("private-payload"),
                    javax.net.ssl.SSLException("private-payload"),
                )) {
                    val lease = testLease()
                    val gateway =
                        object : ClusterGateway {
                            override suspend fun take() = ClusterTakeResult.Ready(lease)

                            override suspend fun result(
                                lease: ClusterLease,
                                outcome: ClusterTransportOutcome,
                                receipt: ByteArray?,
                            ) = true
                        }
                    assertEquals(
                        ClusterDeliveryResult.RETRY,
                        ClusterCourier(gateway, ClusterTransport { throw failure }).run(),
                    )
                }
            } finally {
                ru.voboost.Logger.shutdown()
            }
            val rows =
                directory.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }
                    .flatMap { it.readLines() }.map { org.json.JSONObject(it) }
                    .filter { it.getString("component") == "ClusterCourier" }
            assertEquals(1, rows.count { it.getString("level") == "debug" && it.getString("message") == "waiting: peer-unavailable" })
            assertEquals(2, rows.count { it.getString("level") == "error" })
            org.junit.Assert.assertFalse(rows.any { it.toString().contains("private-payload") })
            directory.deleteRecursively()
        }

    @Test fun relaysOnlyDeclaredFilesAndReturnsExactReceipt() =
        runTest {
            val lease = testLease()
            val receipt = byteArrayOf(8, 9)
            var takes = 0
            var results = 0
            val gateway =
                object : ClusterGateway {
                    override suspend fun take(): ClusterTakeResult =
                        if (takes++ == 0) {
                            ClusterTakeResult.Ready(
                                lease,
                            )
                        } else {
                            ClusterTakeResult.Empty
                        }

                    override suspend fun result(
                        lease: ClusterLease,
                        outcome: ClusterTransportOutcome,
                        receipt: ByteArray?,
                    ): Boolean {
                        assertEquals(testId, lease.leaseId)
                        assertEquals(testTransaction, lease.transactionId)
                        assertEquals(ClusterTransportOutcome.DELIVERED, outcome)
                        assertArrayEquals(byteArrayOf(8, 9), receipt)
                        results++
                        return true
                    }
                }
            val transmitted = mutableListOf<ByteArray>()
            val transport =
                ClusterTransport { approved ->
                    approved.files.forEach { transmitted += readClusterFile(it, CLUSTER_MAX_DOCUMENT_BYTES) }
                    receipt
                }
            assertEquals(ClusterDeliveryResult.COMPLETE, ClusterCourier(gateway, transport).run())
            assertEquals(1, results)
            assertEquals(2, transmitted.size)
            assertArrayEquals(byteArrayOf(1, 2, 3), transmitted[0])
            assertArrayEquals(byteArrayOf(4, 5), transmitted[1])
            (lease.files + lease.credentials).forEach { assertEquals(1, (it as BytesSource).closes) }
        }

    @Test fun failedTransportPreservesRetryAndClosesTheLease() =
        runTest {
            val lease = testLease()
            var outcome: ClusterTransportOutcome? = null
            val gateway =
                object : ClusterGateway {
                    override suspend fun take() = ClusterTakeResult.Ready(lease)

                    override suspend fun result(
                        lease: ClusterLease,
                        value: ClusterTransportOutcome,
                        receipt: ByteArray?,
                    ): Boolean {
                        outcome = value
                        return true
                    }
                }
            assertEquals(
                ClusterDeliveryResult.RETRY,
                ClusterCourier(gateway, ClusterTransport { throw java.io.IOException("offline") }).run(),
            )
            assertEquals(ClusterTransportOutcome.UNAVAILABLE, outcome)
            (lease.files + lease.credentials).forEach { assertEquals(1, (it as BytesSource).closes) }
        }

    @Test
    fun cancellationReleasesItsLeaseWithoutClaimingDelivery() =
        kotlinx.coroutines.runBlocking {
            val retained = testLease()
            var results = 0
            val gateway =
                object : ClusterGateway {
                    override suspend fun take() = ClusterTakeResult.Ready(retained)

                    override suspend fun result(
                        lease: ClusterLease,
                        outcome: ClusterTransportOutcome,
                        receipt: ByteArray?,
                    ): Boolean {
                        org.junit.Assert.assertSame(retained, lease)
                        assertEquals(ClusterTransportOutcome.UNAVAILABLE, outcome)
                        org.junit.Assert.assertNull(receipt)
                        results++
                        return true
                    }
                }
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job =
                launch {
                    ClusterCourier(
                        gateway,
                        ClusterTransport {
                            started.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                        },
                    ).run()
                }
            started.await()
            job.cancel()
            job.join()
            assertEquals(1, results)
            (retained.files + retained.credentials).forEach { assertEquals(1, (it as BytesSource).closes) }
        }

    @Test
    fun aRunIsBoundedToFourLeasesAndRejectedObservationsRetry() =
        runTest {
            var takes = 0
            var accepts = true
            val gateway =
                object : ClusterGateway {
                    override suspend fun take(): ClusterTakeResult {
                        takes++
                        return ClusterTakeResult.Ready(testLease())
                    }

                    override suspend fun result(
                        lease: ClusterLease,
                        outcome: ClusterTransportOutcome,
                        receipt: ByteArray?,
                    ) = accepts
                }
            val courier = ClusterCourier(gateway, ClusterTransport { byteArrayOf(1) })
            assertEquals(ClusterDeliveryResult.RETRY, courier.run())
            assertEquals(4, takes)
            takes = 0
            accepts = false
            assertEquals(ClusterDeliveryResult.RETRY, courier.run())
            assertEquals(1, takes)
        }
}
