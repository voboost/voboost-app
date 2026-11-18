package ru.voboost.cluster

internal class BytesSource(override val metadata: ClusterFile, private val bytes: ByteArray) : ClusterFileSource {
    var closes = 0

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int {
        val count = minOf(length, bytes.size - offset.toInt())
        if (count <= 0) return -1
        bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
        return count
    }

    override fun verifyIdentity() = Unit

    override fun close() {
        closes++
    }
}

internal fun testSource(
    role: String,
    file: String,
    bytes: ByteArray,
): BytesSource =
    BytesSource(
        ClusterFile(role, file, bytes.size.toLong(), clusterSha256(bytes)),
        bytes,
    )

internal val testId = "1".repeat(64)
internal val testTransaction = "2".repeat(64)

internal fun testLease(
    credential: ByteArray = byteArrayOf(99),
    config: ClusterTransportConfig? = null,
): ClusterLease =
    ClusterLease(
        testId,
        testTransaction,
        config ?: ClusterTransportConfig(
            "127.0.0.1", 1234, "3".repeat(64), "4".repeat(64),
            credential.size.toLong(), clusterSha256(credential),
        ),
        listOf(
            testSource("request", "request.vbc", byteArrayOf(1, 2, 3)),
            testSource("agent-module", "libagent-fixture.so", byteArrayOf(4, 5)),
        ),
        testSource("credentials", "transport.p12", credential),
    )
