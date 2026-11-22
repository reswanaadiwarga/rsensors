# Rsensors — design and task log

Development design and task log. Public overview: [README.md](README.md);
architecture summary: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Development evidence was archived outside this repository and is not bundled.

Status: **T0-T17 complete.** The RAM pill and process-memory page are committed
on `feat/process-ram` and installed/device-verified on the target (250 tests).
The 0.2.0 release remains the published baseline and does not include these
changes; nothing is merged or pushed yet. This file is the source of truth for
the app; change it before changing code.

## 1. Goal

An Android port of the Rsensors desktop widget: an always-on-top floating
overlay that shows CPU and GPU temperatures with a green / amber / red language
and staleness behaviour, sourced from whatever the phone actually exposes.

Non-goals: home-screen widget, Quick Settings tile, Play Store release,
history graphs, fan control, undervolting, per-app stats.

## 2. Reality check

Android does not let a sandboxed app read CPU or GPU temperatures. What
exists:

| Data | Stock app | Shell / Shizuku |
|---|---|---|
| CPU °C | No public API. `HardwarePropertiesManager` needs `DEVICE_POWER` + device-owner provisioning | `dumpsys thermalservice` (confirmed on the target); sysfs only where the OEM allows it |
| GPU °C | Device-dependent; `/sys/class/kgsl/kgsl-3d0/temp` on some Qualcomm | `dumpsys thermalservice`; vendor nodes |
| Battery °C | `BatteryManager.EXTRA_TEMPERATURE` (tenths °C) | Same |
| Thermal pressure | `PowerManager.getCurrentThermalStatus()` API 29+, coarse 0–6 | Same, plus real temps |
| CPU load | `/proc/stat` is blocked for apps on modern Android | `/proc/stat` (confirmed readable as shell) |
| CPU frequency | Usually `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` | Same |
| GPU busy % | `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` on Qualcomm | Same |

On HyperOS 3 / Android 16 (the target phone) the sysfs thermal nodes are
denied even to the shell user. `dumpsys thermalservice` is the only working
route there — see §10.

Consequences baked into this plan:

1. A source chain, not a single source. Each source contributes the fields it
   can; `SourceChain` merges them into one `Reading`.
2. Missing values render as `—`, never as another sensor.
3. A diagnostics screen is a first-class feature, not a debug extra. The
   device decides what works.
4. The overlay is for personal/sideload use. Overlay + foreground service is
   hostile to Play Store review; do not plan for it.

### 2.1 The three tiers

Root is not required. There are three tiers, and only the last one carries a
real cost:

| Tier | Setup | Gets you | Cost |
|---|---|---|---|
| 0 — stock | none | battery °C, thermal status word, GPU busy %, CPU freqs | no CPU/GPU °C |
| 1 — Shizuku | one-time ADB start (or on-device wireless debugging); re-arm after reboot | shell-level reads: primarily `dumpsys thermalservice`; sysfs and vendor nodes where the OEM allows them | ~1 min after each reboot, no root |
| 2 — root | unlock bootloader (wipes data), Magisk/KernelSU | what tier 1 gets, plus `/proc/stat` | banking / Play Integrity arms race, OTA breakage |

Shizuku starts `app_process` as the shell UID over ADB. It does not unlock the
bootloader, does not touch the system partition, and does not trip Play
Integrity. Banking apps keep working. Root is the only tier that threatens
them, and it is not worth it for a temperature widget.

Even at tier 2, many phones expose only a handful of aggregate thermal zones
and no per-core sensors, so the full card layout is a best-effort target, not
a guarantee.

### 2.2 Design non-negotiables

Generic Shizuku overlay apps fail in predictable ways. These are not
nice-to-haves:

- One `TYPE_APPLICATION_OVERLAY` window. No accessibility service, no screen
  capture, no full-screen tricks.
- No `INTERNET` permission. Sensor data never leaves the phone.
- A missing sensor renders `—`. Never relabel battery temperature as CPU.
- Diagnostics lists every zone with its raw value and read status, plus a
  manual assignment for the ones the heuristic gets wrong.
- Screen off stops polling; screen on polls immediately.
- Battery cost is measured in T6 with Battery Historian, not claimed.
- Small APK, R8 on, no ads, no analytics, no "boost" buttons.

## 3. Product behaviour

- **Collapsed pill** (default): `CPU/GPU 41°  BAT 34°  FPS 60  RAM 8.6G`, plus
  a small source dot. CPU/GPU is the hotter of the two SoC sensors, coloured by
  the worse of their levels; BAT is a first-class metric, not only a fallback;
  RAM is used-only decimal GB in blue.
  Palette: dark background, green / amber / red
  digits, dim when stale (15 s) and `—` when unknown. The dot is green for
  dumpsys/Shizuku, amber for sysfs, and gray for the battery/status fallback.
- **Expanded card** (tap): individual CPU, GPU and battery rows, each with its
  current value and the 2-minute `max`/`min`; thermal status, the FPS, a system
  RAM row (`used / total` in one-decimal GB) with a compact usage bar under it,
  Refresh, Diagnostics, Stop monitoring (formerly "Stop overlay"), and a
  2-minute sparkline tagged `GRAPH · 2 MIN` with a cycle
  button selecting CPU+GPU / CPU / GPU / BAT / FPS (battery in blue, FPS in
  purple on a fixed 0–120 scale).
  Four toggle chips (CPU/GPU / BAT / FPS / RAM) choose what the pill and
  notification show; at least one stays on. The card keeps its detail rows. The
  graph selector is independent of the chips, and the in-memory history always
  records CPU, GPU, BAT and FPS regardless of either choice. The wordy source/age line is gone — the source
  lives in Diagnostics. On the target phone the per-core list is empty (no
  sensors); show `per-core n/a` instead of an empty section.
- **Process memory** (expanded card → RAM row): the RAM row is a button with a
  `›`; it opens a second page in the same overlay. The page lists the top
  processes by PSS from `dumpsys meminfo --oom`, eight per page, with an
  `Apps` / `Processes` toggle: `Apps` groups by package and hides the
  `Native`/`System` OOM groups, `Processes` shows every row. The header has
  `Back` and `Refresh`; the summary keeps the card's system `used / total` and
  the footer shows `N apps/processes`, `Updated …` and `‹ 1–8 of N ›`. The dump
  is a snapshot taken when the page opens and on Refresh (~2 s), never part of
  the 2 s/5 s poll; the pill keeps updating behind it. The card grows to 280 dp
  and returns to 220 dp on Back. No new permissions. Without Shizuku, or in
  demo mode, the page shows an unavailable state and never invents data.
