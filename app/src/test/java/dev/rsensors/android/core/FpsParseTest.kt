package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus/layer/latency parsing and the fresh-window FPS estimate (ANDROID.md §8
 * T14, §9).
 *
 * Fixtures are the captured probe shapes from `input/t14-fps-probe.txt`: a
 * Settings `ActivityRecord` focus line, the `--list` layer names for Settings
 * and for the Wuthering Waves BLAST SurfaceView, and `--latency` rows in
 * CLOCK_MONOTONIC nanoseconds. Expected values are hand-derived: 16.667 ms
 * between five frames is 60 fps, a ring older than the 1.5 s window is 0, and
 * the shell reply carries the double after `fps=`.
 */
class FpsParseTest {

  private val settingsComponent = "com.android.settings/com.android.settings.MiuiSettings"
  private val gameComponent =
    "com.kurogame.wutheringwaves.global/com.epicgames.ue4.GameActivity"

  private val settingsList = listOf(
    "RequestedLayerState{com.android.settings/com.android.settings.MiuiSettings#5643 parentId=129}",
    "RequestedLayerState{9ecc164 com.android.settings/com.android.settings.MiuiSettings#5342 parentId=129}",
    "RequestedLayerState{ActivityRecord{1a2b3c u0 com.android.settings/.MiuiSettings t5643}#5644 parentId=129}",
    "RequestedLayerState{ActivityRecordInputSink com.android.settings/.MiuiSettings#5645 parentId=129}",
    "RequestedLayerState{Background for com.android.settings/com.android.settings.MiuiSettings#5646 parentId=129}",
    "RequestedLayerState{Bounds for com.android.settings/com.android.settings.MiuiSettings#5647 parentId=129}",
  ).joinToString("\n")

  private val gameList = listOf(
    "RequestedLayerState{89e2605 SurfaceView[$gameComponent](BLAST)#5734 parentId=129}",
    "RequestedLayerState{89e2605 SurfaceView[$gameComponent]#5733 parentId=129}",
    "RequestedLayerState{$gameComponent#5729 parentId=129}",
    "RequestedLayerState{cc6d380 $gameComponent#5726 parentId=129}",
  ).joinToString("\n")

  @Test
  fun `focused component expands a leading dot against the package`() {
    val dump = "mFocusedApp=ActivityRecord{230081998 u0 com.android.settings/.MiuiSettings t5643}"

    assertEquals(settingsComponent, parseFocusedComponent(dump))
  }

  @Test
  fun `focused component falls back to the current focus window`() {
    val dump =
      "mCurrentFocus=Window{81c33ba u0 com.mi.android.globallauncher/com.miui.home.launcher.Launcher}"

    assertEquals(
      "com.mi.android.globallauncher/com.miui.home.launcher.Launcher",
      parseFocusedComponent(dump),
    )
  }

  @Test
  fun `focused component keeps a fully qualified class verbatim`() {
    val dump = "mFocusedApp=ActivityRecord{1 u0 $gameComponent t2}"

    assertEquals(gameComponent, parseFocusedComponent(dump))
  }

  @Test
  fun `focused component is null without a recognised focus line`() {
    assertNull(parseFocusedComponent("mFocusedWindow=Window{1 u0 com.example/.Main}"))
    assertNull(parseFocusedComponent("mCurrentFocus=null"))
  }

  @Test
  fun `candidate layers keep the plain layer and skip containers`() {
    // The hash-prefixed twin (#5342) is an Android 16 container with valid=0
    // when the exact plain layer exists, so it must not be a candidate.
    assertEquals(
      listOf("com.android.settings/com.android.settings.MiuiSettings#5643"),
      parseCandidateLayers(settingsList, settingsComponent),
    )
  }

  @Test
  fun `candidate layers order the BLAST SurfaceView before the plain game layer`() {
    val candidates = parseCandidateLayers(gameList, gameComponent)

    assertEquals(
      listOf(
        "89e2605 SurfaceView[$gameComponent](BLAST)#5734",
        "$gameComponent#5729",
      ),
      candidates,
    )
  }

  @Test
  fun `a fresh BLAST SurfaceView wins over a plain activity layer`() {
    // Both layers can be drawing: a game updates its HWUI activity layer and
    // its render layer at the same time. The resolver takes the first candidate
    // that has two fresh frames, so the parser must return the SurfaceView
    // first; otherwise the graph would show the UI rate, not the render rate
    // (ANDROID.md §8 T14).
    val bothLayers = listOf(
      "RequestedLayerState{$gameComponent#5729 parentId=129}",
      "RequestedLayerState{89e2605 SurfaceView[$gameComponent](BLAST)#5734 parentId=129}",
    ).joinToString("\n")

    val candidates = parseCandidateLayers(bothLayers, gameComponent)

    assertEquals(
      listOf(
        "89e2605 SurfaceView[$gameComponent](BLAST)#5734",
        "$gameComponent#5729",
      ),
      candidates,
    )
  }

