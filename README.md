# Smart HDD Tester + Fake Card Detector

An offline Android storage diagnostic tool with read-only SMART and surface checks, plus a separate destructive USB fake-card capacity test. I made this totally offline and read-only, other than the fake-card testing section. The destructive test only works with storage connected through the USB port and cannot access the tablet's internal NAND or SD card slot. 100% offline, no annoying ads, no “buy now for the Pro version” nonsense — nothing like that. Just an honest, easy-to-use app. I have tested it on phones and a Fire OS tablet using both fake SD cards and genuine cards.

The full fake-card test writes each block and reads it back before moving on. The quick check writes scattered sample blocks, then reads them back. The full test checks two extra blocks after a repeated failure before reporting the failure boundary.

## Why I built this

I built Smart HDD Tester because I spend a lot of time repairing and refurbishing computers, consoles and retro hardware, and I regularly come across second-hand hard drives, SSDs, USB storage and memory cards that need testing. I wanted a simple tool I could keep with me on an Android tablet rather than having to reach for a PC every time I wanted to check a drive. The original idea was a small offline SMART and storage-health tool that could quickly tell me what a drive had been through — things like power-on hours, temperature, health information and whether the media could actually be read reliably. While testing second-hand storage, I also came across counterfeit/fake-capacity memory cards. That led to the Fake Card Detector: a separate test designed to find storage that claims to have more capacity than it physically has. Smart HDD Tester is therefore very much a practical repair-tool project. It started as something I wanted for my own work and has grown into something I thought might be useful to other people doing the same sort of thing. The project is deliberately offline and avoids accounts, advertising and telemetry. The aim is simply to give people useful storage diagnostics without sending their drive information anywhere.

## Important safety note

The Fake Card Detector writes test data to the selected USB mass-storage card and destroys existing data. Use it only on a card you are willing to erase, after backing up anything important. It does not access internal NAND or the tablet's built-in SD slot. Other diagnostics are read-only. Test reports stay in memory and are not saved to internal storage.

## Install

The APK in the v0.1.0 release folder is a debug build for testing, not a production-signed release. Android 9 (API 28) or newer is required. Enable installation from your file manager when Android asks.

## Languages

The app follows the Android system language by default, and English can be selected in Settings. Interface text, settings, surface-test information, and destructive-test warnings are translated for German, Spanish, French, Italian, Brazilian Portuguese, Dutch, and Polish. Some detailed SMART and surface-test diagnostic output still uses English technical wording.

## Source and licence

The complete Android project is included in the source archive in the v0.1.0 release folder. The source is provided under the Smart HDD personal, non-commercial source-available licence. You may inspect, build, and privately modify your own copy; commercial use, resale, and rebranding are not permitted. Third-party build-tool notices are included alongside the project licence.

## Support

If you like this project feel free to Buy Me a Coffee: https://buymeacoffee.com/richsretro
