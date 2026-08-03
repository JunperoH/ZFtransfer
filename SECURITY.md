# Security Policy

## Supported versions

Only the latest published version is supported during the public beta.

## Reporting a vulnerability

Do not publish camera identifiers, private media, Wi-Fi credentials, or proof-of-concept files containing personal data. Once the public repository exists, use its private vulnerability-reporting feature when available. Otherwise, open a minimal issue requesting a private contact channel without including exploit details.

Reports should include the affected app version, Android version, camera model, impact, and reproducible steps using synthetic or redacted data.

## Security boundaries

Nikon Pull communicates with a camera over an isolated local Wi-Fi network using unencrypted PTP/IP, as provided by the camera. Users should disconnect from the camera network after transfers and should not reuse camera Wi-Fi credentials for other services. The app does not expose a network listener or upload media to a developer-operated server.
