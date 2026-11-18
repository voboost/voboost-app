# Provenance

This repository is a new current-working-tree baseline. No Git history was copied from the source repository.

## Source

- Path: `/Users/vitaly/voboost/voboost`
- Origin: `git@github.com:voboost/voboost.git`
- Branch: `main`
- HEAD: `a79b951f18dbcf2bf61fd8fd3f9abe121d7da603`
- Snapshot time: `2026-09-05T21:29:37+0300`
- Working tree: dirty; the complete `git status --short` snapshot is stored in `PROVENANCE.source-status.txt`.
- Independent backup supplied by the user: `/Users/vitaly/voboost/bak7`

The source baseline was the current untracked `app/` tree plus the app-owned root Gradle inputs, wrapper, dependency verification metadata, app contracts, fixtures, license, and repository guidance. The original `/Users/vitaly/voboost/voboost/app` remains unchanged.

## Inventory

`PROVENANCE.sha256` is the sorted SHA-256 inventory of every transferred and authored baseline file except itself and this document. It excludes `.git/`, `.gradle/`, and `build/`.

SHA-256 of `PROVENANCE.sha256`:

```text
195e67267655943ad77cf405ecc7d1fea46865b64f6a36278eaf667525e8bf8f
```

No `local.properties`, signing key, `node_modules`, Node package file, Gradle cache, build output, or other secret/cache was transferred into source control.

## Baseline evidence

The source `./gradlew :app:testUnit --no-daemon` reached app test compilation but failed dependency verification because the source metadata lacked `org.jetbrains.kotlin:kotlin-reflect:1.9.10` POM verification. This was recorded as a source-baseline blocker rather than interpreted as a code failure.

The new root project regenerates its dependency lock state for the app-only graph and adds the missing checksum to its copied strict verification metadata. Host unit, lint, format, minified packaging, APK inventory, disposable signature, package metadata, and strict OpenSpec evidence are recorded in the migration handoff in the source product repository.

## Ownership cut

The new baseline intentionally excludes the embedded agent producer/packaging edge, `AgentsExtractor`, `AgentStagingCoordinator`, agent applicability/selection, and `InstalledAppsProvider`. Review remediation also removed the unreachable legacy component/native OTA parser, orchestration, downloader, signature verifier, native stager, transaction fixtures, paths, and their direct OkHttp, BouncyCastle, and coroutines dependencies. It preserves only current reachable app code plus the protocol-independent PackageInstaller identity/provider leaves and `UninstallActivity`.

The persistent foreground polling service was replaced with a JobScheduler boundary. Kotlin conformance, signed product discovery, all-changed planning, bounded resumable download, app-private transaction state, and PackageInstaller self-update are now implemented. The job still fails closed before network access until the daemon repository supplies the exact active-identity/proposal transport binding.

The product schemas and Rust corpus now exist and are copied byte-exactly into test resources with their own inventory. Reachable OkHttp, BouncyCastle, and coroutines dependencies were reintroduced for the reviewed courier and measured in the minified APK. The optional `apkEvidence` task describes an exact final signed APK with pinned Android build tools, but its output is non-authoritative and must be independently verified by product `inspect-apk`/composition. Protected workflow integration, the daemon transport binding, and device evidence remain external gates.

The OTA review pass adds manifest-correlated terminal retirement, retained descriptor proposal handles, exact persisted manifest/signature bytes, cancellation-bound OkHttp Calls, same-origin redirect policy, and retained nofollow filesystem authority. The Kotlin corpus follows the product `versionCode` bound `1..=2100000000`.

The final lifecycle pass treats a strictly newer metadata-only release as an empty-body manifest proposal and reserves `NoUpdate` for equal/older targets. Coordinator setup/reconcile failures remain inside the run result, and each asynchronous JobService start completes its scheduler callback exactly once, including failure and cancellation.

The exact native OTA client is implemented and selected by production: abstract `voboost.ota.v1`, bounded big-endian frames, retained `SCM_RIGHTS` descriptor order and copied native golden corpus. Android 9 interoperability ran the real minified production app and daemon against one complete ephemeral-key product closure, including active identity, two descriptor-bound proposals, replay, wrong-peer rejection, daemon absence, a 2003 ms SIGSTOP timeout and cold-reboot recovery. Android `LocalSocket.flush()` is intentionally absent because API 28 waits for peer drain; connect, request writes and response reads have independent deadlines, and timeout uses raw socket shutdown plus asynchronous close. Cancellation is rethrown after aborting the exact socket and every duplicated proposal descriptor. Current native source uses the distinct release-catalog key, and there is no fallback socket, path transport or debug-only gateway selector. The Android-provided `org.json` is compile-only while the same pinned desktop implementation remains test-only, preventing a duplicate minified runtime package. This evidence covers emulator transport only: the test installer deliberately bypasses OEM SELinux transformation and does not prove production init/SELinux, update activation, rollback, Android 11, physical-device or vehicle behavior.

The beta1 unsigned adapter requires an explicit source-pinned Ed25519 product public PEM, packages it from a generated build directory without mutating the committed asset, verifies the exact packaged raw root, and proves byte-identical output across two clean minified builds. It rejects signing material, debug/gateway overrides, and stale output.

The APK continuation contract adds the canonical daemon proposal status `install-apk`. Only that status can invoke `PackageUpdater`, and only when the retained changed closure contains the exact app artifact. `pending` and `accepted` never open PackageInstaller. Package replacement or a cancelled installer attempt re-enters the same durable 64-hex transaction and resubmits the byte-identical candidate; the daemon independently observes the installed APK before advancing.
