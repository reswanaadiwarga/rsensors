# Building

## Requirements

- JDK 17 or newer
- Android SDK 36 and build-tools 36
- An SDK path in `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`

No network access is needed by the app; Gradle resolves the declared
dependencies during the build.

## Commands

From the project root:

```bat
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:lintDebug
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`; the
release APK to `app/build/outputs/apk/release/app-release.apk`.

## Tests

The JVM suite has no device dependency. It covers the pure logic only:
formatting, thresholds, pill segments, FPS parsing and the fresh-window
arithmetic, CSV v1/v2 round-trips, session chart geometry, zone mapping,
visibility, ranges and the Shizuku version predicate.

Manual checks on a device: overlay permission, drag/expand, Shizuku binding and
CPU/GPU values, FPS during scrolling and at rest, recording + Sessions graph,
Diagnostics, the setup guide and boot start.

## Release signing

The release build is configured with the local **debug** keystore so it installs
over the debug build (same package and certificate) and preserves app data.
This is for personal sideloading only. For any distribution, replace
`signingConfigs.getByName("debug")` in `app/build.gradle.kts` with a private
release keystore and keep that keystore out of the repository (`*.jks` and
`*.keystore` are ignored).

Verify a build with:

```bat
apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
```

## R8

The release runs R8 full mode and resource shrinking. The only custom keep rule
is:

```proguard
-keep class dev.rsensors.android.shizuku.** { *; }
```

The shell-side `DumpsysUserService` and the AIDL interface are started by name
from Shizuku's process, so they must not be renamed or removed. Mapping output
is written to `app/build/outputs/mapping/release/`.

## Version

`versionCode` / `versionName` live in `app/build.gradle.kts`. The current release
is `versionCode 2`, `versionName 0.2.0`.
