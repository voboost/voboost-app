# Inheritance

- Follow [common Voboost rules](../voboost-codestyle/AGENTS.md).

# Architecture

- This repository owns only the unprivileged headless Android OTA courier.
- Keep the Android application at root `src/`; do not add an `app/` module.
- Do not add agent code, applicability, selection, root mutation, product publication, or product SemVer inputs.
- Preserve PackageInstaller and `DescriptorBoundApkProvider` for OTA APK updates. The app has no uninstall entry point; removal belongs to Settings-host initiation and the root daemon/uninstaller.
- `JobScheduler` is the periodic trigger. Do not restore a resident foreground polling loop.
- Product release schemas are external canonical contracts copied only into the conformance corpus; do not fork their semantics.
- The production daemon gateway is the exact LocalSocket client; it stays selected only while the distinct-release-catalog native server keeps passing the Android end-to-end kernel interoperability gate. Never add a fallback path or command.

# Build

- Use JDK 17, `minSdk = targetSdk = 28`, `compileSdk = 34`, and NDK r29.
- `versionCode` is source-controlled and changes only when APK bytes or app source change.
- The output is `build/outputs/apk/release/voboost-app.apk`.
- `apkEvidence` may describe the final signed APK, but its JSON is never authority; product release tooling independently verifies the APK.
- Keep build, tests, formatting, and app code generation in Gradle/JVM. Do not add Node package files.
