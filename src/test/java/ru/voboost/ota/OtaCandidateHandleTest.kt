package ru.voboost.ota

import org.junit.Assert.assertThrows
import org.junit.Test

class OtaCandidateHandleTest {
    @Test
    fun candidateIsOpaqueAndAnExactUniqueManifestSubsequence() {
        val bytes = "manifest\n".toByteArray()
        val rows =
            listOf(
                artifact("voboost-app.apk", 1, "a"),
                artifact("voboost-daemon", 2, "b"),
                artifact("agent-settings.dex", 3, "c"),
            )
        val manifest = ProductManifest(ProductSemVer.parse("1.0.0"), ProductCompatibility(1, 1, 1, 1, 1, 1), rows, bytes)
        val manifestSha = OtaHttpTransport.sha256(bytes)
        val valid =
            OtaCandidateHandle(
                "d".repeat(64),
                "1.0.0",
                manifestSha,
                listOf(identity(rows[0]), identity(rows[2])),
            )
        valid.validateFor(manifest)
        OtaCandidateHandle("e".repeat(64), "1.0.0", manifestSha, emptyList()).validateFor(manifest)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(transactionId = manifestSha)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(artifacts = listOf(identity(rows[2]), identity(rows[0]))).validateFor(manifest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(artifacts = listOf(identity(rows[0]), identity(rows[0])))
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(artifacts = listOf(identity(rows[0]).copy(sha256 = "e".repeat(64)))).validateFor(manifest)
        }
    }

    private fun artifact(
        file: String,
        size: Long,
        hash: String,
    ): ProductArtifact =
        ProductArtifact(
            role =
                if (file.endsWith(".apk")) {
                    "app"
                } else if (file.startsWith("agent-")) {
                    "agent-module"
                } else {
                    "daemon"
                },
            file = file,
            size = size,
            sha256 = hash.repeat(64),
            activation =
                if (file.endsWith(".apk")) {
                    "package-installer"
                } else if (file.startsWith("agent-")) {
                    "agent-generation"
                } else {
                    "native-runtime"
                },
            packageName = if (file.endsWith(".apk")) "ru.voboost" else null,
            versionCode = if (file.endsWith(".apk")) 1 else null,
            certificateSha256 = if (file.endsWith(".apk")) "f".repeat(64) else null,
            abi = null,
        )

    private fun identity(row: ProductArtifact) = CandidateArtifactIdentity(row.file, row.size, row.sha256)
}
