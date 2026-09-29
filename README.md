# Smart HDD Tester + Fake Card Detector

An offline Android storage diagnostic tool with read-only SMART and surface checks, plus a separate destructive USB fake-card capacity test. I made this totally offline and read-only, other than the fake-card testing section. The destructive test only works with storage connected through the USB port and cannot access the tablet's internal NAND or SD card slot. 100% offline, no annoying ads, no “buy now for the Pro version” nonsense — nothing like that. Just an honest, easy-to-use app. I have tested it on phones and a Fire OS tablet using both fake SD cards and genuine cards.

The full fake-card test writes each block and reads it back before moving on. Before that full pass starts, it checks a few tiny 512-byte markers at fixed 8 GB, 16 GB, 32 GB, 64 GB and later checkpoints. That way the check locations do not jump to odd places just because a device claims a ridiculous capacity. If one of those markers fails, the result explains that only the small setup checks ran, how far it got, and why it stopped. When the device is still communicating and aliasing has not been confirmed, you can choose to continue with the sequential full test anyway. It keeps checking lower-address markers that already passed, skips the failed and unverified higher markers, and may show the usable limit if the card fails again during the full pass. A bad marker on its own does not prove a card is fake; the app only calls capacity aliasing when it actually finds another address's intact pattern. The quick check still writes and reads scattered sample blocks, and the full test still checks two extra blocks after a repeated failure before reporting the failure boundary.

## Why I built this

I built Smart HDD Tester because I spend a lot of time repairing and refurbishing computers, consoles and retro hardware, and I regularly come across second-hand hard drives, SSDs, USB storage and memory cards that need testing. I wanted a simple tool I could keep with me on an Android tablet rather than having to reach for a PC every time I wanted to check a drive. The original idea was a small offline SMART and storage-health tool that could quickly tell me what a drive had been through — things like power-on hours, temperature, health information and whether the media could actually be read reliably. While testing second-hand storage, I also came across counterfeit/fake-capacity memory cards. That led to the Fake Card Detector: a separate test designed to find storage that claims to have more capacity than it physically has. Smart HDD Tester is therefore very much a practical repair-tool project. It started as something I wanted for my own work and has grown into something I thought might be useful to other people doing the same sort of thing. The project is deliberately offline and avoids accounts, advertising and telemetry. The aim is simply to give people useful storage diagnostics without sending their drive information anywhere.

## Important safety note

The Fake Card Detector writes test data to the selected USB mass-storage card and destroys existing data. Use it only on a card you are willing to erase, after backing up anything important. It does not access internal NAND or the tablet's built-in SD slot. Other diagnostics are read-only. Test reports stay in memory and are not saved to internal storage.

## Install

The APK in the v0.1.2 release is a debug build for testing, not a production-signed release. Android 9 (API 28) or newer is required. Enable installation from your file manager when Android asks. This update uses the same debug signing key as v0.1.0 and v0.1.1.

## Languages

The app follows the Android system language by default. Settings also lets you choose English, German, Spanish, French, Italian, Brazilian Portuguese, Dutch, Polish, Russian, Japanese, Simplified Chinese, Korean, or Turkish. Main interface text, settings, surface-test information, and destructive-test warnings are translated; less common screens and detailed technical diagnostics may still use English.

## Source and licence

The complete Android project is included in the v0.1.2 source archive. The source is provided under the Smart HDD personal, non-commercial source-available licence. You may inspect, build, and privately modify your own copy; commercial use, resale, and rebranding are not permitted. Third-party build-tool notices are included alongside the project licence.

## Support

If you like this project feel free to Buy Me a Coffee: https://buymeacoffee.com/richsretro
