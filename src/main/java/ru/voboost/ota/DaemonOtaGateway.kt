package ru.voboost.ota

import android.os.ParcelFileDescriptor
import ru.voboost.AppFileIdentity
import ru.voboost.AppReadAuthority

internal sealed interface DaemonActiveResult {
    data object Unavailable : DaemonActiveResult

    data class Active(
        val identity: ActiveReleaseIdentity,
        val terminal: DaemonTerminalAcknowledgement? = null,
    ) : DaemonActiveResult
}

internal enum class DaemonTerminalOutcome { COMPLETE, REJECTED }

internal data class DaemonTerminalAcknowledgement(
    val transactionId: String,
    val releaseIdentity: String,
    val manifestSha256: String,
    val outcome: DaemonTerminalOutcome,
)

internal data class CandidateArtifactIdentity(
    val file: String,
    val size: Long,
    val sha256: String,
) {
    init {
        require(file.isNotEmpty() && file.none { it == '/' || it == '\\' || it == '\u0000' })
        require(size > 0)
        require(sha256.matches(Regex("^[0-9a-f]{64}$")))
    }
}

/** Opaque app-private transaction identity; it is not a filesystem path or command. */
internal data class OtaCandidateHandle(
    val transactionId: String,
    val releaseIdentity: String,
    val manifestSha256: String,
    val artifacts: List<CandidateArtifactIdentity>,
) {
    init {
        require(transactionId.matches(Regex("^[0-9a-f]{64}$")))
        ProductSemVer.parse(releaseIdentity)
        require(manifestSha256.matches(Regex("^[0-9a-f]{64}$")))
        require(transactionId != manifestSha256) { "Transaction ID must be opaque and independent of manifest identity" }
        require(artifacts.map { it.file }.toSet().size == artifacts.size)
    }

    fun validateFor(manifest: ProductManifest) {
        require(releaseIdentity == manifest.releaseIdentity.source)
        require(manifestSha256 == OtaHttpTransport.sha256(manifest.exactBytes))
        val byFile = manifest.artifacts.associateBy(ProductArtifact::file)
        val canonical =
            artifacts.map { changed ->
                val row = requireNotNull(byFile[changed.file]) { "Candidate artifact is absent from manifest" }
                require(changed.size == row.size && changed.sha256 == row.sha256) {
                    "Candidate artifact identity differs from manifest"
                }
                changed.file
            }
        val positions = canonical.map { file -> manifest.artifacts.indexOfFirst { it.file == file } }
        require(positions.zipWithNext().all { (left, right) -> left < right }) {
            "Candidate artifacts are not an exact manifest-order subsequence"
        }
    }
}

internal sealed interface DaemonProposalResult {
    data object Accepted : DaemonProposalResult

    data object Pending : DaemonProposalResult

    data object InstallApk : DaemonProposalResult

    data class Rejected(val reason: String) : DaemonProposalResult

    data object Unavailable : DaemonProposalResult
}

internal class RetainedOtaFile internal constructor(
    val file: String,
    val size: Long,
    val sha256: String,
    val identity: AppFileIdentity,
    private val authority: AppReadAuthority,
) {
    fun duplicateForTransport(): ParcelFileDescriptor = authority.duplicateForTransfer()
}

/** Retained directory/file authority ordered as manifest, signature, then changed bodies. */
internal class OtaCandidateFiles internal constructor(
    val files: List<RetainedOtaFile>,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    override fun close() = closeAction()
}

internal interface DaemonOtaGateway {
    suspend fun activeRelease(): DaemonActiveResult

    suspend fun propose(
        candidate: OtaCandidateHandle,
        files: OtaCandidateFiles,
    ): DaemonProposalResult
}
