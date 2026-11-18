package ru.voboost.cluster

import org.json.JSONObject
import ru.voboost.ota.DuplicateKeyJsonValidator
import ru.voboost.ota.jsonString

/** PW courier transports opaque native challenge and root-signed proof only. */
internal object ClusterPwContracts {
    private val id = Regex("^[0-9a-f]{64}$")

    fun request(
        requestId: String,
        challenge: ByteArray,
    ): ByteArray {
        require(id.matches(requestId))
        require(challenge.size in 2..3072 && challenge.last() == 10.toByte() && challenge.all { it.toInt() in 0..127 })
        val text = challenge.toString(Charsets.US_ASCII)
        DuplicateKeyJsonValidator.validate(text)
        // Shape/capability/config is independently validated in root. The app
        // cannot turn any field here into actual PW state or a signing key.
        require(text.trim().startsWith("{") && text.trim().endsWith("}"))
        return (
            "{\"request\":\"clusterPwLiveProof\",\"protocolVersion\":1,\"requestId\":" +
                jsonString(requestId) + ",\"challenge\":" + text.dropLast(1) + "}\n"
        ).toByteArray(Charsets.US_ASCII)
    }

    fun response(
        bytes: ByteArray,
        requestId: String,
    ): ByteArray? {
        require(id.matches(requestId))
        require(bytes.size in 1..12288 && bytes.last() == 10.toByte() && bytes.all { it.toInt() in 0..127 })
        val text = bytes.toString(Charsets.US_ASCII)
        DuplicateKeyJsonValidator.validate(text)
        val root = JSONObject(text)
        val hasProof = root.has("proof")
        val expected = setOf("response", "protocolVersion", "requestId") + if (hasProof) setOf("proof") else emptySet()
        require(root.keys().asSequence().toSet() == expected)
        require(root.get("response") == "clusterPwLiveProof" && root.get("requestId") == requestId)
        require(root.get("protocolVersion") is Int && root.getInt("protocolVersion") == 1)
        val proof = if (hasProof) root.getString("proof") else null
        val suffix = proof?.let { ",\"proof\":" + jsonString(it) } ?: ""
        require(
            text == "{\"response\":\"clusterPwLiveProof\",\"protocolVersion\":1,\"requestId\":" + jsonString(requestId) + suffix + "}\n",
        )
        if (proof == null) return null
        require(proof.length in 138..8328 && proof.length % 2 == 0 && proof.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(proof.length / 2) { index -> proof.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
