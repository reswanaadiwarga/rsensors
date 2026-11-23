# Rsensors — design and task log

Development design and task log. Public overview: [README.md](README.md);
architecture summary: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Development evidence was archived outside this repository and is not bundled.

Status: **T0-T19a plus the record toggle, snap toggle, T19b-1 FPS parser,
T19b-2 meminfo parser, T19b-3 RSS fallback, T19b-4 thermal hardening, T19b-5
dynamic FPS axis, T19c-1 OEM stop denylist, T19c-2 post-stop verification,
T19c-3a shell UID enrichment, T19c-3b on-demand hidden-package flags, T19d-1
brand-aware setup guidance and T19d-2 manual Manage apps route, complete in
code.**
The RAM pill, process-memory page and Stop app are committed on `feat/stop-app`
(T17 is on the parent `feat/process-ram`) and installed/device-verified on the
target (**280 tests** at T18). T19a adds the Diagnostics feature-status table
(**299 tests**, 160 independent acceptance checks; device-verified 2026-11-23
with caveats). Record and snap add 11 tests (**310 total**); T19b-1 adds 14
(**324 total**); T19b-2 adds 6 (**330 total**); T19b-3 adds 15 (**345 total**);
T19b-4 adds 10 (**355 total**); T19c-1 adds 5 (**360 total**); T19c-2 adds 9
(**369 total**); T19d-1 adds 18 (**387 total**); T19b-5 adds 5 (**392 total**).
The Shizuku 13.5.4 guide update adds 1 (**393 total**); T19c-3a adds 8
(**401 total**); T19c-3b adds 15 (**416 total**).
All are on local `main`. Device evidence is Android 16 only where available;
normal UserService transport is currently blocked by Shizuku 13.6.0. The 0.2.0
release remains the published baseline and does not include these changes.
Nothing is pushed. This file is the source of truth for the app; change it
before changing code.

## Verification scope

"Works", "verified" and "device-verified" in this file mean: the named build was
run on the POCO X7 Pro / Android 16 / HyperOS / Shizuku 13.5.4 target and behaved
as described. They are not a compatibility guarantee for another phone.

The app is capability-based:

- **No shell:** battery temperature, thermal status (API 29+), system RAM,
  own-app data, overlay, recording, sessions and settings. OEM policy can still
  block the overlay or autostart.
- **Shizuku:** CPU/GPU temperatures, FPS, process memory and stopping apps. The
  HAL/OEM may omit a sensor, use a different SurfaceFlinger layer or `dumpsys`
  format, or refuse a command.
- **Diagnostics is the per-feature source of truth:** `OK` / `DEGRADED` /
  `UNAVAILABLE` / `BLOCKED` / `UNKNOWN`, source and exact reason. A missing value
  is a reported capability result, not evidence that the feature works
  everywhere. See `docs/UNIVERSALITY.md` for the research and limits.

Below, "device-verified" in a task row means on that target only, not universal
support.

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
  Refresh, Diagnostics, a full-width Record session / Stop recording toggle,
  Stop monitoring (formerly "Stop overlay"), and a
  2-minute sparkline tagged `GRAPH · 2 MIN` with a cycle
  button selecting CPU+GPU / CPU / GPU / BAT / FPS (battery in blue, FPS in
  purple on a 0–120 axis that rises to the next 30 fps step — 150, 180, … up to
  500 — only when the recorded window peaks above 120).
  Four toggle chips (CPU/GPU / BAT / FPS / RAM) choose what the pill and
  notification show; at least one stays on. The card keeps its detail rows. The
  graph selector is independent of the chips, and the in-memory history always
  records CPU, GPU, BAT and FPS regardless of either choice. The wordy source/age line is gone — the source
  lives in Diagnostics. On the target phone the per-core list is empty (no
  sensors); show `per-core n/a` instead of an empty section.
- **Process memory** (expanded card → RAM row): the RAM row is a button with a
  `›`; it opens a second page in the same overlay. The page lists the top
  processes by PSS from `dumpsys meminfo --oom`, falling back to RSS from
  `ps -A -o PID,RSS,NAME` when that dump has no PSS rows, eight per page, with
  an `Apps` / `Processes` toggle: `Apps` groups by package and hides the
  `Native`/`System` OOM groups on the PSS route (RSS rows carry no OOM group, so
  those system/native rows stay visible), `Processes` shows every row. The
  header has `Back` and `Refresh`; the summary keeps the card's system
  `used / total` and the footer shows `PSS · N apps/processes · age` or
  `RSS · …` matching the metric actually shown, plus `‹ 1–8 of N ›`. The dump
  is a snapshot taken when the page opens and on Refresh (~2 s), never part of
  the 2 s/5 s poll; the pill keeps updating behind it. The card grows to 280 dp
  and returns to 220 dp on Back. No new permissions. Without Shizuku, or in
  demo mode, the page shows an unavailable state and never invents data.
- **Stop app** (process page → tap a row): a tap selects the row (blue tint,
  left accent, larger row) and opens a selection bar; nothing stops on the
  first tap. The bar shows the app/process, PSS, the package on its own line and
  a protection reason; protected rows show a padlock + `PROTECTED` and no Stop.
  `Stop` always asks `Stop <app>?` once more before the action. Warning rows
  (system/updated-system apps such as AICore, foreground/visible groups,
  launcher, current IME, GMS when its OOM group is not protected) show the
  reason and still allow the stop. Normal cached/background apps also
  confirm. `am force-stop <package>` runs through Shizuku, so it stops the whole
  app; in `Processes` the action is labelled `Stop app` and says that all of the
  package's processes stop. Native rows are protected. After a stop the page
  rescans once and shows `Stop requested` or the failure; a system app that
  restarts simply reappears. No raw `kill`, no undo.
