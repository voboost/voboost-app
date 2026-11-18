# App Uninstall specification

## Purpose

Fix the app's role in product removal: the app never initiates, confirms, or publishes an uninstall. Removal is initiated from the machine Settings host (its own stock confirm dialog plus the daemon socket with per-operation peer attestation); the app keeps no uninstall entry point at all.

## Requirements

### Requirement: The app never initiates or confirms a removal

The app SHALL expose no activity, service, receiver, provider, action, or permission that participates in uninstall initiation or confirmation. No app source SHALL publish or delete an uninstall request marker, and no app path SHALL carry one. The fixed daemon request channels are the desktop CLI file in the root-owned run area and the Settings-host socket RPC; the app zone SHALL contain only OTA and log data.

#### Scenario: The manifest keeps no uninstall entry point

- **WHEN** the production manifest and the main sources are audited
- **THEN** zero activities exist, no `Uninstall*` component, action, or permission is declared, and no main source file mentions an uninstall request

# Gate: ANDROID_HOME=~/Library/Android/sdk ./gradlew test --tests 'ru.voboost.HeadlessManifestAuditTest.noActivityEntryPointRemains' --tests 'ru.voboost.HeadlessManifestAuditTest.appNeverInitiatesAnUninstallRemoval'

Gate contract: Maps: Requirement `The app never initiates or confirms a removal`; Scenario `The manifest keeps no uninstall entry point`. Preconditions: the source-level manifest audit. Observable result: zero `<activity>` elements and no Uninstall-named main source or path. Evidence boundary: source audit of this repository only.

#### Scenario: App paths carry no uninstall marker

- **WHEN** the app path contract is resolved
- **THEN** every path stays under the app zone and names only logs, OTA state, and the OTA transaction

# Gate: ANDROID_HOME=~/Library/Android/sdk ./gradlew test --tests 'ru.voboost.PathsOwnershipContractTest.allAppPathsRemainUnderTheAppZone'

Gate contract: Maps: Requirement `The app never initiates or confirms a removal`; Scenario `App paths carry no uninstall marker`. Preconditions: the JVM Paths contract with a temp app zone. Observable result: only logs, ota, and ota-transaction resolve. Evidence boundary: path contract only.
