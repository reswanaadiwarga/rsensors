# Architecture

Rsensors is a single Android app module. One foreground service hosts the
floating overlay and the poller; activities provide the main screen, sessions,
diagnostics and the Shizuku guide.

## Components

| Package | Responsibility |
|---|---|
| `core/` | pure Kotlin: `Reading`, thresholds, pill segments, formatting, FPS parsing, CSV sessions, chart geometry, visibility, ranges, process-memory PSS parsing/grouping/paging, the stop-risk policy, drag-release placement, Shizuku version predicate |
| `source/` | sensor sources, the source chain, the Shizuku runner, session storage |
| `shizuku/` | `DumpsysUserService` and the AIDL contract executed as the shell user |
| `overlay/` | foreground service, window, pill/card/process-page views, row selection and stop, notification, recording, preferences, Diagnostics probe store |
| `ui/` | Compose activities: main, sessions, session detail, diagnostics, setup guide |

## Data flow

```
OverlayService (foreground service)
  worker poll: 2 s expanded / 5 s collapsed
        |
        v
  SourceChain.poll(now)
    1. DumpsysThermalSource  (Shizuku UserService: dumpsys thermalservice)
    2. SysfsSource           (best-effort sysfs zones)
    3. BatterySource         (ACTION_BATTERY_CHANGED)
    4. ThermalStatusSource   (PowerManager)
        |
        v
  Reading (cpu/cpuMax/cores/gpu/gpuHot/battery/thermalStatus/board/source/fps)
        |
        +--> pill segments + notification
        +--> expanded card + 2-minute sparkline + min/max ranges
        +--> ReadingHistory (120 samples, in memory)
        +--> SessionRecorder (CSV v2, one row per poll)
        +--> ReadingStore (Diagnostics snapshot)
        +--> process page summary/age (snapshot list stays on demand)

OverlayService
  meminfo thread: dumpsys meminfo --oom on page open / Refresh
        |
        v
  ProcessMemory parser -> Apps/Processes rows -> eight-row pager
  StopPolicy -> selection bar -> stopPackage on the meminfo thread -> one rescan
```

Screen-off pauses polling and recording; screen-on resumes immediately. A
process-memory scan in flight is invalidated on screen-off and the page falls
back to its last snapshot (or a retryable error). A stop result is dropped if
the page closed or a newer stop replaced it.

## Sources

| Source | Field | Notes |
|---|---|---|
| `dumpsys thermalservice` | CPU, GPU, board zones | through Shizuku's shell UserService; primary route on the test device |
| sysfs thermal zones | CPU/GPU/BAT | best-effort; denied to the app UID on the test device |
| `ACTION_BATTERY_CHANGED` | battery °C | extra value divided by 10 |
| `PowerManager` thermal status | NONE…SHUTDOWN | API 29+ |
| SurfaceFlinger | FPS | focused app's presented frames; see below |

The source chain merges the first non-null value per field and records each
source's error. The source dot is green for the dumpsys route, amber for sysfs,
grey for battery/status only.

## FPS

The Shizuku UserService runs `dumpsys window` for the focused component, finds
that component's layers in `dumpsys SurfaceFlinger --list`, and reads
`dumpsys SurfaceFlinger --latency`. A fresh `(BLAST)` SurfaceView layer is
preferred over the activity layer. Frame timestamps are CLOCK_MONOTONIC
nanoseconds; only frames within the last 1500 ms count. Fewer than two fresh
frames, or a ring whose newest timestamp is older than the window, reports 0.
The overlay's own layer is never used. Without Shizuku the field is null and
the UI shows `—`.

## Sessions

One file per recording: `filesDir/sessions/session-<startedAt>[-N].csv`.
Version 2 columns:

```
t,cpu,cpuMax,cores,gpu,gpuHot,battery,thermal,board,source,fps
```

`cores` and `board` are semicolon lists (`name=value`). v1 files decode with a
null FPS series. Rows are flushed on append. The recorder owns one open file;
its name is published to the sessions screen so an in-progress recording cannot
be opened or deleted. Deletion is permanent and confirmed.

## UI

