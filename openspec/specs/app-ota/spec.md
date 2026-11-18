# App OTA specification

## Purpose

Define the app-owned OTA courier boundary without inventing the product-owned release service.

## Requirements

### Requirement: The APK contains no product or agent release payload

The APK SHALL contain no agent catalog, agent DEX, agent resource APK, applicability implementation, selection receipt, or Product SemVer input. Its release artifact SHALL be named `voboost-app.apk`; source-controlled Android `versionCode` SHALL change only with app source or APK bytes.

#### Scenario: A minified APK is packaged

- **WHEN** Gradle packages the release variant
- **THEN** the exact output name is `voboost-app.apk` and its asset inventory contains no agent payload

# Gate: ./gradlew verifyApkInventory

Gate contract: Maps: Requirement `The APK contains no product or agent release payload`; Scenario `A minified APK is packaged`. Preconditions: External disposable signing inputs are available. Observable result: The task opens the packaged APK and rejects the wrong output path or any agent catalog, signature, DEX, or agent asset directory. Evidence boundary: Host APK ZIP inventory only; signature identity, PackageManager installation, and byte reproducibility are separate evidence.

### Requirement: Package handoff retains bounded authority

APK self-update SHALL use only the trusted system installer and the existing `DescriptorBoundApkProvider`; path replacement, delayed open, cancellation, expiry, or process restart SHALL not widen the already implemented descriptor authority. The current courier downloads and constructs the typed global candidate before requesting the daemon continuation.

#### Scenario: The installer opens after path replacement

- **WHEN** a granted APK pathname is replaced before the installer opens it
- **THEN** the provider returns only the originally retained verified descriptor

# Gate: ./gradlew testUnit --tests ru.voboost.ota.DescriptorBoundApkProviderTest.delayedOpenKeepsTheOriginallyIssuedLeaseAfterPathReplacement

Gate contract: Maps: Requirement `Package handoff retains bounded authority`; Scenario `The installer opens after path replacement`. Preconditions: The host provider fixture issues a one-shot lease and replaces the pathname. Observable result: The named test reads the original retained bytes and never the replacement. Evidence boundary: Host descriptor/provider model only; installed PackageInstaller behavior is not observed.

### Requirement: Product release selection remains external

The product repository SHALL own `channel.json`, `channel.json.sig`, `voboost.json`, Product SemVer, and publication. The daemon SHALL own active identity, compatibility admission, applicability, and generation selection. The app SHALL download every artifact whose signed identity differs and send the reviewed bounded descriptor-based proposal; root activation remains daemon-owned.

#### Scenario: The daemon identity endpoint is unavailable

- **WHEN** JobScheduler starts and the exact daemon gateway cannot confirm the active identity
- **THEN** the courier requests a later retry before document downloads, package handoff, or root mutation

# Unverified: Global OTA protocol integration

### Requirement: Signed product documents use the shared closed contract

The app SHALL verify raw 64-byte Ed25519 signatures over the exact bounded `channel.json` and `voboost.json` bytes with an immutable APK trust root before parsing. It SHALL reject duplicate keys, non-canonical bytes, unknown fields, invalid channel/SemVer relation, unsafe manifest URL, invalid inventory, filename/role/activation/ABI mismatch, incomplete APK identity, duplicate filename, non-canonical artifact order, and any accepted/rejected result that differs from the shared product corpus.

#### Scenario: Shared product corpus is evaluated

- **WHEN** every copied accepted and rejected `channel.json` and `voboost.json` fixture is parsed
- **THEN** Kotlin produces the same accept/reject classification as product Rust

# Gate: ./gradlew testUnit --tests ru.voboost.ota.ProductContractCorpusTest

Gate contract: Maps: Requirement `Signed product documents use the shared closed contract`; Scenario `Shared product corpus is evaluated`. Preconditions: Exact test-only copies of the product corpus are present. Observable result: The named test accepts every valid fixture and rejects every invalid fixture, including duplicate keys and non-canonical bytes. Evidence boundary: Host Kotlin parsing only; signature transport and product publication are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.ReleaseSignatureVerifierTest.exactEd25519SignatureVerifiesAndMutationFails

