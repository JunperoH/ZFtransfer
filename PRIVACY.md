# Privacy Policy

Effective date: August 3, 2026

Nikon Pull is an unofficial, open-source application for browsing and downloading media from compatible cameras over a local Wi-Fi connection.

## Data collection and sharing

Nikon Pull does not collect, transmit, sell, or share personal data with its developer. The app contains no analytics, advertising, telemetry, account system, or developer-operated network service.

The Android `INTERNET` permission is used only to communicate directly with a camera on the local Wi-Fi network using PTP/IP. The app does not contact an internet server.

## Data stored on the device

The app stores the following locally:

- Camera media catalogs and preview thumbnails, separated by detected camera.
- Connection preferences, an app-generated PTP/IP client identifier, and cache settings.
- A pending download queue and transfer results.
- Originals explicitly downloaded by the user under `Pictures/Nikon Pull` or `Movies/Nikon Pull` through Android MediaStore.

Preview caches can be cleared from the app. Uninstalling the app removes its private catalogs, settings, and queues, but Android may retain originals that the user downloaded into shared media storage.

## Compatibility reports

The user can explicitly invoke Android's share sheet to send a compatibility report to a destination they choose. The report includes app and Android versions, phone manufacturer/model, detected camera model and firmware, PTP capabilities, endpoint mode, and aggregate media counts. It intentionally omits camera serial numbers, media filenames, image contents, and downloaded files.

After the user chooses another app or service to receive a report, that recipient's privacy practices apply.

## Contact

Privacy questions and deletion concerns can be submitted through the project's public issue tracker once the source repository is published. Because Nikon Pull has no developer-operated service or account database, there is no remotely held user data to delete.

## Changes

Material changes to this policy will be documented in the source repository and shipped with the corresponding app release.

Nikon is a trademark of Nikon Corporation. Nikon Pull is not affiliated with or endorsed by Nikon Corporation.
