# Camera Compatibility

Nikon Pull targets cameras designed for Nikon's legacy Wireless Mobile Utility (WMU) transport. Nikon's official WMU list includes built-in-Wi-Fi cameras and models using WU-1a/WU-1b adapters:

https://downloadcenter.nikonimglib.com/en/download/sw/197.html

SnapBridge-only cameras use a different connection system and are currently unsupported.

## Status levels

- **Verified:** Catalog, previews, and an original download were physically tested with Nikon Pull.
- **Community verified:** A camera owner supplied a successful compatibility report and test checklist.
- **Capability-compatible:** The camera exposes all required PTP operations, but a complete hardware test has not been reported.
- **Unsupported:** The camera lacks a required operation or uses a different transport.

## Current matrix

| Family | Model/adapter | Status | Notes |
|---|---|---|---|
| DSLR, built-in Wi-Fi | D5300 | Verified | JPEG, NEF, MOV, catalog, thumbnails, reconnect, and downloads tested |
| DSLR, built-in Wi-Fi | D5500, D7200, D750 | Testers wanted | Official WMU models |
| DSLR, WU-1a | D3200, D3300, D5200, D7100, Df | Testers wanted | Adapter behavior must be verified |
| DSLR, WU-1b | D600, D610 | Testers wanted | Adapter behavior must be verified |
| Nikon 1 | WMU-compatible V/J/S/AW models | Testers wanted | File formats and association objects may vary |
| COOLPIX | WMU-compatible models | Testers wanted | NRW, MPO, AVI, and video behavior may vary |
| SnapBridge-only | Any | Unsupported | Different transport; not implied by WMU support |

## Required PTP operations

The app currently requires:

- `GetStorageIDs` (`0x1004`)
- `GetObjectHandles` (`0x1007`)
- `GetObjectInfo` (`0x1008`)
- `GetObject` (`0x1009`)

`GetThumb` (`0x100A`) is optional. JPEG previews can fall back to a bounded original download; RAW previews require a camera-supplied thumbnail.
