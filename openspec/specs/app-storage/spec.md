# App Storage specification

## Purpose

Define the app-private storage authority implemented before the global OTA protocol exists.

## Requirements

### Requirement: Current app-private storage contains no legacy OTA authority

The app SHALL resolve its private authority from Android `dataDir`, not `filesDir`. The fixed uninstall marker and exact `ota-transaction` identity SHALL live directly under `/data/user/0/ru.voboost`; bounded app logs and OTA candidate bytes SHALL use sibling `logs` and `ota` directories. Production source SHALL define no native component manifest, native role selection, daemon-tree path, or legacy app/native transaction path.

#### Scenario: Current production paths are resolved

- **WHEN** the path contract is instantiated for an application root
- **THEN** only the implemented uninstall, log, and app-private OTA paths resolve below that root

# Gate: ./gradlew testUnit --tests ru.voboost.PathsOwnershipContractTest

Gate contract: Maps: Requirement `Current app-private storage contains no legacy OTA authority`; Scenario `Current production paths are resolved`. Preconditions: The host test supplies an application root. Observable result: The named test observes the exact uninstall marker, log directory, OTA directory, and transaction identity with no intermediate `files` directory. Evidence boundary: Host path construction only; Android ownership and daemon consumption are not observed.

### Requirement: Security-relevant app state publishes through exact descriptor authority

Security-relevant app state SHALL use a retained exact app-owned `0700` directory authority, CSPRNG exclusive `O_NOFOLLOW` `0600` temporary descriptors, complete same-descriptor write and fsync, descriptor, path, and target revalidation, same-parent atomic rename, and directory fsync. Android 9 directory open SHALL use `O_RDONLY|O_CLOEXEC|O_NOFOLLOW` without the unsupported numeric `O_DIRECTORY` flag, then immediately require directory type, exact app uid/gid and the same opened-versus-`lstat` inode; removing `O_DIRECTORY` SHALL NOT remove either no-follow or type/path identity verification. Existing targets SHALL be exact app-owned `0600` regular single-link files. Symlink, hardlink, directory, or leaf substitution SHALL fail closed, and cleanup or clear SHALL never remove a pathname whose identity differs from the exact transaction-owned inode.

# Gate: ./gradlew testUnit --tests ru.voboost.ota.OtaAuthorityTest.androidDirectoryAuthorityKeepsNoFollowWithoutApi28UnsupportedODirectory

Gate contract: Android 9 app-directory open flags. Maps: Requirement `Security-relevant app state publishes through exact descriptor authority`. Preconditions: The production Android flag constructor is inspected directly. Observable result: Flags contain read-only, close-on-exec and no-follow while excluding the API 28 `EINVAL`-producing `O_DIRECTORY` value. Evidence boundary: Host constant composition; the rooted emulator gate executes `Os.open`, `fstat` and opened-versus-path identity verification.

#### Scenario: A security-state temporary path is substituted before publication

- **WHEN** the temporary path no longer identifies the exact transaction-owned inode
- **THEN** publication and cleanup fail closed without removing the substitute or its victim

#### Scenario: A security-state target or retained directory is swapped

- **WHEN** the target or retained parent directory changes identity during publication
- **THEN** publication fails closed without modifying a foreign object

# Gate: ./gradlew testUnit --tests ru.voboost.AppSecureFilesTest.tempSubstitutionFailsWithoutDeletingSubstituteOrVictim

Gate contract: Maps: Requirement `Security-relevant app state publishes through exact descriptor authority`; Scenario `A security-state temporary path is substituted before publication`. Preconditions: The host secure-file fixture substitutes the exclusive temporary inode before publication. Observable result: The named test observes failure without deleting the substitute or its victim. Evidence boundary: Host descriptor-authority adapter only; Android ownership enforcement and crash durability are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.AppSecureFilesTest.targetAndDirectorySwapsFailClosed

Gate contract: Maps: Requirement `Security-relevant app state publishes through exact descriptor authority`; Scenario `A security-state target or retained directory is swapped`. Preconditions: The host secure-file fixture swaps the target or retained parent directory identity during publication. Observable result: The named test observes fail-closed publication and preservation of foreign objects. Evidence boundary: Host filesystem fixture only.
