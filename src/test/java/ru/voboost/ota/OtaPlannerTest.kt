package ru.voboost.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URI

class OtaPlannerTest {
    @Test
    fun replayedOrOlderReleaseProducesNoCandidate() {
        val active = ActiveReleaseIdentity("beta", ProductSemVer.parse("1.2.0-beta2"), emptyMap())
        val equal = channel("1.2.0-beta2")
        val older = channel("1.1.9-beta9")
        assertThrows(ProductContractException::class.java) { OtaPlanner.requireNewer(equal, manifest("1.2.0-beta2"), active) }
        assertThrows(ProductContractException::class.java) { OtaPlanner.requireNewer(older, manifest("1.1.9-beta9"), active) }
        assertThrows(ProductContractException::class.java) {
            OtaPlanner.requireNewer(channel("1.3.0-beta1", "stable"), manifest("1.3.0-beta1"), active)
        }
    }

    @Test
    fun downloadPlanContainsEveryChangedRoleWithoutApplicability() {
        val artifacts =
            listOf(
                artifact("daemon", "voboost-daemon", 2, "1"),
                artifact("agent-module", "agent-settings.dex", 3, "2"),
                artifact("agent-module", "agent-vehicle.dex", 4, "3"),
            )
        val manifest = ProductManifest(ProductSemVer.parse("1.1.0"), compatibility(), artifacts, byteArrayOf())
        val active =
            ActiveReleaseIdentity(
                "stable",
                ProductSemVer.parse("1.0.0"),
                mapOf(
                    "voboost-daemon" to ActiveArtifactIdentity(2, sha("1")),
                    "agent-settings.dex" to ActiveArtifactIdentity(3, sha("x")),
                ),
            )
        assertEquals(listOf("agent-settings.dex", "agent-vehicle.dex"), OtaPlanner.changedArtifacts(manifest, active).map { it.file })
    }

    private fun channel(
        version: String,
        channel: String = "beta",
    ) = ChannelDocument(channel, ProductSemVer.parse(version), URI("https://voboost.ru/v/$version/voboost.json"), 1, sha("a"))

    private fun manifest(version: String) = ProductManifest(ProductSemVer.parse(version), compatibility(), emptyList(), byteArrayOf())

    private fun compatibility() = ProductCompatibility(1, 1, 1, 1, 1, 1)

    private fun artifact(
        role: String,
        file: String,
        size: Long,
        hash: String,
    ) = ProductArtifact(role, file, size, sha(hash), if (role == "daemon") "native-runtime" else "agent-generation", null, null, null, null)

    private fun sha(value: String) = value.first().code.toString(16).padStart(64, '0').takeLast(64)
}