Gate contract: Maps: Requirement `Signed product documents use the shared closed contract`; exact Ed25519 verification before parsing. Preconditions: A test Ed25519 key pair, one exact 64-byte signature, and mutated document/length variants are available. Observable result: The named test verifies the exact bytes, rejects changed bytes and a truncated signature. Evidence boundary: Host signature primitive only; the APK trust root and network transport are not observed.

### Requirement: OTA discovery is monotonic and bounded

The app SHALL fetch only HTTPS product documents and permit at most two relative or same-origin HTTPS redirects without credentials, fragments, IDN hosts, or alternate ports. Cross-origin redirect requires an immutable reviewed origin configured in source; the default allowlist is empty. The app SHALL enforce the channel-declared manifest size and SHA-256 and reject any channel release that is not strictly newer than the daemon-confirmed active SemVer. Stable and beta channels SHALL not cross.

#### Scenario: A signed release is replayed

- **WHEN** channel discovery returns the active or an older release identity
- **THEN** the app performs no artifact download or proposal

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaPlannerTest.replayedOrOlderReleaseProducesNoCandidate

Gate contract: Maps: Requirement `OTA discovery is monotonic and bounded`; Scenario `A signed release is replayed`. Preconditions: The host fixture supplies parsed signed channel/manifest identities and a daemon-confirmed active identity. Observable result: The named test rejects equal, older, and cross-channel candidates before download. Evidence boundary: Host planning only; daemon identity transport and production HTTPS are simulated.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaTransportTest.crossOriginIdnAndAlternatePortAreRejected

Gate contract: Maps: Requirement `OTA discovery is monotonic and bounded`; redirect origin restrictions. Preconditions: Loopback servers and hostile HTTPS URI values are available. Observable result: The named test rejects a redirect to another origin, an IDN origin, and an alternate HTTPS port. Evidence boundary: Host URI/HTTP policy only; production DNS and TLS are not contacted.

### Requirement: Every changed artifact is downloaded without applicability selection

The app SHALL compare the complete candidate artifact list with daemon-confirmed active artifact SHA-256 values by exact filename. It SHALL download every row whose size or SHA-256 differs or is absent, including every agent and native role, and SHALL NOT evaluate applicability, dependencies, conflicts, ABI activation, or native role order. Each artifact URL SHALL resolve from the authenticated manifest directory using its exact basename.

A strictly newer metadata-only release with no changed artifact rows SHALL still persist and pass exact `voboost.json` plus signature with an empty ordered changed/body-handle list. The daemon independently validates the installed closure and commits the new product manifest. `NoUpdate` SHALL mean only that the signed target SemVer is equal to or older than the active SemVer.

#### Scenario: Agent and native artifacts change together

- **WHEN** candidate agent and native rows differ while other rows match active identities
- **THEN** the plan contains both changed rows and no unchanged row

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaPlannerTest.downloadPlanContainsEveryChangedRoleWithoutApplicability

Gate contract: Maps: Requirement `Every changed artifact is downloaded without applicability selection`; Scenario `Agent and native artifacts change together`. Preconditions: The host manifest contains mixed exact roles and the fake active identity marks only a subset changed. Observable result: The named test returns every changed filename in manifest order without vehicle or role filtering. Evidence boundary: Host identity diff only; bytes are not downloaded.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaCoordinatorTest.metadataOnlyReleaseProposesManifestAndEmptyBodyList

Gate contract: Maps: Requirement `Every changed artifact is downloaded without applicability selection`; metadata-only release branch. Preconditions: A strictly newer signed manifest has artifact identities equal to the daemon-confirmed active closure. Observable result: The named test observes no body download, retained manifest/signature handles, an empty ordered changed list, and a daemon proposal rather than `NoUpdate`. Evidence boundary: Host coordinator and retained descriptor model only; daemon closure validation and manifest commit are simulated.

### Requirement: Downloads resume inside one bounded app-private transaction