- **Manage apps** (main screen): the manual fallback when Shizuku is
  unavailable. It opens Android's app list; the user opens the app's info page
  and taps Force stop. Android asks for confirmation and performs the stop, and
  Rsensors cannot verify or undo it. No permission, no package list and no
  programmatic stop.
- **FPS**: the foreground app's frame rate, measured through Shizuku's
  UserService with `dumpsys SurfaceFlinger --list/--latency` for the top
  activity's layer — never the overlay's own frames. Without Shizuku the value
  is `—`. Colours: below 30 red, below 50 amber, otherwise green.
- **Recording**: a Record switch on the main screen or the expanded card's
  Record session / Stop recording toggle starts a session; every poll appends
  one CSV row including the FPS (time, CPU/max, cores, GPU/hot, battery,
  thermal status, board temps, source, FPS). Stop or overlay stop flushes and
  closes the file. Screen-off pauses polling, so a recording has a gap while
  the phone sleeps; that gap is visible in the graph. The card toggle posts to
  the same worker and uses the same recorder, so CSV format and behaviour do
  not depend on where it was started.
- **Shizuku help**: a dedicated setup/troubleshooting screen with live status
  (installed/running/permission/version, wireless debugging if readable). The
  topics are collapsible cards — First-time setup, Start keeps searching,
  Pairing lost / full reset, After a reboot, BAT-only, Why, If nothing works —
  so the page reads as a menu, not a wall of text. It is reachable from the
  main screen, from the card when Shizuku is down, and from the degraded
  notification.
- **Sessions**: a list of saved sessions (start, duration, samples) and a
  full-session graph with CPU/GPU/BAT/SOC/SKIN plus FPS on its own right-hand
  axis. The FPS axis starts at 0–120 and grows to the next 30 fps step (150,
  180, … up to 500) when the session's recorded peak exceeds the current
  ceiling. Long-press selects sessions for deletion (tap toggles in
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
- **Drag** anywhere; position and collapsed/expanded state persist. `Snap to
  edges` on the main screen (default on) makes a release jump to the nearer
  left/right side with an 8 dp margin; off keeps the exact drop position,
  clamped fully on-screen, and persists it. The switch applies to the next
  drag; no service restart.
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
    OverlayPlacement.kt     drag-release x: snap to the nearer side or free drop
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
    StopPolicy.kt           protected/warning/normal tier for stopping an app
  source/
    SensorSource.kt         interface: id, poll(now): Reading?, close()
    SysfsReader.kt          open(path): String?  + listDir(path)
    DirectReader.kt         File-based reader (stock)
    SysfsSource.kt          thermal zones + cpufreq + kgsl (best-effort)
    DumpsysRunner.kt        interface: run(): String? / close()
    DumpsysThermalSource.kt runner + parser -> Reading
    ShizukuDumpsysRunner.kt Shizuku UserService binding (dumpsys + fps + meminfo + stop)
    FpsRunner.kt            interface: runFps(): String? (shell reply)
    MeminfoRunner.kt        interface: runMeminfo(): String? (raw --oom dump)
    StopRunner.kt           interface: stopPackage(): String? (OK/ERR)
    BatterySource.kt        sticky ACTION_BATTERY_CHANGED
    ThermalStatusSource.kt  PowerManager thermal status
    DemoSource.kt           synthetic ramps (preview)
    SourceChain.kt          merges sources, records per-source errors
    SessionStore.kt         session files: list/read/write under filesDir
  shizuku/
    DumpsysUserService.kt    shell-side UserService (dumpsys + FPS + meminfo + stop)
  overlay/
    OverlayService.kt       FGS, poller, screen on/off, notification, meminfo scan, stop
    OverlayView.kt          pill + expanded card + process page, classic Views
    ProcessPageView.kt      paged Apps/Processes list + selection/stop in the card
    SparklineView.kt        2-minute CPU/GPU history graph
    SessionRecorder.kt      CSV writer owned by the poller
    OverlayParams.kt        WindowManager.LayoutParams helpers
    OverlayPreferences.kt   SharedPreferences: demo, position, expanded, boot,
                            snap to edges, CPU/GPU/BAT/FPS/RAM visibility
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
  core/StopPolicyTest.kt
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
  9 NPU, 10 TPU, 13 SOC). Samsung aliases are recognised: `AP` is CPU and
  `BAT` is BATTERY. `SUBBAT` is dropped *before* the `mType` fallback, so the
  secondary battery rail never becomes the main battery even though its type is
  2; any other unknown name still falls back to `mType`.
- CPU and GPU take the hottest value when the HAL lists more than one entry.
  Battery and board sensors keep the first occurrence. The cached block stays
  ignored either way.
- A value outside `-40.0..150.0` °C is rejected as a sentinel or unit error
  (for example `999` or `-273.15`); a genuine `-5.5` is kept. If every entry for
  a sensor is rejected, that sensor stays absent rather than being invented.
- `cmd thermalservice` has no read command (only override/reset/headroom), so
  the text dump is the interface.
- The block is ~60 lines; parse leniently because OEMs add types such as
  `POWER_AMPLIFIER`.

Zone mapping is a scored match on the lowercased `type` string, seeded with:

| Category | Patterns |
|---|---|
| CPU | `cpu-*`, `cpuss-*`, `cpu_thermal`, `soc_thermal`, `soc-thermal` (Kirin), `mtktscpu`, `cpu-0-0`, `ap` (Samsung) |
| GPU | `gpuss-*`, `gpu-*`, `gpu_thermal`, `gpu_tmu` (Samsung), `kgsl` (Qualcomm), `mtktsgpu`, `gpu0` |
| Battery | `battery`, `mtktsbattery`, `batt` |

