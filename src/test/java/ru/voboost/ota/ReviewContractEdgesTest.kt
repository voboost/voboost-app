package ru.voboost.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewContractEdgesTest {
    @Test
    fun numericPrereleaseIdentifiersCompareByMagnitudeBeyondUnsignedLong() {
        val left = ProductSemVer.parse("1.0.0-beta.99999999999999999999999")
        val right = ProductSemVer.parse("1.0.0-beta.100000000000000000000000")
        assertTrue(left < right)
    }

    @Test
    fun derivedArtifactAndSignatureUrlsPreserveEscapedManifestDirectory() {
        val manifest = parseHttpsUrl("https://example.com/releases%20v1/voboost.json")
        assertEquals(
            "https://example.com/releases%20v1/voboost.json.sig",
            OtaHttpTransport.signatureUrl(manifest).toASCIIString(),
        )
        assertEquals(
            "https://example.com/releases%20v1/voboost-app.apk",
            OtaHttpTransport.artifactUrl(manifest, "voboost-app.apk").toASCIIString(),
        )
    }

    @Test
    fun numericIdentifiersStayBelowNonnumericAndPreserveBuildInsensitiveOrder() {
        assertTrue(ProductSemVer.parse("1.0.0-beta.18446744073709551616") < ProductSemVer.parse("1.0.0-beta.a"))
        assertTrue(ProductSemVer.parse("1.0.0-beta.18446744073709551615") < ProductSemVer.parse("1.0.0-beta.18446744073709551616"))
        assertEquals(
            0,
            ProductSemVer.parse("1.0.0-beta.18446744073709551616+x").compareTo(ProductSemVer.parse("1.0.0-beta.18446744073709551616+y")),
        )
    }

    @Test
    fun derivedUrlsPreserveEncodedSeparatorsUnicodeAndSignedQuery() {
        val manifest = parseHttpsUrl("https://example.com/a%2Fb/%E7%95%8C/voboost.json?token=a%2Fb%20c")
        assertEquals(
            "https://example.com/a%2Fb/%E7%95%8C/voboost.json.sig?token=a%2Fb%20c",
            OtaHttpTransport.signatureUrl(manifest).toASCIIString(),
        )
        assertEquals(
            "https://example.com/a%2Fb/%E7%95%8C/agent-demo.dex",
            OtaHttpTransport.artifactUrl(manifest, "agent-demo.dex").toASCIIString(),
        )
        org.junit.Assert.assertThrows(Exception::class.java) { OtaHttpTransport.artifactUrl(manifest, "../outside") }
    }
}