Each candidate SHALL use one canonical unpredictable 256-bit transaction ID encoded as 64 lowercase hexadecimal characters across app download state and the typed daemon proposal. Every artifact SHALL be created/opened through one retained nofollow app-private directory authority with exact uid/gid/mode/type/link count. Initial partial creation SHALL be exclusive; write, hash and fsync SHALL use the same descriptor; parent and leaf identities SHALL be revalidated before fd-relative rename and directory fsync. A partial artifact SHALL resume only with an exact `206` and matching `Content-Range`; `200` SHALL restart from zero. Coroutine cancellation SHALL call `cancel()` on the exact active OkHttp Call during headers, body, redirects and resume. Redirect count, response lifetime, per-file signed size, aggregate candidate bytes, final SHA-256, cancellation, fsync, and ready publication SHALL be bounded. No partial, linked, substituted, foreign or mismatched artifact SHALL be proposed.

#### Scenario: A partial download resumes

- **WHEN** a partial file exists and the server returns the matching remaining range
- **THEN** the app appends, verifies exact final size/SHA-256, syncs the file, and publishes it as complete

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaTransportTest.resumesMatchingRangeAndVerifiesFinalIdentity

Gate contract: Maps: Requirement `Downloads resume inside one bounded app-private transaction`; Scenario `A partial download resumes`. Preconditions: MockWebServer returns a matching bounded range for a known artifact. Observable result: The named test observes the Range request and exact verified final bytes. Evidence boundary: Host HTTP and temporary filesystem only; Android storage ownership and power loss are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaTransportTest.cancellationCancelsStalledHeadersAndBodyCalls

Gate contract: Maps: Requirement `Downloads resume inside one bounded app-private transaction`; cancellation during headers and body. Preconditions: MockWebServer stalls headers and throttles a body behind a counting Call factory. Observable result: Coroutine cancellation invokes `Call.cancel()` for each exact active call and completes the worker. Evidence boundary: Host sockets only; Android JobScheduler deadline delivery is separate.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaTransportTest.cancellationCancelsRedirectAndResumeCalls

Gate contract: Maps: Requirement `Downloads resume inside one bounded app-private transaction`; cancellation during redirect and resume. Preconditions: A redirected stalled response and a throttled exact range response are available. Observable result: Cancellation reaches the active post-redirect and range Calls. Evidence boundary: Host sockets and temporary filesystem only.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaAuthorityTest

Gate contract: Maps: Requirement `Downloads resume inside one bounded app-private transaction`; retained filesystem authority. Preconditions: Host NIO authority and deterministic parent/leaf mutation hooks are available. Observable result: The tests reject hard links, wrong mode, foreign owner, parent replacement and leaf replacement without publishing or deleting the substitute. Evidence boundary: Host POSIX filesystem only; Android kernel/openat behavior remains a device gate.

### Requirement: Daemon integration is typed and path-free

The app SHALL obtain active release/artifact identities and submit one completed candidate only through a typed `DaemonOtaGateway`. Before proposal it SHALL persist and fsync exact `voboost.json`, raw signature and every changed body, open them through one retained nofollow directory, verify their descriptor identities, and pass retained handles in canonical order: manifest, signature, then changed bodies in manifest order. The proposal SHALL contain the canonical opaque 64-hex transaction ID, exact SHA-256 of the verified `voboost.json`, exact release identity, and a possibly empty unique changed artifact list as a manifest-order subsequence. The transaction ID SHALL differ from the manifest digest and SHALL NOT be interpreted as a path or command. The proposal SHALL contain no native role plan or applicability result. Production SHALL select the exact LocalSocket gateway after rooted Android interoperability is proven; daemon absence, rejection, timeout and malformed responses SHALL fail closed as typed unavailable results.

#### Scenario: The daemon adapter is unavailable

- **WHEN** the daemon is absent, stalls, or answers with a malformed response
- **THEN** the job requests retry without publishing a candidate

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaCoordinatorTest.unavailableDaemonStopsBeforeNetwork