- **FPS**: the foreground app's frame rate, measured through Shizuku's
  UserService with `dumpsys SurfaceFlinger --list/--latency` for the top
  activity's layer — never the overlay's own frames. Without Shizuku the value
  is `—`. Colours: below 30 red, below 50 amber, otherwise green.
- **Recording**: a Record switch starts a session; every poll appends one CSV
  row including the FPS (time, CPU/max, cores, GPU/hot, battery, thermal
  status, board temps, source, FPS). Stop or overlay stop flushes and closes
  the file. Screen-off pauses polling, so a recording has a gap while the phone
  sleeps; that gap is visible in the graph.
- **Shizuku help**: a dedicated setup/troubleshooting screen with live status
  (installed/running/permission/version, wireless debugging if readable). The
  topics are collapsible cards — First-time setup, Start keeps searching,
  Pairing lost / full reset, After a reboot, BAT-only, Why, If nothing works —
  so the page reads as a menu, not a wall of text. It is reachable from the
  main screen, from the card when Shizuku is down, and from the degraded
  notification.
- **Sessions**: a list of saved sessions (start, duration, samples) and a
  full-session graph with CPU/GPU/BAT/SOC/SKIN plus FPS on its own right-hand
  0–120 axis. Long-press selects sessions for deletion (tap toggles in
  selection mode, Cancel exits, Delete confirms). The session currently being
  recorded is hidden until it stops. Mouse hover and touch drag show a
  crosshair and a tooltip with the timestamp and values at the nearest sample.
  Tapping a legend chip dims that line and draws it under the highlighted ones;
  re-highlighting moves it to the top. Tapping a table header explains that
  column. Empty or corrupt logs are reported, never crash; v1 files without FPS
  still open, without an FPS chip or column.
- **UX reference**: MIUI Game Turbo's performance monitor — a small
  always-on-top panel with CPU/GPU readings and a mini graph — without the
  game launcher or the vendor lock-in.
- **Drag** anywhere; position and collapsed/expanded state persist. Snap to
  left/right edge on release.
- **Screen off pauses polling**; screen on triggers an immediate poll so the
  first frame is never stale.
- **Poll cadence**: 2 s expanded, 5 s collapsed, paused with screen off.
- **Foreground service** with a silent, low-importance ongoing notification.
  The notification large icon is the rounded badge with the CPU number.
- **Never invent numbers.** If a temperature is unavailable, show `—` or the
  thermal status word (`LIGHT`, `MODERATE`, …), not a guess.

Thresholds:

| Field | warn | hot |
|---|---|---|
| CPU | 75 °C | 85 °C |
| GPU | 70 °C | 83 °C |
| Battery | 42 °C | 45 °C |
| Thermal status | MODERATE | SEVERE+ |

## 4. Architecture

Single Gradle module, packages split by dependency direction.

```
app/src/main/java/dev/rsensors/android/
  core/                     pure Kotlin, zero android imports
    Reading.kt              t (epoch millis), cpu, cpuMax, cores, gpu, gpuHot,
                            battery, thermalStatus, board, sourceName, fps
    Thresholds.kt           level(), thermalLevel(), warn/hot constants
    Format.kt               fmtTemp(), fmtFps(), fmtAge(), tooltip()
    Strip.kt                stripSegments(reading, fps, now, visibility) -> segments + dim
    FpsParse.kt             focus/layer/latency parsing + fresh-window fps (pure)
    Icon.kt                 iconSpec(reading, now) -> label + level
    ZoneMap.kt              thermal-zone type -> Cpu / Gpu / Battery
    BatteryTemperature.kt   battery tenths -> °C
    ThermalStatusMapper.kt  PowerManager int -> ThermalStatus
    DumpsysParser.kt        "Current temperatures from HAL" parser
    ReadingHistory.kt       2-minute ring buffer for the sparkline
    SessionLog.kt           versioned CSV encode/decode for saved sessions
    SessionChart.kt         series bounds/nearest index for the session graph
    ProcessMemory.kt        meminfo PSS parser, app grouping, paging, formatting
  source/
    SensorSource.kt         interface: id, poll(now): Reading?, close()
    SysfsReader.kt          open(path): String?  + listDir(path)
    DirectReader.kt         File-based reader (stock)
    SysfsSource.kt          thermal zones + cpufreq + kgsl (best-effort)
    DumpsysRunner.kt        interface: run(): String? / close()
    DumpsysThermalSource.kt runner + parser -> Reading
    ShizukuDumpsysRunner.kt Shizuku UserService binding (dumpsys + fps + meminfo)
    FpsRunner.kt            interface: runFps(): String? (shell reply)
    MeminfoRunner.kt        interface: runMeminfo(): String? (raw --oom dump)
    BatterySource.kt        sticky ACTION_BATTERY_CHANGED
    ThermalStatusSource.kt  PowerManager thermal status
    DemoSource.kt           synthetic ramps (preview)
    SourceChain.kt          merges sources, records per-source errors
    SessionStore.kt         session files: list/read/write under filesDir
  shizuku/
    DumpsysUserService.kt    shell-side UserService (dumpsys + FPS + meminfo)
  overlay/
    OverlayService.kt       FGS, poller, screen on/off, notification, meminfo scan
    OverlayView.kt          pill + expanded card + process page, classic Views
    ProcessPageView.kt      paged Apps/Processes memory list inside the card
    SparklineView.kt        2-minute CPU/GPU history graph
    SessionRecorder.kt      CSV writer owned by the poller
    OverlayParams.kt        WindowManager.LayoutParams helpers
    OverlayPreferences.kt   SharedPreferences: demo, position, expanded, boot,
                            CPU/GPU/BAT/FPS/RAM visibility
  ui/
    MainActivity.kt         permission flow, start/stop, settings
    SessionsActivity.kt     saved session list
    SessionDetailActivity.kt full-session graph + hover/touch tooltip
    DiagnosticsActivity.kt  zone table, read errors, override picker
    Palette.kt              overlay colours as Int constants
    BadgeIcon.kt            rounded notification large icon
    Theme.kt
app/src/test/java/dev/rsensors/android/
  core/...                  pure logic and its JVM tests
  core/ZoneMapTest.kt
  core/DumpsysParserTest.kt
  core/ReadingHistoryTest.kt
  core/ProcessMemoryTest.kt
  source/DemoSourceTest.kt
  source/DumpsysThermalSourceTest.kt
  source/SysfsParserTest.kt
  source/SysfsSourceTest.kt
  source/SourceChainTest.kt
app/src/test/resources/fixtures/sysfs/...   fake zone trees
app/src/test/resources/fixtures/meminfo/... trimmed --oom dumps
app/src/main/aidl/dev/rsensors/android/shizuku/IDumpsysService.aidl
```

Decisions:

- **Classic Views for the overlay**, Compose only for the two activities.
  Compose inside a `WindowManager` overlay needs manual lifecycle/saved-state
  owners; the pill is a handful of `TextView`s and does not justify that.
