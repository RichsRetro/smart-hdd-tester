# Third-party notices

## Gradle Wrapper 8.13

This project includes the Gradle Wrapper (`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`) to let developers build the source. The wrapper is provided by the Gradle project and is licensed under the Apache License, Version 2.0. A copy of that licence is in `THIRD_PARTY_LICENSES/Apache-2.0.txt`; the wrapper JAR also contains its own `META-INF/LICENSE` copy. The Gradle distribution is fetched from `services.gradle.org` by the wrapper when needed and is not bundled in the Android app.

The Smart HDD personal-use licence does not change the Apache 2.0 terms that apply to the Gradle wrapper files.

## Android platform APIs

The app calls Android framework APIs and does not include Android framework implementation code. No separate Android SDK source attribution is required for those API calls.

## App runtime dependencies

The provenance audit found no third-party app runtime libraries in the app build or APK. The USB Mass Storage / SCSI command structures used by the app are standard protocol implementation, not bundled third-party code.
