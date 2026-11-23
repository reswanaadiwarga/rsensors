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
- Shizuku 13.6.0 itself supports Android 16 QPR1, but its UserService fails with
  `LoadedApk.makeApplicationInner` on this HyperOS/MediaTek build. The guide
  explains the uninstall/reinstall/re-pair path to 13.5.4.

## Gates

Current `main` tree:

```bat
gradlew.bat :app:testDebugUnitTest --rerun-tasks
gradlew.bat :app:lintDebug
gradlew.bat :app:assembleDebug
```

Latest result: **401 JVM tests, 33 suites, 0 failures/errors/skipped**; lint and
assemble green. Independent acceptance harnesses live in `tests/acceptance/`.

## Feature matrix

| Feature | No shell | Shizuku | Device evidence |
|---|---|---|---|
| Battery temperature | Yes | cross-check | Verified on target |
| Thermal status (API 29+) | Yes | — | Verified on target |
| System RAM | Yes | — | Verified on target |
| Overlay, pill, card, recording, sessions, snap toggle, Record toggle | Yes | — | Verified on target |
| Diagnostics feature status | Yes | — | Verified on target (forced-RSS shim for the RSS path) |
| OEM autostart/Shizuku guidance, Manage apps fallback | Yes | — | Guidance is code/UI reviewed; Manage apps opened the HyperOS app list and returned without a crash |
| CPU/GPU temperatures | No | `dumpsys thermalservice` | Verified on target with 13.5.4 (historical); transport restored, not re-run in the latest pass. Parser hardening is JVM-tested |
| Foreground FPS | No | SurfaceFlinger | Verified on target with 13.5.4 (historical 0/53/60/120 fps); transport restored, not re-run in the latest pass. Android 8–14/17 formats are fixture-only |
| Per-process PSS/RSS | No | `dumpsys meminfo` / `ps` | PSS + shell UID table re-verified on 13.5.4: 296-row scan, 1050 numeric UID rows, pager and Diagnostics OK. RSS UI/source verified through a forced shim; normal `ps` fallback not forced |
| Stop app | No | `am force-stop` + `ps` verification | Stop verified on target with 13.5.4 (historical); post-stop verification is code/JVM-tested, normal transport restored but not re-run in the latest pass |
| High-refresh FPS graph (144/165) | No | SurfaceFlinger | Geometry JVM-tested; real 144/165 Hz rendering not verified |

## Not verified

- Android 8–14 and Android 17 SurfaceFlinger formats on real devices.
- OEM behaviour on Samsung, Huawei/HONOR, ColorOS/Oppo/Realme, vivo/iQOO and
  near-AOSP phones; the guidance is conservative, not a support matrix.
- A second physical device; the emulator/API matrix was not run.
- HAL `HAL Ready`/cached/partial thermal dumps and extra sysfs paths
  (`hwmon`, `devfreq`, `kgsl`, Mali/GED, Samsung `gpu_tmu`, `power_supply`).
- The shell `ps` fallback, package resolver and post-stop verification on the
  normal Shizuku transport while 13.6.0 is installed.
- `gfxinfo` / TimeStats FPS fallbacks, previous-app cache invalidation and
  period-only `deprecate_frame_tracker` rows.
- Real high-refresh graph rendering and TalkBack/link opening on other devices.

## Reading rule

- “Verified” in README/ANDROID means the target only.
- “OK” in Diagnostics means the feature produced a value on the current phone.
- “Unavailable” includes the reason and is the honest answer on unsupported
  hardware, OS versions or shell states.
- Do not convert a target-only result into a claim that a feature works on every
  Android 8+ phone.
