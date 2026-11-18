package ru.voboost.ota

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.voboost.Paths
import java.io.File
import java.net.URI
import java.nio.file.Files

class OtaCoordinatorTest {
    @Test
    fun unavailableDaemonStopsBeforeNetwork() =
        runTest {
            var fetches = 0
            val transport =
                object : OtaTransportClient {
                    override suspend fun fetch(
                        url: URI,
                        maximumBytes: Long,
                    ): ByteArray {
                        fetches++
                        return byteArrayOf()
                    }

                    override suspend fun download(
                        manifestUrl: URI,
                        artifact: ProductArtifact,
                        directory: File,
                        transactionId: String,
                    ): File = error("download must not run")
                }
            val root = Files.createTempDirectory("voboost-ota-coordinator").toFile()
            val verifier = ReleaseSignatureVerifier.fromPem(File("src/main/assets/config/release-public.pem").readText())
            val coordinator =
                OtaCoordinator(
                    URI("https://voboost.ru/channel.json"),
                    verifier,
                    transport,
                    UnavailableDaemonOtaGateway,
                    OtaTransactionStore(Paths(root)),
                    object : PackageUpdater {
                        override fun request(
                            verifiedApk: File,
                            artifact: ProductArtifact,
                        ): Boolean = error("package update must not run")
                    },
                )

            assertEquals(OtaRunResult.Retry, coordinator.run())
            assertEquals(0, fetches)
        }

