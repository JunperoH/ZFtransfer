# Release Checklist

## Shared preparation

- Confirm the application ID and public display name before the first store release; changing the application ID later creates a separate app.
- Build from a clean checkout using the checked-in Gradle wrapper.
- Run `./gradlew testDebugUnitTest lintDebug assembleDebug bundleRelease`.
- Update `versionCode`, `versionName`, changelog, privacy policy, compatibility matrix, and screenshots.
- Tag the exact release commit as `v<versionName>`.
- Verify the tag builds without local-only files or credentials.
- Keep signing keys and passwords outside the repository and maintain encrypted backups.

## Google Play

- Target the API level required on the intended submission date. New submissions require API 36 beginning August 31, 2026.
- Generate a signed Android App Bundle and configure Play App Signing with a separate upload key.
- Host `PRIVACY.md` at a stable public HTTPS URL and complete the Data Safety declaration, including a declaration that the app does not collect or share user data.
- Complete content rating, app access, target audience, store listing, screenshots, icon, and feature graphic.
- For applicable new personal accounts, complete the required closed test before requesting production access.

## F-Droid

- Keep the complete source in a public Git repository with `LICENSE` and version tags.
- Ensure every runtime and build dependency is free/open-source and version-pinned.
- Keep the F-Droid build free of proprietary services and SDKs.
- Maintain Fastlane-compatible metadata under `fastlane/metadata/android/en-US`.
- Test the release build from a clean source archive, then submit metadata to `fdroiddata` or open a Request for Packaging.

F-Droid normally signs its build with a different key, so users may be unable to switch an installed app directly between Google Play and F-Droid. Reproducible upstream-signed builds can address this later but require additional release infrastructure.