Gate contract: Maps: Requirement `Daemon integration is typed and path-free`; Scenario `The daemon adapter is unavailable`. Preconditions: A fake gateway returns unavailable and a counting transport is installed. Observable result: The named test observes zero network calls and a retry result. Evidence boundary: Host orchestration only; the daemon transport itself is covered by the LocalSocket gates below.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaCandidateHandleTest.candidateIsOpaqueAndAnExactUniqueManifestSubsequence

Gate contract: Maps: Requirement `Daemon integration is typed and path-free`; exact candidate identity constraints. Preconditions: A host manifest and candidate variants are available. Observable result: The named test accepts one 64-hex opaque transaction with an exact unique ordered changed subsequence and rejects manifest-digest IDs, duplicate/reordered rows, and changed artifact identity. Evidence boundary: Host typed validation only; daemon transport binding is not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaCoordinatorTest.signedFullReleaseDownloadsEveryChangedArtifactAndRequestsSelfUpdate

Gate contract: Maps: Requirement `Daemon integration is typed and path-free`; descriptor-complete proposal. Preconditions: Signed canonical channel/manifest bytes, exact bodies and a fake daemon gateway are available. Observable result: The gateway receives retained manifest/signature/body handles in canonical order and exact changed identities. Evidence boundary: Host retained-handle abstraction only; `SCM_RIGHTS` transport and native verification are not executed.

### Requirement: Native OTA transport uses one exact abstract LocalSocket exchange

The Android client SHALL connect only to abstract stream socket `voboost.ota.v1` with a 2000 ms deadline for connect, request-header write, request-body write and each response header/body/EOF read. Android 9 `LocalSocket` creates its underlying socket during `connect`, rejects `soTimeout` before creation, does not implement the `connect(address, timeout)` overload, may leave a blocking read unaffected by `soTimeout`, may block `close()` while another thread is inside I/O, and implements `flush()` by waiting for the peer to drain the send queue. The client SHALL therefore use the ordinary fixed-address `connect(address)`, SHALL NOT call `LocalSocket` output `flush()`, and SHALL run every potentially blocking connect, write and read operation on a named daemon thread. Timeout or cancellation SHALL request `SHUT_RDWR` on the exact socket descriptor and perform platform close only on a separate named daemon thread. The caller SHALL return `SocketTimeoutException` after the bounded deadline even if platform close remains blocked; blocked I/O/close helpers SHALL be daemon threads and SHALL not retain the process. The client MAY additionally apply `soTimeout` after successful connection, but correctness SHALL NOT depend on that platform option. One connection SHALL carry one request and one response, each framed by a 4-byte unsigned big-endian payload length followed by exact canonical JSON bytes including one final LF; payload length SHALL be in `1..524288`. `activeRelease` SHALL send zero descriptors. `propose` SHALL attach exactly one `SCM_RIGHTS` message to the header write containing `voboost.json`, `voboost.json.sig`, then zero to 251 changed body descriptors in manifest order; total descriptors SHALL be `2..253`. The client SHALL clear outbound descriptors before writing payload, accept no response descriptors or trailing bytes, close every socket/duplicate on success, timeout, disconnect, cancellation or malformed frame, rethrow coroutine cancellation without parsing it as a daemon response, parse canonical `{"status":"install-apk"}\n` only as `InstallApk`, and send no path, command, role plan or applicability. Production SHALL use this single LocalSocket gateway without a debug-only selector or alternate transport; daemon absence, rejection, timeout and malformed responses SHALL fail closed as typed unavailable results. The client SHALL verify the kernel `SO_PEERCRED` identity (via `LocalSocket.getPeerCredentials()`) immediately after connect and send no byte, path or descriptor when the peer UID is not the expected daemon UID; abstract socket names are unclaimable and a foreign early binder MUST fail closed.

#### Scenario: Golden native requests and responses cross the client seam

- **WHEN** the client sends active/propose requests and receives the copied native golden responses
- **THEN** framing, canonical payload bytes, FD order/count and typed results match the native contract exactly

# Gate: ./gradlew testUnit --tests ru.voboost.ota.LocalSocketDaemonOtaGatewayTest.goldenFramesAndDescriptorOrderMatchNativeFixtures