  @Test
  fun `candidate layers reject the hash container and the non-BLAST SurfaceView`() {
    val candidates = parseCandidateLayers(gameList, gameComponent)

    assertEquals(2, candidates.size)
    assertTrue(candidates.none { it.endsWith("GameActivity#5726") })
    assertTrue(candidates.none { it.endsWith("GameActivity#5733") })
  }

  @Test
  fun `focused component reads an AppWindowToken focus line`() {
    val dump = "mFocusedApp=AppWindowToken{9f4e2b1 token=Token{7a1 " +
      "ActivityRecord{abc u0 com.example.app/.Main t12}}}"

    assertEquals("com.example.app/com.example.app.Main", parseFocusedComponent(dump))
  }

  @Test
  fun `focused component reads a generic mFocusedApp token line`() {
    val dump = "mFocusedApp=WindowToken{4d2 u0 com.example.app/.Main t7}"

    assertEquals("com.example.app/com.example.app.Main", parseFocusedComponent(dump))
  }

  @Test
  fun `focused component does not match unrelated focus lines`() {
    assertNull(parseFocusedComponent("mFocusedWindow=Window{1 u0 com.example/.Main}"))
    assertNull(parseFocusedComponent("mFocusedApp=null"))
  }

  @Test
  fun `focused component reads a plain legacy focus line`() {
    // Android 8 prints `AppWindowToken{hash token=Token{hash ActivityRecord{...}}}`;
    // older dumps are just `mFocusedApp=... u<id> pkg/cls ...`.
    val dump = "mFocusedApp=AppWindowToken{3f1 u0 com.example.app/com.example.app.Main t9}"

    assertEquals("com.example.app/com.example.app.Main", parseFocusedComponent(dump))
  }

  @Test
  fun `a plain layer list line resolves the focused component`() {
    val dump = listOf(
      "SurfaceView[$settingsComponent]#1",
      settingsComponent + "#5643",
      "Background for $settingsComponent#5646",
    ).joinToString("\n")

    assertEquals(
      listOf("com.android.settings/com.android.settings.MiuiSettings#5643"),
      parseCandidateLayers(dump, settingsComponent),
    )
  }

  @Test
  fun `a plain legacy SurfaceView layer is preferred over the plain layer`() {
    val dump = listOf(
      gameComponent + "#5729",
      "SurfaceView - $gameComponent#5735",
    ).joinToString("\n")

    assertEquals(
      listOf(
        "SurfaceView - $gameComponent#5735",
        gameComponent + "#5729",
      ),
      parseCandidateLayers(dump, gameComponent),
    )
  }

  @Test
  fun `hash prefixed BLAST resolves before the exact plain layer`() {
    // The hash-prefixed BLAST layer is the game's real render target and stays
    // a candidate, but the hash-prefixed plain twin is a container here.
    val dump = listOf(
      "cc6d380 $gameComponent#5726",
      "89e2605 SurfaceView[$gameComponent](BLAST)#5734",
      gameComponent + "#5729",
    ).joinToString("\n")

    assertEquals(
      listOf(
        "89e2605 SurfaceView[$gameComponent](BLAST)#5734",
        gameComponent + "#5729",
      ),
      parseCandidateLayers(dump, gameComponent),
    )
  }

  @Test
  fun `hash prefixed plain layer is the fallback when no exact plain layer exists`() {
    // Android 17 prefixes every name; without an exact plain twin the prefixed
    // layer is the only activity-layer candidate.
    val dump = listOf(
      "89e2605 SurfaceView[$gameComponent](BLAST)#5734",
      "cc6d380 $gameComponent#5726",
    ).joinToString("\n")

    assertEquals(
      listOf(
        "89e2605 SurfaceView[$gameComponent](BLAST)#5734",
        "cc6d380 $gameComponent#5726",
      ),
      parseCandidateLayers(dump, gameComponent),
    )
  }

  @Test
  fun `both BLAST at zero orderings resolve`() {
    val dump = listOf(
      "SurfaceView[$gameComponent]@0(BLAST)#5734",
      "SurfaceView[$gameComponent](BLAST)@0#5735",
    ).joinToString("\n")

    assertEquals(
      listOf(
        "SurfaceView[$gameComponent]@0(BLAST)#5734",
        "SurfaceView[$gameComponent](BLAST)@0#5735",
      ),
      parseCandidateLayers(dump, gameComponent),
    )
  }

  @Test
  fun `plain layer lists reject containers the non-BLAST SurfaceView and other components`() {
    val dump = listOf(
      "ActivityRecord{1a2b3c u0 $gameComponent t5643}#5644",
      "ActivityRecordInputSink $gameComponent#5645",
      "Background for $gameComponent#5646",
      "Bounds for $gameComponent#5647",
      "SurfaceView[$gameComponent]#5733",
      "unrelated.pkg/unrelated.pkg.Activity#2",
      gameComponent + "#5729",
    ).joinToString("\n")

    assertEquals(listOf(gameComponent + "#5729"), parseCandidateLayers(dump, gameComponent))
  }

