package ru.voboost.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterContractsTest {
    @Test fun rejectsNoncanonicalAndMismatchedLeaseFrames() {
        val ready = ready()
        val parsed = ClusterContracts.takeResponse(ready.toByteArray(), testId).second!!
        assertEquals("request.vbc", parsed.files.single().file)
        assertEquals(3L, parsed.files.single().size)
        listOf(
            ready.replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1"),
            ready.replace("\"leaseMillis\":30000", "\"leaseMillis\":30000,\"unknown\":1"),
            ready.replace("\"leaseMillis\":30000", "\"leaseMillis\":30000.0"),
            ready.replace("\"leaseMillis\":30000", "\"leaseMillis\":29999"),
            ready.replace("\"role\":\"request\"", "\"role\":\"credentials\""),
            ready.replace("request.vbc", "../request.vbc"),
            ready.replace("\"size\":3", "\"size\":65537"),
            ready.replace("\"port\":1234", "\"port\":0"),
            ready.replace("\"host\":\"127.0.0.1\"", "\"host\":\"user@host\""),
            ready.replace("{\"response\"", "{ \"response\""),
            ready + " ",
        ).forEach { bytes -> assertTrue(runCatching { ClusterContracts.takeResponse(bytes.toByteArray(), testId) }.isFailure) }
        assertTrue(runCatching { ClusterContracts.takeResponse(ready.toByteArray(), testTransaction) }.isFailure)
        val recorded =
            "{\"response\":\"clusterDeliveryResult\",\"protocolVersion\":1," +
                "\"requestId\":\"$testId\",\"result\":{\"status\":\"recorded\"}}\n"
        assertTrue(ClusterContracts.resultResponse(recorded.toByteArray(), testId))
        assertFalse(
            ClusterContracts.resultResponse(
                recorded.replace("\"status\":\"recorded\"", "\"status\":\"rejected\",\"reason\":\"stale lease\"").toByteArray(),
                testId,
            ),
        )
    }

    @Test fun rejectsUnknownAndMalformedResultFrames() {
        val recorded =
            "{\"response\":\"clusterDeliveryResult\",\"protocolVersion\":1," +
                "\"requestId\":\"$testId\",\"result\":{\"status\":\"recorded\"}}\n"
        listOf(
            recorded.replace("\"status\":\"recorded\"", "\"status\":\"recorded\",\"status\":\"recorded\""),
            recorded.replace("\"status\":\"recorded\"", "\"status\":\"pending\""),
            recorded.replace("\"status\":\"recorded\"", "\"status\":null"),
            recorded.replace("\"status\":\"recorded\"}", "\"status\":\"recorded\",\"unknown\":1}"),
            recorded.replace("\"status\":\"recorded\"", "\"status\":\"\""),
            recorded.replace("result\":{\"status\":\"recorded\"}", "result\":{\"status\":\"rejected\"}"),
            recorded.replace("result\":{\"status\":\"recorded\"}", "result\":{\"status\":\"rejected\",\"reason\":\"bot \u0007 x\"}"),
            recorded.replace("result\":{\"status\":\"recorded\"}", "result\":{\"status\":\"rejected\",\"reason\":\"\"}"),
            recorded.replace(
                "result\":{\"status\":\"recorded\"}",
                "result\":{\"status\":\"rejected\",\"reason\":\"one\",\"reason\":\"two\"}",
            ),
            recorded.replace("\"requestId\":\"$testId\"", "\"requestId\":\"$testTransaction\""),
            recorded.replace("{\"response\"", "{ \"response\""),
            recorded.trimEnd(),
        ).forEach { bytes -> assertTrue(runCatching { ClusterContracts.resultResponse(bytes.toByteArray(), testId) }.isFailure) }
        val nonAscii = recorded.toByteArray().also { it[it.lastIndex - 1] = 0xC3.toByte() }
        assertTrue(runCatching { ClusterContracts.resultResponse(nonAscii, testId) }.isFailure)
        assertTrue(
            runCatching {
                ClusterContracts.resultResponse(
                    recorded.replace("}}\n", "},\"padding\":\"" + "a".repeat(600_000) + "\"}" + "\n").toByteArray(),
                    testId,
                )
            }.isFailure,
        )
    }

    @Test fun localAndNetworkFramesUseExactFrozenBytes() {
        assertEquals(
            "{\"request\":\"clusterDeliveryTake\",\"protocolVersion\":1,\"requestId\":\"$testId\"}\n",
            ClusterContracts.takeRequest(testId).toString(Charsets.US_ASCII),
        )
        testLease().use { lease ->
            val header = ClusterContracts.networkHeader(lease.files.map { it.metadata }).toString(Charsets.US_ASCII)
            assertTrue(header.startsWith("{\"protocolVersion\":1,\"files\":[{\"role\":\"request\",\"file\":\"request.vbc\","))
            assertFalse(header.contains("transport.p12"))
            assertTrue(runCatching { ClusterContracts.resultRequest(testId, lease, ClusterTransportOutcome.DELIVERED, null) }.isFailure)
        }
    }

    @Test
    fun sharedNativeCorpusHasExactLocalAndNetworkBytes() {
        val root = java.io.File("src/test/resources/cluster-wire")
        val checksums = root.resolve("SHA256SUMS").readLines()
        val names = checksums.map { it.substringAfter("  ") }.toSet()
        require(root.listFiles()!!.map { it.name }.toSet() == names + "SHA256SUMS")
        checksums.forEach { line ->
            assertEquals(line.substringBefore("  "), clusterSha256(root.resolve(line.substringAfter("  ")).readBytes()))
        }

        fun fixture(name: String) = root.resolve(name).readBytes()
        val id = "a".repeat(64)
        org.junit.Assert.assertArrayEquals(fixture("take.json"), ClusterContracts.takeRequest(id))
        val metadata = ClusterContracts.takeResponse(fixture("take-ready.json"), id).second!!
        val sources = metadata.files.map { BytesSource(it, fixture(it.file)) }
        val credentials = testSource("credentials", "transport.p12", ByteArray(32))
        ClusterLease(metadata.leaseId, metadata.transactionId, metadata.transport, sources, credentials).use { lease ->
            org.junit.Assert.assertArrayEquals(fixture("network-header.json"), ClusterContracts.networkHeader(metadata.files))
            org.junit.Assert.assertArrayEquals(
                fixture("result-delivered.json"),
                ClusterContracts.resultRequest(id, lease, ClusterTransportOutcome.DELIVERED, fixture("receipt.vbc")),
            )
            org.junit.Assert.assertArrayEquals(fixture("request.vbc"), readClusterFile(sources.single(), CLUSTER_MAX_DOCUMENT_BYTES))
        }
        assertEquals("empty", ClusterContracts.takeResponse(fixture("take-empty.json"), id).first)
        assertEquals("setup-required", ClusterContracts.takeResponse(fixture("take-setup-required.json"), id).first)
        assertTrue(ClusterContracts.resultResponse(fixture("result-recorded.json"), id))
    }

    @Test fun resourcePairsAreTransportedWithTheCompleteClusterGeneration() {
        val files = listOf(
            "{\"role\":\"qnx-resource-pack\",\"file\":\"qnx-resource-i18n-ru-a9-light.kzb\",\"size\":3,\"sha256\":\"${"7".repeat(64)}\"}",
            "{\"role\":\"qnx-resource-ledger\",\"file\":\"qnx-resource-i18n-ru-a9-light.json\",\"size\":3,\"sha256\":\"${"8".repeat(64)}\"}",
        )
        val frame = ready().replace("]}}\n", "," + files.joinToString(",") + "]}}\n")
        val metadata = ClusterContracts.takeResponse(frame.toByteArray(), testId).second!!
        assertEquals(listOf("request", "qnx-resource-pack", "qnx-resource-ledger"), metadata.files.map { it.role })
        val network = ClusterContracts.networkHeader(metadata.files).toString(Charsets.US_ASCII)
        assertTrue(network.contains("qnx-resource-pack"))
        assertTrue(network.contains("qnx-resource-ledger"))
        assertTrue(runCatching { ClusterContracts.takeResponse(frame.replace("qnx-resource-pack", "unknown-role").toByteArray(), testId) }.isFailure)
    }

    private fun ready(): String =
        "{\"response\":\"clusterDeliveryTake\",\"protocolVersion\":1,\"requestId\":\"$testId\",\"result\":{" +
            "\"status\":\"ready\",\"leaseId\":\"$testId\",\"transactionId\":\"$testTransaction\",\"leaseMillis\":30000," +
            "\"transport\":{\"host\":\"127.0.0.1\",\"port\":1234," +
            "\"serverCertificateSha256\":\"${"3".repeat(64)}\",\"clientCertificateSha256\":\"${"4".repeat(64)}\"," +
            "\"credentials\":{\"size\":1,\"sha256\":\"${"5".repeat(64)}\"}}," +
            "\"files\":[{\"role\":\"request\",\"file\":\"request.vbc\",\"size\":3,\"sha256\":\"${"6".repeat(64)}\"}]}}\n"
}