Gate contract: Maps: Requirement `Native OTA transport uses one exact abstract LocalSocket exchange`; Scenario `Golden native requests and responses cross the client seam`. Preconditions: Exact copied native request/response fixtures and retained fake descriptors are available. Observable result: The named test observes byte-identical payloads, big-endian framing, zero active FDs, canonical proposal FD order, zero output-flush calls and typed response decoding. Evidence boundary: Host socket seam only; the rooted emulator gate executes Android LocalSocket and the native server together.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.LocalSocketDaemonOtaGatewayTest.androidConnectDeadlineCreatesSocketBeforeReadTimeoutAndClosesAStall

Gate contract: Maps: Requirement `Native OTA transport uses one exact abstract LocalSocket exchange`; Android socket creation and connect deadline. Preconditions: The production seam receives one immediate connection and one connect operation that stalls until socket close. Observable result: Success orders connect before read-timeout; timeout closes the socket, unblocks the worker and never configures read timeout. Evidence boundary: Host ordering/deadline seam; the rooted emulator interoperability gate executes the real Android socket.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.LocalSocketDaemonOtaGatewayTest.responseReadDeadlineClosesSocketWhenAndroidSoTimeoutDoesNotUnblockRead

Gate contract: Maps: Requirement `Native OTA transport uses one exact abstract LocalSocket exchange`; Android read deadline. Preconditions: The response-read operation ignores platform timeout while the exact socket-close callback also blocks. Observable result: The two-second mechanism is modeled with a short deterministic deadline, invokes abort asynchronously and returns `SocketTimeoutException` within the caller bound without accepting bytes or waiting for platform close. Evidence boundary: Host deadline/close seam; the rooted emulator SIGSTOP gate executes the real Android peer-drain and response-wait behavior.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.LocalSocketDaemonOtaGatewayTest.disconnectTimeoutBadFrameFdCountAndReplayFailClosed

Gate contract: Maps: Requirement `Native OTA transport uses one exact abstract LocalSocket exchange`; hostile transport branches. Preconditions: The host seam injects EOF, timeout, oversized/noncanonical frames, wrong FD counts, response FDs, repeated requests and a foreign peer UID. Observable result: Every malformed exchange returns unavailable or throws within the bounded lifecycle, closes resources, repeated candidate bytes remain byte-identical, and a foreign peer UID is rejected before any payload byte is sent. Evidence boundary: Host seam only; native replay map and daemon-side SO_PEERCRED are server-owned tests.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.LocalSocketDaemonOtaGatewayTest.cancellationClosesStalledSocketsAndProposalDuplicatesWithoutParsing

Gate contract: Maps: Requirement `Native OTA transport uses one exact abstract LocalSocket exchange`; coroutine cancellation. Preconditions: The socket seam stalls connect, active-response read, and proposal-response read after duplicating retained descriptors. Observable result: Each coroutine rethrows `CancellationException`, closes its socket, closes every proposal duplicate exactly once, and never reaches response descriptor inspection/parsing. Evidence boundary: Host blocking seam only; Android LocalSocket close-unblock behavior remains part of device interoperability.

### Requirement: Terminal acknowledgement retires only its exact transaction

The app SHALL retain a pending or rejected candidate with the same transaction ID across JobService stop, process restart, reboot and package replacement. Only a daemon `complete` acknowledgement matching transaction ID, release identity and manifest SHA-256 SHALL retire that transaction and its exact owned files. A mismatched terminal result SHALL remove nothing. A newer channel release SHALL supersede and retire any not-yet-installed transaction in any phase (`DOWNLOADING`, `READY`, `PROPOSED`): a hotfix must be able to retire a bad older release quickly instead of forcing its installation first. After exact completion, the next product release SHALL receive a new 64-hex transaction ID.

#### Scenario: Two releases complete sequentially

- **WHEN** the first manifest receives an exact complete acknowledgement and a newer manifest is discovered
- **THEN** the app removes only the first owned transaction and admits the second with a new transaction ID

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaTransactionStoreTest.completeRetiresExactTransactionAndAllowsSecondRelease

