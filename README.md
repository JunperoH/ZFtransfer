# Nikon Pull

Nikon Pull is an experimental Android app for browsing and copying original media from compatible Nikon cameras over Wi-Fi using PTP/IP. Originals are streamed directly into Android MediaStore without image re-encoding.

The Nikon D5300 is the first physically verified camera. Other cameras designed for Nikon's legacy Wireless Mobile Utility (WMU), including models using WU-1a/WU-1b adapters, are capability-detected but must be hardware-tested before they are marked verified. SnapBridge-only cameras are currently out of scope.

## Current test build

Version `0.7.0-beta.4` requires Android 10 or newer and targets Android 16/API 36. Its permanent Android application ID is `io.github.noisetta.nikonpull`. The installable debug APK is at `app/build/outputs/apk/debug/app-debug.apk` after a successful build.

## Features

- Discovers the camera at the current non-internet Wi-Fi gateway, with an optional manual IPv4 address and PTP/IP port.
- Uses an install-stable PTP/IP client identity and reads the camera's standard PTP DeviceInfo record.
- Checks the operations required for browsing and downloading before synchronizing media.
- Keeps separate offline catalogs and thumbnail namespaces for each detected camera.
- Browses JPEG, Nikon NEF/NRW, MPO/TIFF, MOV/MP4/AVI, and other standard PTP video formats.
- Filters media by capture date and format.
- Maintains a persistent, verified download queue with duplicate detection, reconnect retries, and chunk-boundary resume after interruptions or app restarts.
- Uses `GetPartialObject` chunks for large files when the camera reports support, with full-object fallback for other cameras.
- Saves photos under `Pictures/Nikon Pull` and videos under `Movies/Nikon Pull`.
- Exports a compatibility report that omits serial numbers, filenames, and image contents.

## Test a camera

1. Enable Wi-Fi on a WMU-compatible camera or attach and enable its WU-1a/WU-1b adapter.
2. Join the camera's Wi-Fi network on Android and remain connected if Android reports that it has no internet.
3. Open Nikon Pull. Automatic connection should detect the camera model and its capabilities.
4. If automatic discovery fails, open **Camera**, enter the camera's IPv4 address and PTP/IP port, and save.
5. Confirm that the catalog and thumbnails load, then download one small JPEG.
6. Open **Camera → Share privacy-safe compatibility report** and attach the report to the test feedback.

Compatibility levels used by the project are:

- **Verified:** physically tested successfully with this app.
- **Capability-compatible:** required PTP browsing operations are present, but that model still needs a hardware report.
- **Unsupported:** required PTP operations are missing or the camera uses another transport such as SnapBridge.

## Build

The project currently builds with Java 17, Android SDK 36, Android Gradle Plugin 8.13.2, Kotlin 2.3.0, and Gradle 8.13:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The GitHub Actions workflow runs tests, lint, debug APK assembly, and an unsigned release-bundle build. Public release signing remains intentionally external to source control.

## Protocol notes

The transport follows CIPA DC-X005 PTP/IP. Numeric fields are little-endian, command and event channels are initialized separately, and incomplete MediaStore items are deleted. Large originals use bounded `GetPartialObject` transactions when advertised by the camera; otherwise the app falls back to `GetObject`. The app treats `GetThumb` and `GetPartialObject` as optional but requires storage enumeration, object-handle enumeration, object metadata, and full-object download support.