    @Test
    fun activeIpcAndMalformedStoreFailuresStayInsideCoordinatorLifecycle() =
        runTest {
            val signing = OtaSigningFixture()
            val transport =
                object : OtaTransportClient {
                    override suspend fun fetch(
                        url: URI,
                        maximumBytes: Long,
                    ): ByteArray = error("network must not run")

                    override suspend fun download(
                        manifestUrl: URI,
                        artifact: ProductArtifact,
                        directory: File,
                        transactionId: String,
                    ): File = error("download must not run")
                }
            val throwingDaemon =
                object : DaemonOtaGateway {
                    override suspend fun activeRelease(): DaemonActiveResult = throw java.io.IOException("IPC")

                    override suspend fun propose(
                        candidate: OtaCandidateHandle,
                        files: OtaCandidateFiles,
                    ): DaemonProposalResult = error("proposal must not run")
                }
            val firstStore = OtaTransactionStore(Paths(Files.createTempDirectory("voboost-ota-ipc").toFile()))
            assertTrue(
                coordinator(
                    URI("https://updates.voboost.ru/channel.json"),
                    signing,
                    transport,
                    throwingDaemon,
                    firstStore,
                ).run() is OtaRunResult.Failed,
            )

            val malformedRoot = Files.createTempDirectory("voboost-ota-malformed").toFile()
            val malformedPaths = Paths(malformedRoot)
            malformedPaths.otaTransactionFile.apply {
                writeText("malformed\n")
                Files.setPosixFilePermissions(toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            }
            val activeDaemon =
                object : DaemonOtaGateway {
                    override suspend fun activeRelease(): DaemonActiveResult =
                        DaemonActiveResult.Active(ActiveReleaseIdentity("beta", ProductSemVer.parse("1.0.0-beta1"), emptyMap()))

                    override suspend fun propose(
                        candidate: OtaCandidateHandle,
                        files: OtaCandidateFiles,
                    ): DaemonProposalResult = error("proposal must not run")
                }
            assertTrue(
                coordinator(
                    URI("https://updates.voboost.ru/channel.json"),
                    signing,
                    transport,
                    activeDaemon,
                    OtaTransactionStore(malformedPaths),
                ).run() is OtaRunResult.Failed,
            )
        }

    @Test
    fun signedFullReleaseDownloadsEveryChangedArtifactAndRequestsSelfUpdate() =
        runTest {
            val signing = OtaSigningFixture()
            val baseline = ProductManifest.parse(File("src/test/resources/product/voboost/valid/minimal.json").readBytes())
            val payloads = baseline.artifacts.associate { it.file to it.file.toByteArray() }
            val rows =
                baseline.artifacts.map { row ->
                    val payload = payloads.getValue(row.file)
                    row.copy(size = payload.size.toLong(), sha256 = OtaHttpTransport.sha256(payload))
                }
            val manifestBytes =
                (
                    "{\"schemaVersion\":1,\"releaseIdentity\":\"${baseline.releaseIdentity.source}\"," +
                        "\"compatibility\":${baseline.compatibility.canonical()}," +
                        "\"artifacts\":[${rows.joinToString(",", transform = ProductArtifact::canonical)}]}\n"
                ).toByteArray()
            val manifest = ProductManifest.parse(manifestBytes)
            val manifestUrl = URI("https://updates.voboost.ru/releases/${manifest.releaseIdentity.source}/voboost.json")
            val channel =
                ChannelDocument(
                    "beta",
                    manifest.releaseIdentity,
                    manifestUrl,
                    manifestBytes.size.toLong(),
                    OtaHttpTransport.sha256(manifestBytes),
                )
            val channelBytes = channel.canonicalBytes()
            val channelUrl = URI("https://updates.voboost.ru/channel.json")
            val responses =
                mapOf(
                    channelUrl to channelBytes,
                    OtaHttpTransport.signatureUrl(channelUrl) to signing.sign(channelBytes),
                    manifestUrl to manifestBytes,
                    OtaHttpTransport.signatureUrl(manifestUrl) to signing.sign(manifestBytes),
                )
            val downloaded = mutableListOf<String>()
            val transport =
                object : OtaTransportClient {
                    override suspend fun fetch(
                        url: URI,
                        maximumBytes: Long,
                    ): ByteArray = requireNotNull(responses[url])

                    override suspend fun download(
                        manifestUrl: URI,
                        artifact: ProductArtifact,
                        directory: File,
                        transactionId: String,
                    ): File {
                        downloaded += artifact.file
                        directory.mkdirs()
                        return File(directory, "$transactionId--${artifact.file}").apply {
                            writeBytes(payloads.getValue(artifact.file))
                            setReadable(false, false)
                            setWritable(false, false)
                            setExecutable(false, false)
                            setReadable(true, true)
                        }
                    }
                }
            var proposed: OtaCandidateHandle? = null
            val daemon =
                object : DaemonOtaGateway {
                    override suspend fun activeRelease(): DaemonActiveResult =
                        DaemonActiveResult.Active(
                            ActiveReleaseIdentity("beta", ProductSemVer.parse("0.9.0-beta1"), emptyMap()),
                        )

                    override suspend fun propose(
                        candidate: OtaCandidateHandle,
                        files: OtaCandidateFiles,
                    ): DaemonProposalResult {
                        proposed = candidate
                        assertEquals(
                            listOf("voboost.json", "voboost.json.sig") + manifest.artifacts.map { it.file },
                            files.files.map { it.file },
                        )
                        return DaemonProposalResult.InstallApk
                    }
                }
            val root = Files.createTempDirectory("voboost-ota-full").toFile()
            var packageRequests = 0
            val coordinator =
                OtaCoordinator(
                    channelUrl,
                    ReleaseSignatureVerifier.fromPem(signing.publicKeyPem),
                    transport,
                    daemon,
                    OtaTransactionStore(Paths(root)),
                    object : PackageUpdater {
                        override fun request(
                            verifiedApk: File,
                            artifact: ProductArtifact,
                        ): Boolean {
                            packageRequests++
                            return true
                        }
                    },
                )

            assertEquals(OtaRunResult.SelfUpdateRequested, coordinator.run())
            assertEquals(manifest.artifacts.map { it.file }, downloaded)
            assertEquals(downloaded, proposed?.artifacts?.map { it.file })
            assertTrue(proposed?.transactionId?.matches(Regex("^[0-9a-f]{64}$")) == true)
            assertEquals(1, packageRequests)
        }

    @Test
    fun oversizedChangedDescriptorInventoryFailsBeforeAnyDownload() =
        runTest {
            val signing = OtaSigningFixture()
            val baseline = ProductManifest.parse(File("src/test/resources/product/voboost/valid/minimal.json").readBytes())
            // Build a changed closure one descriptor over the gateway
            // admission bound: the coordinator must refuse BEFORE downloading
            // any artifact body, not after exhausting FD inventory. The
            // required product closure comes from the valid minimal
            // fixture (canonical order), the extra agent modules are
            // zero-padded so the inventory stays canonically sorted.
            val extraCount = LocalSocketDaemonOtaGateway.MAX_CHANGED_DESCRIPTORS + 1 - baseline.artifacts.size
            check(extraCount > 0)
            val overAdmission =
                baseline.artifacts.filterNot { it.role.startsWith("qnx-") } +
                    (1..extraCount).map { index ->
                        ProductArtifact(
                            "agent-module",
                            "agent-z%04d.dex".format(index),
                            1L,
                            OtaHttpTransport.sha256(byteArrayOf(0)),
                            "agent-generation",
                            null,
                            null,
                            null,
                            null,
                        )
                    } + baseline.artifacts.filter { it.role.startsWith("qnx-") }
            val manifestBytes =
                (
                    "{\"schemaVersion\":1,\"releaseIdentity\":\"${baseline.releaseIdentity.source}\"," +
                        "\"compatibility\":${baseline.compatibility.canonical()}," +
                        "\"artifacts\":[${overAdmission.joinToString(",", transform = ProductArtifact::canonical)}]}\n"
                ).toByteArray()
            val manifest = ProductManifest.parse(manifestBytes)
            val manifestUrl = URI("https://updates.voboost.ru/releases/${manifest.releaseIdentity.source}/voboost.json")
            val channel =
                ChannelDocument(
                    "beta",
                    manifest.releaseIdentity,
                    manifestUrl,
                    manifestBytes.size.toLong(),
                    OtaHttpTransport.sha256(manifestBytes),
                )
            val channelBytes = channel.canonicalBytes()
            val channelUrl = URI("https://updates.voboost.ru/channel.json")
            val responses =
                mapOf(
                    channelUrl to channelBytes,
                    OtaHttpTransport.signatureUrl(channelUrl) to signing.sign(channelBytes),
                    manifestUrl to manifestBytes,
                    OtaHttpTransport.signatureUrl(manifestUrl) to signing.sign(manifestBytes),
                )
            val downloads = mutableListOf<String>()
            val transport =
                object : OtaTransportClient {
                    override suspend fun fetch(
                        url: URI,
                        maximumBytes: Long,
                    ): ByteArray = requireNotNull(responses[url])

                    override suspend fun download(
                        manifestUrl: URI,
                        artifact: ProductArtifact,
                        directory: File,
                        transactionId: String,
                    ): File {
                        downloads += artifact.file
                        error("no artifact body may be downloaded past the admission bound")
                    }
                }
            val coordinator =
                OtaCoordinator(
                    channelUrl,
                    ReleaseSignatureVerifier.fromPem(signing.publicKeyPem),
                    transport,
                    object : DaemonOtaGateway {
                        override suspend fun activeRelease(): DaemonActiveResult =
                            DaemonActiveResult.Active(
                                ActiveReleaseIdentity("beta", ProductSemVer.parse("0.9.0-beta1"), emptyMap()),
                            )

                        override suspend fun propose(
                            candidate: OtaCandidateHandle,
                            files: OtaCandidateFiles,
                        ): DaemonProposalResult = error("no proposal may be attempted")
                    },
                    OtaTransactionStore(Paths(Files.createTempDirectory("voboost-ota-admission").toFile())),
                    object : PackageUpdater {
                        override fun request(
                            verifiedApk: File,
                            artifact: ProductArtifact,
                        ): Boolean = error("no package install may be requested")
                    },
                )
            assertTrue(coordinator.run() is OtaRunResult.Failed)
            assertTrue("no artifact body downloaded", downloads.isEmpty())
        }

    @Test
    fun pendingAndAcceptedNeverRequestPackageInstaller() =
        runTest {
            listOf(
                DaemonProposalResult.Pending to OtaRunResult.CandidatePending,
                DaemonProposalResult.Accepted to OtaRunResult.CandidateAccepted,
            ).forEach { (proposal, expected) ->
                val harness = FullReleaseHarness(listOf(proposal))
                assertEquals(expected, harness.coordinator().run())
                assertEquals(0, harness.packageRequests)
            }
        }

    @Test
    fun cancelledPackageInstallerRetriesSameDurableCandidateAfterRestart() =
        runTest {
            val harness =
                FullReleaseHarness(
                    listOf(DaemonProposalResult.InstallApk, DaemonProposalResult.InstallApk),
                    listOf(false, true),
                )
            assertEquals(OtaRunResult.Retry, harness.coordinator().run())
            assertEquals(OtaRunResult.SelfUpdateRequested, harness.coordinator().run())
            assertEquals(2, harness.packageRequests)
            assertEquals(2, harness.proposals.size)
            assertEquals(harness.proposals[0], harness.proposals[1])
            assertEquals(OtaTransactionPhase.PROPOSED, harness.store().current()?.phase)
        }

    @Test
    fun installApkWithoutChangedAppFailsWithoutPackageInstaller() =
        runTest {
            val harness = FullReleaseHarness(listOf(DaemonProposalResult.InstallApk), appChanged = false)
            val result = harness.coordinator().run()
            assertTrue(result is OtaRunResult.Failed)
            assertEquals(0, harness.packageRequests)
        }

    @Test
    fun metadataOnlyReleaseProposesManifestAndEmptyBodyList() =
        runTest {
            val signing = OtaSigningFixture()
            val beta1Bytes = File("src/test/resources/product/voboost/valid/minimal.json").readBytes()
            val beta1 = ProductManifest.parse(beta1Bytes)
            val beta2Bytes = beta1Bytes.toString(Charsets.UTF_8).replace("1.0.0-beta1", "1.0.0-beta2").toByteArray()
            val beta2 = ProductManifest.parse(beta2Bytes)
            val channelUrl = URI("https://updates.voboost.ru/channel.json")
            var responses = signedResponses(channelUrl, beta1, beta1Bytes, signing)
            var active =
                DaemonActiveResult.Active(
                    ActiveReleaseIdentity(
                        "beta",
                        ProductSemVer.parse("0.9.0-beta1"),
                        beta1.artifacts.associate { it.file to ActiveArtifactIdentity(it.size, it.sha256) },
                    ),
                )
            val proposals = mutableListOf<OtaCandidateHandle>()
            val daemon =
                object : DaemonOtaGateway {
                    override suspend fun activeRelease(): DaemonActiveResult = active

                    override suspend fun propose(
                        candidate: OtaCandidateHandle,
                        files: OtaCandidateFiles,
                    ): DaemonProposalResult {
                        assertTrue(candidate.artifacts.isEmpty())
                        assertEquals(listOf("voboost.json", "voboost.json.sig"), files.files.map { it.file })
                        proposals += candidate
                        return DaemonProposalResult.Accepted
                    }
                }
            val transport =
                object : OtaTransportClient {
                    override suspend fun fetch(
                        url: URI,
                        maximumBytes: Long,
                    ): ByteArray = requireNotNull(responses[url])

                    override suspend fun download(
                        manifestUrl: URI,
                        artifact: ProductArtifact,
                        directory: File,
                        transactionId: String,
                    ): File = error("Metadata-only release must not download artifact bodies")
                }
            val paths = Paths(Files.createTempDirectory("voboost-ota-metadata").toFile())
            val store = OtaTransactionStore(paths)

            assertEquals(
                OtaRunResult.CandidateAccepted,
                coordinator(channelUrl, signing, transport, daemon, store).run(),
            )
            val first = proposals.single()
            active =
                DaemonActiveResult.Active(
                    ActiveReleaseIdentity(
                        "beta",
                        beta1.releaseIdentity,
                        beta1.artifacts.associate { it.file to ActiveArtifactIdentity(it.size, it.sha256) },
                    ),
                    DaemonTerminalAcknowledgement(
                        first.transactionId,
                        first.releaseIdentity,
                        first.manifestSha256,
                        DaemonTerminalOutcome.COMPLETE,
                    ),
                )
            assertEquals(OtaRunResult.NoUpdate, coordinator(channelUrl, signing, transport, daemon, store).run())
            assertEquals(null, store.current())

            responses = signedResponses(channelUrl, beta2, beta2Bytes, signing)
            active =
                DaemonActiveResult.Active(
                    ActiveReleaseIdentity(
                        "beta",
                        beta1.releaseIdentity,
                        beta1.artifacts.associate { it.file to ActiveArtifactIdentity(it.size, it.sha256) },
                    ),
                )
            assertEquals(
                OtaRunResult.CandidateAccepted,
                coordinator(channelUrl, signing, transport, daemon, store).run(),
            )
            assertTrue(proposals.last().transactionId != first.transactionId)
        }

    private fun coordinator(
        channelUrl: URI,
        signing: OtaSigningFixture,
        transport: OtaTransportClient,
        daemon: DaemonOtaGateway,
        store: OtaTransactionStore,
    ) = OtaCoordinator(
        channelUrl,
        ReleaseSignatureVerifier.fromPem(signing.publicKeyPem),
        transport,
        daemon,
        store,
        object : PackageUpdater {
            override fun request(
                verifiedApk: File,
                artifact: ProductArtifact,
            ): Boolean = error("No APK body")
        },
    )

    private fun signedResponses(
        channelUrl: URI,
        manifest: ProductManifest,
        manifestBytes: ByteArray,
        signing: OtaSigningFixture,
    ): Map<URI, ByteArray> {
        val manifestUrl = URI("https://updates.voboost.ru/releases/${manifest.releaseIdentity.source}/voboost.json")
        val channel =
            ChannelDocument(
                "beta",
                manifest.releaseIdentity,
                manifestUrl,
                manifestBytes.size.toLong(),
                OtaHttpTransport.sha256(manifestBytes),
            )
        val channelBytes = channel.canonicalBytes()
        return mapOf(
            channelUrl to channelBytes,
            OtaHttpTransport.signatureUrl(channelUrl) to signing.sign(channelBytes),
            manifestUrl to manifestBytes,
            OtaHttpTransport.signatureUrl(manifestUrl) to signing.sign(manifestBytes),
        )
    }

    private inner class FullReleaseHarness(
        proposalResults: List<DaemonProposalResult>,
        packageResults: List<Boolean> = emptyList(),
        appChanged: Boolean = true,
    ) {
        private val signing = OtaSigningFixture()
        private val channelUrl = URI("https://updates.voboost.ru/channel.json")
        private val root = Files.createTempDirectory("voboost-ota-continuation").toFile()
        private val proposalQueue = java.util.ArrayDeque<DaemonProposalResult>(proposalResults)
        private val packageQueue = java.util.ArrayDeque<Boolean>(packageResults)
        val proposals = mutableListOf<OtaCandidateHandle>()
        var packageRequests = 0

        private val baseline = ProductManifest.parse(File("src/test/resources/product/voboost/valid/minimal.json").readBytes())
        private val payloads = baseline.artifacts.associate { it.file to it.file.toByteArray() }
        private val rows =
            baseline.artifacts.map { row ->
                val payload = payloads.getValue(row.file)
                row.copy(size = payload.size.toLong(), sha256 = OtaHttpTransport.sha256(payload))
            }
        private val manifestBytes =
            (
                "{\"schemaVersion\":1,\"releaseIdentity\":\"${baseline.releaseIdentity.source}\"," +
                    "\"compatibility\":${baseline.compatibility.canonical()}," +
                    "\"artifacts\":[${rows.joinToString(",", transform = ProductArtifact::canonical)}]}\n"
            ).toByteArray()
        private val manifest = ProductManifest.parse(manifestBytes)
        private val responses = signedResponses(channelUrl, manifest, manifestBytes, signing)
        private val activeArtifacts =
            if (appChanged) {
                emptyMap()
            } else {
                manifest.artifacts.filter { it.role == "app" }.associate { it.file to ActiveArtifactIdentity(it.size, it.sha256) }
            }

        private val transport =
            object : OtaTransportClient {
                override suspend fun fetch(
                    url: URI,
                    maximumBytes: Long,
                ): ByteArray = requireNotNull(responses[url])

                override suspend fun download(
                    manifestUrl: URI,
                    artifact: ProductArtifact,
                    directory: File,
                    transactionId: String,
                ): File {
                    directory.mkdirs()
                    val target = File(directory, "$transactionId--${artifact.file}")
                    if (target.isFile) return target
                    return target.apply {
                        writeBytes(payloads.getValue(artifact.file))
                        setReadable(false, false)
                        setWritable(false, false)
                        setExecutable(false, false)
                        setReadable(true, true)
                    }
                }
            }
        private val daemon =
            object : DaemonOtaGateway {
                override suspend fun activeRelease(): DaemonActiveResult =
                    DaemonActiveResult.Active(
                        ActiveReleaseIdentity("beta", ProductSemVer.parse("0.9.0-beta1"), activeArtifacts),
                    )

                override suspend fun propose(
                    candidate: OtaCandidateHandle,
                    files: OtaCandidateFiles,
                ): DaemonProposalResult {
                    proposals += candidate
                    return proposalQueue.removeFirst()
                }
            }
        private val updater =
            object : PackageUpdater {
                override fun request(
                    verifiedApk: File,
                    artifact: ProductArtifact,
                ): Boolean {
                    packageRequests++
                    return if (packageQueue.isEmpty()) true else packageQueue.removeFirst()
                }
            }

        fun store() = OtaTransactionStore(Paths(root))

        fun coordinator() =
            OtaCoordinator(
                channelUrl,
                ReleaseSignatureVerifier.fromPem(signing.publicKeyPem),
                transport,
                daemon,
                store(),
                updater,
            )
    }
}
