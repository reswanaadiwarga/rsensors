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
  fun `candidate layers keep only the plain layer and skip containers`() {
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
