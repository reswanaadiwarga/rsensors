package dev.rsensors.android.overlay

import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.rsensors.android.core.GraphSeries
import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.ReadingHistory
import dev.rsensors.android.core.RamUsage
import dev.rsensors.android.core.Strip
import dev.rsensors.android.core.VisibilityKind
import dev.rsensors.android.core.iconSpec
import dev.rsensors.android.core.parseFpsReply
import dev.rsensors.android.core.stripSegments
import dev.rsensors.android.source.BatterySource
import dev.rsensors.android.source.DemoSource
import dev.rsensors.android.source.DirectReader
import dev.rsensors.android.source.DumpsysThermalSource
import dev.rsensors.android.source.FpsRunner
import dev.rsensors.android.source.SensorSource
import dev.rsensors.android.source.SessionStore
import dev.rsensors.android.source.ShizukuDumpsysRunner
import dev.rsensors.android.source.SourceChain
import dev.rsensors.android.source.SysfsSource
import dev.rsensors.android.source.ThermalStatusSource
import dev.rsensors.android.ui.DiagnosticsActivity
import dev.rsensors.android.ui.MainActivity
import dev.rsensors.android.ui.Palette
import dev.rsensors.android.ui.ShizukuHelpActivity
import java.io.File
import rikka.shizuku.Shizuku

/**
 * Foreground service that owns the overlay pill (ANDROID.md §4, §8 T6).
 *
 * Lifecycle: [onCreate] posts the notification, verifies the overlay grant and
 * adds the window; polling runs on its own [HandlerThread] and every UI touch
 * hops back to the main looper. The screen receiver pauses the worker while the
 * display is off and polls immediately on wake, so the pill is never stale when
 * it becomes visible again.
 *
 * Source selection is demo-vs-device, chosen once per start or restart. Device
 * mode leads with dumpsys through Shizuku (the only route to CPU/GPU on the
 * target, §10), then sysfs, battery and thermal status; a source that cannot
 * read stays null and the pill draws a dash or falls back to battery (§2.2).
 * The source dot is green for dumpsys, amber for sysfs and grey for the
 * battery/status fallback (§3).
 *
 * T9 adds the expanded card: every non-null reading also goes into an in-memory
 * [ReadingHistory] that feeds the sparkline, the poll interval follows the
 * collapsed/expanded state (5 s / 2 s), and the window position plus expanded
 * state are persisted so a force-stop and relaunch restores the panel (§8 T9).
 *
 * T12 adds recording: [ACTION_RECORD_START] opens a CSV session on the worker
 * and [ACTION_RECORD_STOP] flushes and closes it. The recorder lives and dies
 * with the poller, so screen-off pausing leaves the gap the session graph shows
 * (§3, §9); [onDestroy] closes it like an explicit stop.
 *
 * T14 adds the foreground FPS: each device-mode poll also asks the shared
 * Shizuku runner for one SurfaceFlinger measurement and copies a non-null value
 * onto the reading, so the recorder's v2 rows and the session graph see it.
 * Demo mode has no shell route, and an FPS failure is simply a null field.
 *
 * T15 adds the pill controls: the card's CPU/GPU / BAT / FPS / RAM chips persist
 * in [OverlayPreferences] and drive the strip, so the pill and the notification
 * always agree with the chips, and a toggle polls immediately instead of
 * waiting for the next tick. The card's Stop overlay button shares the
 * notification's exit path; [onDestroy] flushes the recorder and clears the
 * window in both cases.
 *
 * T15b gives the card's sparkline its own series selector: the persisted
 * [GraphSeries] is applied when the window is created and on every tap, with no
 * effect on polling, recording, the pill or the notification. The history keeps
 * full readings, so CPU, GPU and FPS remain available whichever series is drawn.
 *
 * T7 publishes each poll to [ReadingStore] for Diagnostics and keeps the sysfs
 * member plus its per-path errors. The expanded card's Diagnostics button and
 * the launcher's button both open [DiagnosticsActivity]; the screen also works
 * while the service is stopped, because it probes sysfs on its own.
 *
 * T15e publishes [recordingSessionName] for the saved-sessions screen, which
 * hides the file that is still being written; it is cleared on stop, on
 * [onDestroy] and whenever a failed append ends the recording.
 *
 * T15g keeps the tier-1 failure visible: every poll's Shizuku probe drives the
 * card's Start Shizuku row and a "Shizuku help" notification action, and both
 * open the manager or [ShizukuHelpActivity] when the server is down. The state
 * is part of the notification key, so the action appears and disappears with the
 * server.
 *
 * State: [isRunning] and [isRecording] are process-wide flags for the activity's
 * UI. [isRunning] turns true only after the window is attached and false in
 * [onDestroy], so the activity never claims a running overlay that failed to
 * appear. [isRecording] follows the worker's writer, except that a stop request
 * clears it immediately so the switch answers in the same frame.
 *
 * Errors: a refused `addView` (permission revoked between the check and the
 * call, or a bad token) stops the service instead of crashing; a refused
 * notification update is logged and skipped.
 */