- **One reader abstraction** (`SysfsReader`) so direct and Shizuku reads share
  the same paths. Direct is a `File` reader; Shizuku is added without touching
  `SysfsSource` or the mapper.
- **Fixture trees** under `src/test/resources` drive the parser tests. No
  hardware needed for unit tests.
- `SyfsReader.root` is injectable so tests point at fixtures.
- Settings via `SharedPreferences`; no DataStore dependency.

## 5. Source chain

Order of preference for merging (first non-null value wins per field):

1. `DumpsysThermalSource` via Shizuku — HAL-reported CPU/GPU/SOC/BATTERY/SKIN,
   executed in a UserService as the shell UID. The only working route on the
   target phone (§10), and it supplies the canonical names the widget draws.
2. `SysfsSource` via direct file reads — zero-setup on devices that allow it.
3. `BatterySource` — battery temp always contributes.
4. `ThermalStatusSource` — always contributes a status, never a temperature.

A Shizuku-backed sysfs reader is not implemented: `dumpsys` is the privileged
path on the target, and direct sysfs remains for devices that expose it. The
`SysfsReader` interface leaves room for one without changing `SysfsSource`.

`SysfsSource` reads, per poll (best-effort):

```
/sys/class/thermal/thermal_zone*/type + temp
/sys/class/kgsl/kgsl-3d0/temp
```

CPU frequencies and `gpu_busy_percentage` are readable on some devices but are
not part of `Reading` in v1; Diagnostics may show them later.

Parsing rules:

- Thermal-zone values are usually millidegrees. Normalise with
  `abs(v) > 200 → v / 1000`, drop `0`, drop values outside `-40..150`.
- kgsl `temp` is whole degrees and is never treated as millidegrees.
  Thermal-zone values are detected by magnitude (`abs(v) > 200`).
- A mapped thermal GPU zone wins over kgsl; kgsl is only a fallback.
- `gpu_busy_percentage` may be `"12 %"` with a suffix. Parse leniently.
- Read every file defensively; record `EACCES` / `ENOENT` per path for
  diagnostics instead of failing the poll.

On the target phone `ls` and `cat` of `/sys/class/thermal` are both denied to
the shell user, so this source yields nothing and the chain falls through. It
stays in the plan for devices that do expose the nodes.

`DumpsysThermalSource` parses the `Current temperatures from HAL` block, not
`Cached temperatures`:

```
Temperature{mValue=38.351, mType=0, mName=CPU, mStatus=0}
Temperature{mValue=38.351, mType=1, mName=GPU, mStatus=0}
Temperature{mValue=33.5, mType=2, mName=BATTERY, mStatus=0}
Temperature{mValue=33.5, mType=3, mName=SKIN, mStatus=0}
```

- Values are already °C; do not apply the millidegree normaliser.
- Prefer `mName`; fall back to `mType` (0 CPU, 1 GPU, 2 BATTERY, 3 SKIN,
  9 NPU, 10 TPU, 13 SOC).
- `cmd thermalservice` has no read command (only override/reset/headroom), so
  the text dump is the interface.
- The block is ~60 lines; parse leniently because OEMs add types such as
  `POWER_AMPLIFIER`.

Zone mapping is a scored match on the lowercased `type` string, seeded with:

| Category | Patterns |
|---|---|
| CPU | `cpu-*`, `cpuss-*`, `cpu_thermal`, `soc_thermal`, `mtktscpu`, `cpu-0-0` |
| GPU | `gpuss-*`, `gpu-*`, `gpu_thermal`, `mtktsgpu`, `gpu0` |
| Battery | `battery`, `mtktsbattery`, `batt` |

Exact aliases score higher than prefix matches. A name that scores CPU but also
mentions `gpu` or `batt` is treated as OTHER rather than guessed. Unknown zones
are shown in Diagnostics and can be assigned manually; the override is stored by
zone `type` string, not index. This is what makes the app usable on an arbitrary
phone without a per-model table. On the target phone the zone metadata is
unreadable, so the mapping is inert there.

## 6. Platform constraints

- `minSdk 26` (overlay type + notification channels), `targetSdk` current.
- Permissions: `SYSTEM_ALERT_WINDOW` (special), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE` (API 34+),
  `POST_NOTIFICATIONS` (API 33+, runtime), `RECEIVE_BOOT_COMPLETED`
  (optional).
- FGS type `specialUse` with
  `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = "temperature overlay".
- Start the service only from the visible activity. Android 12+ forbids
  background FGS starts; Android 15 restricts several FGS types from
  `BOOT_COMPLETED` (`specialUse` is not one of them, but OEM autostart
  policies still bite — Xiaomi, Oppo, Samsung).
- Overlay windows are hidden over some system screens and cannot receive
  touches through secure windows. Expected, not a bug.
- Shizuku's server does not survive a reboot on non-root. The app must check
  `Shizuku.pingBinder()`, show `Shizuku not running` in Diagnostics, and keep
  working on tier 0. The app cannot start the server; MainActivity links to the
  Shizuku app and requests its permission.
- Shizuku 13.6.0's legacy `start.sh` fails on repacked APKs with a compressed
  `librish.so` (`UnsatisfiedLinkError`). Start the server with the official
  command instead:
  `pm_path=$(pm path moe.shizuku.privileged.api) && apk_path=${pm_path#package:} && $(dirname $apk_path)/lib/*/libshizuku.so`
