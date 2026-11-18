package ru.voboost.ota

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.io.pem.PemReader
import java.io.StringReader

internal class ReleaseSignatureVerifier private constructor(
    private val publicKey: Ed25519PublicKeyParameters,
) {
    fun verify(
        bytes: ByteArray,
        signature: ByteArray,
    ) {
        if (signature.size != SIGNATURE_BYTES) throw ProductContractException("Ed25519 signature must be 64 bytes")
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(bytes, 0, bytes.size)
        if (!verifier.verifySignature(signature)) throw ProductContractException("Ed25519 signature verification failed")
    }

    companion object {
        private const val SIGNATURE_BYTES = 64
        private const val ED25519_OID = "1.3.101.112"

        fun fromPem(pem: String): ReleaseSignatureVerifier {
            val objectBytes =
                PemReader(StringReader(pem)).use { it.readPemObject()?.content }
                    ?: throw ProductContractException("Release public key PEM is empty")
            val key =
                try {
                    // The generic factory retains converters for unrelated key algorithms.
                    val keyInfo = SubjectPublicKeyInfo.getInstance(objectBytes)
                    if (keyInfo.algorithm.algorithm.id == ED25519_OID) {
                        Ed25519PublicKeyParameters(keyInfo.publicKeyData.octets)
                    } else {
                        null
                    }
                } catch (error: Exception) {
                    throw ProductContractException("Release public key is invalid", error)
                } ?: throw ProductContractException("Release public key is not Ed25519")
            return ReleaseSignatureVerifier(key)
        }
    }
}
