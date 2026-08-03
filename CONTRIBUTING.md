# Contributing

Thank you for helping improve Nikon Pull. Camera owners are especially valuable because compatibility cannot be established reliably from model names alone.

## Before reporting a camera

1. Confirm that the camera is intended for Nikon's legacy Wireless Mobile Utility (WMU), either through built-in Wi-Fi or a WU-1a/WU-1b adapter. SnapBridge-only cameras are currently out of scope.
2. Install the latest test build and connect the phone directly to the camera's Wi-Fi network.
3. Verify catalog loading, several thumbnails, one small JPEG download, and—when available—one RAW or video download.
4. Open **Camera → Share privacy-safe compatibility report** and attach the resulting text to the camera compatibility issue form.
5. Do not attach photographs, camera serial numbers, Wi-Fi passwords, or unredacted network captures.

## Source changes

- Use Java 17 and the checked-in Gradle wrapper.
- Run `./gradlew testDebugUnitTest lintDebug assembleDebug` before submitting a change.
- Keep PTP parsing bounded and validate every length/count received from a camera.
- Preserve original media bytes; never decode and re-encode downloads.
- Avoid analytics, trackers, proprietary SDKs, or unnecessary Android permissions.
- Add unit tests for protocol parsing and media classification changes.

By contributing, you agree that your contribution is licensed under `GPL-3.0-or-later`.
