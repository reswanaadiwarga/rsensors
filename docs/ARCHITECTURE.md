# Architecture

Rsensors is a single Android app module. One foreground service hosts the
floating overlay and the poller; activities provide the main screen, sessions,
diagnostics and the Shizuku guide.

## Components

| Package | Responsibility |
|---|---|
| `core/` | pure Kotlin: `Reading`, thresholds, pill segments, formatting, FPS parsing, CSV sessions, chart geometry, visibility, ranges, Shizuku version predicate |
| `source/` | sensor sources, the source chain, the Shizuku runner, session storage |
| `shizuku/` | `DumpsysUserService` and the AIDL contract executed as the shell user |
| `overlay/` | foreground service, window, pill/card views, notification, recording, preferences |
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
```

Screen-off pauses polling and recording; screen-on resumes immediately.

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

## Shizuku

`ShizukuDumpsysRunner` binds `DumpsysUserService` with
`Shizuku.bindUserService` (tag `dumpsys`, version 3). The AIDL exposes
`runDumpsys()` and `runFps()`. The service executes absolute `/system/bin/dumpsys`
commands and returns compact strings; the app catches every binder failure and
falls back to the next source. The R8 keep rule is
`dev.rsensors.android.shizuku.**` because Shizuku starts the class by name.

On the tested POCO X7 Pro / Android 16, Shizuku 13.6.0 fails to start a
UserService (upstream issue #2633); 13.5.4 works. The setup guide warns about
that combination and points at Diagnostics for the version.

## Permissions and security

- `SYSTEM_ALERT_WINDOW`, foreground-service permissions, notifications and boot
  completed. No `INTERNET`, accessibility, screen capture or root.
- Session files and preferences are app-private.
- The Shizuku UserService runs as the shell user only for the two dumps.

## Tests

Pure logic is covered by JVM tests: formatting, thresholds, segments, FPS
parsing/freshness, CSV v1/v2, session chart geometry, zone mapping, visibility,
ranges, and the version predicate. The suite is independent of a device.
Overlay, Shizuku, recording and UI behaviour are checked manually on the target
phone; see `docs/BUILDING.md`.
