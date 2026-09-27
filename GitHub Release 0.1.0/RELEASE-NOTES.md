# Smart HDD Tester + Fake Card Detector 0.1.0 — testing build

This is an early testing release of the separate Fake Card Edition app.

## Included
- SMART and HDD surface diagnostics.
- USB fake-card detector with quick and full test modes.
- Progress display and repeat-failure boundary reporting.
- The screen stays awake while a fake-card test is running.
- Phone sizing adapts to available screen space; text size and button size can be tuned in Settings. Tablets keep their standard sizing.
- App pages scroll if content is longer than the screen, and Android system-bar spacing keeps controls clear of navigation buttons.
- About / Support includes Rich's Buy Me a Coffee page.
- USB data transfers are split into device-friendly chunks, and progress updates are throttled to keep the interface responsive on phones.
- Added a language picker for English, German, Spanish, French, Italian, Brazilian Portuguese, Dutch, Polish, Russian, Japanese, Simplified Chinese, Korean, and Turkish. System-language selection follows Android locale resources.
- Fixed padding restoration for theme controls so switching themes does not distort controls when returning to Settings. Fixed language selection so chosen app languages are applied after Settings is closed.
- Kept the fake-card test algorithm unchanged.

## Before installing or testing
- Android 9 (API 28) or newer is required.
- This APK is a debug build intended for testing, not a production-signed release.
- The fake-card test writes patterns and destroys existing data on the selected USB card. Back up first and use only a card you are prepared to erase.
- The detector uses USB mass storage only. It does not test internal NAND or the built-in SD slot.
- USB vendor, model, and serial values are reader-provided metadata and are not evidence that a card is genuine or fake.
- Test reports stay in memory and are not saved to internal storage.

## Build
Built with `gradlew.bat assembleDebug` on 2026-09-27. The source archive includes the complete Android project, build wrapper, project licence, and required third-party licence notices. Device identifiers, user-specific card test results, local build settings, and debug logs are not included.

