# Voboost app

## Project overview

This repository builds the headless `ru.voboost` Android OTA courier. It owns bounded network download, app-private staging, PackageInstaller self-update, descriptor-bound APK sharing, and explicit in-car uninstall confirmation. The root daemon owns applicability, agent selection, root mutation, generation publication, and canonical installed state. The product repository owns SemVer, `channel.json`, `voboost.json`, release orchestration, and publication.

The app consumes the existing product schemas and shared fixture corpus, verifies release-role signatures on API 28, plans every changed artifact without applicability, resumes bounded app-private downloads, and owns PackageInstaller self-update. Current native source uses the distinct release-catalog key. The exact abstract-LocalSocket/SCM_RIGHTS client is the production gateway and passed the Android 9 end-to-end kernel interoperability gate (see the verified evidence in `specs/app-ota`); daemon absence, timeouts and malformed responses still fail closed as typed unavailable results.

## Validation

```bash
./gradlew testUnit
./gradlew check
./gradlew buildRelease
```

Host validation does not prove Android PackageInstaller, reboot, device, vehicle, daemon, signing, or publication behavior.
