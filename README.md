# iCloud Sync for Android

Personal Android 10+ media backup client for iCloud Photos. It uses Apple's private iCloud.com web services, so it may stop working when Apple changes those services and is intentionally designed to safe-stop rather than guess.

## Build

1. Install Android Studio and Android SDK 36.
2. Open this directory in Android Studio, or set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to the Android SDK.
3. Run `./gradlew assembleDebug` (`gradlew.bat assembleDebug` on Windows).
4. Sideload `app/build/outputs/apk/debug/app-debug.apk` on your personal device.

The checked-in wrapper is pinned to Gradle 8.13 with a distribution checksum. The project uses Kotlin/JVM 17, min SDK 29, and compile/target SDK 36.

The first backup does not upload anything until it has built exact local and remote content-hash catalogs. Depending on Apple's checksum format, that may stream the entire active iCloud library once over Wi-Fi.

## Security and behavior

- Apple credentials, tokens, and cookies are AES-GCM encrypted using Android Keystore.
- The encryption key is available to background work without biometric authentication.
- Media bytes are streamed and are not copied into app storage.
- Local deletion never deletes remote media.
- Remote deletion is not automatically undone.
- Unsupported Photos formats are stored unchanged under `IcloudSync/Unsupported` in iCloud Drive.

## Implemented workflow

- Native SRP sign-in, trusted-session cookies, interactive 2FA recovery, encrypted background credentials, and service discovery.
- Full and partial media-permission detection, MediaStore hashing, exact remote hashing, hidden-library coverage, sync-token deltas, and item-level reconciliation checkpoints.
- Serial Photos uploads, deterministic Drive fallback paths, foreground WorkManager execution, content-URI triggers, six-hour catch-up work, Wi-Fi/charging policies, and server-aware retry scheduling.
- Compose onboarding, protected item/byte progress, destination counts, authentication and permission recovery, a status-filtered gallery, exclusions, diagnostics, logout, and local-state erasure.

## Verification

Run `./gradlew testDebugUnitTest lintDebug assembleDebug`. The local suite covers Apple SRP, a sanitized MockWebServer password/2FA contract, exact matching, interruption recovery, checksum recognition, remote deletion, state recovery, progress, changed-file detection, and fallback naming.

No live Apple account is embedded or used by the automated suite. Before trusting this with a real library, perform the opt-in disposable-account/device matrix described in the project plan; private Apple endpoints can change independently of this source.

Use a disposable Apple account for protocol development and live tests. Never commit credentials or captured responses containing tokens, cookies, DSIDs, signed download URLs, or personal media metadata.
