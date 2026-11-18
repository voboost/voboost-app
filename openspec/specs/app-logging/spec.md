# App logging specification

## Purpose

Define the bounded, retry-safe handoff of `ru.voboost` diagnostics to the daemon-owned canonical log.

## Requirements

### Requirement: The app emits only closed-level bounded batches

The app SHALL accept only `debug`, `info`, and `error`. It SHALL redact secrets and bound every record before admission. It SHALL close a batch after an `error`, 250 ms, 64 records, or 256 KiB, whichever happens first, and atomically persist it as `batch-<128-bit-id>.json` before transmission.

#### Scenario: A batch reaches a boundary

- **WHEN** any batch record, byte, time, or error boundary is reached
- **THEN** the app closes the batch without admitting a record beyond that boundary

#### Scenario: A producer message contains a known secret form

- **WHEN** a message contains a token, password, cookie, secret, or authorization bearer value
- **THEN** the app replaces the secret value before the record enters persistent diagnostic storage

# Gate: ./gradlew testUnit --tests 'ru.voboost.AppLogBatchTest.exactRecordAndTimeLimitsCloseBatch'

Gate contract: Maps: Requirement `The app emits only closed-level bounded batches`; Scenario `A batch reaches a boundary`. Preconditions: The host fixture submits exact record, byte, time, and error boundaries. Observable result: The batch closes at each boundary without accepting the next record. Evidence boundary: Host batching policy only; Android scheduling and daemon delivery remain separate evidence.

# Gate: ./gradlew testUnit --tests 'ru.voboost.LoggerTest.secretsAreRedactedBeforePersistence'

Gate contract: Maps: Requirement `The app emits only closed-level bounded batches`; Scenario `A producer message contains a known secret form`. Preconditions: The host fixture submits one message containing each closed known secret form. Observable result: Persisted diagnostic bytes contain redaction markers and none of the supplied values. Evidence boundary: Host logger serialization only; producer coverage on a physical vehicle remains a separate review and device gate.

### Requirement: App batches are acknowledged idempotently

The app SHALL send a persisted batch through the fixed daemon socket after a bounded connect and kernel root-peer check, configure read timeout only after connect, and complete the framed write/ACK exchange within a three-second deadline and remove it only after an ACK carrying the exact batch ID. A timeout, reconnect, or retry SHALL retain and reuse the same batch ID. The app SHALL NOT accept an ACK for a different ID; a persistently mismatching batch SHALL be moved into a bounded quarantine so later batches stay deliverable, and the quarantine itself SHALL be bounded and remove its oldest entries. Spool enumeration, read, rewrite, and removal SHALL remain relative to a retained private-directory authority, accept only app-owned `0600` single-link regular files, and reject a symlink, hard link, changed inode, or replacement path without reading or deleting its target.

#### Scenario: Delivery is interrupted after daemon persistence

- **WHEN** the app retries a batch whose first ACK was lost
- **THEN** the daemon returns the same ACK and the canonical log contains each record once

#### Scenario: A spool batch pathname is substituted

- **WHEN** a matching `batch-*.json` name is a symlink, hard link, or changes identity between read, ACK, rewrite, and removal
- **THEN** the app rejects the batch without following the path or deleting the substituted target

# Gate: ./gradlew testUnit --tests 'ru.voboost.AppLogBatchTest.ackQuarantinesMismatchInsteadOfBlockingDelivery'

Gate contract: Maps: Requirement `App batches are acknowledged idempotently`; Scenario `Delivery is interrupted after daemon persistence`. Preconditions: The daemon persistently ACKs a different batch ID for one persisted batch. Observable result: The mismatched batch is quarantined aside, never replayed to the daemon, and later batches stay deliverable; only the exact matching batch ID permits deletion. Evidence boundary: Host sender and spool behavior only; daemon persistence and Android peer credentials remain separate evidence.

# Gate: ./gradlew testUnit --tests 'ru.voboost.AppLogBatchTest.spoolRejectsSymlinkAndHardlinkWithoutTouchingVictim'

Gate contract: Maps: Requirement `App batches are acknowledged idempotently`; Scenario `A spool batch pathname is substituted`. Preconditions: The host fixture places symlink and hard-link batch names beside one victim file in an app-private-mode directory. Observable result: Delivery rejects both names, preserves victim bytes, and never invokes the sender. Evidence boundary: Host retained-directory implementation; Android kernel and app UID enforcement remain device evidence.

### Requirement: The app spool remains bounded

The app spool SHALL not exceed 2 MiB. Overflow SHALL discard oldest `debug`, then oldest `info`, then the oldest `error`, and SHALL add at most one nonrecursive `error` loss record for one overflow episode.

#### Scenario: Only error records remain during overflow

- **WHEN** admitting another record would exceed 2 MiB and no debug or info record remains
- **THEN** the oldest error is removed and one bounded loss record is admitted without recursively producing another loss record

# Gate: ./gradlew testUnit --tests 'ru.voboost.AppLogBatchTest.errorOnlyOverflowCreatesOneNonrecursiveLossRecord'

Gate contract: Maps: Requirement `The app spool remains bounded`; Scenario `Only error records remain during overflow`. Preconditions: The host fixture fills a reduced spool with error-only batches and admits one more record. Observable result: The oldest error is removed, one loss record is retained, and no recursive loss record is produced. Evidence boundary: Host spool policy only; Android filesystem durability remains platform evidence.

### Requirement: Android log connection precedes socket options

The sender SHALL create/connect the Android LocalSocket before configuring read timeout and SHALL send no bytes before confirming root peer credentials.

#### Scenario: SDK28 socket receives a batch

- **WHEN** a successful root-peer socket is connected and returns the exact framed batch ACK
- **THEN** timeout configuration follows connect and the wire contains one length prefix plus the exact batch bytes

# Gate: ./gradlew testUnit --tests ru.voboost.ReviewAndroidLogSocketTest.sdk28LogSocketConnectsBeforeOptionsAndTransfersExactBatch

Gate contract: Maps: Requirement `Android log connection precedes socket options`; Scenario `SDK28 socket receives a batch`. Preconditions: The production factory runs on a SDK28 Robolectric socket mock that rejects options before connect and supplies a root peer and exact ACK. Observable result: The sender returns the ACK, configures options only after connection and emits the exact frame. Evidence boundary: Production adapter call order and framing only; actual Android kernel IO remains device evidence.
