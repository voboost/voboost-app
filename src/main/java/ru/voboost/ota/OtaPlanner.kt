package ru.voboost.ota

internal data class ActiveArtifactIdentity(val size: Long, val sha256: String)

internal data class ActiveReleaseIdentity(
    val channel: String,
    val releaseIdentity: ProductSemVer,
    val artifacts: Map<String, ActiveArtifactIdentity>,
)

internal object OtaPlanner {
    fun requireNewer(
        channel: ChannelDocument,
        manifest: ProductManifest,
        active: ActiveReleaseIdentity,
    ) {
        if (channel.channel != active.channel) throw ProductContractException("Cross-channel OTA is forbidden")
        if (channel.releaseIdentity != manifest.releaseIdentity) throw ProductContractException("Channel and manifest identities differ")
        if (manifest.releaseIdentity <= active.releaseIdentity) throw ProductContractException("OTA release is not newer")
    }

    fun changedArtifacts(
        manifest: ProductManifest,
        active: ActiveReleaseIdentity,
    ): List<ProductArtifact> =
        manifest.artifacts.filter { candidate ->
            val installed = active.artifacts[candidate.file]
            installed == null || installed.size != candidate.size || installed.sha256 != candidate.sha256
        }
}
