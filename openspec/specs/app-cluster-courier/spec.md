# App Cluster Courier specification

## Purpose

Define the unprivileged cluster transport, leased descriptor boundary and bounded background delivery lifecycle.
## Requirements
### Requirement: Cluster delivery preserves daemon authority

The courier SHALL consume only version-1 clusterDeliveryTake leases from the root-authenticated voboost.ota.v1 endpoint, preserve canonical ordered file bytes and return opaque receipt bytes through clusterDeliveryResult. It SHALL reject malformed frames, unknown or duplicate fields, mismatched descriptor counts, writable or non-regular descriptors, changed identities and size or digest mismatch. Legacy OTA operations SHALL retain their exact frames and response descriptor prohibition. The credential descriptor SHALL be last outside the wire file inventory and SHALL never be transmitted.

#### Scenario: An immutable lease is relayed

- **WHEN** a root-issued lease contains exact read-only files and one transport credential
- **THEN** the courier transmits only the declared files in order and returns the exact bounded receipt with the same lease and transaction identities

#### Scenario: Hostile mailbox metadata is received

- **WHEN** a frame or descriptor violates the canonical lease contract
- **THEN** the courier closes all received descriptors and performs no network delivery

# Gate: ./gradlew testUnit --tests ru.voboost.cluster.ClusterCourierTest.relaysOnlyDeclaredFilesAndReturnsExactReceipt

Gate contract: Maps: Requirement `Cluster delivery preserves daemon authority`; Scenario `An immutable lease is relayed`. Preconditions: A host gateway supplies retained exact fixture files and a recording transport. Observable result: Declared files retain order and exact bytes, credential bytes stay outside transmitted bodies, and the returned receipt carries the lease and transaction identity. Evidence boundary: Host courier composition only; Android SCM_RIGHTS and QNX signature validation are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.cluster.ClusterContractsTest.rejectsNoncanonicalAndMismatchedLeaseFrames

Gate contract: Maps: Requirement `Cluster delivery preserves daemon authority`; Scenario `Hostile mailbox metadata is received`, canonical metadata branch. Preconditions: Canonical and malformed frozen local frames are supplied. Observable result: Duplicate, unknown, noncanonical, excessive and mismatched identities are rejected. Evidence boundary: Host parsing only; kernel descriptor ownership is separate.

# Gate: ./gradlew testUnit --tests ru.voboost.cluster.LocalSocketClusterGatewayTest.foreignPeerAndMalformedMailboxFailBeforeDelivery

Gate contract: Maps: Requirement `Cluster delivery preserves daemon authority`; Scenario `Hostile mailbox metadata is received`, peer and frame branches. Preconditions: A host socket fixture exposes a foreign peer or malformed response. Observable result: Foreign peers receive no request bytes; unknown fields and mismatched request identities return unavailable. Evidence boundary: Host socket abstraction only; kernel peer credentials and SCM_RIGHTS remain separate.

# Gate: ./gradlew testUnit --tests ru.voboost.cluster.ClusterContractsTest.sharedNativeCorpusHasExactLocalAndNetworkBytes

Gate contract: Maps: Requirement `Cluster delivery preserves daemon authority`; Scenario `An immutable lease is relayed`, canonical common wire bytes. Preconditions: Exact copied native common corpus and its complete checksum inventory are present. Observable result: Kotlin emits the exact native take, delivered result and network header bytes, parses every common mailbox result, and retains opaque request and receipt envelope bytes. Evidence boundary: Host cross-language corpus parity only; Android descriptor transfer and QNX execution are separate.

# Unverified: Android cluster descriptor interoperability

### Requirement: Cluster transport uses the paired mutual TLS identity

The courier SHALL use only the root-issued endpoint and exact pinned server and client certificate digests with provisioned empty-password PKCS12 credentials and TLS 1.2 or later. One attempt SHALL use bounded connect, handshake, read and write operations, reject trailing or excessive response bytes and retain the opaque signed receipt without rewriting it. Cancellation SHALL close sockets and descriptor leases and propagate its coroutine cancellation cause rather than an error caused by closing the socket. No credential or device-authority creation, redirect, shell helper or alternate transport is permitted.