class OverlayService : Service() {
  private var windowManager: WindowManager? = null
  private var view: OverlayView? = null

  /** Live window parameters, shared with [OverlayView] so drags stay in sync. */
  private var params: WindowManager.LayoutParams? = null

  private var powerManager: PowerManager? = null

  private var handlerThread: HandlerThread? = null
  private var workerHandler: Handler? = null
  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Sparkline buffer. Touched only on the worker: [pollRunnable] appends and
   * snapshots, [installSource] clears; the snapshot list is handed to the view
   * on the main thread as an immutable copy.
   */
  private val history = ReadingHistory()

  /**
   * Session recorder, created on first use on the worker. It opens files under
   * `filesDir/sessions` and is closed by [ACTION_RECORD_STOP] or [onDestroy].
   */
  private val recorder by lazy {
    SessionRecorder(SessionStore(File(filesDir, SessionStore.DIRECTORY)))
  }

  /** Card state; read on the worker to pick the poll interval. */
  @Volatile private var expanded = false

  /**
   * Owner-thread-only source; rebuilt on ACTION_RESTART. Mutated and closed on
   * the worker, including the final close during [onDestroy].
   */
  private var source: SensorSource? = null

  /**
   * The sysfs member of the current chain, kept so Diagnostics can show its
   * per-path errors. Worker-thread only, like [source].
   */
  private var sysfsSource: SysfsSource? = null

  /**
   * FPS path for the current chain, sharing the Shizuku UserService binding with
   * the thermal dump; null in demo mode. Worker-thread only, like [source].
   */
  private var fpsRunner: FpsRunner? = null

  /** The current chain, kept for its per-source exception log. Worker-only. */
  private var chain: SourceChain? = null

  /** True while a poll is scheduled or executing; written on both threads. */
  @Volatile private var polling = false

  /** Worker-thread-only: log the first completed poll of each source build. */
  private var awaitingFirstPoll = false

  /**
   * Last notification content; a change here is what makes a notification
   * update. Written on the worker when a source is installed and on the main
   * thread when the shade is refreshed, hence the volatile marker.
   */
  @Volatile private var notificationKey: String? = null