- Shizuku 13.6.0 cannot start UserServices on the target (POCO X7 Pro /
  Android 16): the starter crashes in `LoadedApk.makeApplicationInner` before
  app code runs (upstream #2629 and #2633). Use the 13.5.4 manager/server on the
  target — uninstall + reinstall, then start it with its `start.sh` — until a
  fixed release. The 13.1.5 client API is compatible with both.
- With a secure keyguard, `BOOT_COMPLETED` is dispatched only after the first
  unlock; the overlay starts then. `LOCKED_BOOT_COMPLETED` / directBootAware is
  out of scope: the overlay is hidden on the keyguard and the preferences live
  in credential-protected storage.
- HyperOS blocks `adb install` until **Install via USB** is enabled in
  Developer options and the on-device prompt is accepted. T6's device checks
  depend on it.
- Battery temperature updates arrive with the battery broadcast, roughly
  every 10–30 s. Polling faster does not produce fresher battery data.

## 7. Testing and gates

Gates (must pass before every commit):

```bat
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Test layers:

- **Unit (JVM)**: the pure logic — formatting, thresholds, segments,
  staleness, tooltip, zone scoring, value normalisation, chain merging.
  Expected values are hand-written from this spec, not copied from output.
- **Fixture**: fake sysfs trees for Qualcomm, MediaTek, and Exynos, plus a
  test-local denied reader for a tree where every read throws `EACCES`
  (POSIX permissions are not portable in checked-in fixtures on every host).
- **Session logs**: `SessionLog` encode/decode round-trip and `SessionStore`
  list/read on a temp directory; the graph interaction is on-device/manual.
- **Process memory**: a trimmed `dumpsys meminfo --oom` fixture; parser
  handling of comma thousands and `/ activities` suffixes, Apps grouping and
  Native/System exclusion, paging, and formatting. Manual: compare the top rows
  with `adb shell dumpsys meminfo --oom` and confirm the scan does not stall the
  pill's 2 s poll.
- **Instrumented (manual)**: overlay permission flow, drag, service
  survives screen off/on, reboot autostart. Run on the target phone.

End-to-end verification for the whole project: install the APK, grant
overlay + notifications, and confirm the pill shows the same number as the
CPU/GPU lines of `adb shell dumpsys thermalservice` within one poll; then lock
the screen for a minute and confirm polling stops in logcat and resumes
instantly. For T17, open the RAM row, compare the first rows with
`adb shell dumpsys meminfo --oom`, toggle Apps/Processes and page through,
confirm Back restores the 220 dp card, and kill Shizuku to confirm the
unavailable state; the pill must keep updating throughout.

## 8. Tasks

Each task is one reviewable change with a test. v1 scope is tier 0 + tier 1
(§2.1); root is out of scope. T3, T6, and T8 are high risk and get acceptance
checks written before implementation. T8 is implemented before T7 on the
target device so Diagnostics can be built against the working dumpsys source.
T9 (the expanded card) lands before T7 as well: it is the user-facing feature,
and T7 can read the card's snapshot. T12/T13 (recording and the session graph)
follow T9 and precede T7: they are the requested session feature. T14/T15 are
post-release: FPS data first, then the overlay controls; T16 rebuilds the
release. T17 adds the process-memory page after the RAM row and pill.

| # | Task | Acceptance |
|---|---|---|
| T0 ✅ | Probe the phone (no code) | Results in §10: device profile, sysfs denied to shell, `dumpsys` working, Shizuku installed but not running |
| T1 ✅ | Project skeleton, gates green on empty app | `assembleDebug` + `testDebugUnitTest` + `lintDebug` pass |
| T2 ✅ | `core/` logic + JUnit | Thresholds, formatting, segments and stale dimming match the table above |
| T3 ✅ | `SysfsReader` + `SysfsSource` + `ZoneMap` | Fixture trees for 3 SoC layouts map CPU/GPU/BAT correctly; restricted tree yields `—` and per-path errors; values normalised (milli vs whole degrees) |
| T4 ✅ | `BatterySource` + `ThermalStatusSource` | Battery temp = extra / 10; status maps NONE..SHUTDOWN to the colour scale |
| T5 ✅ | `SourceChain` + notification large icon | Merge takes first non-null per field; a failing source does not blank the others; badge renders the CPU number or `?` |
| T6 ✅ | Overlay service, permission flow, collapsed pill | Overlay appears after grant, survives home/app switch, updates on the 2 s expanded / 5 s collapsed cadence, stops polling on screen off (no poll logs) and refreshes on screen on; demo mode shows moving CPU/GPU values; the Battery Historian check is manual |
| T7 ✅ | Diagnostics + manual zone override | Lists every enumerate-able zone with raw value and read status; a denied thermal directory is reported. On a sysfs-readable device assigning a zone changes the pill within one poll and the override survives restart. On the target the app UID cannot list the directory, so Diagnostics shows EACCES and 0 rows; fixture tests cover the table and override. |
| T8 ✅ | Shizuku + `dumpsys` reader (tier 1) | With Shizuku 13.5.4 running and permission granted, the pill shows CPU/GPU from `dumpsys thermalservice`, matching the HAL at the poll instant within 1 °C; killing Shizuku falls back to battery/status within one poll and restarting it recovers; the source dot is green for dumpsys, amber for sysfs, gray for fallback; sysfs stays a no-op on the target |
| T9 ✅ | Expanded card, drag, persistence | Position survives process death; expanded shows per-core/GPU/battery and a 2-minute sparkline; tap toggles; no touch leaks to the app underneath |
| T10 ✅ | Boot autostart + OEM guidance | After the first unlock following a reboot, the service starts when the OEM allows it (BOOT_COMPLETED is not dispatched pre-unlock on a secure keyguard; direct boot is out of scope); UI explains the whitelist steps |
| T11 ✅ | Release packaging | `assembleRelease` green with R8 and resource shrinking on; 1,053,252 B (1.00 MiB) release APK; adaptive icon; debug-key signing for personal sideload; no new permissions; device-tested |
| T12 ✅ | Session recording + saved list | Record switch writes one CSV per session; stop/overlay stop flush; the sessions list shows start, duration, samples and survives restart; CSV round-trip tests; no network |
| T13 ✅ | Session graph viewer | Full-session CPU/GPU/BAT/SOC/SKIN graph; mouse hover and touch drag show a crosshair + timestamp/values tooltip; empty/corrupt logs handled without a crash; touch/empty/corrupt device-verified, mouse hover code-verified (no USB mouse attached) |
| T14 ✅ | FPS source + recording/graph | Shell-side resolver (focus → layer → `--latency`, 1.5 s fresh window; a stale ring is 0, never the last burst; BLAST SurfaceView wins over the activity layer); FPS in `Reading` and `SessionLog` v2; v1 sessions open with no FPS; session graph FPS line on its own 0–120 right axis + tooltip; 161 tests; device: 60.2 scrolling, 120.6 launcher, 29.7 game, 0.0 still, empty cell with Shizuku down |
| T15 ✅ | Overlay FPS controls | `SRC … just now` removed; FPS row; the card's stop action (then labelled "Stop overlay") verified to remove the window, notification and service (recording flushed); CPU/GPU/FPS chips persisted with at least one on; pill and notification follow within one tick; sparkline follows CPU/GPU; 179 tests; device-verified (60 scrolling, 0 red at rest) |
| T15b ✅ | Card graph tag + selector | `GRAPH · 2 MIN` header; cycle button CPU+GPU → CPU → GPU → FPS, persisted; history always records CPU/GPU/FPS; pill chips no longer control the graph; FPS on the fixed 0–120 scale; 184 tests; device-verified (header fits, cycle works, survives a force-stop; with the FPS chip off the graph still draws FPS) |
| T15c ✅ | BAT in the overlay graph | `GraphSeries.BAT` joins the selector cycle; battery drawn in blue on its own padded scale; history already records it; 185 tests; device-verified (blue BAT line, persisted across restart) |
| T15d ✅ | Session focus + column help | Legend chips toggle each series; dimmed lines draw under highlighted ones; the most recently re-highlighted series draws on top; table headers open an explanation dialog; 194 tests; device-verified (BAT/SKIN dim + z-order, SKIN/SOC/FPS dialogs, tooltip unchanged) |
| T15e ✅ | Session management | FPS legend chip hidden when the session has no FPS values; long-press selects sessions, tap toggles, Cancel clears, Delete asks for confirmation and removes the CSVs; the session being recorded is excluded from the list until it stops; 201 tests; device-verified (2 selected, dialog, 2 files deleted, all 18 real sessions untouched, v1 graph has no FPS chip, active recording hidden) |
| T15f ✅ | Service on/off wording | Main screen and card say `Start monitoring` / `Stop monitoring`; subtitle states that polling and recording stop while Shizuku and permissions stay; boot switch says `Start monitoring at boot`; no behaviour change; device-verified |
| T15g ✅ | Shizuku setup guide | Dedicated help screen: live Shizuku status, first-time steps, "Start says searching" toggle ritual, full re-pair reset, reboot flow, BAT troubleshooting, "if nothing above works" pointing at Diagnostics and trying another Shizuku version; plain product copy, no dev/version notes; entry from the main screen, the card when Shizuku is down, and the degraded notification; 201 tests; device-verified |
| T15h ✅ | Collapsible guide sections | Each guide topic is its own expandable card (First-time setup, Start keeps searching, Pairing lost/full reset, After reboot, BAT, Shizuku version problems, Why, If nothing works); status stays pinned; all closed when ready, First-time setup open only when not ready; 13.6.0-on-Android-16 warning; short-window fallback; 201 tests; device-verified via UI dumps (the other PNG captures were UTF-16-corrupt; one valid collapsed shot remains) |
| T15i ✅ | Combined pill + card min/max | Pill becomes `CPU/GPU 41° BAT 34° FPS 60`; CPU/GPU is the hotter sensor, coloured by the worse level; chips become CPU/GPU / BAT / FPS; card keeps individual CPU/GPU/BAT rows with 2-minute max/min; `hot` and the HAL max display are removed; 208 tests; device-verified (70° amber via the GPU warn level; Shizuku down shows `CPU/GPU — BAT 35° FPS —`) |
| T15j ✅ | No-device quality pass | Version warning extracted to `core/ShizukuCompat.kt` with 6 tests; corrupt T15h PNGs removed; 0.2.0 notes/checklist drafted; no behaviour change; 214 tests; no device |
| T16 ✅ | Release 0.2.0 | Installed and device-verified: versionCode 2 / 0.2.0, R8 + shrinking, 1,105,888 B, SHA-256 `4232C96E…EA0023`, v2 debug-signed. The Shizuku UserService/FPS path survives R8, the pill/card/guide/session graph/notification work, recording is v2, and the degraded `CPU/GPU — BAT 36° FPS —` prompt plus `Shizuku help` action recover after a server restart. 214 tests. |
| T17 ✅ | Process memory page | RAM row opens a second page in the expanded card; `dumpsys meminfo --oom` PSS parsed shell-side; `Apps` groups by package and hides `Native`/`System`, `Processes` shows every row; 8 per page with prev/next; `Back`/`Refresh`; on-open + manual scans only; card 280 dp and restores 220 dp on back; no new permissions; unavailable state without Shizuku and in demo mode; parser fixtures; the pill keeps polling during a scan; 250 tests; device-verified: Apps 82 / Processes 270 rows sorted by PSS, pager `1–8`/`9–16`, Refresh scans, no-Shizuku state + Start Shizuku, recovery after `start.sh`, and the header keeps all four metrics + dot (10 sp card / 12 sp page) |

T0-T17 are complete. T17 is committed on `feat/process-ram`; the RAM pill and
process page are not merged or pushed.

### T17 process-memory probe (2026-11-23)

Measured on the target as the shell UID (the UID a Shizuku UserService gets):

| Command | Result | Time |
|---|---|---|
| `dumpsys meminfo --oom` | Per-process **PSS** and RSS, grouped by OOM adjustment | ~2.1 s |
| `dumpsys meminfo <pid>` | App Summary for one process (Java/native/code/graphics/system, total PSS/RSS) | ~0.2 s |
| `dumpsys activity processes` | Process list with `lastPss`, but cached and sometimes `0.00` or 18 min old | ~0.2 s |
| `cat /proc/<pid>/smaps_rollup` | `Permission denied`, even to shell | — |

The PSS section is the source for T17. `dumpsys meminfo --oom` cannot ride the
2 s poll; the page takes one snapshot on open and on Refresh. The `Apps` view
excludes the `Native` and `System` OOM groups (on the target the top native
process is the ~810 MB neural-networks shim); `Processes` shows every parsed
row. The card's `used / total` remains `MemoryInfo`; the list is PSS, so the two
figures are related but not identical.

### T14 FPS probe and algorithm (2026-11-22)

Probe on the target phone as the shell UID (the UID a Shizuku UserService
gets); full log in `input/t14-fps-probe.txt` of the app project.

| Surface | Layer that carries frames | Measured |
|---|---|---|
| Settings while scrolling | `com.android.settings/com.android.settings.MiuiSettings#5643` | 60.2 fps (16.62 ms median) |
| Settings at rest | same layer | newest timestamp frozen over 2 s |
| Launcher while animating | `com.mi.android.globallauncher/com.miui.home.launcher.Launcher#5648` | 120.0 fps (8.33 ms) |
| Launcher at rest | same layer | newest timestamp frozen over 2 s |
| Wuthering Waves | `<hash> SurfaceView[<pkg>/<epicgames activity>](BLAST)#<id>` | 30.1 fps (33.26 ms) |

Confirmed rules:

- `dumpsys window` carries `mFocusedApp=ActivityRecord{… u0 <pkg>/<class> t<n>}`.
  A leading `.` in the class is expanded against the package
  (`com.android.settings/.MiuiSettings` becomes
  `com.android.settings/com.android.settings.MiuiSettings`).
- `dumpsys SurfaceFlinger --list` prints `RequestedLayerState{<name> …}`.
  Candidates for a component are the plain `<component>#<id>` layer and any
  `SurfaceView[<component>]…` layer. The `<hash> <component>#<id>` container,
  `ActivityRecord…`, `ActivityRecordInputSink…`, `Background for …` and the
  non-BLAST SurfaceView container return no frames and are skipped.
- `dumpsys SurfaceFlinger --latency '<layer>'` returns the refresh period on
  line 1 and up to 127 rows of `desiredPresent actualPresent frameReady` in
  CLOCK_MONOTONIC nanoseconds (tab-separated on the target; spaces are accepted
  too). Rows with a value `<= 0` or `>= 1e18` are not frames.
- The ring is **not** cleared when drawing stops: the newest timestamp freezes
  and an idle screen would report its last burst forever (probe: 120 fps,
  frozen). Freshness must be judged against `System.nanoTime()`, the same
  clock: a frame is fresh when `now - actualPresent <= 1500 ms`. With fewer
  than two fresh frames the FPS is `0`; otherwise
  `fps = (fresh - 1) / (newest - oldest)` in seconds.
- Cost, measured through adb: `dumpsys window` ~136 ms, `--list` ~88 ms, one
  `--latency` ~90 ms. While the cached layer keeps producing fresh frames only
  the `--latency` call runs. When it goes stale, re-resolve the component and
  layers, at most once every 4 s, so an idle foreground app does not turn the
  overlay into a dumpsys loop.
- 1.5 s window at the 2 s expanded poll (5 s collapsed). The value is the
  foreground app's frame rate; the overlay's own frames are never counted.
- Candidate preference: a fresh `SurfaceView[...](BLAST)` layer wins (that is
  the game's render target); the activity layer is used only when no SurfaceView
  has two fresh frames. A game whose UI layer also draws must not report the UI
  rate instead of the render rate.

Shell-side reply (one line, parsed by the app):

```
OK fps=59.8 frames=45 span_ms=740 layer=com.android.settings/com.android.settings.MiuiSettings#5643
OK fps=0 frames=0 span_ms=0 layer=-
ERR <reason>
```

`ERR` and any exception mean "no FPS this poll" (`—` in the UI), never a crash
and never a guessed number.

### T0 probe commands

Run on the phone, or over adb. Note that the shell UID sees more than the
app; treat this as discovery, then confirm in Diagnostics.

```bat
adb shell "for z in /sys/class/thermal/thermal_zone*; do echo \"$z $(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)\"; done"
adb shell "ls -l /sys/class/kgsl/kgsl-3d0/temp /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
adb shell "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"
adb shell dumpsys thermalservice
```

## 9. Open decisions

Defaults are chosen; change them now, not during implementation.

- Package id: `dev.rsensors.android`.
- minSdk 26, with runtime checks for thermal status (29+) and FGS type (34+).
- v1 ships tier 0 + tier 1 (stock + Shizuku). Root is explicitly unsupported;
  do not add a `su` reader.
- Shizuku dependency: official `dev.rikka.shizuku:api` and `:provider` 13.1.5.
  `Shizuku.newProcess` is deprecated and private in this version, so `dumpsys`
  runs in a Shizuku UserService (`IDumpsysService`, destroy id 16777114) bound
  with `bindUserService`; AIDL is enabled for that interface. The app still
  builds and runs without Shizuku installed.
- Target device: Shizuku manager/server 13.5.4 (the 13.6.0 UserService
  regression); the 13.1.5 client API works with both.
- Sessions: versioned CSV (`# started=...`, semicolon lists for cores and
  board), one file per session under `filesDir/sessions`; app-private, no
  permission, no network. The parser is pure and unit-tested.
- Recording follows the poller: screen-off pauses it, leaving a gap in the
  graph; do not add a background recorder that defeats the battery rule.
- Session graph: the whole session, no zoom/pan in v1; mouse hover and touch
  drag show a crosshair and the nearest-sample values.
- The target's app UID cannot list `/sys/class/thermal` (only the shell can);
  Diagnostics reports the EACCES and 0 rows. The zone table/override target
  sysfs-readable devices; a Shizuku-backed listing stays out of scope.
- Release (T11): R8 + resource shrinking, an adaptive thermometer icon, and a
  release APK signed with the local debug key for personal sideload (replace
  the key before any distribution). The release is installed and tested on the
  phone, including the Shizuku UserService path.
- FPS is the foreground app's frame rate, measured in the Shizuku UserService
  with `dumpsys SurfaceFlinger --list/--latency` for the focused component's
  layer; the overlay's own frames are irrelevant. The UserService resolves the
  component from `dumpsys window`'s `mFocusedApp`, judges freshness against
  `System.nanoTime()` (1.5 s window), and reports 0 for an idle app, `—` only
  when the shell route is unavailable. Adding `runFps` to the AIDL bumps the
  UserService registration to `version(3)` so a stale v2 shell process cannot
  answer the new call.
- FPS thresholds: below 30 red, below 50 amber, otherwise green. The session
  graph gives FPS its own right-hand 0–120 axis; the temperature axis stays
  independent.
- SessionLog v2 adds `fps`; the decoder still opens v1 files (FPS empty).
- The CPU/GPU/FPS toggles persist in `OverlayPreferences`, at least one stays
  on, and drive the pill and notification. The card's detail rows stay visible
  whatever the chips say. The sparkline has its own persisted series selector
  (`CPU+GPU` / `CPU` / `GPU` / `FPS`) and always sees the full history, so the
  chips never change what the graph has recorded. Stop monitoring lives in the
  card and stops the whole service (polling, recording, pill and
  notification); Shizuku and permissions are untouched. The source/age line is
  removed from the card.
- The graph header reads `GRAPH · 2 MIN`; FPS uses the same purple as the
  session graph and the fixed 0–120 scale, temperatures keep their own padded
  min/max. The selector cycles on tap and survives a restart.
- Shizuku start ritual: pairing saves the key; Start needs the adbd connect
  service (`_adb-tls-connect`). If Start says "searching for wireless debugging
  device service", toggle Wireless debugging off, wait 3 s, turn it on, wait
  ~5 s, then Start. Re-pair only if pairing is actually lost; re-pairing every
  start adds steps without fixing the connect service. The help screen
  documents both the quick fix and the full reset.
- Shizuku 13.5.4 is pinned; 13.6.0's UserService regression is still open
  upstream. This is a development note only: the user-facing guide carries no
  version requirement, and its last-resort section points at Diagnostics
  (which shows the version) and suggests trying another Shizuku version. The
  main-screen source note and the column-help dialogs say "thermal sensors" /
  "thermal service", never "thermal HAL" or "dumpsys".
- The pill shows `CPU/GPU` as the hotter of the two SoC values, coloured by the
  worse level; BAT is its own first-class metric. The four chips
  (CPU/GPU, BAT, FPS, RAM) persist as `show_cpugpu`, `show_bat`, `show_fps`,
  `show_ram`; the old `show_cpu`/`show_gpu` keys are ignored. At least one chip
  stays on.
- RAM is a pill segment after FPS, `RAM 8.6G`, used-only decimal GB with one
  decimal (`—` when unavailable) and `SegmentKind.INFO` mapped to the same blue
  as the card's RAM bar. It comes from the card's `ActivityManager.MemoryInfo`
  sample, so it needs no Shizuku and survives a null reading: with a live RAM
  figure the strip is not dimmed, while the missing sensor values stay dashes.
  When the reading exists, RAM dims with the strip under the usual stale rule.
- The card keeps CPU, GPU and BAT as individual rows; each shows the current
  value plus the max/min over the same 2-minute history as the sparkline. The
  HAL `cpuMax`/`gpuHot` values stay in the CSV but are no longer displayed;
  `hot` is gone from the card.
- The card's RAM row sits after FPS and before the per-core line: used / total in
  one-decimal decimal GB with a 4 dp full-width usage bar underneath (palette
  grey track at low alpha, blue fill). It is read from
  `ActivityManager.MemoryInfo` on the poll worker — `used = totalMem - availMem`,
  no Shizuku or permission — so it updates even when the sensor reading is null.
  A non-positive total or a throwing service call blanks the row to `—` and the
  bar, without touching the poll, strip, recording, diagnostics or notification.
  The bar is decorative for accessibility; the card description carries
  `RAM 4.5 / 7.8 GB` or `RAM not available`.
- Process memory page (T17): the RAM row is the entry point. The list is a
  snapshot of the `Total PSS by OOM adjustment` section of
  `dumpsys meminfo --oom`, parsed by `core/ProcessMemory.kt`. One scan runs when
  the page opens and one per `Refresh` (~2.1 s), on a second HandlerThread so
  the 2 s thermal/FPS poll is never blocked. The page shows eight rows; `Apps`
  groups by base package and hides the `Native`/`System` OOM groups,
  `Processes` shows every parsed row. The toggle and pager are view-local and
  reset/clamp on a new snapshot. `Back` returns to the 220 dp card and keeps it
  expanded; the page itself uses 280 dp. No row detail, icons, auto-refresh or
  full-screen list in v1. The page never invents data: no Shizuku, demo mode or
  a parse failure shows an unavailable/error state. The card's `used / total`
  is `MemoryInfo`; the rows are PSS, so the footer labels the list `PSS`.
- The telemetry header steps its text size down with the available width:
  14 sp collapsed, 10 sp in the 220 dp card and 12 sp on the 280 dp page. At
  14 sp the four metrics plus the source dot overflowed the card/page and
  clipped the RAM value/dot; the source dot is now always inside the panel.
  `Apps` labels come from `PackageManager` only when the package is visible to
  the app; the package/process name is the fallback, and package visibility is
  deliberately not broadened.
- A scan result is accepted only while the page is open and its generation
  still matches. Closing the page, a newer Refresh and screen-off invalidate
  the generation; screen-off restores the last snapshot (or a `Scan cancelled`
  error) so waking never finds a permanent `Scanning processes…`, and there is
  no automatic scan on wake.
- The AIDL adds `runMeminfo() = 3` and bumps the UserService registration to
  `version(4)` so a stale v3 shell process cannot answer the new transaction.
  The UserService runs `/system/bin/dumpsys meminfo --oom` and returns stdout;
  the parser is pure and fixture-tested. The runner forgets the binding on any
  failure, like `runDumpsys`/`runFps`.
- The help screen's topics are collapsible cards; the version warning appears
  only when the manager version starts with `13.6.0` and the API is 36+
  (Android 16). Below 560 dp the status card scrolls with the list instead of
  being pinned, so a landscape window still shows content.
- There is one foreground service (`OverlayService`). `Start/Stop monitoring`
  is the full service toggle: polling, recording, pill and notification.
  `Record session` only writes a CSV while the service runs. Stopping leaves
  Shizuku, the overlay/notification grants and the boot flag untouched.
- The session graph starts with every series highlighted. A legend chip toggles
  its series; an unhighlighted series stays drawn at low alpha under the
  highlighted ones, and re-highlighting moves it to the top of the draw order
  (the newest highlight wins a direct overlap). The tooltip still lists every
  value, so dimming never hides data from the reader.
- Table headers explain their column in a dialog; the texts must say what the
  HAL actually reports (SOC and SKIN are board zones, and on this phone SOC
  tracks CPU while SKIN tracks the battery).
- A v1 session hides the FPS legend chip (it has no FPS values); the FPS
  column already hides. A v2 session with FPS rows shows both.
- Session deletion is permanent and confirmed. The active recording is excluded
  from the sessions list (it is still being written), so it can never be
  deleted mid-recording; it appears once the recorder stops.
- FPS shows `0` when the foreground app is not drawing and `—` only while the
  Shizuku route is unavailable; an idle screen is a measurement, not a gap.
- MainActivity's Service row is an optimistic mirror refreshed on resume. A
  stop from the card while the activity stays resumed can leave it reading
  `Running` until the next resume; the overlay window, notification and
  ServiceRecord are already gone. Pre-existing since T6, not a T15 regression.
- T16 rebuilds the release after T15j as 0.2.0; 0.1.0 remains the installed
  baseline until then.
- 15 s stale threshold, 2 s / 5 s polling, thresholds as in §3.
- Dumpsys polling: parse only the HAL block. A dump took ~117 ms over adb; if
  the in-process Shizuku call exceeds 50 ms, relax expanded polling to 3 s.
- Per-core temps are unavailable on the target phone; hide the section when
  `cores` is empty.
- History: 120 samples (2 minutes at 2 s) in memory only, no database.
- Release: R8 and resource shrinking are enabled in T11; debug stays
  unminified for fast iteration.
- Temperature display rounds half-up (`roundToInt`); no acceptance case
  depends on exact .5 values.
- Overlay tap expands; long-press opens the app; drag moves.

## 10. T0 probe results (2026-11-21)

Device: POCO X7 Pro, MediaTek mt6899 (Dimensity 8400 Ultra), Android 16 /
HyperOS, SELinux enforcing, first API level 35.

| Probe | Result |
|---|---|
| `/sys/class/thermal/*` | 66 zones exist. The shell can list the directory, but `type`/`temp` reads are denied (EACCES); the app UID cannot list it at all, so Diagnostics shows 0 rows |
| `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` | readable: 8 cores, 2.1 / 3.0 / 3.25 GHz clusters |
| `/sys/class/devfreq/13000000.mali/cur_freq` | denied |
| `/sys/class/kgsl/*` | absent (MediaTek GPU) |
| `/proc/stat` | readable as shell |
| `dumpsys thermalservice` | works; CPU, GPU, SOC, NPU, TPU, BATTERY, SKIN, POWER_AMPLIFIER in °C |
| `cmd thermalservice` | no read command; only inject/override/reset/headroom |
| Shizuku | `moe.shizuku.privileged.api` installed, manager process running, **server not running** |
| Battery | 33.2 °C, identical to the HAL BATTERY value |

Sample `Current temperatures from HAL`:

```
Temperature{mValue=38.351, mType=0, mName=CPU, mStatus=0}
Temperature{mValue=38.351, mType=1, mName=GPU, mStatus=0}
Temperature{mValue=33.5, mType=2, mName=BATTERY, mStatus=0}
Temperature{mValue=33.5, mType=3, mName=SKIN, mStatus=0}
Temperature{mValue=35.56, mType=5, mName=POWER_AMPLIFIER, mStatus=0}
Temperature{mValue=38.351, mType=9, mName=NPU, mStatus=0}
Temperature{mValue=38.351, mType=10, mName=TPU, mStatus=0}
Temperature{mValue=38.351, mType=13, mName=SOC, mStatus=0}
```

Live check: two samples 3 s apart moved CPU 38.351 → 37.501 °C, so the HAL
updates at widget timescales. A `dumpsys` round trip over adb took 117 ms;
in-process Shizuku should be lower.

Shizuku's start script is present at
`/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`, so it
can be armed from this adb connection:
`adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`.

Conclusion: the X7 Pro gives real CPU/GPU temperatures through
`dumpsys thermalservice` and nothing through sysfs. `DumpsysThermalSource` is
the primary source, not a fallback.

## 11. Release (T11)

- APK: `app/build/outputs/apk/release/app-release.apk`, 1,053,252 bytes
  (1.00 MiB), SHA-256
  `0944B9E7C2482C921F735BDF55BA5F6FEC092A7F03D1EF2F6196914A4745B59C`.
- R8 full mode + resource shrinking. The only custom keep rule is
  `dev.rsensors.android.shizuku.**` (the AIDL interface and `DumpsysUserService`,
  which Shizuku starts by class name). The debug build is 11,182,172 bytes, so
  the release is 90.6% smaller.
- Signed with the local debug key for personal sideload; it installs over the
  debug app and preserves app data (sessions and preferences). Replace the key
  before any distribution.
- Adaptive thermometer icon (`mipmap-anydpi-v26`); `MissingApplicationIcon` is
  resolved.
- Permissions: overlay, foreground service, special-use FGS, notifications,
  boot completed, plus the Shizuku provider API permission. No `INTERNET`.
- Device-verified after installing the release: the Shizuku UserService/dumpsys
  survives R8 (`runDumpsys exit=0`), the pill matches the HAL, recording and the
  Sessions list/detail graph work, Diagnostics and the boot switch work, the
  notification and icon render.
- Manual checks still open:
  * mouse hover on the session graph (no USB mouse was attached; the touch
    crosshair/tooltip is device-verified, and the hover code path covers
    `PointerEventType.Move/Enter/Exit`);
  * a real reboot with a secure keyguard starts the overlay at first unlock
    (`BOOT_COMPLETED` behavior; direct boot is out of scope).
- Shizuku 13.5.4 is required on this phone because of the 13.6.0 UserService
  regression; other Shizuku apps need to re-approve after the downgrade.
- T14-T15j are post-0.1.0 work: FPS + recording/graph, the overlay controls and
  meters, session management, the setup guide, and the combined pill/min-max.
  The 0.1.0 APK above remains the installed baseline until T16.

### 0.2.0 (built and device-verified 2026-11-22)

Built as versionCode 2 / versionName `0.2.0` and installed over the debug app;
app data was preserved.

| | |
|---|---|
| File | GitHub Release asset `rsensors-0.2.0.apk` |
| Size | 1,105,888 bytes |
| SHA-256 | `4232C96E6F1696FD348B593C1DF19FDD3894CD1F4E4CB1E8C42F931318EA0023` |
| Signature | v2, `CN=Android Debug` (SHA-256 `d0736603…621228`) |
| Build | R8 full mode + resource shrinking; keep rule unchanged |

Device verification on the POCO X7 Pro:

- Install over the debug app with the local release; `versionName=0.2.0`,
  `versionCode=2`.
- Shizuku UserService under R8: `bindUserService version=3`,
  `onServiceConnected binder=true`, `runDumpsys exit=0`, `runFps` replies.
- Pill and notification `CPU/GPU 44° BAT 38° FPS 0`; expanded card shows the
  individual rows with 2-minute max/min and the batched pill metrics.
- Setup guide: live status, 8 collapsed sections, version section; no warning on
  13.5.4.
- Recording: a 2-sample v2 session, Sessions list, session graph with the FPS
  axis/column.
- Degraded state: after killing `shizuku_server`, the pill/notification read
  `CPU/GPU — BAT 36° FPS —`, the notification gained the `Shizuku help` action;
  restarting via `start.sh` rebound the UserService and recovered to
  `CPU/GPU 41° BAT 36° FPS 0`.
- No crash; overlay left running.

Repository docs staged in the app project: `README.md`, `LICENSE` (MIT),
`CHANGELOG.md`, `docs/ARCHITECTURE.md`, `docs/BUILDING.md`, and curated
`docs/images/`. The APK and checksum live in the `v0.2.0` GitHub Release;
the repository tree is source-only.

Changes since 0.1.0:

- **FPS**: the foreground app's frame rate through Shizuku/SurfaceFlinger; on
  the pill, the card, the CSV (`SessionLog` v2) and the session graph's 0–120
  right axis. A stale ring reads 0; no Shizuku reads `—`.
- **Overlay graph**: `GRAPH · 2 MIN` header with a CPU+GPU / CPU / GPU / BAT /
  FPS selector; the history always records all of them; the pill chips are
  independent.
- **Pill**: `CPU/GPU` (the hotter SoC value, worse level), `BAT` as a
  first-class metric, `FPS`, `RAM`; chips CPU/GPU / BAT / FPS / RAM.
- **Card**: individual CPU/GPU/BAT rows with 2-minute max/min; `hot` and the
  HAL max display removed (the CSV keeps the fields).
- **Sessions**: legend focus (dim + restack, newest highlight on top), column
  explanation dialogs, long-press selection and confirmed deletion, the active
  recording hidden until it stops, and v1 sessions without an FPS chip/column.
- **Shizuku setup guide**: collapsible sections, the toggle-off/3 s/on/Start
  fix, the full re-pair reset, after-reboot and BAT checks, a version-problems
  section, the conditional 13.6.0-on-Android-16 warning, the card's Start
  Shizuku prompt and the notification's Shizuku help action.
- **Wording**: Start/Stop monitoring, Start monitoring at boot; product copy
  without build/version requirements outside Diagnostics.

T16 checklist: bump versionCode/versionName; `assembleRelease` with R8 and the
unchanged Shizuku keep rule; install over the current app; re-grant the overlay
app-op; device-verify the Shizuku UserService and FPS survive R8, the pill/card
metrics, the graph selector, session focus/delete, the guide, the notification
action and the boot switch; record size/hash/signature. Tests: 214.