#### Scenario: The paired TLS server returns a receipt

- **WHEN** both peers authenticate using the provisioned transport identity
- **THEN** the server receives the bounded canonical header and exact ordered declared bodies and the courier returns its exact bounded receipt

#### Scenario: The peer identity differs

- **WHEN** the server certificate differs from the root-issued pin or the client credential differs from its declared certificate
- **THEN** the courier fails before transmitting file bodies

# Gate: ./gradlew testUnit --tests ru.voboost.cluster.ClusterTransportTest.pairedTlsRelaysExactBytesAndRejectsForeignCertificate

Gate contract: Maps: Requirement `Cluster transport uses the paired mutual TLS identity`; Scenarios `The paired TLS server returns a receipt` and `The peer identity differs`. Preconditions: Disposable host TLS identities and a real loopback TLS server are available. Observable result: Mutual TLS transmits the exact declared bytes and receipt; foreign server and client pins reject delivery. Evidence boundary: Host JSSE TCP/TLS only; Android provider and QNX TLS interoperability are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.cluster.ClusterTransportTest.cancellationClosesAStalledHandshake

Gate contract: Maps: Requirement `Cluster transport uses the paired mutual TLS identity`; Scenario `The paired TLS server returns a receipt`, cancellation branch. Preconditions: A real loopback peer accepts TCP and stalls the TLS handshake. Observable result: Coroutine cancellation closes the peer connection, propagates TimeoutCancellationException and returns within the host bound. Evidence boundary: Host TCP/JSSE cancellation only; Android and QNX cleanup are separate environment gates.

# Gate: ./gradlew testUnit --tests ru.voboost.cluster.ClusterCourierTest.cancellationClosesTheLeaseWithoutInventingAResult

Gate contract: Maps: Requirement `Cluster transport uses the paired mutual TLS identity`; Scenario `The paired TLS server returns a receipt`, retained lease cancellation branch. Preconditions: A suspended transport owns a host descriptor lease. Observable result: Cancellation closes every lease source exactly once and emits no invented delivered result. Evidence boundary: Host coroutine ownership only; Android descriptor closure is separate.

# Unverified: QNX mutual TLS delivery

### Requirement: Cluster jobs are independent and bounded

Cluster repair SHALL use an independent persisted fifteen-minute JobScheduler job and a coalesced one-shot wake job even when OTA discovery is disabled. Application start, boot and package replacement SHALL restore repair scheduling. The private explicit ru.voboost.CLUSTER_DELIVERY_WAKE receiver SHALL only schedule work and ignore extras. Each asynchronous run SHALL finish exactly once, exclude concurrent cluster delivery, cancel retained resources when stopped, and request bounded scheduler retry after unavailable transport or exhausted mailbox progress. A run SHALL remain within thirty seconds without a resident foreground loop.

#### Scenario: Wake and boot restore delivery

- **WHEN** explicit wake, boot or package replacement is received while OTA discovery is disabled
- **THEN** cluster work remains scheduled under fixed job identities without creating a resident service

#### Scenario: A bounded job finishes or is stopped

- **WHEN** the runner succeeds, requests retry, throws or is cancelled
- **THEN** the job completes exactly once with the matching retry decision

# Gate: ./gradlew testUnit --tests ru.voboost.ClusterSchedulerTest.wakeAndBootScheduleIndependentCoalescedJobs

Gate contract: Maps: Requirement `Cluster jobs are independent and bounded`; Scenario `Wake and boot restore delivery`. Preconditions: Robolectric invokes the production scheduler and receivers. Observable result: Fixed persisted repair and one-shot job identities survive repeated wakes independent of OTA configuration. Evidence boundary: Host Android scheduler shadow only; OEM background delivery is not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.ClusterJobServiceTest.asyncOutcomesFinishOnceAndCancellationRetries

