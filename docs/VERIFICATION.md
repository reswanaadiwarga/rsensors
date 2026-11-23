# Verification matrix

This file records what has actually been exercised. It is not a support list.

**Verified** means the named build was run on the POCO X7 Pro / Android 16 /
HyperOS 3 target and behaved as described. A feature that is absent on another
phone is a capability result, not a silent failure: Diagnostics reports the
per-feature state, source and reason.

## Current environment

- Target: POCO X7 Pro (2412DPC0AG), Android 16 / HyperOS 3.
- Current Shizuku: **13.5.4** (`13.5.4.r1049.0e53409`), restored from the official
  GitHub release; server running and Rsensors permission granted.
- Installed app: **0.3.0 (versionCode 3)**, release build signed with the local
  debug key, installed over the debug build on 2026-11-24 with data preserved.
- Shizuku 13.6.0 itself supports Android 16 QPR1, but its UserService fails with
  `LoadedApk.makeApplicationInner` on this HyperOS/MediaTek build. The guide
  explains the uninstall/reinstall/re-pair path to 13.5.4.

## Gates

Current `main` tree:

```bat
gradlew.bat :app:testDebugUnitTest --rerun-tasks
gradlew.bat :app:lintDebug
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

Latest result: **417 JVM tests, 34 suites, 0 failures/errors/skipped**; lint,
assembleDebug and assembleRelease green. Independent acceptance harnesses live in
`tests/acceptance/`.

## Feature matrix

| Feature | No shell | Shizuku | Device evidence |
|---|---|---|---|
| Battery temperature | Yes | cross-check | Verified on target |
| Thermal status (API 29+) | Yes | — | Verified on target |
| System RAM | Yes | — | Verified on target |
| Overlay, pill, card, recording, sessions, snap toggle, Record toggle | Yes | — | Verified on target |
| Diagnostics feature status | Yes | — | Verified on target; the 0.3.0 release smoke reported all eight rows `OK` |
| OEM autostart/Shizuku guidance, Manage apps fallback | Yes | — | Guidance is code/UI reviewed; Manage apps opened the HyperOS app list and returned without a crash |
| CPU/GPU temperatures | No | `dumpsys thermalservice` | Verified on target with 13.5.4; the 0.3.0 release smoke returned live dumpsys temperatures. Parser hardening is JVM-tested |
| Foreground FPS | No | SurfaceFlinger | Verified on target with 13.5.4, including app-switch correction: Settings 60 → Launcher 115 within one poll, idle 0 across 5 polls. The 0.3.0 release smoke returned idle 0; Android 8–14/17 formats and non-13.6/HyperOS focus shapes remain fixture/manual-only |
| Per-process PSS/RSS | No | `dumpsys meminfo` / `ps` | PSS + shell UID table re-verified on 13.5.4: 296-row scan, 1050 numeric UID rows, pager and Diagnostics OK. The 0.3.0 release smoke parsed 267 PSS rows into 81 apps and 267 processes. RSS UI/source verified through a forced shim; normal `ps` fallback not forced |
| Hidden-package flags | No | `dumpsys package <pkg>` | Device: hidden Apps rows showed `Resolving app details…`, then resolved SYSTEM; real dumps matched parser for Settings/Chrome/YouTube/android. Hidden Processes rows remain protected as unresolved. Not re-run in the 0.3.0 release smoke |
| Stop app | No | `am force-stop` + `ps` verification | Stop verified on target with 13.5.4 (historical); post-stop verification is code/JVM-tested. Not re-run in the 0.3.0 release smoke |
| Release build (R8) | No | Shizuku v7 UserService | Verified 2026-11-24: v7 binder, thermal/FPS/meminfo/UID calls, Diagnostics 8/8, process page, no FATAL/ANR. Stop and `dumpsys package` actions were not invoked |
| High-refresh FPS graph (144/165) | No | SurfaceFlinger | Geometry JVM-tested; real 144/165 Hz rendering not verified |

## Not verified

- Android 8–14 and Android 17 SurfaceFlinger formats on real devices.
- OEM behaviour on Samsung, Huawei/HONOR, ColorOS/Oppo/Realme, vivo/iQOO and
  near-AOSP phones; the guidance is conservative, not a support matrix.
- A second physical device; the emulator/API matrix was not run.
- HAL `HAL Ready`/cached/partial thermal dumps and extra sysfs paths
  (`hwmon`, `devfreq`, `kgsl`, Mali/GED, Samsung `gpu_tmu`, `power_supply`).
- The shell `ps` fallback and post-stop verification end-to-end on another
  device; the target's 13.5.4 transport is working, but those paths were not
  re-forced in the latest pass.
- Stop and hidden-package `dumpsys package` actions in the 0.3.0 release build;
  the v7 binder and service class survived R8, but those two user actions were
  not invoked during the release smoke.
- `gfxinfo` / TimeStats FPS fallbacks and period-only
  `deprecate_frame_tracker` rows.
- Real high-refresh graph rendering and TalkBack/link opening on other devices.

## Reading rule

- “Verified” in README/ANDROID means the target only.
- “OK” in Diagnostics means the feature produced a value on the current phone.
- “Unavailable” includes the reason and is the honest answer on unsupported
  hardware, OS versions or shell states.
- Do not convert a target-only result into a claim that a feature works on every
  Android 8+ phone.
