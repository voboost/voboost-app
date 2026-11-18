# App Headless specification

## Purpose

Define the headless Android package surface and periodic execution boundary of `ru.voboost`.

## Requirements

### Requirement: The APK exposes only its explicit system boundaries

`ru.voboost` SHALL have no Activity, application icon, application theme, agent settings, agent extraction service, or resident foreground polling service. `DescriptorBoundApkProvider` SHALL remain private and grant only one-shot descriptor authority. The JobScheduler service SHALL require `android.permission.BIND_JOB_SERVICE`; boot, package-replacement and explicit root-recovery receivers SHALL only restore the independent persisted OTA and Cluster schedules. The exported root-recovery receiver SHALL require the same platform signature permission, accept only `ru.voboost.action.RECONCILE_SCHEDULER`, expose no implicit intent filter and never mutate startup mode. Android package execution admission remains root-owned; the courier SHALL not acquire root configuration authority.

#### Scenario: The manifest is audited

- **WHEN** the repository inspects the declared Android components
- **THEN** only the headless OTA, Cluster courier and PackageInstaller/provider boundaries remain, with zero Activity entry points

# Gate: ./gradlew testUnit --tests ru.voboost.HeadlessManifestAuditTest

Gate contract: Maps: Requirement `The APK exposes only its explicit system boundaries`; Scenario `The manifest is audited`. Preconditions: The source manifest and component fixtures are present. Observable result: The named host tests reject launcher, resident foreground service, embedded-agent carrier, unprotected JobScheduler service, weakened PackageInstaller boundaries, or any app uninstall entry point. Evidence boundary: Source manifest and host fixtures only; installed Android component behavior is not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.HeadlessManifestAuditTest.noActivityEntryPointRemains

Gate contract: Maps: Requirement `The APK exposes only its explicit system boundaries`; Scenario `The manifest is audited`, zero Activity entry points. Preconditions: The production manifest is available as source. Observable result: The named source audit asserts that the manifest declares zero activities. Evidence boundary: Source manifest only; packaged or installed Android component behavior is not observed.

### Requirement: Periodic OTA work uses JobScheduler

Periodic checks SHALL use one persisted JobScheduler job requiring network access. Application start, boot and package replacement SHALL schedule the same fixed job identity so a fresh install receives its first bounded check without waiting for a reboot. Daemon absence, transport failure and every malformed input SHALL fail closed without network beyond the bound, package, or root mutation.

Every asynchronous start SHALL invoke `jobFinished` exactly once from one catch/finally lifecycle. Normal no-update/accepted/pending/self-update completion SHALL not retry; malformed transaction state, store failure, daemon IPC exception, transport failure and coroutine cancellation SHALL retry. Synchronous disabled execution SHALL return `false` without calling `jobFinished`.

#### Scenario: Android replaces the package

- **WHEN** `MY_PACKAGE_REPLACED` is delivered
- **THEN** the receiver restores one persisted OTA job and starts no resident service

# Gate: ./gradlew testUnit --tests ru.voboost.PackageReplacedReceiverTest.replacementRestoresOnePersistedOtaJob

Gate contract: Maps: Requirement `Periodic OTA work uses JobScheduler`; Scenario `Android replaces the package`. Preconditions: Robolectric delivers the protected replacement action. Observable result: The named test observes one pending job with the fixed ID and `OtaJobService` component. Evidence boundary: Host JobScheduler shadow only; Android 9 delivery and OEM power management are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.OtaJobServiceTest.jobRunsOneInjectedBoundedAttempt

Gate contract: Maps: Requirement `Periodic OTA work uses JobScheduler`; bounded execution owner. Preconditions: Robolectric creates the service with one injected fake runner. Observable result: The named test observes asynchronous execution of exactly one attempt through the JobService entry point. Evidence boundary: Host service lifecycle only; platform deadlines, network constraints, and OEM power management are not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.OtaJobServiceTest.disabledJobCompletesSynchronouslyWithoutAsyncOwnership

Gate contract: Maps: Requirement `Periodic OTA work uses JobScheduler`; disabled synchronous lifecycle. Preconditions: No configured runner is available. Observable result: The service returns `false` without taking asynchronous job ownership. Evidence boundary: Host service lifecycle only; Android scheduler retry policy is not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.OtaJobServiceTest.asyncOutcomesAndExceptionsFinishExactlyOnce

Gate contract: Maps: Requirement `Periodic OTA work uses JobScheduler`; asynchronous exact-once completion and retry mapping. Preconditions: Fake runners return success/failure results or throw IPC, malformed-store and cancellation exceptions. Observable result: Every asynchronous start calls its injected job finisher exactly once with the expected retry value. Evidence boundary: Host coroutine lifecycle only; Android scheduler process death is not observed.

#### Scenario: Root restores execution after none

- **WHEN** the root lifecycle re-enables the installed package and explicitly delivers `ru.voboost.action.RECONCILE_SCHEDULER` to `ru.voboost/.SchedulerRecoveryReceiver`
- **THEN** the receiver restores the same persisted OTA job idempotently without changing startup mode and returns ordered-broadcast result1 with data `scheduler-ready` only when JobScheduler reports success for both OTA and Cluster repair jobs; failed scheduling returns result0 and `scheduler-failed`
- **AND** foreign actions do not schedule work, and foreign ordinary applications lack the platform signature permission required to deliver this explicit broadcast

# Gate: ./gradlew testUnit --tests ru.voboost.SchedulerRecoveryReceiverTest

Gate contract: Maps: Requirement `The APK exposes only its explicit system boundaries` and `Periodic OTA work uses JobScheduler`; Scenario `Root restores execution after none`. Preconditions: Production manifest and Robolectric JobScheduler are available. Observable result: The host tests assert exact exported component permission, no implicit filter, lack of the signature permission in the ordinary application context, ignored foreign actions and idempotent persisted scheduling. Evidence boundary: Manifest and host scheduling only; actual Android broadcast rejection, root recovery and package enable/disable require an installed Enforcing gate.

# Gate: ./gradlew testUnit --tests ru.voboost.OtaJobServiceTest.runnerConstructionCancellationFinishesExactlyOnceAsRetry

Gate contract: Maps: Requirement `Periodic OTA work uses JobScheduler`; asynchronous exact-once completion during construction. Preconditions: The factory passes the enabled probe and throws CancellationException during construction. Observable result: One retry completion occurs and no runner executes. Evidence boundary: Host coroutine lifecycle only; Android process death is not observed.

# Gate: ./gradlew testUnit --tests ru.voboost.OtaJobServiceTest.cancellationWhileConstructionIsSuspendedFinishesExactlyOnceAsRetry

Gate contract: Maps: Requirement `Periodic OTA work uses JobScheduler`; asynchronous exact-once completion during construction suspension. Preconditions: A deterministic dispatcher holds construction before the factory invocation. Observable result: Cancellation completes once with retry=true, and the suspended factory never runs. Evidence boundary: Host coroutine lifecycle only; Android process death is not observed.

# Unverified: Android periodic OTA execution

## Unverified

### Unverified: Android periodic OTA execution

Maps: Requirement `Periodic OTA work uses JobScheduler`; device execution beyond the host replacement-scheduling scenario.

Unavailable observation: Android 9 and Android 11 persisted scheduling, boot delivery, network constraints, OEM power management, process death, and rescheduling.

Why unavailable: Host tests do not execute the platform scheduler or target firmware power manager.

Closure evidence: Install the exact APK on named Android 9 and Android 11 targets, exercise boot and package replacement, and capture scheduler execution and rescheduling without a resident foreground process.