The exact OEM aliases score like the other exact names, so they still lose to a
manual override. `subbat` stays OTHER rather than being treated as a battery.

Exact aliases score higher than prefix matches. A name that scores CPU but also
mentions `gpu` or `batt` is treated as OTHER rather than guessed. Unknown zones
are shown in Diagnostics and can be assigned manually; the override is stored by
zone `type` string, not index. This is what makes the app usable on an arbitrary
phone without a per-model table. On the target phone the zone metadata is
unreadable, so the mapping is inert there.

The OEM aliases (`soc-thermal`, `ap`, `gpu_tmu`, `kgsl`) come from the research
in `docs/UNIVERSALITY.md`; they are not device-verified.

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
- Shizuku 13.6.0 supports Android 16 QPR1 in general, but on the target
  (POCO X7 Pro / Android 16 / HyperOS / MediaTek) its UserService starter
  crashes in `LoadedApk.makeApplicationInner` before app code runs (upstream
  #2629 and #2633). Do not generalise this to every Android 16 device. Use the
  13.5.4 manager/server on the target — uninstall, reinstall from the
  [13.5.4 release](https://github.com/RikkaApps/Shizuku/releases/tag/v13.5.4),
  then start it with its `start.sh` — until a fixed release. Android refuses an
  in-place downgrade, and uninstalling clears Shizuku's pairing/permission data,
  so permissions must be granted again. The 13.1.5 client API is compatible
  with both. The help screen's version card links that release and gives the
  download, uninstall, install, re-pair and re-grant steps.
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
  Native/System exclusion, paging, formatting, the `ps` RSS parser with its
  header-position mapping, and PSS-vs-RSS snapshot routing. Manual: compare the
  top rows with `adb shell dumpsys meminfo --oom` and confirm the scan does not
  stall the pill's 2 s poll.
- **Stop policy**: JVM tests for every tier and honouring package, UID, system/
  updated-system/persistent flags, OOM group, launcher/IME, self and Shizuku;
  the AOSP core and OEM manager denylists (including case/whitespace variants);
  AICore is a warning, not a block. Manual: select each tier, confirm the
  presence/absence of Stop, then stop a cached app and AICore once; verify the
  package disappears and the page rescan reflects a restart.
- **Instrumented (manual)**: overlay permission flow, drag with `Snap to edges`
  on and off, service survives screen off/on, reboot autostart. Run on the
  target phone.

End-to-end verification for the whole project: install the APK, grant
overlay + notifications, and confirm the pill shows the same number as the
CPU/GPU lines of `adb shell dumpsys thermalservice` within one poll; then lock
the screen for a minute and confirm polling stops in logcat and resumes
instantly. For T17, open the RAM row, compare the first rows with
`adb shell dumpsys meminfo --oom`, toggle Apps/Processes and page through,
confirm Back restores the 220 dp card, and kill Shizuku to confirm the
unavailable state; the pill must keep updating throughout. For T18, tap AICore
in the page, verify the highlight, warning and confirmation; stop it and confirm
the page rescan (AICore may restart when Nano is used). Do the same for a cached
user app and a Processes row, then verify a protected row has no Stop and the
no-Shizuku route cannot stop anything.

## 8. Tasks

Each task is one reviewable change with a test. v1 scope is tier 0 + tier 1
(§2.1); root is out of scope. T3, T6, and T8 are high risk and get acceptance
checks written before implementation. T8 is implemented before T7 on the
target device so Diagnostics can be built against the working dumpsys source.
T9 (the expanded card) lands before T7 as well: it is the user-facing feature,
and T7 can read the card's snapshot. T12/T13 (recording and the session graph)
follow T9 and precede T7: they are the requested session feature. T14/T15 are
post-release: FPS data first, then the overlay controls; T16 rebuilds the
release. T17 adds the process-memory page after the RAM row and pill. T19a adds
the per-feature Diagnostics status table before the universality work.

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
| T18 ✅ | Stop app | Tap-select a process/app row with a highlight and selection bar; Stop always confirms; Apps and Processes both supported (Processes says Stop app and warns the whole package stops; native protected); tiered `StopPolicy` — protected: self, Shizuku, native/no package, UID < 10000, core denylist, persistent, Native/System/Persistent/Persistent Service; warning: system/updated-system/privileged including AICore, foreground/visible, launcher, IME, GMS; normal: cached/background; `am force-stop <validated package>` through Shizuku AIDL version 5, no raw kill; one rescan after a stop; 280 tests; device-verified: AICore selected as `WARNING · On-device AI…`, confirmed `Stop AICore?`, `stopPackage … exit=0 reply=OK`, AICore pid gone, one rescan 266 rows, RAM 8.7G→7.1G, poll still running; protected `system` row showed a padlock + `PROTECTED · Core system process.` with no Stop; warning/protected bars show the package on its own line; Cancel restored the footer; Processes warning row showed the `Stop app` label; no crash; warning confirmation shows the full package + reason + `This stops every process of the app.` with no truncation |
| T19a ✅ | Diagnostics feature status | "Feature status" table above the snapshot: eight rows (battery, thermal status, CPU/GPU, board, FPS, RAM, process memory, Stop app), each with state, source and exact reason; precedence demo → Shizuku installed/running/permission → overlay → probe/value; battery/thermal/RAM stay tier 0; FPS probe every poll and meminfo/stop last-attempt probes with `shellBlocked` recovery clearing; cancelled scans/stops get terminal states; no new permissions, dependencies or measurement behaviour; 299 tests; 160 independent acceptance checks; device-verified 2026-11-23 with caveats (Shizuku-down/recovery and the protected-stop UI path were not forced) |
| T19b-1 ✅ | FPS parser portability | Accepts Android 8–14 plain `--list` lines and Android 15+ wrappers; hash/legacy/BLAST/`@0(BLAST)` names; `desiredPresentTime=0` rows; `AppWindowToken` focus. Hash-prefixed plain layers are skipped when an exact twin exists (Android 16 containers) and kept only as an Android 17 fallback. 324 tests; 56 independent acceptance checks; Android 16 smoke at 0/53/60/120 fps. Real Android 8–14/17 formats remain unverified. |
| T19b-2 ✅ | Meminfo parser tolerance | Work-profile `(pid N) (user 10)`, `kB`/`KB`/`kb`, `/ activities` and `:remote` pid suffixes, case-insensitive `pid`/`user`; unclean headings become `Unknown` instead of inheriting the previous group; malformed `(pid` rows do not change the group. PSS/grouping/sort/page behavior unchanged. 330 tests; 25 independent acceptance checks; Android 16 smoke scanned 281 processes in Apps/Processes with a working pager. The `ps`/RSS fallback and the PSS-vs-RSS label are delivered by T19b-3 below. |
| T19b-3 ✅ | RSS fallback and metric label | When `dumpsys meminfo --oom` yields no PSS rows, the UserService runs `/system/bin/ps -A -o PID,RSS,NAME` (absolute path, argv array, read to EOF before waiting) and returns its stdout; a meminfo failure also falls through to `ps`, and both failing is one concise exception. `parsePsRss` finds a case-insensitive `PID`/`RSS`/`NAME` header (also `CMD`/`COMMAND`), maps columns by header position, joins a trailing name, and rejects non-positive pids, negative or non-integer RSS and short rows; zero RSS is accepted deliberately because kernel threads report it (real `ps` on the target has 779 zero-RSS rows). Every row carries group `RSS` so the Apps filter keeps system/native rows it cannot classify. `parseProcessSnapshot` prefers PSS, falls back to RSS and defaults to an empty PSS snapshot. The process page's footer and accessibility text label the metric actually shown (PSS or RSS), and Diagnostics reports the matching source (`dumpsys meminfo` or `ps`) plus the metric in its detail, or the neutral source `process memory` while a scan is running, failed or cancelled before any snapshot exists; `pssKb` became `memoryKb` on `ProcessMemory`/`AppMemory`. 345 tests. Device result: the RSS UI, footer label and Diagnostics source were exercised through a temporary forced-RSS verifier shim only; the production Shizuku UserService is currently blocked on this MediaTek/Xiaomi device by the known `makeApplicationInner` NPE, so the normal-transport `ps` fallback is not device-verified. |
| T19b-4 ✅ | Thermal hardening | CPU/GPU HAL entries are aggregated by **hottest**, not first; battery/board keep first. HAL values outside `-40.0..150.0` are rejected, while legitimate negatives remain. `AP`→CPU, `BAT`→BATTERY, `SUBBAT` ignored before the `mType` fallback. ZoneMap adds `soc-thermal`/`ap`→CPU and `gpu_tmu`/`kgsl`→GPU; `subbat` stays OTHER. 355 tests; 61 independent acceptance checks; parent-red + mutation-red. Real OEM HAL/cached/`HAL Ready` formats remain unverified because Shizuku 13.6.0 blocks the normal UserService transport. |
| T19b-5 ✅ | Dynamic FPS axis | Session graph and card sparkline use a 120 fps baseline raised in 30 fps steps (150/180/…/500) from the recorded peak; 120 is still the default. v1/no-FPS sessions still hide the axis. 392 tests; 53 independent acceptance checks; parent-red + mutation-red. Real 144/165 Hz rendering remains device-unverified. |
| T19c-1 ✅ | OEM stop denylist | Adds normalized OEM protected packages: Xiaomi (`securitycenter`, `powerkeeper`), Huawei/HONOR (`systemmanager`, `powergenie`), ColorOS/Oppo/Realme (`safecenter`, `phonemanager`, `securitypermission`, `apprecover`, `athena`, `gamespace`), vivo/iQOO (`pem`, `secure`, `abe`, `daemonService`, `permissionmanager`). They are PROTECTED with `"OEM system package."`, checked after core and before persistent/system/group reasons; exact membership only. 360 tests; 94 independent acceptance checks; parent-red + mutation-red. OEM package names/build variants remain device-unverified. |
| T19c-2 ✅ | Post-stop verification | `am force-stop` exit 0 is no longer proof: on success the shell ignores benign output, reads `ps -A -o PID,RSS,NAME`, and returns `ERR still running: …` when the package or a `package:` child survives; otherwise `OK`. A failed `ps` returns `OK` with a verification-unavailable log and the page rescan remains the fallback. `stillRunningProcesses` is exact/colon-boundary only, trimmed, order-preserving. 369 tests; 13 independent acceptance checks; parent-red + mutation-red. Real `am` wording, restart races and SELinux `ps` denial remain device-unverified. |
| T19c-3a ✅ | Shell UID enrichment | The AIDL adds `runProcessUids() = 5` and the UserService registration moves to `version(6)`. `DumpsysUserService.runProcessUids` runs `/system/bin/ps -A -o PID,UID,NAME` (absolute path, argv array, read to EOF before waiting) and returns raw stdout; the runner returns null on Shizuku/binder failure like `runMeminfo`. Pure `parsePsUid` locates `PID`/`UID`/`NAME` (or `CMD`/`COMMAND`) by header position, case-insensitively, accepts only positive pids and non-negative numeric UIDs, skips malformed rows and keeps the last duplicate pid; `attachUids` copies rows with matching UIDs and preserves order/fields without mutating. The scan attaches the table to the snapshot on the meminfo thread, and `groupApps` carries the first non-null member UID. The stop target uses `ApplicationInfo.uid` when visible, otherwise the row UID, so a hidden core process with UID < 10000 is PROTECTED; T19c-3b later adds the system/persistent flags on demand. A failed UID command keeps null UIDs and the scan continues. 401 tests. Device-verified: the target printed numeric UIDs for all 1050 rows, a 296-row process scan attached them and paged without a crash, and Diagnostics showed the scan. Hidden-core-row protection remains manual; T19c-3b now resolves the system/persistent flags on demand for the selected hidden package. |
| T19c-3b ✅ | Hidden-package flags | The AIDL adds `runPackageDump(packageName) = 6` and the UserService registration moves to `version(7)`. `DumpsysUserService.runPackageDump` validates with the same `PACKAGE_NAME`/`MAX_PACKAGE_LENGTH` rule as `stopPackage` (`ERR invalid package` otherwise) and runs `/system/bin/dumpsys package <pkg>` through the existing absolute-path argv helper, read to EOF before waiting; failures throw so the runner reports null like `runMeminfo`. Pure `PackageFacts(uid, isSystem, isUpdatedSystem, isPersistent)` + `parsePackageFacts` read only the first `Package [pkg]` block and stop at the next package/user/permission section: `appId` wins over `userId`, `pkgFlags` wins over the first package-level `flags`, and whole tokens are matched case-insensitively (`SYSTEM`, `UPDATED_SYSTEM_APP`, `PERSISTENT`), so `SYSTEMISH` or permission `flags` later never count; null when the block has neither UID nor flags. Selecting a row with hidden `ApplicationInfo` shows `Resolving app details…`, hides/disables Stop and asks the service once through `onResolvePackage`; the `dumpsys package` call runs on the meminfo thread, is cached with its null result, and a stale result only updates a matching selection. `stopTargetFor` uses `info.uid ?: facts.uid ?: row.uid` and ORs the three flags only when `ApplicationInfo` is hidden; `detailsHidden` stays true only with neither source, so a hidden system/persistent app warns/protects instead of always warning. Flags are resolved on demand for the selected package only; no `QUERY_ALL_PACKAGES`, no result verification for manual stops. 416 tests. Device-verified: hidden Apps rows showed `Resolving app details…` with no Stop, then resolved to a system warning; the shell dump ran on the meminfo thread (~133 ms for YouTube), and closing the page now clears the in-flight marker. Hidden Processes rows remain protected as unresolved; system/persistent flags for them are not resolved. |
| T19d-1 ✅ | Brand-aware setup guidance | New pure `core/OemGuidance.kt`: `oemFamily(Build.MANUFACTURER, Build.BRAND)` checks the manufacturer before the brand, ignores case and surrounding spaces, and maps Xiaomi/Redmi/POCO, Samsung, Huawei/HONOR, Oppo/OnePlus/Realme/ColorOS, vivo/iQOO and stock-like makers (Google/AOSP/Motorola/Sony/Asus/Nothing) to a family; unknown, blank and `null` fall back to `OTHER`. The main screen's "At boot" paragraph and the guide's "After a reboot" note now show the family copy instead of HyperOS-only text, and the XIAOMI copy keeps the original HyperOS wording. README gains a compatibility-notes table. 387 tests; no device. Real OEM setting labels and behaviour remain device-unverified, and HarmonyOS NEXT cannot run Android APKs. |
| T19d-2 ✅ | Manual Manage apps fallback | Tier 0 escape hatch for phones without the Shizuku shell route: an `OutlinedButton` labelled `Manage apps` on the main screen opens Android's app list, where the user opens an app's info page and taps `Force stop` by hand. API 29+ launches `Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS` and falls back to `Settings.ACTION_APPLICATION_SETTINGS` when that launch throws; both attempts are guarded, so a missing Settings activity cannot crash the app. The copy states that Android asks for confirmation and Rsensors cannot verify or undo the stop, and no code reads a result. No new permission, no `QUERY_ALL_PACKAGES`, no package list, and no Shizuku/shell/process-page change. UI-only; 393 tests unchanged. Device-checked on the target: the intent opened HyperOS's `Apps` screen (`com.miui.securitycenter/.appmanager.AppManagerMainActivity`), Back returned to the main screen without a crash, and logcat showed no FATAL/ANR. The vendor may substitute the settings activity; still not a universal or device-verified claim for other phones. |
| T19e-1 ✅ | Verification matrix | Adds `docs/VERIFICATION.md`: current target/Shizuku environment, gates, feature × no-shell/Shizuku × device evidence, and the explicit not-verified list (Android 8–14/17 formats, OEMs, second device, HAL/sysfs, shell fallbacks, high-refresh rendering). Links it from README. No code change; device claims remain target-only. |

T0-T19a plus the record/snap/T19b-1/T19b-2/T19b-3/T19b-4/T19b-5/T19c-1/T19c-2/
T19d-1/T19d-2/T19e-1 slices are complete in code on `main`. T17/T18/T19a are
ancestors of `main` from their feature branches; record, snap, all five T19b
slices, both T19c slices, both T19d slices and T19e-1 are merged. Nothing is
pushed.

### T19d-2 manual Manage apps (2026-11-24)

`Manage apps` on the main screen is the tier-0 fallback for stopping an app when
Shizuku's shell route is unavailable. It opens Android's app list
(`Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS` on API 29+,
`Settings.ACTION_APPLICATION_SETTINGS` when that launch throws) and leaves the
stop to the user: pick the app, open its info page, `Force stop`, confirm.
Rsensors cannot observe or undo the result, so neither the UI nor Diagnostics
claims a stop; this is not the T18 programmatic stop and it does not touch the
Shizuku, AIDL or process-page path. On the target phone the intent resolves to
HyperOS's `Apps` app manager (`com.miui.securitycenter`) rather than AOSP
Settings, and Back returns to the main screen. No new permission, no
`QUERY_ALL_PACKAGES` and no package-visibility change; the button always shows,
with copy that frames it as the Shizuku-unavailable fallback. Other phones may
map these actions to a different screen, so this is a device-checked route on
the target only, not a universal or device-verified claim for other phones.

### T19c-3b on-demand hidden-package facts (2026-11-24)

A hidden `ApplicationInfo` used to leave the stop policy without the package's
app id and system flags, so every hidden Apps row fell back to the generic
"details are hidden" warning. `runPackageDump(packageName) = 6` now lets the
page ask the shell for exactly that package's `dumpsys package <pkg>` stdout
after the user selects the row; the registration is `version(7)` so a stale
`version(6)` shell process cannot answer the new transaction. The name passes
the same validation as `stopPackage` before any process starts, and the dump
goes through the existing absolute-path argv helper, read to EOF before waiting.

`parsePackageFacts` reads only the first `Package [pkg]` block. It stops at the
next package header or at the first user/permission section, which is what keeps
permission-level `flags=[ … ]` lines out. `appId` is preferred over `userId`;
`pkgFlags` is preferred over the first package-level `flags`. Only whole
space-separated tokens count, case-insensitively. A block with neither a UID
nor a flag block yields null, and a null shell result is cached the same way as
a successful one so selecting the row again does not re-run `dumpsys`.

The page shows `Resolving app details…` and removes/disables Stop until the
result arrives; the list, filter, pager and Refresh stay usable. The service
runs the dump on the meminfo thread, never the 2 s poll or the main thread, and
posts the parsed facts back to the main thread. The view caches facts for any
package but recomputes the target only when that package is still selected, so
a late result cannot change another selection. With facts, `stopTargetFor` takes
`info.uid ?: facts.uid ?: row.uid` and takes the system/updated-system/
persistent booleans from the facts only when `ApplicationInfo` is hidden;
`detailsHidden` remains true only when neither source exists. A hidden
`SYSTEM`/`PERSISTENT` package therefore reaches its real warning/protected tier
instead of always showing the generic hidden warning. No `QUERY_ALL_PACKAGES`
is added, no package list is read, and a manual stop still has no result
verification. The real flag text and the resolving bar are device-only checks.

### T19a feature status (2026-11-23)

Diagnostics now opens with a "Feature status" table. Each row is one
measurement or action the app can show, not one source: battery temperature,
thermal status, CPU/GPU temperature, board sensors, FPS, system RAM, process
memory and Stop app. The row carries `OK`, `DEGRADED`, `UNAVAILABLE`, `BLOCKED`
or `UNKNOWN`, the source (`BatteryManager`, `PowerManager`, `dumpsys
thermalservice`, `SurfaceFlinger`, `dumpsys meminfo`/`ps`/`process memory`,
`am force-stop`, `ActivityManager`), and the exact reason when it is not
working. The Copy report contains the same rows.

The decision is pure (`core/Capabilities.kt`): demo, then Shizuku
installed/running/permission, then overlay running, then the feature probe or
value. Battery, thermal status and system RAM never depend on the shell. A
partial CPU/GPU pair or a sample older than `STALE_AFTER_MS` is `DEGRADED`; a
0 fps sample is `OK` (idle screen), not a failure. CPU/GPU with no value and no
source error says so explicitly instead of drawing two dashes.

The overlay publishes optional shell probes to `overlay/DiagnosticsStore.kt`:
FPS every poll, meminfo and Stop on each attempt. A probe caused by a missing
shell prerequisite is marked `shellBlocked`; when Shizuku recovers, only those
probes are cleared, and the table ignores a shell-blocked probe once the gates
pass, so a stale "needs Shizuku" reason cannot outlive the failure. Cancelling a
scan (screen off, page close) or closing the page during a stop writes a terminal
probe instead of leaving `Scanning…`/`Stopping…` forever. The process-memory
probe names the source of the rows it reports or restores — `dumpsys meminfo` for
PSS, `ps` for the RSS fallback — and a successful or restored detail carries
that metric.
A protected stop is `DEGRADED` ("Last attempt blocked: `<pkg>` is protected."),
not a global Stop failure. The store is cleared on service create and after the
worker's final poll on teardown.

No permission, manifest, dependency or measurement behaviour changed. The JVM
suite covers the pure rules (299 tests); `tests/acceptance/CapabilitiesAcceptance.kt`
holds 160 independent checks. Device rendering, clipboard output and the live
service transitions remain unverified until the phone is connected.

### T17 process-memory probe (2026-11-23)

Measured on the target as the shell UID (the UID a Shizuku UserService gets):

| Command | Result | Time |
|---|---|---|
| `dumpsys meminfo --oom` | Per-process **PSS** and RSS, grouped by OOM adjustment | ~2.1 s |
| `dumpsys meminfo <pid>` | App Summary for one process (Java/native/code/graphics/system, total PSS/RSS) | ~0.2 s |
| `dumpsys activity processes` | Process list with `lastPss`, but cached and sometimes `0.00` or 18 min old | ~0.2 s |
| `cat /proc/<pid>/smaps_rollup` | `Permission denied`, even to shell | — |

The PSS section is the source for T17. If a device cannot produce it, T19b-3
falls back to `ps -A -o PID,RSS,NAME` and the page labels the list RSS instead
of mislabelling it PSS. `dumpsys meminfo --oom` cannot ride the 2 s poll; the
page takes one snapshot on open and on Refresh. The `Apps` view excludes the
`Native` and `System` OOM groups (on the target the top native process is the
~810 MB neural-networks shim); `Processes` shows every parsed row. RSS rows have
no OOM group, so `Apps` keeps the system/native rows it cannot classify. The
card's `used / total` remains `MemoryInfo`; the list is PSS or RSS, so the two
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

- `dumpsys window` carries `mFocusedApp=ActivityRecord{… u0 <pkg>/<class> t<n>}`;
  older dumps use `AppWindowToken{…}` or a generic `mFocusedApp=… u<id>
  <pkg>/<class> …` record. A leading `.` in the class is expanded against the
  package (`com.android.settings/.MiuiSettings` becomes
  `com.android.settings/com.android.settings.MiuiSettings`).
- `dumpsys SurfaceFlinger --list` prints `RequestedLayerState{<name> …}` on
  Android 15+ and a bare `<name>` line on Android 8–14; both are parsed. A name
  may start with a `<hex> ` layer-id prefix, which is ignored for matching but
  kept in the returned name because `--latency` takes the exact name.
  Candidates are `SurfaceView[<component>](BLAST)#<id>` (including
  `@0(BLAST)`/`(BLAST)@0`), `SurfaceView - <component>#<id>` and the plain
  `<component>#<id>` layer, in that order; hash-prefixed BLAST/legacy layers
  stay candidates, exact names before hash-prefixed ones. A hash-prefixed plain
  layer is only a fallback: on Android 16 the probe's `9ecc164 …#5342` and
  `cc6d380 …#5726` are containers with `valid=0` while the exact `#5643` /
  `#5729` carry the frames, so it is dropped when an exact plain twin exists
  and kept when none does (Android 17 prefixes every name). `ActivityRecord…`,
  `ActivityRecordInputSink…`, `Background for …`, `Bounds for …` and the
  non-BLAST `SurfaceView[<component>]#<id>` container return no frames and are
  skipped.
- `dumpsys SurfaceFlinger --latency '<layer>'` returns the refresh period on
  line 1 and up to 127 rows of `desiredPresent actualPresent frameReady` in
  CLOCK_MONOTONIC nanoseconds (tab-separated on the target; spaces are accepted
  too). Rows with `actualPresent` or `frameReady` `<= 0` or `>= 1e18` are not
  frames; `desiredPresentTime` may be `0`.
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
  the game's render target), then a legacy `SurfaceView - …` render layer, then
  the activity layer. A game whose UI layer also draws must not report the UI
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
  graph gives FPS its own right-hand axis, 0–120 by default and raised in 30 fps
  steps (150, 180, … up to 500) only when the recorded peak exceeds it; the
  temperature axis stays independent.
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
  session graph and the same 0–120 baseline (raised to 150/180/… up to 500 when
  the visible window records faster frames), temperatures keep their own padded
  min/max. The selector cycles on tap and survives a restart.
- Shizuku start ritual: pairing saves the key; Start needs the adbd connect
  service (`_adb-tls-connect`). If Start says "searching for wireless debugging
  device service", toggle Wireless debugging off, wait 3 s, turn it on, wait
  ~5 s, then Start. Re-pair only if pairing is actually lost; re-pairing every
  start adds steps without fixing the connect service. The help screen
  documents both the quick fix and the full reset.
- Shizuku 13.5.4 is pinned; 13.6.0's UserService regression is still open
  upstream. The user-facing guide's version card links the 13.5.4 GitHub
  release and gives the uninstall → install → re-pair → re-grant steps, so a
  reader can act without a dev note; Diagnostics still shows the installed
  version. The main-screen source note and the column-help dialogs say "thermal
  sensors" / "thermal service", never "thermal HAL" or "dumpsys".
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
- Process memory page (T17/T19b-3): the RAM row is the entry point. The list is
  a snapshot of the `Total PSS by OOM adjustment` section of
  `dumpsys meminfo --oom`, parsed by `core/ProcessMemory.kt`; when that section
  is empty the shell runner falls back to `ps -A -o PID,RSS,NAME` and the page
  labels the rows RSS. One scan runs when the page opens and one per `Refresh`
  (~2.1 s), on a second HandlerThread so
  the 2 s thermal/FPS poll is never blocked. The page shows eight rows; `Apps`
  groups by base package and hides the `Native`/`System` OOM groups,
  `Processes` shows every parsed row. The toggle and pager are view-local and
  reset/clamp on a new snapshot. `Back` returns to the 220 dp card and keeps it
  expanded; the page itself uses 280 dp. No row detail, icons, auto-refresh or
  full-screen list in v1. The page never invents data: no Shizuku, demo mode or
  a parse failure shows an unavailable/error state. The card's `used / total`
  is `MemoryInfo`; the rows are PSS or RSS, so the footer labels the metric
  actually shown. RSS rows carry group `RSS`, not an OOM adjustment, so `Apps`
  keeps system/native rows it cannot classify.
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
  The UserService runs `/system/bin/dumpsys meminfo --oom` and returns stdout,
  falling back to `/system/bin/ps -A -o PID,RSS,NAME` when the dump has no PSS
  rows; the parser is pure and fixture-tested. The runner forgets the binding on
  any failure, like `runDumpsys`/`runFps`.
- Stop app (T18): "important" means needed for the phone or the tool, not large
  or system. PROTECTED: self, Shizuku, native/no package, UID < 10000, core
  denylist (`android`, `com.android.systemui`, `com.android.phone`,
  `com.android.bluetooth`, `com.android.nfc`, telephony/providers), OEM
  denylist, `FLAG_PERSISTENT`, OOM groups `Native`/`System`/`Persistent`/
  `Persistent Service`. The core-package reason outranks the OEM reason, and
  both outrank the persistent reason when several match; the tier is PROTECTED
  either way. The OEM denylist covers the vendor managers whose removal can
  break device security, power management or the permission backend — Xiaomi
  (`com.miui.securitycenter`, `com.miui.powerkeeper`), Huawei/HONOR
  (`com.huawei.systemmanager`, `com.huawei.powergenie`,
  `com.hihonor.systemmanager`, `com.hihonor.powergenie`), ColorOS/Oppo/Realme
  (`com.coloros.safecenter`, `com.coloros.phonemanager`,
  `com.coloros.securitypermission`, `com.coloros.apprecover`,
  `com.coloros.athena`, `com.coloros.gamespace`) and vivo/iQOO
  (`com.vivo.pem`, `com.iqoo.secure`, `com.vivo.abe`,
  `com.vivo.daemonservice`, `com.vivo.permissionmanager`). It is a
  conservative denylist, not a claim that every build of these vendors uses
  these exact package names, and it is not device-verified. WARNING: system/
  updated-system apps not in the denylist (AICore; privileged apps are
  normally system apps, so there is no separate privileged flag check), OOM
  groups `Foreground`/`Visible`/`Perceptible`/`Heavy Weight`, launcher,
  current IME, GMS when its OOM group is not protected. On this phone GMS is in
  a `Persistent`/`System` group and is therefore PROTECTED. NORMAL:
  cached/background/service apps. The decision is pure (`core/StopPolicy.kt`)
  and tested; the UI shows the tier and reason, with the package on its own
  line for Apps rows; a NORMAL initial bar hides the reason line because there
  is none. AICore is appId 10308, `SYSTEM | UPDATED_SYSTEM_APP`, OOM group
  `Visible` (~956 MB): WARNING, not protected; Android may restart it when Nano
  is used. Apps rows count as known apps even when Android package visibility
  hides `ApplicationInfo`; selecting one starts a single on-demand
  `dumpsys package <pkg>` lookup (T19c-3b) and the bar shows `Resolving app
  details…` with no Stop until it returns. The facts supply the app id and
  `SYSTEM`/`UPDATED_SYSTEM_APP`/`PERSISTENT`; a null result keeps the generic
  WARNING ("details hidden"). Hidden Apps rows stay stoppable. Processes rows are stoppable
  only when the base package resolves; native or unresolved names are
  PROTECTED. Package visibility is not broadened. A shell UID table
  (`ps -A -o PID,UID,NAME`) is attached to every snapshot row (T19c-3a); the
  stop target prefers `ApplicationInfo.uid`, then the facts UID, then the row
  UID, so a hidden app whose UID is < 10000 is PROTECTED instead of a WARNING.
  The table itself fills only the UID; system/persistent flags for a Processes
  row still require the package resolver, so an unresolved Processes row stays
  PROTECTED.
- Stop runs only as `am force-stop <package>` in the shell UserService; no raw
  `kill` and no per-process kill. The package must match
  `^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$` and be passed as a `ProcessBuilder` arg,
  never a shell string. Exit 0 is not taken as proof of the stop: any output
  the command prints on success (some builds print `Force stopping …`) is
  ignored, and the service then re-reads `ps -A -o PID,RSS,NAME` and matches
  the exact package or its `package:` children (`core/StopVerification.kt`,
  T19c-2). A surviving process turns the reply into
  `ERR still running: <up to 3 names>` instead of `OK`; when that verification
  `ps` cannot run or parses to no rows the reply stays `OK`, because the
  command was accepted and the page's rescan is the fallback proof. Only a
  non-zero `am` exit keeps the command output in `ERR exit=<code> <line>`. The
  AIDL adds `stopPackage() = 4` and bumps the
  UserService to `version(5)`; a failed command returns `ERR …` and never
  crashes the page. T19c-3a adds `runProcessUids() = 5` and bumps the
  UserService to `version(6)`; T19c-3b adds `runPackageDump(packageName) = 6`
  and bumps it to `version(7)`. `runPackageDump` validates with the same
  package rule as `stopPackage`, returns raw `dumpsys package` stdout, and
  throws on shell failure so the runner reports null and the page keeps its
  hidden-details fallback. The runner ignores late `onServiceConnected`/
  `onServiceDisconnected` callbacks unless a bind is pending (T17 verifier
  finding).
- Tap selects; Stop always confirms; protected rows never show Stop and show a
  padlock + `PROTECTED`. The confirmation names the app/package and the reason.
  The selection bar hides the pager/age; selection clears on Cancel, Back,
  page/mode change and Refresh. Back while a stop is already in flight closes
  the page; the committed stop still runs and its UI result is dropped. After a
  successful stop the page rescans once and reports `Stop requested`; a system
  app that restarts simply reappears. No undo, stop history, user-editable
  allowlist, auto-kill or scheduled kill in v1.
- If the first page open happens before the shared UserService binds, the
  first scan can be null and show a retryable error; a one-shot retry after the
  bind is a follow-up, not part of T18.
- The help screen's topics are collapsible cards; the version warning appears
  only when the manager version starts with `13.6.0` and the API is 36+
  (Android 16). The version card carries the 13.5.4 release link and the
  download/uninstall/install/re-pair/re-grant steps; opening the link is
  device-only (no unit test), and a missing browser leaves the copy readable.
  Below 560 dp the status card scrolls with the list instead of being pinned, so
  a landscape window still shows content.
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
- Overlay tap expands; long-press opens the app; drag moves and either snaps to
  the nearer side (`Snap to edges`, default on) or stays at the clamped drop
  position.

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