  @Test
  fun `identical layer names are deduplicated`() {
    val dump = listOf(gameComponent + "#5729", gameComponent + "#5729").joinToString("\n")

    assertEquals(listOf(gameComponent + "#5729"), parseCandidateLayers(dump, gameComponent))
  }

  @Test
  fun `latency parsing keeps a zero desired present with valid actual and ready`() {
    // Some devices report `desiredPresentTime = 0` for frames that were still
    // presented; only actual and ready decide whether a row is a frame.
    val text = "16666667\n" +
      "0 100 200\n"

    assertEquals(listOf(FrameRow(0L, 100L, 200L)), parseLatencyRows(text))
  }

  @Test
  fun `latency parsing rejects a zero actual or ready`() {
    val text = "16666667\n" +
      "10 0 30\n" +
      "10 20 0\n" +
      "10 20 30\n"

    assertEquals(listOf(FrameRow(10L, 20L, 30L)), parseLatencyRows(text))
  }

  @Test
  fun `latency parsing rejects a desired sentinel`() {
    val text = "16666667\n" +
      "9223372036854775807 20 30\n"

    assertEquals(emptyList<FrameRow>(), parseLatencyRows(text))
  }

  @Test
  fun `latency parsing drops zero and INT64_MAX sentinels`() {
    val text = "16666667\n" +
      "0 0 0\n" +
      "9223372036854775807 9223372036854775807 9223372036854775807\n" +
      "100 200 300\n"

    assertEquals(listOf(FrameRow(100L, 200L, 300L)), parseLatencyRows(text))
  }

  @Test
  fun `latency parsing accepts the tab separated device format`() {
    // The phone prints `desired\tactual\tready`; a space-only split sees one
    // token per row and would report a permanent 0 fps.
    val text = "16666667\n" +
      "0\t0\t0\n" +
      "10\t20\t30\n"

    assertEquals(listOf(FrameRow(10L, 20L, 30L)), parseLatencyRows(text))
  }

  @Test
  fun `latency parsing ignores the period and short lines`() {
    val text = "16666667\n" +
      "1 2\n" +
      "10 20 30\n"

    assertEquals(listOf(FrameRow(10L, 20L, 30L)), parseLatencyRows(text))
  }

  @Test
  fun `five fresh frames 16 point 667 ms apart are 60 fps`() {
    val newest = BASE + 4 * PERIOD
    val now = newest + 100_000_000L

    val measurement = measureFps(fiveFrames(), now)

    assertEquals(5, measurement.frames)
    assertEquals(60.0, measurement.fps, 0.5)
  }

  @Test
  fun `a frozen ring older than the fresh window is 0 fps`() {
    val newest = BASE + 4 * PERIOD
    val now = newest + 10_000_000_000L

    val measurement = measureFps(fiveFrames(), now)

    assertEquals(0, measurement.frames)
    assertEquals(0.0, measurement.fps, 0.0)
  }

  @Test
  fun `an implausibly high rate clamps to 500 fps`() {
    // Two frames 1 ms apart would be 1000 fps without the ceiling.
    val rows = listOf(
      FrameRow(BASE, BASE, BASE),
      FrameRow(BASE + 1_000_000L, BASE + 1_000_000L, BASE + 1_000_000L),
    )

    assertEquals(500.0, measureFps(rows, BASE + 2_000_000L).fps, 0.0)
  }

  @Test
  fun `a single fresh frame is 0 fps`() {
    val row = FrameRow(BASE, BASE, BASE)

    val measurement = measureFps(listOf(row), BASE + 100_000_000L)

    assertEquals(1, measurement.frames)
    assertEquals(0.0, measurement.fps, 0.0)
  }

  @Test
  fun `reply parsing reads the fps double`() {
    assertEquals(
      59.8,
      parseFpsReply(
        "OK fps=59.8 frames=45 span_ms=740 layer=com.android.settings/" +
          "com.android.settings.MiuiSettings#5643",
      )!!,
      0.0,
    )
    assertEquals(
      0.0,
      parseFpsReply("OK fps=0 frames=0 span_ms=0 layer=-")!!,
      0.0,
    )
  }

  @Test
  fun `reply parsing rejects errors and malformed lines`() {
    assertNull(parseFpsReply("ERR no focused app"))
    assertNull(parseFpsReply(""))
    assertNull(parseFpsReply("OK fps=fast frames=2 span_ms=1 layer=x"))
    assertNull(parseFpsReply("OK frames=2 span_ms=1 layer=x"))
  }

  private fun fiveFrames(): List<FrameRow> = (0 until 5).map { index ->
    val actual = BASE + index * PERIOD
    FrameRow(actual, actual, actual)
  }

  private companion object {
    /** 16.667 ms expressed in nanoseconds, the probe's 60 Hz frame spacing. */
    const val PERIOD = 16_667_000L

    /** Arbitrary CLOCK_MONOTONIC base; only differences matter. */
    const val BASE = 33_002_540_928_274L
  }
}