- The pill and expanded card are classic Views inside one
  `TYPE_APPLICATION_OVERLAY` window owned by the service.
- The main screen, sessions, session detail, diagnostics and setup guide are
  Compose activities.
- The session graph is a Compose Canvas: shared temperature scale, a separate
  0–120 FPS axis, a legend that dims/restacks series, and a crosshair tooltip.
- The card's 2-minute graph and min/max ranges use the same in-memory history.
- The expanded card has a full-width `Record session` / `Stop recording` toggle.
  It posts to the same recorder worker as the main-screen Record switch, so the
  CSV format and start/stop semantics are identical; the label and
  accessibility description come from `core/RecordingControl.kt`.
- Drag persists the window position and expanded state. Release geometry comes
  from `core/OverlayPlacement.kt`: with `Snap to edges` on (default) the window
  keeps an 8 dp margin from the nearer side; off, it stays at the exact drop
  position, clamped on-screen. The service reads the preference at each
  release, so a change needs no restart.

## Diagnostics

`DiagnosticsActivity` shows the live snapshot, Shizuku state, source errors and
the thermal-zone table. Its top "Feature status" table is one row per
measurement/action: battery, thermal status, CPU/GPU, board sensors, FPS, RAM,
process memory and Stop app. The decision is pure
(`core/Capabilities.kt`): demo, Shizuku state and overlay state gate the shell
rows; battery, thermal status and RAM are read directly and never need Shizuku.
Values that are partial or older than `STALE_AFTER_MS` are degraded; a 0 fps
sample is a valid idle measurement.

The overlay publishes FPS (every poll), meminfo (each scan) and Stop (each
attempt) probe results to `overlay/DiagnosticsStore.kt`, including the exact
failure reason and whether the cause was a missing shell prerequisite
(`shellBlocked`). Shell-blocked probes are cleared on Shizuku recovery and are
ignored once the prerequisites pass, so a stale "needs Shizuku" reason cannot
outlive the failure. Cancelled scans/stops publish a terminal state rather than
leaving `Scanning…`/`Stopping…`; the store is cleared on service create and by
the worker's final teardown post. The same rows are appended to the screen's
Copy report.

## Shizuku

`ShizukuDumpsysRunner` binds `DumpsysUserService` with
`Shizuku.bindUserService` (tag `dumpsys`, version 7). The AIDL exposes
`runDumpsys()`, `runFps()`, `runMeminfo()`, `runProcessUids()`,
`runPackageDump()` and `stopPackage()`. The service
executes absolute `/system/bin/dumpsys` commands and returns stdout/compact
strings; `stopPackage` validates the package name and runs
`/system/bin/am force-stop` with a `ProcessBuilder` argument array, returning
`OK`/`ERR …`. The app catches every binder failure and falls back to the next
source. The R8 keep rule is `dev.rsensors.android.shizuku.**` because Shizuku
starts the class by name.

On the tested POCO X7 Pro / Android 16, Shizuku 13.6.0 fails to start a
UserService (upstream issue #2633); 13.5.4 works. The setup guide warns about
that combination and points at Diagnostics for the version.

## Permissions and security

- `SYSTEM_ALERT_WINDOW`, foreground-service permissions, notifications and boot
  completed. No `INTERNET`, accessibility, screen capture or root.
- Session files and preferences are app-private.
- The Shizuku UserService runs as the shell user for the thermal, FPS and
  meminfo dumps, plus one confirmed `am force-stop` per user action. Process
  memory is a snapshot taken on demand, never part of the 2 s poll. The stop is
  package-wide and irreversible; the stop policy protects the overlay, Shizuku,
  core system, native and persistent processes.

## Tests

Pure logic is covered by JVM tests: formatting, thresholds, segments, FPS
parsing/freshness, CSV v1/v2, session chart geometry, zone mapping, visibility,
ranges, process-memory PSS parsing/grouping/paging, stop-risk tiers, capability
status/reason precedence, and the version predicate. The suite is independent
of a device. Overlay, Shizuku, recording, stop and UI behaviour are checked
manually on the target phone; see `docs/BUILDING.md`.
