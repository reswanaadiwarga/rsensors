# Rsensors universality research — 2026-11-23

Scope: can Rsensors provide its CPU/GPU/battery/FPS/process-memory/stop features
beyond the tested POCO X7 Pro (Android 16 / HyperOS 3 / Shizuku 13.5.4)? Seven
parallel research threads covered AOSP/Pixel, thermal sensors, FPS, process
stopping, Samsung One UI, Xiaomi/Huawei, and ColorOS/OriginOS. The raw reports
and their full source lists are in
`F:\Project\Rsensors-evidence\research-2026-11-23\`.

This is a research document, not a promise of support. It separates official /
AOSP evidence from community reports and labels the current app gaps.

**Status (2026-11-24):** the T19a–T19d slices below shipped, except for the
fallbacks and probes still listed as open. Statements in the “current app”
sections are the findings as of 2026-11-23; where a later slice fixed one, the
text now says so. The only device-verified combination remains the POCO X7 Pro /
Android 16 / HyperOS / Shizuku 13.5.4 target, and Diagnostics is the per-feature
source of truth.

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
| 1 | Shizuku shell | `dumpsys thermalservice` (Android 10+), sysfs where OEM allows, SurfaceFlinger/gfxinfo FPS, meminfo/PSS, `/proc`, `am force-stop` | Setup + re-arm after reboot on non-root; 13.6.0 UserService failure on some Android 16 OEM builds (verified HyperOS/MediaTek); OEM restrictions |
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
- **At research time (fixed in T19b-4):** the app selected the first/
  lowest-index CPU/GPU zone rather than the hottest. Examples: Pixel 6 34 vs
  36 °C; Xiaomi 56.0 vs 61.3 °C. T19b-4 now aggregates by the hottest zone,
  rejects out-of-range values and maps common OEM aliases. HAL `Ready`/override
  metadata, cached/partial dumps and non-standard `mType` protection remain
  open; Diagnostics cannot yet distinguish not-ready, permission, timeout and
  empty-block failures.

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
- **At research time (addressed in T19b):** `FpsParse` only understood the
  Android 15+ wrapper, so it could report 0 FPS on Android 8–14; it dropped valid
  `desiredPresentTime = 0` rows, missed legacy/hash layers, could show the
  previous app for up to 4 s and clipped the session graph above 120 fps.
  T19b-1 accepts the 8–17 formats, T19b-5 expands the axis, and T19b-6 checks
  focus while the cached layer is drawing. `gfxinfo`/TimeStats fallbacks remain
  unimplemented.
- Shizuku/root is mandatory for another app's FPS; own-app FrameMetrics is the
  only public path.

### Process memory / task-manager detail

- `dumpsys meminfo --oom` is the best single source (~2.1 s on the target):
  PSS, PID, name and AOSP OOM groups. Fallbacks: `dumpsys meminfo <pid>`,
  `ps -A -o PID,RSS,NAME`, `/proc/<pid>/status`, `smaps_rollup` (SELinux may
  deny), `dumpsys activity processes` (cached PSS), `procstats` (historical).
  PSS is not RSS; `ps` RSS is KiB and its page size assumption needs checking
  on 16 KiB-page devices.
- **At research time (fixed in T19b-2/T19b-3):** the parser dropped work-profile
  rows `(pid N) (user 10)`, rejected `kB`/`KB` and kept the previous OOM group on
  an unknown heading. It now tolerates those shapes, falls back to `ps`/RSS when
  meminfo has no PSS rows, and labels the metric and source actually shown.
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
- **Delivered in T19c-1:** OEM critical packages are protected, not only AOSP: Xiaomi
  (`com.miui.securitycenter`, `com.miui.powerkeeper`), Huawei/HONOR
  (`com.huawei.systemmanager`, `com.huawei.powergenie`, HONOR equivalents),
  ColorOS/Oppo/Realme (`com.coloros.safecenter`, `com.coloros.phonemanager`,
  `com.coloros.securitypermission`, `com.coloros.apprecover`,
  `com.coloros.athena`, `com.coloros.gamespace`), vivo/iQOO (`com.vivo.pem`,
  `com.iqoo.secure`, `com.vivo.abe`, `com.vivo.daemonService`,
  `com.vivo.permissionmanager`).
- **At research time (addressed in T19c-3):** hidden `ApplicationInfo` left
  UID/system/persistent flags null, so Apps rows became warnings. T19c-3a adds a
  shell UID table and T19c-3b resolves the selected package's flags through
  `dumpsys package`; no `QUERY_ALL_PACKAGES`. Hidden Processes rows without a
  resolvable package remain protected rather than guessing.
- **Fixed in T19c-2:** `stopPackage` treated exit 0 with benign output as a
  failure and had no post-stop verification. Success now ignores benign output,
  checks `ps` for survivors and reports `ERR still running` when one remains.

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

## Research findings and delivery status

### P0 (portability correctness)

1. **FPS formats — delivered in T19b-1/T19b-5/T19b-6; fallbacks open.**
   `FpsParse` now accepts Android 8–14 plain names and Android 15+
   `RequestedLayerState`, strips hashes, accepts legacy/BLAST `@0` and
   `desiredPresentTime = 0`, follows a changed focus while the cached layer is
   drawing, and expands the graph above 120 fps. `gfxinfo`/TimeStats fallbacks
   and a visible source label remain unimplemented.
2. **Meminfo parser — delivered in T19b-2/T19b-3.**
   `ProcessMemory` tolerates `kB`/`KB`, work-profile `(user N)`, spaced/
   suffixed pids and unknown group headings; it falls back to `ps` RSS and
   labels PSS vs RSS. Per-pid `meminfo` and `/proc` fallbacks were not added.
3. **Thermal — partly delivered in T19b-4.**
   Hottest CPU/GPU aggregation, range rejection and common aliases
   (`AP`/`BAT`/`SUBBAT`, `soc-thermal`, `gpu_tmu`, `kgsl`) are in. HAL
   `Ready`/override/status metadata, cached/partial/SKIN-only dumps and
   sentinel/NaN/unit rules remain open.
4. **Stop — delivered in T19c-1/T19c-2/T19c-3a/T19c-3b.**
   OEM protected packages, the on-demand package-facts resolver, shell UIDs,
   post-stop `ps` verification and benign-output handling are in. `pidof`/
   `dumpsys package` cross-checks and advanced kill/disable tiers were not
   added.
5. **Sysfs — open.**
   `SysfsSource` still probes a narrow set of paths; hwmon/devfreq/kgsl/Mali/
   GED/Samsung `gpu_tmu`/power_supply probing and a Shizuku sysfs reader are
   not implemented.

### P1 (UX/portability)

- **Delivered in T19a:** `DiagnosticsActivity` has the per-feature state,
  source and reason for battery, thermal status, CPU/GPU, board, FPS, RAM,
  process memory and Stop. HAL ready state and per-feature permission/timeout
  detail remain incomplete.
- **Delivered in T19d-1/T19d-2:** OEM-aware autostart/Shizuku copy for the
  named families, and the Tier 0 `Manage apps` route with manual Force stop
  guidance. The OEM labels and behaviour remain device-unverified.
- **Open:** API 30+ `getThermalHeadroom()` as a normalized secondary severity;
  runner timeouts/binder-size guard/capability probe; HOME/IME `<queries>`
  entries (only the Shizuku query is present).
- **Delivered:** Shizuku guide variants include Samsung Auto Blocker, ColorOS
  permission monitoring, vivo rebind, Huawei USB and the 13.5.4 downgrade;
  the docs now state HarmonyOS NEXT is unsupported and LADB is not a route.

### P2 (advanced, not universal)

- Root/device-owner tier documentation and optional per-process kill/disable
  actions behind explicit advanced confirmation.
- CPU load/frequency/GPU busy `PerfSnapshot`; Shizuku sysfs; high-refresh graph.
- Emulator/API fixtures and a second physical device (Pixel/Samsung/OnePlus).

## T19 plan status

- **T19a delivered:** tiered Diagnostics with source/state/reason; HAL/sysfs
  sub-probes remain partial.
- **T19b delivered except** `gfxinfo`/TimeStats fallbacks, HAL
  Ready/cached/partial metadata and the wider sysfs probe list.
- **T19c delivered:** OEM denylist, shell resolver, UID enrichment and post-stop
  verification. The manual Settings route shipped later as T19d-2; advanced
  kill/disable tiers were deliberately left out.
- **T19d delivered:** brand-aware guidance, compatibility notes, Manage apps
  and the 13.5.4 setup path; only the target phone has device evidence.
- **T19e partial:** 417 JVM tests and independent acceptance harnesses ran; the
  emulator/API matrix and a second physical device were not run.
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
  for the TimeStats probe; `--timestats` remains device-test pending. The manual
  Manage apps route was later device-checked in T19d-2. Some OEM details may
  change with One UI 8.x, HyperOS 3.x, ColorOS 16.x, OriginOS 6.x and HarmonyOS
  NEXT.
- Community reports are labelled as such in the raw reports; treat them as
  hypotheses until a device probe confirms.
- The app has no `INTERNET`; this research does not propose adding it or
  LADB/libadb.
- This document records research and the delivery status of T19; it does not
  claim the app supports every row. The only verified combination remains POCO
  X7 Pro / Android 16 / HyperOS 3 / Shizuku 13.5.4.
