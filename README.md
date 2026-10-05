# CamDiss

A tiny Android app for Samsung phones that disables the CSC-enforced camera shutter sound using **local Wireless ADB**. No computer and no root are required.

## Flow

1. Tap **Disable shutter sound**.
2. CamDiss starts a foreground pairing service and opens Developer options at Wireless debugging.
3. Tap **Pair device with pairing code**.
4. CamDiss discovers the local `_adb-tls-pairing._tcp` service and posts a notification with an inline pairing-code field.
5. Enter the six-digit code in the notification.
6. CamDiss pairs, connects to the phone's `_adb-tls-connect._tcp` service, runs only:

   ```text
   settings put system csc_pref_camera_forced_shuttersound_key 0
   ```

7. It verifies the result with `settings get ...` and reports success only when the value is `0`.

The pairing interaction is intentionally similar to Shizuku Manager, but the ADB protocol implementation is provided by [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android).

## Notes

- Android 11+ is required (`minSdk 30`). The app currently targets SDK 36; on Android 17, apps targeting SDK 36 or lower retain implicit local-network access through the `INTERNET` permission, so no extra local-network permission prompt is required.
- Designed primarily for Samsung firmware where `csc_pref_camera_forced_shuttersound_key` is honored.
- Disabling the *forced* shutter sound does not necessarily mute the shutter in normal sound mode. Use silent/vibrate mode when needed.
- The ADB key is stored in the app's private internal storage. Clearing app data creates a new key and requires pairing again.
- CamDiss does not expose a general-purpose shell UI.

## Build

The project uses Android Gradle Plugin 9.4.0 with built-in Kotlin, Gradle 9.6.0, Compose Compiler 2.4.10, and stable Compose Material 3 1.4.0.

```bash
gradle :app:assembleDebug
```

### Persistent APK signing

CamDiss follows the same signing model used by Kirigram. A fixed keystore is restored only at build time from GitHub Actions Secrets. When the keystore is available, both `debug` and `release` variants use the same persistent signing identity.

The required repository Secrets are:

- `CAMDISS_KEYSTORE_BASE64`
- `CAMDISS_KEYSTORE_PASSWORD`
- `CAMDISS_KEY_ALIAS`
- `CAMDISS_KEY_PASSWORD`

The keystore file itself is never committed. Main-branch CI publishes an APK artifact only when all four Secrets are present. Before upload, `apksigner` verifies that the certificate SHA-256 digest is exactly:

```text
ed686a582e601d12760495d42b0e844ac89f79bfe939c3fd7c5d4853f87f8a5f
```

If signing Secrets are missing, CI only performs a debug compilation check and deliberately publishes no APK, preventing accidental distribution of an APK signed by a temporary runner key.

## License

Apache-2.0. Third-party dependencies retain their own licenses. `libadb-android` is used under its Apache-2.0 option.