Gate contract: Maps: Requirement `Terminal acknowledgement retires only its exact transaction`; Scenario `Two releases complete sequentially`. Preconditions: One proposed transaction and exact/mismatched complete plus rejected acknowledgements are available. Observable result: Rejected/mismatched terminal state retains the original transaction, exact complete removes it, and the next release gets a distinct ID. Evidence boundary: Host retained-directory filesystem only; daemon publication and reboot are simulated.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaTransactionStoreTest.rebootKeepsPendingIdentityAndNewerReleaseSupersedesAnyUninstalledState

Gate contract: Maps: Requirement `Terminal acknowledgement retires only its exact transaction`; process restart, pending and hotfix-supersede branches. Preconditions: New store instances reopen one downloading/proposed state. Observable result: Same-manifest restart retains ID, and a newer manifest supersedes any not-yet-installed transaction including proposed state, so a hotfix retires a bad older release without forcing its installation first. Evidence boundary: Host process restart model only; Android reboot and PackageManager replacement are not executed.

### Requirement: Package replacement resumes the same app-side transaction

When and only when the daemon returns canonical `install-apk`, the app SHALL require the changed closure to contain exactly one `voboost-app.apk` and hand only its verified bytes to the trusted PackageInstaller boundary. `pending`, `accepted`, `unavailable`, and `rejected` SHALL never open PackageInstaller. The first `install-apk` response SHALL issue one request. A cancelled installer request SHALL retry without retiring the transaction. After `MY_PACKAGE_REPLACED`, JobScheduler SHALL observe the daemon's still-old active release, reuse the exact durable transaction/candidate and propose again; the daemon independently observes the newly installed APK and advances. Boot and package replacement SHALL schedule the same JobScheduler owner; no embedded agents are extracted and no second transaction is created for the same manifest identity.

#### Scenario: Daemon requests APK continuation

- **WHEN** a proposal containing a changed app artifact returns `install-apk`
- **THEN** the app requests PackageInstaller exactly once for that run

#### Scenario: Proposal remains pending or is accepted

- **WHEN** the same changed closure returns `pending` or `accepted`
- **THEN** the app does not request PackageInstaller

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaCoordinatorTest.pendingAndAcceptedNeverRequestPackageInstaller

Gate contract: Maps: Requirement `Package replacement resumes the same app-side transaction`; Scenario `Proposal remains pending or is accepted`. Preconditions: A full changed release receives each non-continuation success status. Observable result: Both runs return their candidate result with zero PackageUpdater calls. Evidence boundary: Host authority routing only.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaCoordinatorTest.cancelledPackageInstallerRetriesSameDurableCandidateAfterRestart

Gate contract: Maps: Requirement `Package replacement resumes the same app-side transaction`; cancelled/restart continuation. Preconditions: The first PackageUpdater request reports cancellation/failure, a fresh store/coordinator instance reads the same app-private state, active release remains old, and daemon returns `install-apk` again. Observable result: The first run retries, the second requests installation, and both proposals have exactly equal transaction/candidate identity. Evidence boundary: Host process-restart model only; PackageManager replacement is not executed.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaCoordinatorTest.installApkWithoutChangedAppFailsWithoutPackageInstaller

Gate contract: Maps: Requirement `Package replacement resumes the same app-side transaction`; fail-closed changed closure. Preconditions: The daemon returns `install-apk` while the app artifact is unchanged and absent from the proposal. Observable result: The run fails with zero PackageUpdater calls. Evidence boundary: Host routing predicate only.

#### Scenario: Package replacement is delivered

- **WHEN** Android delivers `MY_PACKAGE_REPLACED` during an app-containing candidate
- **THEN** the receiver restores the same fixed OTA job and starts no resident service

# Gate: ./gradlew testUnit --tests ru.voboost.PackageReplacedReceiverTest.replacementRestoresOnePersistedOtaJob

Gate contract: Maps: Requirement `Package replacement resumes the same app-side transaction`; Scenario `Package replacement is delivered`. Preconditions: Robolectric delivers the protected replacement action. Observable result: The named test observes one fixed persisted job and no service start. Evidence boundary: Host scheduler state only; PackageInstaller replacement and transaction continuation on Android are not observed.

