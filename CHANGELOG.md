# Changelog

## 0.3.1 — 2026-11-26

- Updated the Shizuku setup guide's **Shizuku version problems** section. It now
  tells users to check the installed manager version in the live status card,
  explains that an app store can reinstall 13.6.0 shortly after a 13.5.4
  downgrade, and names the auto-update setting on Xiaomi/POCO GetApps, Samsung
  Galaxy Store, Huawei/HONOR AppGallery and Google Play. No behavior changes.
- Release: versionCode 4, `0.3.1`, 1,155,040 bytes, SHA-256
  `96d8f83db06a04650377e74f89f2c9213d4824b5fc224b824fb24e53d33f563d`,
  debug-signed; 417 JVM tests unchanged.

## 0.3.0 — 2026-11-24

- Added a system RAM row to the expanded card: used / total in one-decimal GB
  with a compact usage bar. It reads `ActivityManager.MemoryInfo` on the normal
  poll, needs no Shizuku or extra permission, and keeps updating when the
  temperature reading is unavailable; a missing figure renders `—` with an empty
  bar.
- Added RAM as a fourth pill metric and card chip: `RAM 8.6G` after FPS in
  used-only decimal GB, coloured blue like the RAM bar. The `show_ram`
  preference persists like the other chips, and the last-chip rule covers all
  four. RAM is independent of the sensor reading, so a missing temperature
  sample still shows the RAM value without dimming the strip; an unavailable
  figure renders `RAM —`.
- Added the process-memory page: the expanded card's RAM row opens a paged list
  of PSS consumers from `dumpsys meminfo --oom` through Shizuku. `Apps` groups
  by package and hides Native/System, `Processes` shows every process, eight
  rows per page, with `Back` and `Refresh`. The scan runs on its own thread and
  the normal poll keeps updating; without Shizuku the page shows a Start
  Shizuku state instead of stale data. When the meminfo dump has no PSS rows,
  the page falls back to `ps` RSS and labels the metric actually shown.
- Added **Stop app** to the process page: tap a process/app row to select it
  (larger rows, blue tint), then Stop and confirm. A tiered policy protects
  Rsensors, Shizuku, native/system UIDs, persistent and core system packages,
  plus known OEM security/power-manager packages; system apps such as AICore,
  foreground/visible apps, the launcher, keyboard and Play services warn first.
  Hidden apps are resolved one package at a time through `dumpsys package`, and
  a shell UID table protects hidden core rows. The stop is Android's package
  `am force-stop` through Shizuku, not a raw kill; the shell checks `ps` for
  survivors, the page rescans once, and a system app that restarts simply
  reappears.
- Added the Diagnostics feature-status table: battery, thermal status, CPU/GPU,
  board sensors, FPS, RAM, process memory and Stop app, each with `OK`,
  `DEGRADED`, `UNAVAILABLE`, `BLOCKED` or `UNKNOWN`, its source and the exact
  reason. A failed shell probe keeps its cause until Shizuku or the overlay
  recovers.
- FPS parser portability: accepts Android 8–14 plain `--list` lines and
  Android 15+ `RequestedLayerState` wrappers, hash/legacy/BLAST names,
  `@0(BLAST)`, `desiredPresentTime = 0` rows and `AppWindowToken` focus. A
  hash-prefixed plain layer is used only when no exact twin exists.
- FPS app switching: while the cached layer is still drawing, a lighter
  `dumpsys window displays` focus read bypasses the 4 s re-resolve gate, so the
  value follows a foreground-app change on the next poll. A focus read failure
  keeps the cached FPS; an idle layer skips the extra dump.
- FPS graph axis: 120 fps remains the baseline, raised in 30 fps steps
  (150/180/…/500) when a session or the 2-minute sparkline records a higher
  peak. Sessions without FPS still hide the axis.
- Meminfo parser tolerance: work-profile `(pid N) (user 10)` rows, `kB`/`KB`,
  `/ activities` and `:remote` suffixes, case-insensitive headers, and unknown
  headings that become `Unknown` instead of inheriting the previous OOM group.
