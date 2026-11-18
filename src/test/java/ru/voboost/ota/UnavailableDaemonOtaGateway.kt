package ru.voboost.ota

internal object UnavailableDaemonOtaGateway : DaemonOtaGateway {
    override suspend fun activeRelease(): DaemonActiveResult = DaemonActiveResult.Unavailable

    override suspend fun propose(
        candidate: OtaCandidateHandle,
        files: OtaCandidateFiles,
    ): DaemonProposalResult = DaemonProposalResult.Unavailable
}
