# Changelog

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
