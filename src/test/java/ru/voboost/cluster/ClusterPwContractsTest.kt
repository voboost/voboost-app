package ru.voboost.cluster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterPwContractsTest {
    private val requestId = "a".repeat(64)

    @Test fun unavailableIsExplicitAndStrict() {
        val text = "{\"response\":\"clusterPwLiveProof\",\"protocolVersion\":1,\"requestId\":\"$requestId\"}\n"
        assertNull(ClusterPwContracts.response(text.toByteArray(), requestId))
        listOf(
            text.replace("\"protocolVersion\":1", "\"protocolVersion\":1.0"),
            text.replace("\"protocolVersion\":1", "\"protocolVersion\":1,\"protocolVersion\":1"),
            text.replace("{\"response\"", "{ \"response\""),
            text.replace("}\n", ",\"extra\":true}\n"),
        ).forEach { assertTrue(runCatching { ClusterPwContracts.response(it.toByteArray(), requestId) }.isFailure) }
    }

    @Test fun proofIsOpaqueBoundedAndNeverInterpretedAsActualState() {
        val hex = "12".repeat(69)
        val text = "{\"response\":\"clusterPwLiveProof\",\"protocolVersion\":1,\"requestId\":\"$requestId\",\"proof\":\"$hex\"}\n"
        assertArrayEquals(ByteArray(69) { 0x12 }, ClusterPwContracts.response(text.toByteArray(), requestId))
        assertTrue(runCatching { ClusterPwContracts.response(text.replace(hex, "zz".repeat(69)).toByteArray(), requestId) }.isFailure)
        assertTrue(runCatching { ClusterPwContracts.response(text.toByteArray(), "b".repeat(64)) }.isFailure)
    }

    @Test fun nativeChallengeCannotBreakLocalEnvelope() {
        val bytes = ClusterPwContracts.request(requestId, "{\"schemaVersion\":1}\n".toByteArray())
        assertTrue(bytes.toString(Charsets.US_ASCII).contains("\"challenge\":{\"schemaVersion\":1}"))
        listOf("{}\n,\"actual\":\"disabled\"\n", "{}\n{}\n", "{\"x\":1,\"x\":2}\n").forEach {
            assertTrue(runCatching { ClusterPwContracts.request(requestId, it.toByteArray()) }.isFailure)
        }
    }
}
