# Rsensors universality research — 2026-11-23

Scope: can Rsensors provide its CPU/GPU/battery/FPS/process-memory/stop features
beyond the tested POCO X7 Pro (Android 16 / HyperOS 3 / Shizuku 13.5.4)? Seven
parallel research threads covered AOSP/Pixel, thermal sensors, FPS, process
stopping, Samsung One UI, Xiaomi/Huawei, and ColorOS/OriginOS. The raw reports
and their full source lists are in
`F:\Project\Rsensors-evidence\research-2026-11-23\`.

This is a research document, not a promise of support. It separates official /
AOSP evidence from community reports and labels the current app gaps.

## Executive answer

**There is no universal, permissionless way for a normal third-party app to
read CPU/GPU temperatures, another app's FPS, per-process PSS, or
programmatically force-stop another app.** The only genuinely universal tier
is:

- battery temperature (`ACTION_BATTERY_CHANGED` / `BatteryManager`);
- thermal status (`PowerManager.getCurrentThermalStatus()`, API 29+) and
  normalized headroom (`PowerManager.getThermalHeadroom()`, API 30+);
- system RAM (`ActivityManager.MemoryInfo`);
- the app's own process memory and own-window frame metrics;
- overlay, notifications, recording, sessions and settings;
- a **user-mediated** Settings Force stop for a package the app can name
  (`ACTION_APPLICATION_DETAILS_SETTINGS`; no shell, but the user taps the
  button — it is not a programmatic stop).

Everything else is a **capability**, not a guarantee:

- **Shell/Shizuku/root** for `dumpsys thermalservice`, SurfaceFlinger/gfxinfo,
  `dumpsys meminfo`, `/proc`, and `am force-stop`.
- **Device-owner** `HardwarePropertiesManager` for real CPU/GPU/battery/skin °C
  on a provisioned/factory-reset device, but not FPS, process memory or stop.
- **Root** for unrestricted `/proc`/sysfs, per-process `kill`, `pm disable`.
- **OEM-specific** behaviour, sleep/autostart managers and protected packages.

Shizuku is the practical tier 1. LADB/local ADB is **not** a credible second
route (terminal app, Android 11+ wireless debugging, `INTERNET`, fragile
pairing, explicitly incompatible with Shizuku; embedding libadb would be a
rewrite and violates the no-`INTERNET` rule). HarmonyOS NEXT has no Android
runtime and cannot run this APK. HarmonyOS 2–4 / EMUI / MagicOS are
Android-based and can run it, subject to Huawei restrictions.

**Most universal:** battery °C, thermal status/headroom, system RAM, own-app
data.
**Always shell/root for other apps:** FPS, per-process memory, programmatic
stop (the Settings Force-stop route is user-mediated).
**Usually shell/root:** CPU/GPU temperatures (sysfs/HAL/device-owner/OEM).

## Tier model

| Tier | Access | What works | Cost / caveat |
|---|---|---|---|
| 0 | No special permission | Battery °C, thermal status (29+), thermal headroom (30+), system RAM, own-process memory, own-window frames, visible package info, overlay/recording/sessions, user-mediated Settings Force stop | Universal; the only tier that needs no shell |
| 0.5 | Device owner | CPU/GPU/battery/skin °C, thresholds, CPU usage, fans via `HardwarePropertiesManager` | Needs factory-reset/unprovisioned device; no FPS/memory/stop; niche |
| 1 | Shizuku shell | `dumpsys thermalservice` (Android 10+), sysfs where OEM allows, SurfaceFlinger/gfxinfo FPS, meminfo/PSS, `/proc`, `am force-stop` | Setup + re-arm after reboot on non-root; 13.6.0 UserService regression on Android 16; OEM restrictions |
| 1b | Local ADB/LADB | Could run shell commands | Not credible as an embedded route: Android 11+, `INTERNET`, pairing, incompatible with Shizuku, libadb rewrite; not recommended |
| 2 | Root | Full sysfs/proc, per-process `kill`, `pm disable`, restricted `smaps_rollup` | Security/integrity cost; not universal |
| OEM | Vendor services | Some sensors/management | No public third-party API; needs probes/copy |

Rsensors today is roughly Tier 0 UI + Tier 1 Shizuku for temperatures, FPS,
process memory and stop. That is the right shape; the gaps are parser breadth,
capability probing and OEM policy, not a missing permission.

## Feature matrix

| Feature | No permission | Shell/Shizuku | Root / device owner | Universal? |
|---|---|---|---|---|
| Battery °C | `EXTRA_TEMPERATURE` / tenths °C | `dumpsys battery` cross-check | — | **Yes** |
| Thermal status | `PowerManager` 0–6, API 29+ | `dumpsys thermalservice` | device owner | Yes (29+) |
| Thermal headroom | `getThermalHeadroom()`, API 30+, normalized; `NaN` if unsupported | — | device owner | Yes (30+) |
| System RAM | `ActivityManager.MemoryInfo` | — | — | **Yes** |
| Own-process memory | `Debug.MemoryInfo` | — | — | **Yes** |
| Own-app FPS | `FrameMetrics` / `Choreographer` | — | — | **Yes (own app)** |
| Other-app FPS | **No public API** | SurfaceFlinger `--latency`, `gfxinfo framestats`; FrameTimeline/Perfetto | root | **No** |
| CPU/GPU °C | **No public API** (`IThermalService` is `DEVICE_POWER`) | `dumpsys thermalservice`, sysfs if allowed | device owner / root / OEM | **No** |
| CPU load/freq | cpufreq often app-readable | `/proc/stat`, `dumpsys cpuinfo`, `top` | root | Load: no; freq: partly |
| GPU load/busy | **No public API** | vendor sysfs, OEM | root | **No** |
| Per-process PSS | own process only | `dumpsys meminfo --oom`, `ps`, `/proc` | root/smaps | **No (other apps)** |
| Stop another app | **No programmatic**; user-mediated `ACTION_APPLICATION_DETAILS_SETTINGS` → Settings Force stop (manual; needs the package name) | `am force-stop` | root / `pm disable-user` | **No programmatic; manual fallback** |
| Permanent disable | No | `pm disable-user` (shell) | `pm disable` (root) | **No** |
| Overlay/FGS/notification/recording | Standard permissions | — | — | Mostly; OEM restrictions |
| Boot autostart | `BOOT_COMPLETED` + permission | — | — | OEM policy |
| Package labels/flags | visible only | `cmd package list packages -U`, `dumpsys package`, `ps` | root | Shell resolver needed |

### Thermal detail

- `dumpsys thermalservice` is Android 10+ and shell-only. The modern block is
  `Current temperatures from HAL`; it is present only when `HAL Ready: true`.
  `Cached temperatures` may be the only data; partial dumps (SKIN/SOC only) and
  no GPU sensor are common. `mType`/`mName` semantics: 0 CPU, 1 GPU, 2 BATTERY,
  3 SKIN, 9 NPU, 10 TPU, 13 SOC; 6–8 are mV/mA/%, not °C. Samsung can report
  `AP/BAT/LRB/...` with no GPU; Kirin can use `soc-thermal`; a HAL value may be
  guard-banded rather than the true temperature.
- `PowerManager.getThermalHeadroom(forecastSeconds)` is API 30+ and returns a
  normalized headroom (`NaN` when unsupported), not a temperature. It is a
  cross-chip “distance to throttling” signal for Tier 0; keep the API 29+
  thermal status as the coarse status word.
- Sysfs is not a universal fallback: AOSP denies `sysfs_thermal` to apps and
  often to shell; OEM grants vary. Add `/sys/class/hwmon`, `/sys/class/devfreq`,
  `/sys/class/kgsl`, Mali/GED, Samsung `gpu_tmu`, `power_supply`; probe every
  read. `/proc/stat`, `dumpsys cpuinfo` and GPU load nodes are shell-only.
- **Current accuracy bug:** the app selects the first/lowest-index CPU/GPU zone
  rather than the hottest. Examples: Pixel 6 34 vs 36 °C; Xiaomi 56.0 vs
  61.3 °C. The HAL parser also lacks `HAL Ready`/override/status metadata,
  range/sentinel rules and non-standard `mType` protection; Diagnostics cannot
  distinguish not-ready, permission, timeout and empty-block failures.

### FPS detail

- `dumpsys SurfaceFlinger --latency <layer>` is still present in AOSP 16/main
  and is the best per-app source, but it is shell/root only and undocumented.
  Android 15 changed `--list` to `RequestedLayerState{...}`, Android 17 adds
  hash prefixes, and Android 16's `deprecate_frame_tracker` can make rows
  period-only. `SurfaceView[...]@0(BLAST)` and legacy `SurfaceView - ...`
  naming must be accepted.
- `gfxinfo <pkg> framestats` works for other apps as shell, but only sees the
  app's HWUI pipeline (not games/SurfaceView/video); parse columns by name
  (16 → 24 across Android 10–16). AOSP TimeStats (`--timestats -enable/-dump/
  -clear/-disable`) is global and tracks per-layer stats; Google's games docs
  describe **per-layer averageFPS**, while the FPS research initially called it
  display-level. Treat it as a candidate fallback after `--latency`/`gfxinfo`,
  not a 2 s source; do not leave global tracking enabled permanently. The
  focused-layer average and overhead need a device test (pending; no phone
  attached during research). FrameTimeline/Perfetto remain diagnosis tools.
- **Current P0 bug:** `FpsParse` only understands the Android 15+ wrapper, so
  it can report 0 FPS on Android 8–14; it drops valid `desiredPresentTime = 0`
  rows; misses legacy/hash layers; can show the previous app for up to 4 s; has
  no fallback/probe; the session graph clips above 120 fps.
- Shizuku/root is mandatory for another app's FPS; own-app FrameMetrics is the
  only public path.

### Process memory / task-manager detail

- `dumpsys meminfo --oom` is the best single source (~2.1 s on the target):
  PSS, PID, name and AOSP OOM groups. Fallbacks: `dumpsys meminfo <pid>`,
  `ps -A -o PID,RSS,NAME`, `/proc/<pid>/status`, `smaps_rollup` (SELinux may
  deny), `dumpsys activity processes` (cached PSS), `procstats` (historical).
  PSS is not RSS; `ps` RSS is KiB and its page size assumption needs checking
  on 16 KiB-page devices.
- **Current parser is AOSP-pretty-only:** drops work-profile rows
  `(pid N) (user 10)`, rejects `kB`/`KB`, and keeps the previous OOM group on
  an unknown heading. It has no `ps`/RSS fallback or source/metric label.
- **Robust stop tiers:**
  - **Primary:** `am force-stop --user <target>` (package-wide, stopped state,
    alarm/job cleanup; shell only; defaults to all users; skips persistent
    processes and silently no-ops for protected/invalid packages). Verify the
    result with `pidof`/`ps`/`dumpsys package`; exit 0 is not proof.
  - **Background reclaim:** `am kill` / `kill-all` / `killBackgroundProcesses`
    (background/cached only; no stopped state; restart allowed; third-party
    `killBackgroundProcesses` is own-UID on Android 14+). Not a task-manager
    stop.
  - **Advanced per-process:** `kill -9 <pid>` (Shizuku/root; SELinux blocks
    non-appdomain targets; no stopped state; likely restart). Never present it
    as a guaranteed single-process stop.
  - **Advanced permanent:** `pm disable-user` (shell; persists; can break
    system/Device Care; ColorOS may reset; vivo may throw `SecurityException`;
    `pm disable` needs root). `pm suspend` is reversible. There is no
    `cmd package set-stopped <pkg> true` in AOSP 16.
- **Policy needs OEM critical packages**, not only AOSP: Xiaomi
  (`com.miui.securitycenter`, `com.miui.powerkeeper`), Huawei/HONOR
  (`com.huawei.systemmanager`, `com.huawei.powergenie`, HONOR equivalents),
  ColorOS/Oppo/Realme (`com.coloros.safecenter`, `com.coloros.phonemanager`,
  `com.coloros.securitypermission`, `com.coloros.apprecover`,
  `com.coloros.athena`, `com.coloros.gamespace`), vivo/iQOO (`com.vivo.pem`,
  `com.iqoo.secure`, `com.vivo.abe`, `com.vivo.daemonService`,
  `com.vivo.permissionmanager`).
- **Package visibility is a stop-flow bug:** hidden `ApplicationInfo` leaves
  UID/system/persistent flags null; Apps rows become warnings, Processes rows
  protected. The page already has shell; resolve UID/flags/labels through
  `cmd package list packages -U`, `dumpsys package` or `ps` instead of
  `QUERY_ALL_PACKAGES`.
- The current app's `stopPackage` treats exit 0 with benign output as a
  failure and has no post-stop verification; both need fixing.

## OEM matrix

| Platform | Thermal/CPU/GPU | FPS | Memory/stop | Platform notes |
|---|---|---|---|---|
| **Pixel/AOSP** | HAL names; Tensor; sysfs restricted; probe | `--latency`/`gfxinfo`; Android 16 flags | AOSP meminfo/am | Easiest baseline; no OEM manager |
| **Samsung One UI/Knox** | HAL aliases (`AP/BAT/LRB`), sometimes no GPU; sysfs denied; `gpu_tmu` | parser/hash/period-only; `--timestats` | AOSP meminfo; `am force-stop` + post-check | Sleeping apps restrict FGS; Auto Blocker blocks sideload/USB; package visibility |
| **Xiaomi HyperOS/MIUI** | HAL works; sysfs dead; Shizuku 13.6 regression | `RequestedLayerState`/legacy; Game Turbo | AOSP meminfo; force-stop may restart; OEM packages | Autostart + Background autostart + battery + Recents lock; Install via USB |
| **Huawei/HONOR (Android)** | probe; Kirin `soc-thermal`; HAL support uncertain | raw layer names; `--latency` may be empty | meminfo AOSP; force-stop; OEM managers | No wireless debugging on many; USB Shizuku; no GMS; App Launch |
| **HarmonyOS NEXT** | — | — | — | **No Android runtime/APK; HAP-only; HDC; no dumpsys/overlay/FGS/boot/Shizuku** |
| **Oppo/OnePlus/Realme** | `dumpsys`; Qualcomm CPU/GPU; MediaTek may duplicate die sensor | hash/`@0(BLAST)`; `--timestats` | meminfo; force-stop; auto-launch may restart; OEM packages | Shizuku “Permission monitoring” / disable system optimization |
| **Vivo/iQOO** | probe-dependent; no public sensor names | shell route; probe | meminfo; force-stop; pm disable can throw | Shizuku UserService instability/rebind; Autostart |
| **Motorola/Sony/Asus/Nothing** | near-AOSP; probe | near-AOSP | near-AOSP | OEM game/autostart managers may still interfere |

## Current app — prioritised gaps

### P0 (portability correctness)

1. `app/src/main/java/dev/rsensors/android/core/FpsParse.kt` — accept Android
   8–14 plain names and Android 15+ `RequestedLayerState`, strip hashes, accept
   legacy/BLAST `@0`, allow `desiredPresentTime = 0`, avoid previous-app
   reuse, add `gfxinfo`/`--timestats` fallbacks and a source label; consider a
   dynamic session-graph ceiling above 120 fps.
2. `.../core/ProcessMemory.kt` — tolerate `kB`/`KB`, work-profile `(user N)`,
   spaced/suffixed pids and unknown group headings; add `meminfo <pid>`,
   `ps`/RSS and `/proc` fallbacks; label PSS vs RSS.
3. `.../core/DumpsysParser.kt` + `.../core/ZoneMap.kt` — hottest CPU/GPU
   aggregation, HAL `ready`/`override`/`status`, cached/partial/SKIN-only
   dumps, range/sentinel/NaN/unit rules, Samsung aliases/`SUBBAT` rejection,
   Kirin `soc-thermal`.
4. `.../core/StopPolicy.kt` + `.../shizuku/DumpsysUserService.kt` +
   `.../overlay/OverlayService.kt` — OEM protected packages, package
   visibility shell resolver, post-stop `pidof`/`ps`/`dumpsys package`
   verification, benign-output handling, no “stopped” claim on a no-op.
5. `.../source/SysfsSource.kt` — probe hwmon/devfreq/kgsl/Mali/GED/Samsung
   `gpu_tmu`/power_supply; a Shizuku sysfs reader is a possible tier-1
   enhancement.

### P1 (UX/portability)

- `.../ui/DiagnosticsActivity.kt` — capability matrix: per feature tier,
  source, ready/permission/timeout/empty state, PSS/RSS, HAL ready.
- `.../ui/MainActivity.kt` + `.../ui/ShizukuHelpActivity.kt` — OEM-aware
  autostart/Shizuku copy (HyperOS background autostart, Samsung Sleeping apps,
  ColorOS Auto launch, vivo Autostart, Huawei App Launch) delivered in T19d-1;
  the OEM labels and behaviour remain device-unverified. A Tier 0 “Manage apps /
  Open app info” list with manual Force stop guidance is still open.
- `.../source/ThermalStatusSource.kt` / `core/Reading.kt` — add the API 30+
  `getThermalHeadroom()` value as a normalized secondary severity, `NaN`
  handling, and a Diagnostics label; keep the API 29+ status.
- `.../ui/ShizukuHelpActivity.kt` — OEM setup variants (Samsung Auto Blocker,
  ColorOS permission monitoring, vivo rebind, Huawei USB, 13.5.4 fallback).
- `.../source/ShizukuDumpsysRunner.kt` — timeouts, binder-size guard,
  capability probe, version/error clarity.
- `app/src/main/AndroidManifest.xml` — add HOME/IME/Shizuku `<queries>`, no
  `QUERY_ALL_PACKAGES`, keep no `INTERNET`.
- Docs — correct the `HardwarePropertiesManager` DEVICE_POWER/device-owner
  wording; document HarmonyOS NEXT unsupported; document LADB not a route.

### P2 (advanced, not universal)

- Root/device-owner tier documentation and optional per-process kill/disable
  actions behind explicit advanced confirmation.
- CPU load/frequency/GPU busy `PerfSnapshot`; Shizuku sysfs; high-refresh graph.
- Emulator/API fixtures and a second physical device (Pixel/Samsung/OnePlus).

## Recommended T19 plan

- **T19a Capability probe + Diagnostics:** a cached, tiered probe per feature
  (battery/status/headroom/RAM/own, HAL, sysfs, FPS, meminfo, stop, package
  resolver) with source/quality/error; show “not available on this phone”
  instead of guessing.
- **T19b Parser hardening:** FPS multi-format + `gfxinfo`/TimeStats (device
  test first); meminfo tolerant + `ps`/RSS fallback; thermal hottest/HAL
  metadata/cached/aliases/sentinels; sysfs probe; fixtures for Pixel/Tensor/
  Samsung A52/A7/HAL-false/Xiaomi/Redmi/Huawei/Qualcomm/Mali/GED/hwmon and
  Android 8–17.
- **T19c Stop robustness:** OEM denylist, shell package resolver, post-stop
  verification, benign-output and timeout handling, explicit advanced
  kill/disable tiers, no fake single-process guarantee; Tier 0 manual Settings
  Force-stop fallback with a visible package list and no result claim.
- **T19d OEM UX:** brand-aware autostart/FGS guidance; OEM Shizuku setup;
  HarmonyOS NEXT unsupported message; compatibility matrix in README; Manage
  apps / app-info route for no-Shizuku devices.
- **T19e Tests:** emulator API matrix + one non-Xiaomi device; red-first;
  mutation checks; no test edits to acceptance.
- **Out of scope:** a universal CPU/GPU API (does not exist), HarmonyOS NEXT,
  LADB/libadb, `QUERY_ALL_PACKAGES`, mandatory root, vendor SDKs.

## Evidence

Seven research reports (full source lists in the files):

| Report | Sources |
|---|---|
| `research-aosp-platform.md` — AOSP/Pixel, no-permission tier, LADB/Shizuku/root | 52 |
| `research-sensors.md` — thermal/CPU/GPU/battery, SoCs, sysfs | 72 |
| `research-fps.md` — SurfaceFlinger/gfxinfo/FrameTimeline, current gap table | 55 |
| `research-process-stop.md` — meminfo, force-stop/kill/disable, task managers | 50 |
| `research-samsung-oneui.md` — One UI/Knox, Device Care, FGS, Auto Blocker | 58 |
| `research-xiaomi-huawei.md` — HyperOS/MIUI, HarmonyOS/EMUI/MagicOS | 90 |
| `research-bbk.md` — ColorOS/OxygenOS/Realme UI, OriginOS/Funtouch | 60 |

Raw reports: `F:\Project\Rsensors-evidence\research-2026-11-23\`.

Key official/AOSP anchors used across the reports: Android Developers
(`BatteryManager`, `PowerManager`, `ActivityManager`, `Debug.MemoryInfo`,
`FrameMetrics`), AOSP `ThermalService`/`SurfaceFlinger`/`ActivityManagerService`
source, Android CDD, Shizuku upstream issues #2629/#2633, LADB/libadb, Samsung
Knox/Device Care docs, Xiaomi HyperOS/MIUI docs, Huawei/HONOR HarmonyOS/EMUI/
MagicOS docs, Oppo/OnePlus/Realme and vivo/iQOO support docs. Full URLs and
access dates are in each raw report.

## Limits

- No device lab: Samsung, BBK and Huawei statements are source/community based;
  only the POCO/HyperOS target has device evidence. The phone was not attached
  for the final TimeStats/settings/manual-stop probes; `--timestats` is
  explicitly marked “device test pending.” Some OEM details may change with
  One UI 8.x, HyperOS 3.x, ColorOS 16.x, OriginOS 6.x and HarmonyOS NEXT.
- Community reports are labelled as such in the raw reports; treat them as
  hypotheses until a device probe confirms.
- The app has no `INTERNET`; this research does not propose adding it or
  LADB/libadb.
- This document proposes work; it does not claim the app currently supports
  every row. The only verified combination remains POCO X7 Pro / Android 16 /
  HyperOS 3 / Shizuku 13.5.4.
