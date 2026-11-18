package ru.voboost.ota

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.BERSequence
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringWriter
import java.security.SecureRandom

class ReleaseSignatureVerifierTest {
    @Test
    fun acceptsTheSameEd25519SpkiEncodingsAsTheGenericFactory() {
        val private = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
        val rawKey = private.generatePublicKey().encoded
        val bytes = "signed key encoding".toByteArray()
        val signature =
            Ed25519Signer().run {
                init(true, private)
                update(bytes, 0, bytes.size)
                generateSignature()
            }
        for (parameters in listOf(null, DERNull.INSTANCE)) {
            val info = SubjectPublicKeyInfo(AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.101.112"), parameters), rawKey)
            for (encoding in listOf(info.encoded, BERSequence(arrayOf(info.algorithm, info.publicKeyData)).encoded)) {
                assertArrayEquals(rawKey, (PublicKeyFactory.createKey(encoding) as Ed25519PublicKeyParameters).encoded)
                ReleaseSignatureVerifier.fromPem(pem(encoding)).verify(bytes, signature)
            }
        }
    }

    @Test
    fun rejectsOtherAlgorithmsMalformedSpkiAndWrongEd25519KeySizes() {
        for (oid in listOf("1.3.101.110", "1.3.101.113", "1.2.3.4")) {
            val info = SubjectPublicKeyInfo(AlgorithmIdentifier(ASN1ObjectIdentifier(oid)), ByteArray(32))
            assertThrows(ProductContractException::class.java) { ReleaseSignatureVerifier.fromPem(pem(info.encoded)) }
        }
        for (size in listOf(0, 31, 33)) {
            val info = SubjectPublicKeyInfo(AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.101.112")), ByteArray(size))
            assertThrows(ProductContractException::class.java) { ReleaseSignatureVerifier.fromPem(pem(info.encoded)) }
        }
        val valid = SubjectPublicKeyInfo(AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.101.112")), ByteArray(32)).encoded
        for (encoding in listOf(byteArrayOf(0), valid.copyOf(valid.size - 1), valid + byteArrayOf(0))) {
            assertThrows(ProductContractException::class.java) { ReleaseSignatureVerifier.fromPem(pem(encoding)) }
        }
    }

    private fun pem(bytes: ByteArray): String =
        StringWriter().also { writer ->
            PemWriter(writer).use { it.writeObject(PemObject("PUBLIC KEY", bytes)) }
        }.toString()

    @Test
    fun exactEd25519SignatureVerifiesAndMutationFails() {
        val private = Ed25519PrivateKeyParameters(ByteArray(32).also(SecureRandom()::nextBytes), 0)
        val keyInfo = SubjectPublicKeyInfo(AlgorithmIdentifier(ASN1ObjectIdentifier("1.3.101.112")), private.generatePublicKey().encoded)
        val writer = StringWriter()
        PemWriter(writer).use { it.writeObject(PemObject("PUBLIC KEY", keyInfo.encoded)) }
        val verifier = ReleaseSignatureVerifier.fromPem(writer.toString())
        val bytes = "signed\n".toByteArray()
        val signer =
            Ed25519Signer().apply {
                init(true, private)
                update(bytes, 0, bytes.size)
            }
        val signature = signer.generateSignature()

        verifier.verify(bytes, signature)
        assertThrows(ProductContractException::class.java) {
            verifier.verify("changed\n".toByteArray(), signature)
        }
        assertThrows(ProductContractException::class.java) { verifier.verify(bytes, signature.copyOf(63)) }
    }
}
