# App Apk Install specification

## Purpose

Define descriptor-bound APK handoff from `ru.voboost` to the trusted system installer.

## Requirements

### Requirement: APK sharing is restricted to the system installer

The APK content authority SHALL be non-exported, descriptor-bound and SHALL expose no filesystem path or directory. After verifying a bounded APK through retained nofollow file and directory descriptors, the app SHALL duplicate that exact opened inode into a process-local grant, durably unlink the transient pathname, and only then start the installer. The provider SHALL bind the grant to the exact UID of the platform-signed stock installer matching the fixed package, activity, action, scheme and MIME contract, permit only one read open of one opaque exact URI, and SHALL never reopen a pathname. Grants SHALL fail closed on replay, caller mismatch, malformed URI, explicit revocation, bounded expiry, or app-process death. Active retained descriptors and their total bytes SHALL be strictly bounded. The app SHALL fail closed when that structural installer contract is absent or spoofed and SHALL never fall back to an arbitrary MIME handler or issue write, prefix, tree, or persistable authority.

#### Scenario: Only an untrusted APK handler is available

- **WHEN** no allowlisted system or privileged package installer resolves the install intent
- **THEN** APK sharing fails without granting a URI to the untrusted handler

#### Scenario: The OEM installer opens the URI after staging returns

- **WHEN** the supported stock installer delays `openInputStream` and another verified OTA is staged or the old pathname is replaced
- **THEN** the first one-shot URI still reads only its originally authorized inode bytes and cannot be replayed

#### Scenario: The app process dies before the installer opens the URI

- **WHEN** the process-local descriptor lease is lost before the delayed installer read
- **THEN** the URI fails closed and no path is reopened to reconstruct the grant

# Gate: ./gradlew testUnit --tests ru.voboost.ota.ApkInstallerContractTest.untrustedDefaultApkHandlerIsNeverAccepted

Gate contract: Maps: Requirement `APK sharing is restricted to the system installer`; Scenario `Only an untrusted APK handler is available`. Preconditions: The host identity fixture supplies an unrelated handler while preserving every other trusted flag. Observable result: The named test rejects the handler before it can receive descriptor authority. Evidence boundary: Host installer-identity predicate only; Android resolution, platform signature verification, URI creation, grant issuance, and activity start are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.DescriptorBoundApkProviderTest.delayedOpenKeepsTheOriginallyIssuedLeaseAfterPathReplacement

Gate contract: Maps: Requirement `APK sharing is restricted to the system installer`; Scenario `The OEM installer opens the URI after staging returns`. Preconditions: The host provider fixture retains one granted inode while its former pathname is replaced before delayed open. Observable result: The named test observes only the originally authorized bytes and rejects replay. Evidence boundary: Host descriptor-lease abstraction only; OEM installer scheduling is simulated.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.DescriptorBoundApkProviderTest.expiryCancellationAndProcessRestartReleaseAuthority

Gate contract: Maps: Requirement `APK sharing is restricted to the system installer`; Scenario `The app process dies before the installer opens the URI`. Preconditions: The host registry fixture expires, revokes, or recreates process-local grant state before open. Observable result: The named test observes closed authority and failure without pathname reconstruction. Evidence boundary: Host process-state abstraction only; Android process death is simulated.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.PackageUpdaterTest.verifiedAppArtifactUsesExactInstallerAndUnlinksSharePath

Gate contract: Maps: Requirement `APK sharing is restricted to the system installer`; exact verified self-update handoff. Preconditions: A host verified APK file, retained NIO directory authority, exact installer identity, and descriptor provider are available. Observable result: The named test observes the explicit trusted installer component, one descriptor grant, and durable removal of the shared pathname. Evidence boundary: Host/Robolectric handoff only; platform PackageInstaller installation and package replacement are not observed.

### Requirement: The copied APK retains signed byte identity

Before issuing a grant, the updater SHALL bound copied bytes to the signed size, verify their SHA-256, and independently hash the retained read descriptor after installer resolution. It SHALL rewind the descriptor before transfer and remove only its own failed temporary or published inode. A pathname or same-size source/output substitution SHALL never receive a grant.

#### Scenario: Source bytes change between validation and copy

- **WHEN** the validated source pathname changes to different bytes of the same signed size before copying
- **THEN** the request fails before grant issuance and removes its own failed share file

# Gate: ./gradlew testUnit --tests ru.voboost.ota.ReviewApkCopyRaceTest.packageInstallerCannotReceiveBytesSubstitutedAfterSourceVerification

Gate contract: Maps: Requirement `The copied APK retains signed byte identity`; Scenario `Source bytes change between validation and copy`. Preconditions: A SDK28 host fixture changes the source during the UID hook after initial validation. Observable result: No descriptor grant or bytes reach the fake installer and the owned share directory is empty. Evidence boundary: Host/Robolectric file handoff only; actual PackageInstaller and Android storage enforcement require device evidence.

### Requirement: Beta1 signing consumes one deterministic secretless APK

The repository SHALL provide executable `scripts/prepare-beta1-unsigned.sh <output-apk>`. It SHALL require an explicit source-pinned `VOBOOST_EMBEDDED_PRODUCT_PUBLIC_KEY_PEM`, validate it as one exact Ed25519 SPKI, package it from generated build state without mutating source, and verify that the APK contains the same raw 32-byte root. It SHALL build the minified release variant twice from clean outputs without a keystore or signing secret, require byte-identical unsigned `voboost-app.apk` inputs, and publish exactly the requested new output with mode 0644. It SHALL fail before building when the destination already exists, signing material is present, debug mode is requested, or the pre-interoperability daemon gateway opt-in is requested. The unsigned build SHALL compile the same production gateway state selected by the current reviewed interoperability gate and SHALL NOT itself authorize production activation.

#### Scenario: CI supplies a stale output or gateway override

- **WHEN** the destination already exists or a Gradle environment property enables the debuggable daemon gateway
- **THEN** preparation fails without overwriting the destination or producing a signing input

# Gate: ./gradlew testUnit --tests ru.voboost.Beta1UnsignedAdapterTest.adapterIsSecretlessDeterministicMinifiedAndProductionTransportEnabled

Gate contract: Maps: Requirement `Beta1 signing consumes one deterministic secretless APK`; Scenario `CI supplies a stale output or gateway override`. Preconditions: The repository script and Gradle build definition are available. Observable result: The named test verifies the stale-output, secret, debug/gateway, clean double-build, minification, unsigned-signing and byte-comparison guards. Evidence boundary: Source/JVM contract only; the separate adapter invocation gate proves actual APK bytes.

# Gate: VOBOOST_EMBEDDED_PRODUCT_PUBLIC_KEY_PEM=<source-pinned-product.pem> scripts/prepare-beta1-unsigned.sh /tmp/voboost-app-beta1-unsigned.apk

Gate contract: Maps: Requirement `Beta1 signing consumes one deterministic secretless APK`; deterministic unsigned output. Preconditions: Pinned Android build tools 34.0.0, one source-pinned Ed25519 product public PEM, and an absent output pathname are available without signing environment variables. Observable result: Two clean builds succeed, their APK bytes compare equal, the packaged raw product root equals the explicit input, `apksigner verify` rejects the unsigned result, and one mode-0644 output is published. Evidence boundary: Local unsigned build only; protected signing, certificate identity and product composition are separate gates.
