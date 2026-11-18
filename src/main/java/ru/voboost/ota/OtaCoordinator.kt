package ru.voboost.ota

import kotlinx.coroutines.CancellationException
import java.net.URI

internal sealed interface OtaRunResult {
    data object Retry : OtaRunResult

    data object NoUpdate : OtaRunResult

    data object CandidateAccepted : OtaRunResult

    data object CandidatePending : OtaRunResult

    data object SelfUpdateRequested : OtaRunResult

    data class Failed(val reason: String) : OtaRunResult
}

internal class OtaCoordinator(
    private val channelUrl: URI,
    private val verifier: ReleaseSignatureVerifier,
    private val transport: OtaTransportClient,
    private val daemon: DaemonOtaGateway,
    private val store: OtaTransactionStore,
    private val packageUpdater: PackageUpdater,
) {
    suspend fun run(): OtaRunResult {
        return try {
            val activeResult =
                when (val result = daemon.activeRelease()) {
                    is DaemonActiveResult.Active -> result
                    DaemonActiveResult.Unavailable -> return OtaRunResult.Retry
                }
            store.reconcile(activeResult.terminal)
            val active = activeResult.identity
            val channelBytes = transport.fetch(channelUrl, MAX_CHANNEL_BYTES)
            val channelSignature = transport.fetch(OtaHttpTransport.signatureUrl(channelUrl), OtaHttpTransport.MAX_SIGNATURE_BYTES)
            verifier.verify(channelBytes, channelSignature)
            val channel = ChannelDocument.parse(channelBytes)
            if (channel.releaseIdentity <= active.releaseIdentity) {
                return OtaRunResult.NoUpdate
            }
            require(channel.channel == active.channel) { "Cross-channel OTA is forbidden" }

            val manifestBytes = transport.fetch(channel.manifestUrl, channel.manifestSize)
            require(manifestBytes.size.toLong() == channel.manifestSize) { "Manifest size mismatch" }
            require(OtaHttpTransport.sha256(manifestBytes) == channel.manifestSha256) { "Manifest SHA-256 mismatch" }
            val manifestSignature =
                transport.fetch(
                    OtaHttpTransport.signatureUrl(channel.manifestUrl),
                    OtaHttpTransport.MAX_SIGNATURE_BYTES,
                )
            verifier.verify(manifestBytes, manifestSignature)
            val manifest = ProductManifest.parse(manifestBytes)
            OtaPlanner.requireNewer(channel, manifest, active)
            val changed = OtaPlanner.changedArtifacts(manifest, active)
            require(changed.sumOf(ProductArtifact::size) <= MAX_CHANGED_BYTES) { "Changed artifact closure exceeds app bound" }
            // Admission must fail before any network download or descriptor
            // open, not at the gateway after the FD budget is spent.
            // Admission must fail before any network download or descriptor
            // open, not at the gateway after the FD budget is spent.
            require(changed.size <= LocalSocketDaemonOtaGateway.MAX_CHANGED_DESCRIPTORS) {
                "Changed descriptor count exceeds the gateway admission bound"
            }

            val transactionId = store.transactionId(channel.manifestSha256, manifest.releaseIdentity.source)
            store.stageDocuments(transactionId, manifestBytes, manifestSignature)
            changed.forEach { artifact ->
                transport.download(channel.manifestUrl, artifact, store.directory(), transactionId)
            }
            val ready =
                OtaTransactionRecord(
                    channel.manifestSha256,
                    transactionId,
                    manifest.releaseIdentity.source,
                    OtaTransactionPhase.READY,
                )
            store.publishReady(ready)
            val candidate =
                OtaCandidateHandle(
                    transactionId,
                    manifest.releaseIdentity.source,
                    channel.manifestSha256,
                    changed.map { CandidateArtifactIdentity(it.file, it.size, it.sha256) },
                )
            candidate.validateFor(manifest)
            val proposal =
                store.openCandidate(ready, manifest, manifestSignature, changed).use { files ->
                    daemon.propose(candidate, files)
                }
            if (proposal != DaemonProposalResult.Unavailable) {
                store.markProposed(ready.copy(phase = OtaTransactionPhase.PROPOSED))
            }
            when (proposal) {
                DaemonProposalResult.Unavailable -> return OtaRunResult.Retry
                is DaemonProposalResult.Rejected -> return OtaRunResult.Failed(proposal.reason)
                DaemonProposalResult.Accepted,
                DaemonProposalResult.Pending,
                DaemonProposalResult.InstallApk,
                -> Unit
            }
            if (proposal == DaemonProposalResult.InstallApk) {
                val app =
                    changed.singleOrNull { it.role == "app" }
                        ?: return OtaRunResult.Failed("Daemon requested APK install without a changed app artifact")
                val requested = packageUpdater.request(store.artifactFile(transactionId, app.file), app)
                if (!requested) return OtaRunResult.Retry
                return OtaRunResult.SelfUpdateRequested
            }
            when (proposal) {
                DaemonProposalResult.Accepted -> OtaRunResult.CandidateAccepted
                DaemonProposalResult.Pending -> OtaRunResult.CandidatePending
                DaemonProposalResult.InstallApk -> error("Handled before candidate result")
                DaemonProposalResult.Unavailable,
                is DaemonProposalResult.Rejected,
                -> error("Handled before package update")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OtaRunResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    companion object {
        const val MAX_CHANNEL_BYTES = 64L * 1024L
        const val MAX_CHANGED_BYTES = 1024L * 1024L * 1024L
    }
}
