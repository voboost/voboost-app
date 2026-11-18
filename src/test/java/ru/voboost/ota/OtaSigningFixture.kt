package ru.voboost.ota

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.security.SecureRandom

internal class OtaSigningFixture {
    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32).also(SecureRandom()::nextBytes), 0)
    val publicKeyPem: String =
        StringWriter().also { writer ->
            val keyInfo =
                SubjectPublicKeyInfo(
                    AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.101.112")),
                    privateKey.generatePublicKey().encoded,
                )
            PemWriter(writer).use { it.writeObject(PemObject("PUBLIC KEY", keyInfo.encoded)) }
        }.toString()

    fun sign(bytes: ByteArray): ByteArray =
        Ed25519Signer().run {
            init(true, privateKey)
            update(bytes, 0, bytes.size)
            generateSignature()
        }
}