  private var receiverRegistered = false

  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        Intent.ACTION_SCREEN_OFF -> pausePolling()
        Intent.ACTION_SCREEN_ON -> startPolling(logResume = true)
      }
    }
  }

  private val pollRunnable = object : Runnable {
    override fun run() {
      if (!polling) return
      val now = System.currentTimeMillis()
      val base = source?.poll(now)
      // FPS rides on the same poll: null leaves the field unavailable without
      // touching temperatures, recording or the notification (ANDROID.md §9).
      val fps = pollFps()
      val reading = if (fps != null) base?.copy(fps = fps) else base
      // System RAM is independent of the Shizuku source, so it is read every
      // tick and survives a null reading; a failure only blanks the RAM row.
      val ram = pollRam()
      // Diagnostics reads this on another thread; build the immutable snapshot
      // once per poll, including a poll that produced no reading, so the screen
      // can show the failure instead of a stale success (ANDROID.md §8 T7).
      // One binder probe per poll feeds the Diagnostics snapshot, the card's
      // Start Shizuku row and the notification's help action, so those three
      // never disagree within a tick (T15g).
      val shizukuRunning = isShizukuRunning()
      ReadingStore.snapshot = SourceSnapshot(
        reading = reading,
        now = now,
        chainErrors = chain?.lastErrors.orEmpty(),
        sysfsErrors = sysfsSource?.lastErrors.orEmpty(),
        shizukuRunning = shizukuRunning,
        shizukuGranted = hasShizukuPermission(),
        overlayRunning = true,
      )
      if (reading != null) {
        history.add(reading)
        // Recording follows the poller exactly: screen-off pauses both, and the
        // resulting gap is what the saved session shows (§9).
        if (isRecording) {
          recorder.append(reading)
          // A failed append closes the writer. Publish idle so the finished
          // file becomes listable again and no stale name hides a deleted or
          // inactive session (T15e).
          if (!recorder.isRecording) {
            isRecording = false
            recordingSessionName = null
            mainHandler.post { view?.setRecording(false) }
          }
        }
      }
      val snapshot = history.snapshot(now)
      if (awaitingFirstPoll) {
        Log.i(TAG, "first poll")
        awaitingFirstPoll = false
      }
      // The chips are the single switch for pill + notification: read them per
      // poll so a preference written by anything else is honoured next tick.
      val visibility = OverlayPreferences.visibility(this@OverlayService)
      // RAM rides along with the same sample the card draws, so the chip's
      // segment and the card's RAM row never disagree within a tick.
      val strip = stripSegments(reading, now, visibility, ram = ram)
      mainHandler.post {
        val current = view ?: return@post
        current.setVisibility(visibility)
        current.setShizukuRunning(shizukuRunning)
        current.setStrip(strip, dotColorFor(reading))
        current.setReading(reading, snapshot, now, ram)
        updateNotification(strip, reading, now, shizukuRunning)
      }
      if (polling) {
        // Replacing the pending poll instead of only adding one keeps a single
        // cadence chain when screen-on or a toggle reschedules mid-poll. The
        // interval is read here so a toggle applies from the next tick on.
        workerHandler?.removeCallbacks(this)
        workerHandler?.postDelayed(this, pollIntervalMs())
      }
    }
  }

  /**
   * One foreground FPS value for this poll, or null when the shell route is
   * unavailable. Called on the worker, after the temperature poll, so a failed
   * or slow FPS call can never blank the temperatures; demo mode has no runner.
   */
  private fun pollFps(): Double? {
    val runner = fpsRunner ?: return null
    return try {
      runner.runFps()?.let(::parseFpsReply)
    } catch (e: Exception) {
      Log.w(TAG, "fps poll failed", e)
      null
    }
  }

  /**
   * One system RAM sample for this poll, or null when the platform gives no
   * usable total.
   *
   * Runs on the worker with the rest of the poll. [ActivityManager.MemoryInfo]
   * needs no Shizuku, permission or shell route, which is why the card's RAM row
   * keeps updating while the temperature source is down. A null result only
   * empties the row and bar; it must never fail the poll, strip, recording,
   * diagnostics snapshot or notification.
   */
  private fun pollRam(): RamUsage? =
    try {
      val info = ActivityManager.MemoryInfo()
      getSystemService(ActivityManager::class.java).getMemoryInfo(info)
      if (info.totalMem <= 0L) {
        null
      } else {
        RamUsage(
          usedBytes = (info.totalMem - info.availMem).coerceAtLeast(0L),
          totalBytes = info.totalMem,
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "ram poll failed", e)
      null
    }

  /** Callbacks the panel raises; all arrive on the main thread. */
  private val viewCallbacks = object : OverlayView.Callbacks {
    override fun onToggleExpanded() {
      toggleExpanded()
    }

    override fun onLongPress() {
      openApp()
    }

    override fun onRefresh() {
      // An immediate poll; the steady cadence resumes from its completion.
      if (isInteractive()) startPolling(logResume = false)
    }

    override fun onDiagnostics() {
      openDiagnostics()
    }

    override fun onVisibilityToggled(kind: VisibilityKind) {
      // Persist first so the next poll (posted below) and a restart agree with
      // the chip the user just tapped; toggle() itself refuses to empty the pill.
      val next = OverlayPreferences.visibility(this@OverlayService).toggle(kind)
      OverlayPreferences.setVisibility(this@OverlayService, next)
      view?.setVisibility(next)
      // One immediate poll, so the pill and the notification drop or regain the
      // kind in the same tick instead of waiting up to a full interval.
      if (isInteractive()) startPolling(logResume = false)
    }

    override fun onGraphSeriesChanged(series: GraphSeries) {
      // The graph selector is independent of the chips: persist it and redraw
      // the existing history at once. No poll or notification change is needed.
      OverlayPreferences.setGraphSeries(this@OverlayService, series)
      view?.setGraphSeries(series)
    }

    override fun onStopOverlay() {
      // Same exit path as the notification's Stop action: onDestroy removes the
      // window, flushes the recorder and clears ReadingStore (ANDROID.md §9).
      stopSelf()
    }

    override fun onStartShizuku() {
      startShizuku()
    }

    override fun onPositionChanged(x: Int, y: Int, expanded: Boolean) {
      OverlayPreferences.setPosition(this@OverlayService, x, y)
    }
  }

  override fun onCreate() {
    super.onCreate()
    NotificationFactory.createChannel(this)
    ServiceCompat.startForeground(
      this,
      NotificationFactory.NOTIFICATION_ID,
      NotificationFactory.placeholder(this),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        0
      },
    )

    if (!Settings.canDrawOverlays(this)) {
      Log.i(TAG, "overlay permission missing; stopping")
      stopSelf()
      return
    }

    windowManager = getSystemService(WindowManager::class.java)
    powerManager = getSystemService(PowerManager::class.java)
    if (!addOverlay()) return

    startWorker()
    registerScreenReceiver()
    workerHandler?.post {
      installSource()
      if (isInteractive()) {
        startPolling(logResume = false)
      } else {
        Log.i(TAG, "paused")
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
    when (intent?.action) {
      ACTION_STOP -> {
        stopSelf()
        START_NOT_STICKY
      }

      ACTION_RESTART -> {
        workerHandler?.post {
          installSource()
          if (isInteractive()) {
            startPolling(logResume = false)
          } else {
            Log.i(TAG, "paused")
          }
        }
        START_STICKY
      }

      ACTION_RECORD_START -> {
        workerHandler?.post { startRecordingOnWorker() }
        START_STICKY
      }

      ACTION_RECORD_STOP -> {
        // The switch should answer in the same frame; the worker owns the file
        // and closes it in queue order behind any in-flight poll.
        isRecording = false
        view?.setRecording(false)
        workerHandler?.post { stopRecordingOnWorker() }
        START_STICKY
      }

      else -> START_STICKY
    }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    polling = false
    workerHandler?.removeCallbacks(pollRunnable)
    // Close on the worker so the source's owner thread stays the only thread
    // that touches it; quitSafely still delivers this queued message.
    workerHandler?.post {
      source?.close()
      source = null
      chain = null
      sysfsSource = null
      fpsRunner = null
      // Runs after any in-flight poll, so a poll that passed its stop check
      // cannot leave a "running" snapshot behind after onDestroy cleared it.
      ReadingStore.snapshot = null
      // Every row was flushed on append; closing only releases the file. A
      // recording still open when the service dies ends here like a stop.
      recorder.stop()
    }
    handlerThread?.quitSafely()
    handlerThread = null
    workerHandler = null

    if (receiverRegistered) {
      try {
        unregisterReceiver(screenReceiver)
      } catch (e: IllegalArgumentException) {
        // Already unregistered by the framework; nothing to undo.
      }
      receiverRegistered = false
    }

    view?.let { current ->
      try {
        windowManager?.removeView(current)
      } catch (e: IllegalArgumentException) {
        // The window was never attached or already removed.
      }
    }
    view = null
    isRunning = false
    isRecording = false
    recordingSessionName = null
    // No poller can refresh the snapshot after this point; Diagnostics must say
    // "not running" instead of showing the last pre-stop values as current.
    ReadingStore.snapshot = null
    Log.i(TAG, "stopped")
    super.onDestroy()
  }

  /** Attaches the panel; stops the service when the manager refuses it. */
  private fun addOverlay(): Boolean {
    val manager = windowManager ?: return false
    val layoutParams = OverlayParams.create(this)
    applySavedPosition(layoutParams)
    expanded = OverlayPreferences.isExpanded(this)
    val panel = OverlayView(this, manager, layoutParams, viewCallbacks)
    panel.setExpanded(expanded)
    // Paint the persisted chips before the first poll so the card never shows a
    // default state that disagrees with the preference (T15).
    panel.setVisibility(OverlayPreferences.visibility(this))
    panel.setGraphSeries(OverlayPreferences.graphSeries(this))
    return try {
      manager.addView(panel, layoutParams)
      params = layoutParams
      view = panel
      isRunning = true
      Log.i(TAG, "overlay added")
      true
    } catch (e: SecurityException) {
      Log.i(TAG, "overlay add refused: ${e.javaClass.simpleName}")
      stopSelf()
      false
    } catch (e: WindowManager.BadTokenException) {
      Log.i(TAG, "overlay add refused: ${e.javaClass.simpleName}")
      stopSelf()
      false
    }
  }

  /**
   * Restores the last dragged position, falling back to the dp defaults.
   *
   * The view's size is unknown before the first layout, so this clamps against
   * the display only; [OverlayView] pulls the window fully on screen once it has
   * been measured (which also covers a width saved on another orientation).
   */
  private fun applySavedPosition(layoutParams: WindowManager.LayoutParams) {
    val manager = windowManager ?: return
    val bounds = OverlayParams.screenBounds(manager, this)
    val savedX = OverlayPreferences.positionX(this)
    val savedY = OverlayPreferences.positionY(this)
    if (savedX != OverlayPreferences.POSITION_UNSET) {
      layoutParams.x = OverlayParams.clamp(savedX, bounds.width())
    }
    if (savedY != OverlayPreferences.POSITION_UNSET) {
      layoutParams.y = OverlayParams.clamp(savedY, bounds.height())
    }
  }

  /**
   * Flips the card, persists the state, re-lays the window out and polls at
   * once so the card is never a step behind the pill.
   */
  private fun toggleExpanded() {
    val next = !expanded
    expanded = next
    OverlayPreferences.setExpanded(this, next)
    view?.setExpanded(next)
    view?.let { current -> params?.let { windowManager?.updateViewLayout(current, it) } }
    // Toggling is only possible while the display is on; if it is not, keep the
    // screen-off pause intact rather than waking the poller.
    if (isInteractive()) startPolling(logResume = false)
  }

  /**
   * Opens the CSV session and shows the pill's REC marker.
   *
   * Runs on the worker so the writer has a single owner. A fresh poll is queued
   * when the display is on, so the first row lands within a second instead of
   * waiting up to a full collapsed interval.
   */
  private fun startRecordingOnWorker() {
    if (recorder.isRecording) return
    val started = recorder.start(System.currentTimeMillis())
    isRecording = started
    // The sessions screen hides the file that is still being written, so it can
    // never be deleted mid-recording (T15e).
    recordingSessionName = if (started) recorder.currentName else null
    Log.i(TAG, if (started) "recording started" else "recording failed")
    mainHandler.post { view?.setRecording(isRecording) }
    if (started && isInteractive()) startPolling(logResume = false)
  }

  /** Flushes and closes the session; also the exit path when a write fails. */
  private fun stopRecordingOnWorker() {
    val wasRecording = recorder.isRecording
    recorder.stop()
    isRecording = false
    recordingSessionName = null
    if (wasRecording) Log.i(TAG, "recording stopped")
    mainHandler.post { view?.setRecording(false) }
  }

  private fun startWorker() {
    if (handlerThread != null) return
    val thread = HandlerThread("rsensors-poll")
    thread.start()
    handlerThread = thread
    workerHandler = Handler(thread.looper)
  }

  /** Closes the old source and builds one for the current preferences. */
  private fun installSource() {
    source?.close()
    source = buildSource()
    // A new source is a new measurement world: the old graph would mix routes.
    history.clear()
    notificationKey = null
    awaitingFirstPoll = true
  }

  /**
   * Builds the device chain in preference order (ANDROID.md §5): dumpsys first
   * because it is the only source of CPU/GPU on the target phone, then sysfs,
   * then the always-available battery and thermal-status sources.
   *
   * The sysfs source is retained and given a live override lookup, so a chip in
   * Diagnostics changes the next poll without rebuilding the chain.
   */
  private fun buildSource(): SensorSource {
    chain = null
    sysfsSource = null
    fpsRunner = null
    if (OverlayPreferences.isDemo(this)) return DemoSource()

    // One runner for both calls: the thermal dump and the SurfaceFlinger probe
    // must share a single UserService binding (ANDROID.md §9).
    val runner = ShizukuDumpsysRunner(this)
    fpsRunner = runner
    val sysfs =
      SysfsSource(
        reader = DirectReader(),
        overrides = { ZoneOverrideStore.load(this) },
      )
    val built =
      SourceChain(
        listOf(
          DumpsysThermalSource(runner),
          sysfs,
          BatterySource(this),
          ThermalStatusSource(this),
        ),
      )
    sysfsSource = sysfs
    chain = built
    return built
  }

  /**
   * Picks the source dot colour from the merged reading's source names
   * (ANDROID.md §3).
   *
   * dumpsys is the trustworthy HAL route (green), sysfs is best-effort (amber),
   * and battery/status alone is the last resort (grey). A null reading has no
   * source name and therefore draws grey, which is the honest state before the
   * first value arrives.
   *
   * @param reading merged sample, or null when every source came back empty
   */
  private fun dotColorFor(reading: Reading?): Int {
    val name = reading?.sourceName.orEmpty()
    return when {
      "dumpsys" in name -> Palette.green
      "sysfs" in name -> Palette.amber
      else -> Palette.gray
    }
  }

  /**
   * Schedules an immediate poll.
   *
   * Safe from any thread: it only posts to the worker queue and flips the
   * volatile flag. The runnable itself always executes on the worker.
   */
  private fun startPolling(logResume: Boolean) {
    if (logResume) Log.i(TAG, "resumed")
    polling = true
    workerHandler?.removeCallbacks(pollRunnable)
    workerHandler?.post(pollRunnable)
  }

  /** Drops the pending poll and marks the worker idle. */
  private fun pausePolling() {
    if (!polling) return
    polling = false
    workerHandler?.removeCallbacks(pollRunnable)
    Log.i(TAG, "paused")
  }

  private fun isInteractive(): Boolean =
    powerManager?.isInteractive ?: true

  /** Card expanded polls every 2 s, collapsed every 5 s (ANDROID.md §3). */
  private fun pollIntervalMs(): Long =
    if (expanded) POLL_EXPANDED_MS else POLL_COLLAPSED_MS

  private fun registerScreenReceiver() {
    if (receiverRegistered) return
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_SCREEN_ON)
      addAction(Intent.ACTION_SCREEN_OFF)
    }
    ContextCompat.registerReceiver(
      this,
      screenReceiver,
      filter,
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    receiverRegistered = true
  }

  /**
   * Publishes changed content to the shade, if the user allows notifications.
   *
   * Called on the main thread after every poll. The key comparison keeps a
   * steady reading from re-posting an identical notification; the permission
   * check and the exception guard cover a revoked POST_NOTIFICATIONS grant. The
   * key includes the Shizuku state so the degraded-state help action appears
   * and disappears as the server starts and stops (T15g).
   *
   * @param shizukuRunning false adds the "Shizuku help" action
   */
  private fun updateNotification(
    strip: Strip,
    reading: Reading?,
    now: Long,
    shizukuRunning: Boolean,
  ) {
    val text = strip.segments.joinToString(" ") { it.text }
    val key = "$text|${iconSpec(reading, now).label}|shizuku=$shizukuRunning"
    if (key == notificationKey) return
    if (!notificationsAllowed()) return
    try {
      NotificationManagerCompat.from(this)
        .notify(
          NotificationFactory.NOTIFICATION_ID,
          NotificationFactory.build(this, strip, reading, now, shizukuRunning),
        )
      notificationKey = key
    } catch (e: SecurityException) {
      Log.i(TAG, "notification update refused")
    }
  }

  private fun notificationsAllowed(): Boolean =
    try {
      NotificationManagerCompat.from(this).areNotificationsEnabled()
    } catch (e: SecurityException) {
      false
    }

  private fun openApp() {
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    startActivity(intent)
  }

  /**
   * Opens Diagnostics from the expanded card (ANDROID.md §3, §8 T7).
   *
   * The service owns the overlay window, not an activity task, so the intent
   * needs NEW_TASK. The screen reads [ReadingStore] and probes sysfs itself; it
   * does not require the overlay to keep running.
   */
  private fun openDiagnostics() {
    val intent = Intent(this, DiagnosticsActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
  }

  /** `pingBinder` is false when the Shizuku server is stopped or absent. */
  private fun isShizukuRunning(): Boolean =
    try {
      Shizuku.pingBinder()
    } catch (e: Exception) {
      false
    }

  /**
   * Opens the Shizuku manager from the card's Start Shizuku button (T15g).
   *
   * The app cannot start the server itself (§6), so the button hands over to the
   * manager where the user taps Start. When the manager is missing — or refuses
   * to launch — the setup guide opens instead, because it is the install path.
   */
  private fun startShizuku() {
    val intent = try {
      packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    } catch (e: Exception) {
      null
    }
    if (intent == null) {
      openShizukuHelp()
      return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      startActivity(intent)
    } catch (e: Exception) {
      openShizukuHelp()
    }
  }

  /**
   * Opens the setup guide from the card or the notification's help action.
   *
   * The service owns no task, hence NEW_TASK; a refusal must not crash the
   * foreground service, so it is logged and dropped.
   */
  private fun openShizukuHelp() {
    val intent = Intent(this, ShizukuHelpActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
      startActivity(intent)
    } catch (e: Exception) {
      Log.i(TAG, "shizuku help refused: ${e.javaClass.simpleName}")
    }
  }

  /** Throws while the server is stopped, which counts as "not granted". */
  private fun hasShizukuPermission(): Boolean =
    try {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
      false
    }

  companion object {
    /** Logcat tag for lifecycle events; polls are deliberately not logged. */
    const val TAG = "Rsensors"

    /** Collapsed-pill cadence (ANDROID.md §3). */
    const val POLL_COLLAPSED_MS = 5_000L

    /** Expanded-card cadence: the sparkline needs twice the resolution. */
    const val POLL_EXPANDED_MS = 2_000L

    const val ACTION_START = "dev.rsensors.android.overlay.START"
    const val ACTION_STOP = "dev.rsensors.android.overlay.STOP"
    const val ACTION_RESTART = "dev.rsensors.android.overlay.RESTART"
    const val ACTION_RECORD_START = "dev.rsensors.android.overlay.RECORD_START"
    const val ACTION_RECORD_STOP = "dev.rsensors.android.overlay.RECORD_STOP"

    /** Shizuku manager package; shared by the Start Shizuku button (T15g). */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** True from the moment the window is attached until [onDestroy]. */
    @Volatile
    var isRunning = false
      private set

    /** True while a session file is open; the activity's Record switch reads it. */
    @Volatile
    var isRecording = false
      private set

    /**
     * File name of the session currently open for writing, or null when idle.
     *
     * [SessionsActivity] hides this file so an in-progress session can never be
     * deleted; it appears once the recorder stops (ANDROID.md §9, T15e).
     */
    @Volatile
    var recordingSessionName: String? = null
      private set

    /** Starts or reuses the foreground service. Call from a visible activity. */
    fun start(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, OverlayService::class.java).setAction(ACTION_START),
      )
    }

    /** Asks the system to tear the service down. */
    fun stop(context: Context) {
      context.stopService(Intent(context, OverlayService::class.java))
    }

    /**
     * Rebuilds the source with the current demo preference and polls at once.
     *
     * Used when the demo switch changes while the overlay is visible.
     */
    fun restart(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, OverlayService::class.java).setAction(ACTION_RESTART),
      )
    }

    /**
     * Opens a recording in the overlay service, starting the service if needed.
     *
     * Call from a visible activity with the overlay permission granted; the
     * service's `onCreate` refuses to run without it.
     */
    fun startRecording(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, OverlayService::class.java).setAction(ACTION_RECORD_START),
      )
    }

    /** Flushes and closes the current session; a no-op when none is open. */
    fun stopRecording(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, OverlayService::class.java).setAction(ACTION_RECORD_STOP),
      )
    }
  }
}
