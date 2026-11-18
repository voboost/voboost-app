package ru.voboost.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.voboost.Paths
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class OtaTransactionStoreTest {
    @Test
    fun oneManifestKeepsOneCanonicalTransactionAndHotfixSupersedesReadyState() {
        val root = Files.createTempDirectory("voboost-ota-store").toFile()
        val paths = Paths(root)
        val store = OtaTransactionStore(paths)

        val first = store.transactionId("a".repeat(64), "1.0.0")
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(first, store.transactionId("a".repeat(64), "1.0.0"))
        store.stageDocuments(first, "manifest\n".toByteArray(), ByteArray(64))
        store.publishReady(OtaTransactionRecord("a".repeat(64), first, "1.0.0", OtaTransactionPhase.READY))
        // Hotfix contract: a newer release supersedes a ready (not yet
        // installed) older transaction instead of blocking on it.
        val second = store.transactionId("b".repeat(64), "1.0.1")
        assertTrue(second != first)
        assertTrue(paths.otaDir.listFiles().orEmpty().none { it.name.startsWith("$first--") })
    }

    @Test
    fun completeRetiresExactTransactionAndAllowsSecondRelease() {
        val root = Files.createTempDirectory("voboost-ota-retire").toFile()
        val paths = Paths(root)
        val store = OtaTransactionStore(paths)
        val first = store.transactionId("a".repeat(64), "1.0.0")
        store.stageDocuments(first, "manifest\n".toByteArray(), ByteArray(64))
        store.markProposed(OtaTransactionRecord("a".repeat(64), first, "1.0.0", OtaTransactionPhase.PROPOSED))

        store.reconcile(DaemonTerminalAcknowledgement(first, "1.0.0", "a".repeat(64), DaemonTerminalOutcome.REJECTED))
        assertEquals(first, OtaTransactionStore(paths).transactionId("a".repeat(64), "1.0.0"))
        store.reconcile(DaemonTerminalAcknowledgement("e".repeat(64), "1.0.0", "a".repeat(64), DaemonTerminalOutcome.COMPLETE))
        assertEquals(first, store.transactionId("a".repeat(64), "1.0.0"))

        store.reconcile(DaemonTerminalAcknowledgement(first, "1.0.0", "a".repeat(64), DaemonTerminalOutcome.COMPLETE))
        val second = store.transactionId("b".repeat(64), "1.0.1")
        assertTrue(second != first)
        assertTrue(paths.otaDir.listFiles().orEmpty().none { it.name.startsWith("$first--") })
    }

    @Test
    fun rebootKeepsPendingIdentityAndNewerReleaseSupersedesAnyUninstalledState() {
        val root = Files.createTempDirectory("voboost-ota-reboot").toFile()
        val paths = Paths(root)
        val firstStore = OtaTransactionStore(paths)
        val first = firstStore.transactionId("a".repeat(64), "1.0.0")
        firstStore.stageDocuments(first, "old\n".toByteArray(), ByteArray(64))

        val rebooted = OtaTransactionStore(paths)
        assertEquals(first, rebooted.transactionId("a".repeat(64), "1.0.0"))
        val newer = rebooted.transactionId("b".repeat(64), "1.0.1")
        assertTrue(newer != first)
        assertTrue(paths.otaDir.listFiles().orEmpty().none { it.name.startsWith("$first--") })

        // A hotfix release must retire a ready/proposed (not yet installed)
        // older transaction quickly instead of forcing its installation first.
        rebooted.markProposed(OtaTransactionRecord("b".repeat(64), newer, "1.0.1", OtaTransactionPhase.PROPOSED))
        val hotfix = OtaTransactionStore(paths).transactionId("c".repeat(64), "1.0.2")
        assertTrue(hotfix != newer)
        assertTrue(paths.otaDir.listFiles().orEmpty().none { it.name.startsWith("$newer--") })
    }

    @Test
    fun retainedCandidateRejectsHardLinkedManifestAuthority() {
        val root = Files.createTempDirectory("voboost-ota-handles").toFile()
        val paths = Paths(root)
        val store = OtaTransactionStore(paths)
        val manifestBytes = "manifest\n".toByteArray()
        val manifestSha = OtaHttpTransport.sha256(manifestBytes)
        val transaction = store.transactionId(manifestSha, "1.0.0")
        val signature = ByteArray(64)
        store.stageDocuments(transaction, manifestBytes, signature)
        val body = "body".toByteArray()
        val artifact =
            ProductArtifact(
                "agent-module",
                "agent-settings.dex",
                body.size.toLong(),
                OtaHttpTransport.sha256(body),
                "agent-generation",
                null,
                null,
                null,
                null,
            )
        store.artifactFile(transaction, artifact.file).apply {
            writeBytes(body)
            Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("r--------"))
        }
        val manifestFile = store.artifactFile(transaction, "voboost.json")
        Files.createLink(File(paths.otaDir, "linked-manifest").toPath(), manifestFile.toPath())
        val record = OtaTransactionRecord(manifestSha, transaction, "1.0.0", OtaTransactionPhase.READY)
        store.publishReady(record)
        val manifest =
            ProductManifest(ProductSemVer.parse("1.0.0"), ProductCompatibility(1, 1, 1, 1, 1, 1), listOf(artifact), manifestBytes)

        assertThrows(IllegalArgumentException::class.java) {
            store.openCandidate(record, manifest, signature, listOf(artifact))
        }
    }
}
