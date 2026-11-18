package ru.voboost.cluster

import org.json.JSONObject
import ru.voboost.ota.DuplicateKeyJsonValidator
import ru.voboost.ota.jsonString
import java.security.SecureRandom

internal data class ClusterLeaseMetadata(
    val leaseId: String,
    val transactionId: String,
    val transport: ClusterTransportConfig,
    val files: List<ClusterFile>,
)

/** Exact copies of the product-owned cluster wire contract, without desired semantics. */
internal object ClusterContracts {
    private val id = Regex("^[0-9a-f]{64}$")
    private val filename = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$")
    private val roles =
        setOf(
            "request",
            "release-manifest",
            "release-manifest-signature",
            "agent-catalog",
            "agent-catalog-signature",
            "daemon",
            "install",
            "restore",
            "uninstall",
            "init",
            "implant",
            "agent-common",
            "agent-module",
            "qnx-daemon",
            "qnx-init",
            "qnx-implant",
            "qnx-runtime",
            "qnx-agent-module",
            "qnx-resource-pack",
            "qnx-resource-ledger",
        )
    private val random = SecureRandom()

    fun requestId(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun takeRequest(requestId: String): ByteArray {
        require(id.matches(requestId))
        return bytes("{\"request\":\"clusterDeliveryTake\",\"protocolVersion\":1,\"requestId\":${jsonString(requestId)}}\n")
    }

    fun resultRequest(
        requestId: String,
        lease: ClusterLease,
        outcome: ClusterTransportOutcome,
        receipt: ByteArray?,
    ): ByteArray {
        require(id.matches(requestId) && id.matches(lease.leaseId) && id.matches(lease.transactionId))
        require((outcome == ClusterTransportOutcome.DELIVERED) == (receipt != null))
        receipt?.let { require(it.size in 1..CLUSTER_MAX_DOCUMENT_BYTES) }
        return bytes(
            "{\"request\":\"clusterDeliveryResult\",\"protocolVersion\":1,\"requestId\":${jsonString(requestId)}," +
                "\"leaseId\":${jsonString(lease.leaseId)},\"transactionId\":${jsonString(lease.transactionId)}," +
                "\"transportOutcome\":${jsonString(outcome.wire)}" +
                (receipt?.let { ",\"receipt\":{\"size\":${it.size},\"sha256\":${jsonString(clusterSha256(it))}}" } ?: "") + "}\n",
        )
    }

    fun takeResponse(
        bytes: ByteArray,
        requestId: String,
    ): Pair<String, ClusterLeaseMetadata?> {
        val root = response(bytes, "clusterDeliveryTake", requestId)
        val result = root.getJSONObject("result")
        val status = result.getString("status")
        val metadata =
            when (status) {
                "empty", "busy", "setup-required" -> {
                    keys(result, "status")
                    null
                }
                "ready" -> {
                    keys(result, "status", "leaseId", "transactionId", "leaseMillis", "transport", "files")
                    val leaseId = result.getString("leaseId")
                    val transactionId = result.getString("transactionId")
                    require(id.matches(leaseId) && id.matches(transactionId))
                    require(integer(result, "leaseMillis") == CLUSTER_LEASE_MILLIS)
                    val config = result.getJSONObject("transport")
                    keys(config, "host", "port", "serverCertificateSha256", "clientCertificateSha256", "credentials")
                    val host = config.getString("host")
                    require(host.matches(Regex("^[A-Za-z0-9:.][A-Za-z0-9.:-]{0,252}$")))
                    val port = integer(config, "port")
                    require(port in 1..65535)
                    val serverPin = digest(config, "serverCertificateSha256")
                    val clientPin = digest(config, "clientCertificateSha256")
                    val credentials = config.getJSONObject("credentials")
                    keys(credentials, "size", "sha256")
                    val credentialSize = integer(credentials, "size")
                    require(credentialSize in 1..CLUSTER_MAX_DOCUMENT_BYTES.toLong())
                    val transport =
                        ClusterTransportConfig(host, port.toInt(), serverPin, clientPin, credentialSize, digest(credentials, "sha256"))
                    val rows = result.getJSONArray("files")
                    require(rows.length() in 1..CLUSTER_MAX_FILES)
                    val files =
                        (0 until rows.length()).map { index ->
                            val row = rows.getJSONObject(index)
                            keys(row, "role", "file", "size", "sha256")
                            ClusterFile(row.getString("role"), row.getString("file"), integer(row, "size"), digest(row, "sha256"))
                        }
                    validateFiles(files)
                    ClusterLeaseMetadata(leaseId, transactionId, transport, files)
                }
                else -> error("Unknown cluster mailbox status")
            }
        val canonical =
            "{\"response\":\"clusterDeliveryTake\",\"protocolVersion\":1,\"requestId\":${jsonString(requestId)},\"result\":{" +
                "\"status\":${jsonString(status)}" + (metadata?.let(::readyFields) ?: "") + "}}\n"
        require(canonical.toByteArray(Charsets.US_ASCII).contentEquals(bytes))
        return status to metadata
    }

    fun resultResponse(
        bytes: ByteArray,
        requestId: String,
    ): Boolean {
        val root = response(bytes, "clusterDeliveryResult", requestId)
        val result = root.getJSONObject("result")
        val status = result.getString("status")
        val suffix =
            when (status) {
                "recorded" -> {
                    keys(result, "status")
                    ""
                }
                "rejected" -> {
                    keys(result, "status", "reason")
                    val reason = result.getString("reason")
                    require(reason.length in 1..512 && reason.all { it.code in 32..126 })
                    ",\"reason\":${jsonString(reason)}"
                }
                else -> error("Unknown cluster result status")
            }
        val canonical =
            "{\"response\":\"clusterDeliveryResult\",\"protocolVersion\":1," +
                "\"requestId\":${jsonString(requestId)},\"result\":{\"status\":${jsonString(status)}$suffix}}\n"
        require(canonical.toByteArray().contentEquals(bytes))
        return status == "recorded"
    }

    fun networkHeader(files: List<ClusterFile>): ByteArray {
        validateFiles(files)
        return bytes("{\"protocolVersion\":1,\"files\":[${files.joinToString(",", transform = ::fileJson)}]}\n")
    }

    private fun readyFields(lease: ClusterLeaseMetadata): String {
        val c = lease.transport
        return ",\"leaseId\":${jsonString(lease.leaseId)},\"transactionId\":${jsonString(lease.transactionId)},\"leaseMillis\":30000," +
            "\"transport\":{\"host\":${jsonString(c.host)},\"port\":${c.port}," +
            "\"serverCertificateSha256\":${jsonString(c.serverCertificateSha256)}," +
            "\"clientCertificateSha256\":${jsonString(c.clientCertificateSha256)}," +
            "\"credentials\":{\"size\":${c.credentialSize},\"sha256\":${jsonString(c.credentialSha256)}}}," +
            "\"files\":[${lease.files.joinToString(",", transform = ::fileJson)}]"
    }

    private fun fileJson(file: ClusterFile): String =
        "{\"role\":${jsonString(file.role)},\"file\":${jsonString(file.file)}," +
            "\"size\":${file.size},\"sha256\":${jsonString(file.sha256)}}"

    private fun validateFiles(files: List<ClusterFile>) {
        require(files.size in 1..CLUSTER_MAX_FILES)
        require(files.map { it.file }.toSet().size == files.size)
        require(files.first().role == "request" && files.first().file == "request.vbc" && files.first().size <= CLUSTER_MAX_DOCUMENT_BYTES)
        files.forEachIndexed { index, f ->
            require(f.role in roles && (index == 0 || f.role != "request"))
            require(filename.matches(f.file) && f.file != "." && f.file != "..")
            require(f.size in 1..CLUSTER_MAX_FILE_BYTES && id.matches(f.sha256))
        }
        require(files.sumOf { it.size } <= CLUSTER_MAX_TOTAL_BYTES)
    }

    private fun response(
        bytes: ByteArray,
        tag: String,
        requestId: String,
    ): JSONObject {
        require(bytes.size in 1..524_288 && bytes.last() == 10.toByte() && bytes.all { it.toInt() in 0..127 })
        val text = bytes.toString(Charsets.US_ASCII)
        DuplicateKeyJsonValidator.validate(text)
        val root = JSONObject(text)
        keys(root, "response", "protocolVersion", "requestId", "result")
        require(root.getString("response") == tag && integer(root, "protocolVersion") == 1L && root.getString("requestId") == requestId)
        return root
    }

    private fun keys(
        json: JSONObject,
        vararg names: String,
    ) {
        require(json.keys().asSequence().toSet() == names.toSet())
    }

    private fun integer(
        json: JSONObject,
        name: String,
    ): Long {
        val value = json.get(name)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }

    private fun digest(
        json: JSONObject,
        name: String,
    ): String = json.getString(name).also { require(id.matches(it)) }

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