Gate contract: Maps: Requirement `Cluster jobs are independent and bounded`; Scenario `A bounded job finishes or is stopped`. Preconditions: Robolectric runs injected success, retry, throwing and cancelled runners. Observable result: Each asynchronous start finishes exactly once with its expected retry decision. Evidence boundary: Host coroutine/service lifecycle only; platform process death and deadlines are separate.

# Gate: ./gradlew testUnit --tests ru.voboost.ClusterJobServiceTest.immediateStopAlwaysFinishesTheOwnedJob

Gate contract: Maps: Requirement `Cluster jobs are independent and bounded`; Scenario `A bounded job finishes or is stopped`, immediate-stop branch. Preconditions: Robolectric starts and immediately stops ten jobs. Observable result: Each owned job finishes exactly once with retry even when cancellation precedes worker dispatch. Evidence boundary: Host service lifecycle only; platform process death is not observed.

# Unverified: Android cluster background execution

### Requirement: Local cluster scheduling is independent of Internet validation

Cluster wake and repair SHALL declare no JobScheduler network requirement, so local-peer delivery eligibility does not depend on an Android default network or validated Internet. The independent periodic OTA job SHALL retain its network requirement.

#### Scenario: A local delivery wake arrives without an Android default network

- **WHEN** boot and repeated local delivery wakes occur with no active default network
- **THEN** the persisted Cluster repair and coalesced wake jobs contain no required network while periodic OTA retains its network constraint

# Gate: ./gradlew testUnit --tests ru.voboost.ClusterSchedulerTest.localDeliverySchedulesWithoutAnInternetNetwork

Gate contract: Maps: Requirement `Local cluster scheduling is independent of Internet validation`; Scenario `A local delivery wake arrives without an Android default network`. Preconditions: Robolectric API 28 has no active default network and invokes the production boot and wake scheduling paths. Observable result: Both persisted Cluster jobs have no required network, wake remains coalesced, repair keeps its period, exponential retry remains bounded, and OTA retains NETWORK_TYPE_ANY. Evidence boundary: Host Android scheduler shadow and declared constraints only; actual automatic Android execution and local transport reachability remain separate.

## Unverified

### Unverified: Android cluster descriptor interoperability

Maps: Requirement `Cluster delivery preserves daemon authority`; Scenarios `An immutable lease is relayed` and `Hostile mailbox metadata is received`, kernel descriptor ownership and closure branches. Unavailable observation: Exact native-server response SCM_RIGHTS order, read-only ownership, bounded own-descriptor fdinfo access, descriptor cleanup and retained bytes on Android 9 and Android 11. Why unavailable: Host tests do not execute the Android LocalSocket kernel bridge. Closure evidence: Run the exact APK and native server on named Android targets with valid and hostile descriptor fixtures and prove exact bytes and no leaked descriptors.

### Unverified: QNX mutual TLS delivery

Maps: Requirement `Cluster transport uses the paired mutual TLS identity`; Scenarios `The paired TLS server returns a receipt` and `The peer identity differs`, target provider and cross-OS behavior. Unavailable observation: Provisioned Android-to-QNX route, TLS handshake, bounded reconnect and signature-validated receipt admission. Why unavailable: The host loopback server does not execute QNX TLS or device pairing. Closure evidence: Run exact paired artifacts on owned Android and QNX targets and capture click, apply, disable and restored receipts and resource cleanup.

### Unverified: Android cluster background execution

Maps: Requirement `Cluster jobs are independent and bounded`; both scenarios beyond the host lifecycle model. Unavailable observation: Real thirty-second deadline, boot, system-UID private wake delivery, local vehicle network constraints, process death and OEM background scheduling. Why unavailable: Robolectric does not execute target firmware scheduling or networking. Closure evidence: Install the exact APK on named Android targets, exercise these triggers and cancellations, and capture bounded runs without a resident foreground process.