### Requirement: Final signing may produce non-authoritative APK evidence

After signing, the app repository SHALL be able to inspect the exact final `voboost-app.apk` with pinned Android build tools and write canonical schema-1 evidence containing the externally supplied full source commit, exact filename, size, SHA-256, package `ru.voboost`, positive `versionCode`, and lowercase SHA-256 of the single signer certificate. Missing source identity, failed APK verification, multiple signers, unexpected package, malformed metadata, or missing tools SHALL produce no evidence. This evidence SHALL remain an untrusted component output: product release tooling SHALL independently inspect the same APK and reject any mismatch before authoring `voboost.json`.

#### Scenario: A protected build finishes APK signing

- **WHEN** the exact signed APK and full app source commit are available
- **THEN** the app build writes deterministic canonical evidence derived from those APK bytes and Android signing/package metadata

# Unverified: Final APK evidence production

## Unverified

### Unverified: Global OTA protocol integration

Maps: Requirement `Product release selection remains external`; Scenario `The daemon identity endpoint is unavailable` and all future successful update branches.

Unavailable observation: Successful activation acknowledgement, post-package-replacement continuation against daemon state, and final active release confirmation for a changed product.

Why unavailable: The rooted Android 9 gate proves the production active/proposal transport, descriptor handoff, replay and peer authentication, but deliberately submits a metadata-only beta2 candidate that the daemon rejects before activation. It does not replace the package or complete an update transaction.

Closure evidence: Run a signed changed-product update through PackageInstaller and the native activation state machine on rooted Android and the vehicle, then prove exact terminal acknowledgement, reboot continuation, final active identity and rollback behavior.

### Unverified: Final APK evidence production

Maps: Requirement `Final signing may produce non-authoritative APK evidence`; Scenario `A protected build finishes APK signing`.

Unavailable observation: Successful evidence production from a clean committed app checkout and an integrated product authoring run over the same protected-signed APK.

Why unavailable: This baseline repository has no commit yet and protected signing is unavailable. Product `inspect-apk` and composition code independently extract Android manifest, signer identity, size, and SHA-256, but the app producer and product verifier have not run together inside one immutable protected workflow.

Closure evidence: Run `./gradlew apkEvidence -PvoboostSourceCommit=<exact-head>` after protected signing in a clean committed checkout, compare its canonical bytes with product `inspect-apk`, then mutate only evidence certificate/version values and require product authoring to reject them before composition.

### Verified: Android 9 LocalSocket native interoperability

Maps: Requirement `Native OTA transport uses one exact abstract LocalSocket exchange`; production activation.

Observed result: A disposable rooted API 28 arm64 AVD installed one complete signed 67-artifact beta1 closure through the fixed no-argument installer and two reboot continuations. The real `ru.voboost` app connected to `@voboost.ota.v1`, received the exact 67-artifact active identity, transferred the signed beta2 manifest pair as two retained `SCM_RIGHTS` descriptors, received the exact pre-activation rejection, replayed the same proposal and observed an unchanged beta1 active identity. A shell-UID native client was disconnected before a response and the daemon recorded the exact peer-authentication rejection. Daemon absence returned unavailable in 3 ms; a SIGSTOP peer returned unavailable in 2003 ms; the same positive exchange passed after restart and after a cold AVD reboot.

Evidence boundary: This proves Android 9 kernel abstract-socket transport, one-message descriptor order/count, package/process/APK peer authority, bounded unavailable behavior, replay and cold daemon restart for the exact ephemeral-key test closure. The emulator-only installer feature deliberately skips OEM SELinux policy transformation and launches services through the generic AOSP `su` domain, so this evidence does not prove production SELinux/init domains, update activation, PackageInstaller replacement, rollback, Android 11, a physical device or vehicle behavior.

Remaining evidence: Run the same signed product on the target vehicle with production SELinux enforcing and OEM init domains; then execute a real changed-product activation, PackageInstaller replacement, reboot, terminal acknowledgement, rollback and clean uninstall. Android 11 remains an additional compatibility target when its image is available.
