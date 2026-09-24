# Smart HDD Tester + Fake Card Detector

An offline Android storage diagnostic tool with read-only SMART and surface checks, plus a separate destructive USB fake-card capacity test. I made this totally offline and read-only, other than the fake-card testing section. The destructive test only works with storage connected through the USB port and cannot access the tablet's internal NAND or SD card slot.
100% offline, no annoying ads, no “buy now for the Pro version” nonsense — nothing like that. Just an honest, easy-to-use app.
I have tested it on phones and a Fire OS tablet using both fake SD cards and genuine cards. The fake-card test works differently from traditional tests: it reads and writes at the same time, so faults can be reported as soon as they are detected rather than waiting until the entire card has been written first.
## Why I built this?

I built Smart HDD Tester because I spend a lot of time repairing and refurbishing computers, consoles and retro hardware, and I regularly come across second-hand hard drives, SSDs, USB storage and memory cards that need testing.
I wanted a simple tool I could keep with me on an Android tablet rather than having to reach for a PC every time I wanted to check a drive. The original idea was a small offline SMART and storage-health tool that could quickly tell me what a drive had been through — things like power-on hours, temperature, health information and whether the media could actually be read reliably.
While testing second-hand storage, I also came across counterfeit/fake-capacity memory cards. That led to the Fake Card Detector: a separate test designed to find storage that claims to have more capacity than it physically has.
Smart HDD Tester is therefore very much a practical repair-tool project. It started as something I wanted for my own work and has grown into something I thought might be useful to other people doing the same sort of thing.
The project is deliberately offline and avoids accounts, advertising and telemetry. The aim is simply to give people useful storage diagnostics without sending their drive information anywhere.

## Important safety note

The Fake Card Detector writes test data to the selected USB mass-storage card and destroys existing data. Use it only on a card you are willing to erase, after backing up anything important. It does not access internal NAND or the tablet's built-in SD slot. Other diagnostics are read-only. Test reports stay in memory and are not saved to internal storage.

## Install

The APK in this release folder is a debug build for phone testing. Android 9 (API 28) or newer is required. Enable installation from your file manager when Android asks. The APK is not a production-signed release build.

## Source and licence

The source is provided under the Smart HDD personal, non-commercial source-available licence in `LICENSE`. The project licence and third-party build-tool notices are included in this release folder.

## Support

If you like this project feel free to Buy Me a Coffee: https://buymeacoffee.com/richsretro