- Thermal hardening: CPU/GPU HAL entries aggregate by the hottest matching
  zone instead of the first, out-of-range values are rejected, and common OEM
  aliases (`AP`, `BAT`, `SUBBAT`, `soc-thermal`, `gpu_tmu`, `kgsl`) are mapped.
- Added a card `Record session` / `Stop recording` toggle that uses the same
  recorder as the main-screen switch, and a persisted `Snap to edges` setting:
  on (default) keeps an 8 dp margin from the nearer side after a drag; off
  keeps the exact drop position, clamped on-screen.
- Added brand-aware autostart and Shizuku guidance for Xiaomi/Redmi/POCO,
  Samsung, Huawei/HONOR, ColorOS/Oppo/Realme, vivo/iQOO and near-AOSP makers,
  plus a `Manage apps` fallback that opens Android's app list for a manual
  Force stop. The manual route is never reported as a verified stop.
- The Shizuku guide now covers the Android notification permission (needed for
  the pairing-code notification), the 13.5.4 download and install steps, the
  uninstall/re-pair/reset path, and the conditional 13.6.0 warning for the
  verified HyperOS/MediaTek device.
- Documentation now separates target-verified behaviour from compatibility
  guidance: `docs/VERIFICATION.md` is the verification matrix, and
  `docs/UNIVERSALITY.md` records the platform research and open limits.
- Tests: 417 JVM tests in 34 suites. Release: versionCode 3, `0.3.0`, R8 full
  mode and resource shrinking, 1,155,040 bytes, SHA-256
  `f356da99f680a5af891a3a6e32232e7d623d3b88ba4834ac8d89623f80255c7e`,
  debug-signed for personal sideloading. Device smoke on the POCO X7 Pro:
  thermal/FPS/meminfo/UID calls survive R8, Diagnostics reports all eight
  features OK, and the process page shows the PSS Apps/Processes views.

## 0.2.0 — 2026-11-22

- Added foreground FPS through Shizuku/SurfaceFlinger, with a 0–120 axis in the
  session graph and FPS in the CSV (`SessionLog` v2).
- Added the 2-minute overlay graph with a `CPU+GPU / CPU / GPU / BAT / FPS`
  selector.
- The pill now shows combined `CPU/GPU` (hotter value, worse level), `BAT` and
  `FPS`; the chips are CPU/GPU / BAT / FPS.
- The card keeps individual CPU/GPU/BAT rows and shows each value's 2-minute
  max/min. The `hot` and HAL-max fields are no longer displayed; they remain in
  the CSV.
- Session graph: legend chips dim and restack a series; table headers open
  column explanations; long-press selection with confirmed deletion; the
  recording session is excluded from the list until it stops.
- Added the in-app Shizuku setup guide with collapsible sections, the
  wireless-debugging toggle fix, the full re-pair reset, after-reboot and
  BAT checks, and a conditional version warning.
- Added a Start Shizuku prompt to the overlay card and a Shizuku help action to
  the notification while the server is down.
- Renamed the service controls to Start/Stop monitoring and added the
  explanatory subtitle.
- Release: versionCode 2, `0.2.0`, R8 and resource shrinking, 1,105,888 bytes,
  debug-signed for personal sideload.
- Installed and device-verified on the POCO X7 Pro: the Shizuku
  UserService/FPS path under R8, recording and the Sessions graph, the setup
  guide, and the degraded-state prompt.
- Tests: 214 JVM tests.

## 0.1.0 — 2026-11-21

- Initial release: floating pill and expanded card, CPU/GPU temperatures via
  Shizuku `dumpsys thermalservice`, battery temperature and thermal status,
  diagnostics with thermal-zone overrides, session recording, full-session
  graph, boot autostart, adaptive icon.
- R8 release, 1,053,252 bytes, debug-signed; 140 JVM tests.
