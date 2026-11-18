package ru.voboost.cluster

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delivery result failures must preserve retry and release every acquired lease. */
class ClusterCourierOutcomeRaceTest {
    @Test
    fun aBrokenResultChannelFailsLoudInsteadOfInventingDeliveredOutcome() =
        runTest {
            val lease = testLease()
            val receipt = byteArrayOf(7, 7)
            var takes = 0
            var deliveries = 0
            var results = 0
            val gateway =
                object : ClusterGateway {
                    override suspend fun take(): ClusterTakeResult =
                        if (takes++ == 0) ClusterTakeResult.Ready(lease) else ClusterTakeResult.Empty

                    override suspend fun result(
                        lease: ClusterLease,
                        outcome: ClusterTransportOutcome,
                        receipt: ByteArray?,
                    ): Boolean {
                        assertEquals(ClusterTransportOutcome.DELIVERED, outcome)
                        assertArrayEquals(byteArrayOf(7, 7), receipt)
                        results++
                        throw java.io.IOException("result channel broke after delivery")
                    }
                }
            val failure =
                runCatching {
                    ClusterCourier(
                        gateway,
                        ClusterTransport {
                            deliveries++
                            receipt
                        },
                    ).run()
                }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals(1, deliveries)
            assertEquals(1, takes)
            assertEquals(1, results)
            (lease.files + lease.credentials).forEach { assertEquals(1, (it as BytesSource).closes) }
        }

    @Test
    fun aRejectedResultRetriesWithFreshResourcesOnTheNextRun() =
        runTest {
            val firstLease = testLease()
            val secondLease = testLease()
            var activeLease = firstLease
            var takes = 0
            var accepts = false
            var results = 0
            val delivered = mutableListOf<ClusterLease>()
            val receipt = byteArrayOf(7, 7)
            val gateway =
                object : ClusterGateway {
                    override suspend fun take(): ClusterTakeResult =
                        if (takes++ == 0) ClusterTakeResult.Ready(activeLease) else ClusterTakeResult.Empty

                    override suspend fun result(
                        lease: ClusterLease,
                        outcome: ClusterTransportOutcome,
                        receipt: ByteArray?,
                    ): Boolean {
                        assertSame(activeLease, lease)
                        assertEquals(ClusterTransportOutcome.DELIVERED, outcome)
                        assertArrayEquals(byteArrayOf(7, 7), receipt)
                        results++
                        return accepts
                    }
                }
            val transport =
                ClusterTransport { lease ->
                    assertSame(activeLease, lease)
                    (lease.files + lease.credentials).forEach { assertEquals(0, (it as BytesSource).closes) }
                    assertArrayEquals(byteArrayOf(1, 2, 3), readClusterFile(lease.files[0], CLUSTER_MAX_DOCUMENT_BYTES))
                    assertArrayEquals(byteArrayOf(4, 5), readClusterFile(lease.files[1], CLUSTER_MAX_DOCUMENT_BYTES))
                    assertArrayEquals(byteArrayOf(99), readClusterFile(lease.credentials, CLUSTER_MAX_DOCUMENT_BYTES))
                    delivered += lease
                    receipt
                }
            val courier = ClusterCourier(gateway, transport)
            assertEquals(ClusterDeliveryResult.RETRY, courier.run())
            assertEquals(1, takes)
            assertEquals(1, results)
            (firstLease.files + firstLease.credentials).forEach { assertEquals(1, (it as BytesSource).closes) }

            activeLease = secondLease
            takes = 0
            accepts = true
            assertEquals(ClusterDeliveryResult.COMPLETE, courier.run())
            assertEquals(2, takes)
            assertEquals(2, results)
            assertEquals(listOf(firstLease, secondLease), delivered)
            (firstLease.files + firstLease.credentials + secondLease.files + secondLease.credentials)
                .forEach { assertEquals(1, (it as BytesSource).closes) }
        }

    @Test
    fun cancellationAfterDeliveryRetainsTheExactReceiptWhenSettlingTheLease() =
        runTest {
            val retained = testLease()
            val receipt = byteArrayOf(7, 8)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            var results = 0
            val gateway =
                object : ClusterGateway {
                    override suspend fun take() = ClusterTakeResult.Ready(retained)

                    override suspend fun result(
                        lease: ClusterLease,
                        outcome: ClusterTransportOutcome,
                        bytes: ByteArray?,
                    ): Boolean {
                        assertSame(retained, lease)
                        assertEquals(ClusterTransportOutcome.DELIVERED, outcome)
                        assertArrayEquals(receipt, bytes)
                        if (++results == 1) {
                            started.complete(Unit)
                            kotlinx.coroutines.awaitCancellation()
                        }
                        return true
                    }
                }
            val job = launch { ClusterCourier(gateway, ClusterTransport { receipt }).run() }
            started.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(2, results)
            (retained.files + retained.credentials).forEach { assertEquals(1, (it as BytesSource).closes) }
        }

    @Test
    fun failedCancellationSettlementPreservesCancellationAndClosesResources() =
        runTest {
            val retained = testLease()
            val cancelled = kotlinx.coroutines.CancellationException("cancelled")
            val unavailable = java.io.IOException("unavailable")
            val gateway =
                object : ClusterGateway {
                    override suspend fun take() = ClusterTakeResult.Ready(retained)

                    override suspend fun result(
                        lease: ClusterLease,
                        outcome: ClusterTransportOutcome,
                        receipt: ByteArray?,
                    ): Boolean {
                        assertSame(retained, lease)
                        assertEquals(ClusterTransportOutcome.UNAVAILABLE, outcome)
                        assertEquals(null, receipt)
                        throw unavailable
                    }
                }
            val failure = runCatching { ClusterCourier(gateway, ClusterTransport { throw cancelled }).run() }.exceptionOrNull()
            assertSame(cancelled, failure)
            assertEquals(1, cancelled.suppressed.size)
            assertEquals(unavailable.javaClass, cancelled.suppressed.single().javaClass)
            assertEquals(unavailable.message, cancelled.suppressed.single().message)
            (retained.files + retained.credentials).forEach { assertEquals(1, (it as BytesSource).closes) }
        }

    @Test
    fun statusesWithoutALeaseNeverTouchTransportOrIssueResults() =
        runTest {
            val statuses =
                listOf(
                    ClusterTakeResult.Empty to ClusterDeliveryResult.COMPLETE,
                    ClusterTakeResult.SetupRequired to ClusterDeliveryResult.COMPLETE,
                    ClusterTakeResult.Busy to ClusterDeliveryResult.RETRY,
                )
            for ((status, result) in statuses) {
                var takes = 0
                val gateway =
                    object : ClusterGateway {
                        override suspend fun take(): ClusterTakeResult {
                            takes++
                            return status
                        }

                        override suspend fun result(
                            lease: ClusterLease,
                            outcome: ClusterTransportOutcome,
                            receipt: ByteArray?,
                        ): Boolean = throw AssertionError("A lease-free status must not issue a result")
                    }
                val transport = ClusterTransport { throw AssertionError("A lease-free status must not deliver") }
                assertEquals(result, ClusterCourier(gateway, transport).run())
                assertEquals(1, takes)
            }
        }
}
