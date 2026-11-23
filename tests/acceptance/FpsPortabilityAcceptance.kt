// Independent acceptance checks for T19b FPS parser portability.
//
// Expected values are hand-derived from the task spec (Android 8-14 plain
// `--list` names, Android 15+ `RequestedLayerState`, hash prefixes, BLAST/legacy
// shapes, focus-line variants, desiredPresent=0 and the unchanged measurement
// arithmetic). They are never copied from the implementation's unit tests or
// from its output.
//
// Compile against `core/FpsParse.kt` and run FpsPortabilityAcceptanceKt; it
// exits non-zero on any failure.

import dev.rsensors.android.core.FrameRow
import dev.rsensors.android.core.FpsMeasurement
import dev.rsensors.android.core.formatFpsReply
import dev.rsensors.android.core.measureFps
import dev.rsensors.android.core.parseCandidateLayers
import dev.rsensors.android.core.parseFocusedComponent
import dev.rsensors.android.core.parseFpsReply
import dev.rsensors.android.core.parseLatencyRows
import kotlin.system.exitProcess

private var failures = 0
private var checks = 0

private fun expect(condition: Boolean, message: String) {
  checks++
  if (!condition) {
    failures++
    println("FAIL: $message")
  } else {
    println("ok: $message")
  }
}

private fun expectEquals(actual: Any?, expected: Any?, label: String) {
  expect(actual == expected, "$label: expected <$expected>, got <$actual>")
}

private val component = "com.example.app/com.example.app.Main"
private val t14Settings = "com.android.settings/com.android.settings.MiuiSettings"
private val t14Game = "com.kurogame.wutheringwaves.global/com.epicgames.ue4.GameActivity"

fun main() {
  candidateLayers()
  focusedComponent()
  latencyRows()
  measurementAndReply()
  println("$checks checks, $failures failures")
  if (failures != 0) exitProcess(1)
}

private fun candidateLayers() {
  // --- Android 10 plain `--list` output: exact component layer only ---------
  // Blank lines are ignored, an unrelated component and every container are
  // rejected. A non-BLAST SurfaceView[...]# is a container.
  val plainList = listOf(
    "",
    "com.example.app/com.example.app.Main#101",
    "com.example.app/com.example.app.MainActivity#102",
    "Background for com.example.app/com.example.app.Main#103",
    "SurfaceView[com.example.app/com.example.app.Main]#104",
    "   ",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(plainList, component),
    listOf("com.example.app/com.example.app.Main#101"),
    "plain Android 8-14 list keeps the exact component layer and drops containers",
  )

  // --- Android 15+ wrapper output; wrapper attributes are not part of name --
  val wrapperList = listOf(
    "RequestedLayerState{ActivityRecord{aa u0 com.example.app/.Main t1}#202 parentId=7}",
    "RequestedLayerState{com.example.app/com.example.app.Main#201 parentId=7}",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(wrapperList, component),
    listOf("com.example.app/com.example.app.Main#201"),
    "wrapper list keeps the plain component layer and drops the ActivityRecord container",
  )

  // --- Both formats in one dump ---------------------------------------------
  val mixedList = listOf(
    "RequestedLayerState{com.example.app/com.example.app.Main#203 parentId=7}",
    "com.example.app/com.example.app.Main#204",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(mixedList, component),
    listOf("com.example.app/com.example.app.Main#203", "com.example.app/com.example.app.Main#204"),
    "mixed wrapper and plain lines are both accepted in input order",
  )

  // --- Ordering: BLAST, then legacy, then plain; exact before hash ----------
  // The exact BLAST layer is last in the input but must come first. The
  // hash-prefixed plain twin is dropped because an exact plain layer exists.
  val ordered = listOf(
    "abc123 SurfaceView[$component](BLAST)#301",
    "$component#302",
    "def456 $component#303",
    "SurfaceView[$component](BLAST)#304",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(ordered, component),
    listOf(
      "SurfaceView[$component](BLAST)#304",
      "abc123 SurfaceView[$component](BLAST)#301",
      "$component#302",
    ),
    "order: BLAST exact/hash, then exact plain; hash plain dropped when exact plain exists",
  )

  // The conditional rule is presence-based, not input-order-based.
  expectEquals(
    parseCandidateLayers(
      "def456 $component#305\n$component#306",
      component,
    ),
    listOf("$component#306"),
    "exact plain wins and the hash-prefixed plain is dropped regardless of input order",
  )

  // --- No exact plain: hash-prefixed plain is the Android 17 fallback --------
  expectEquals(
    parseCandidateLayers(
      "cc6d380 $component#307\ndef456 $component#308",
      component,
    ),
    listOf("cc6d380 $component#307", "def456 $component#308"),
    "hash-prefixed plain layers stay when no exact plain layer exists",
  )
  expectEquals(
    parseCandidateLayers(
      "77aa00 SurfaceView - $component#309\ncc6d380 $component#310",
      component,
    ),
    listOf("77aa00 SurfaceView - $component#309", "cc6d380 $component#310"),
    "hash-prefixed plain fallback follows legacy when no exact plain exists",
  )
  expectEquals(
    parseCandidateLayers(
      "cc6d380 $component#311\n89e2605 SurfaceView[$component](BLAST)#312",
      component,
    ),
    listOf("89e2605 SurfaceView[$component](BLAST)#312", "cc6d380 $component#311"),
    "hash BLAST precedes the hash-prefixed plain fallback when no exact plain exists",
  )

  // --- T14 probe: hash-prefixed plain twins are Android 16 containers --------
  // `9ecc164 …#5342` and `cc6d380 …#5726` returned valid=0 while the exact
  // `#5643` / `#5729` carried frames. The hash BLAST `89e2605 …#5734` is the
  // game's real render target and must stay.
  expectEquals(
    parseCandidateLayers(
      "9ecc164 $component#5342\n$component#5643",
      component,
    ),
    listOf("$component#5643"),
    "settings probe: hash-prefixed plain container skipped when exact twin exists",
  )
  expectEquals(
    parseCandidateLayers(
      "cc6d380 $component#5726\n$component#5729\n" +
        "89e2605 SurfaceView[$component](BLAST)#5734",
      component,
    ),
    listOf(
      "89e2605 SurfaceView[$component](BLAST)#5734",
      "$component#5729",
    ),
    "game probe: hash-prefixed plain container skipped, hash BLAST stays",
  )

  // --- T14 probe layer names verbatim ---------------------------------------
  expectEquals(
    parseCandidateLayers(
      listOf(
        "RequestedLayerState{$t14Settings#5643 parentId=129}",
        "RequestedLayerState{9ecc164 $t14Settings#5342 parentId=129}",
      ).joinToString("\n"),
      t14Settings,
    ),
    listOf("$t14Settings#5643"),
    "T14 settings: 9ecc164 ...#5342 skipped when exact #5643 exists",
  )
  expectEquals(
    parseCandidateLayers(
      listOf(
        "RequestedLayerState{$t14Game#5729 parentId=129}",
        "RequestedLayerState{cc6d380 $t14Game#5726 parentId=129}",
        "RequestedLayerState{89e2605 SurfaceView[$t14Game](BLAST)#5734 parentId=129}",
      ).joinToString("\n"),
      t14Game,
    ),
    listOf(
      "89e2605 SurfaceView[$t14Game](BLAST)#5734",
      "$t14Game#5729",
    ),
    "T14 game: cc6d380 ...#5726 skipped, hash BLAST 89e2605 ...#5734 stays",
  )

  // --- Both BLAST @0 spellings ----------------------------------------------
  val blastAtZero = listOf(
    "SurfaceView[$component]@0(BLAST)#401",
    "SurfaceView[$component](BLAST)@0#402",
    "0a1b2c SurfaceView[$component]@0(BLAST)#403",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(blastAtZero, component),
    listOf(
      "SurfaceView[$component]@0(BLAST)#401",
      "SurfaceView[$component](BLAST)@0#402",
      "0a1b2c SurfaceView[$component]@0(BLAST)#403",
    ),
    "both @0(BLAST) and (BLAST)@0 spellings resolve, exact before hash",
  )

  // --- Legacy pre-BLAST SurfaceView render layer ----------------------------
  val legacy = listOf(
    "$component#501",
    "SurfaceView - $component#502",
    "77aa00 SurfaceView - $component#503",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(legacy, component),
    listOf(
      "SurfaceView - $component#502",
      "77aa00 SurfaceView - $component#503",
      "$component#501",
    ),
    "legacy SurfaceView - layer comes before the plain layer",
  )

  // --- Rejections: containers, non-BLAST SurfaceView, unrelated components --
  val containers = listOf(
    "ActivityRecord{1a2b3c u0 $component t5643}#601",
    "ActivityRecordInputSink $component#602",
    "Background for $component#603",
    "Bounds for $component#604",
    "SurfaceView[$component]#605",
    "SurfaceView[$component]@0#606",
    "SurfaceView[other.pkg/other.pkg.Activity](BLAST)#607",
    "other.pkg/other.pkg.Activity#608",
    "SurfaceView - other.pkg/other.pkg.Activity#609",
    "ff00aa ActivityRecord{1a2b3c u0 $component t1}#610",
    "$component#611",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(containers, component),
    listOf("$component#611"),
    "containers, non-BLAST SurfaceView and unrelated components are rejected",
  )

  // --- Dedupe preserves the first occurrence, after ordering ----------------
  val duplicates = listOf(
    "$component#701",
    "abc123 SurfaceView[$component](BLAST)#702",
    "$component#701",
    "abc123 SurfaceView[$component](BLAST)#702",
    "$component#703",
    "$component#703",
  ).joinToString("\n")
  expectEquals(
    parseCandidateLayers(duplicates, component),
    listOf(
      "abc123 SurfaceView[$component](BLAST)#702",
      "$component#701",
      "$component#703",
    ),
    "duplicates are removed preserving first occurrence after ordering",
  )

  // A truncated wrapper is not a bare layer name and must not leak through.
  expectEquals(
    parseCandidateLayers(
      "RequestedLayerState{$component#801 parentId=1",
      component,
    ),
    emptyList<String>(),
    "a malformed wrapper is not treated as a bare layer name",
  )
}

private fun focusedComponent() {
  // Existing shapes stay intact.
  expectEquals(
    parseFocusedComponent(
      "mFocusedApp=ActivityRecord{230081998 u0 com.android.settings/.MiuiSettings t5643}",
    ),
    "com.android.settings/com.android.settings.MiuiSettings",
    "ActivityRecord focus keeps leading-dot expansion",
  )
  expectEquals(
    parseFocusedComponent(
      "mFocusedApp=ActivityRecord{1 u0 $component t2}",
    ),
    component,
    "ActivityRecord focus keeps a fully qualified class verbatim",
  )
  expectEquals(
    parseFocusedComponent(
      "mCurrentFocus=Window{81c33ba u0 com.mi.android.globallauncher/com.miui.home.launcher.Launcher}",
    ),
    "com.mi.android.globallauncher/com.miui.home.launcher.Launcher",
    "mCurrentFocus Window is still read",
  )

  // New shapes.
  expectEquals(
    parseFocusedComponent(
      "mFocusedApp=AppWindowToken{9f4e2b1 token=Token{7a1 " +
        "ActivityRecord{abc u0 com.example.app/.Main t12}}}",
    ),
    component,
    "AppWindowToken focus is read with leading-dot expansion",
  )
  expectEquals(
    parseFocusedComponent("mFocusedApp=WindowToken{4d2 u0 com.example.app/.Main t7}"),
    component,
    "generic mFocusedApp token focus is read",
  )
  expectEquals(
    parseFocusedComponent("mFocusedApp=Record 55 u0 com.example.app/com.example.app.Main t9"),
    component,
    "generic unprefixed mFocusedApp record is read",
  )
  expectEquals(
    parseFocusedComponent(
      "mCurrentFocus=Window{1 u0 com.other/.Other}\n" +
        "mFocusedApp=ActivityRecord{2 u0 $component t1}",
    ),
    component,
    "mFocusedApp still wins over mCurrentFocus",
  )

  // Unrelated lines must not match.
  expectEquals(
    parseFocusedComponent("mFocusedWindow=Window{1 u0 com.example/.Main}"),
    null,
    "mFocusedWindow is unrelated and must not match",
  )
  expectEquals(
    parseFocusedComponent("mFocusedApp=null"),
    null,
    "mFocusedApp=null must not match",
  )
  expectEquals(
    parseFocusedComponent("mCurrentFocus=null"),
    null,
    "mCurrentFocus=null must not match",
  )
  expectEquals(
    parseFocusedComponent("ActivityRecord{1 u0 com.example/.Main t1}"),
    null,
    "an unprefixed ActivityRecord line is unrelated",
  )
}

private fun latencyRows() {
  // desiredPresent = 0 with valid actual/ready is a present frame.
  expectEquals(
    parseLatencyRows("16666667\n0 100 200\n"),
    listOf(FrameRow(0L, 100L, 200L)),
    "desired=0 with valid actual/ready is accepted",
  )
  expectEquals(
    parseLatencyRows("16666667\n0\t100\t200\n"),
    listOf(FrameRow(0L, 100L, 200L)),
    "desired=0 tab-separated is accepted",
  )

  // The empty ring slot and every INT64_MAX sentinel are dropped.
  expectEquals(parseLatencyRows("16666667\n0 0 0\n"), emptyList<FrameRow>(), "0 0 0 is dropped")
  expectEquals(
    parseLatencyRows("16666667\n9223372036854775807 20 30\n"),
    emptyList<FrameRow>(),
    "INT64_MAX desired is dropped",
  )
  expectEquals(
    parseLatencyRows("16666667\n10 9223372036854775807 30\n"),
    emptyList<FrameRow>(),
    "INT64_MAX actual is dropped",
  )
  expectEquals(
    parseLatencyRows("16666667\n10 20 9223372036854775807\n"),
    emptyList<FrameRow>(),
    "INT64_MAX ready is dropped",
  )

  // actual/ready <= 0 is never a frame, and neither is a negative desired.
  expectEquals(
    parseLatencyRows("16666667\n10 0 30\n"),
    emptyList<FrameRow>(),
    "actual=0 is dropped",
  )
  expectEquals(
    parseLatencyRows("16666667\n10 20 0\n"),
    emptyList<FrameRow>(),
    "ready=0 is dropped",
  )
  expectEquals(
    parseLatencyRows("16666667\n10 -1 30\n"),
    emptyList<FrameRow>(),
    "negative actual is dropped",
  )
  expectEquals(
    parseLatencyRows("16666667\n10 20 -1\n"),
    emptyList<FrameRow>(),
    "negative ready is dropped",
  )
  expectEquals(
    parseLatencyRows("16666667\n-1 20 30\n"),
    emptyList<FrameRow>(),
    "negative desired is dropped",
  )

  // Short rows and the period line are dropped; valid rows keep their order.
  expectEquals(
    parseLatencyRows("16666667\n10 20\n"),
    emptyList<FrameRow>(),
    "short row is dropped",
  )
  expectEquals(
    parseLatencyRows("16666667\n"),
    emptyList<FrameRow>(),
    "period-only output has no rows",
  )
  expectEquals(
    parseLatencyRows("16666667\n100 200 300\n400 500 600\n"),
    listOf(FrameRow(100L, 200L, 300L), FrameRow(400L, 500L, 600L)),
    "valid rows keep their order",
  )
}

private fun measurementAndReply() {
  // Unchanged arithmetic: (frames - 1) / span in seconds.
  val two = listOf(FrameRow(0L, 1_000_000_000L, 1_010_000_000L),
    FrameRow(0L, 1_020_000_000L, 1_030_000_000L))
  val fifty = measureFps(two, 1_020_000_000L)
  expectEquals(fifty.fps, 50.0, "two frames 20 ms apart measure 50 fps")
  expectEquals(fifty.frames, 2, "measurement reports two fresh frames")
  expectEquals(fifty.spanMillis, 20L, "measurement reports a 20 ms span")

  val one = measureFps(listOf(two[0]), 1_000_000_000L)
  expectEquals(one, FpsMeasurement(0.0, 1, 0L), "one fresh frame measures 0")

  expectEquals(
    measureFps(two, 3_000_000_000L),
    FpsMeasurement(0.0, 0, 0L),
    "a stale ring measures 0, never the last burst",
  )
  expectEquals(
    measureFps(
      listOf(FrameRow(0L, 1_000_000_000L, 1_000_000_001L),
        FrameRow(0L, 1_000_000_001L, 1_000_000_002L)),
      1_000_000_001L,
    ).fps,
    500.0,
    "measurement is still clamped at 500 fps",
  )

  // Unchanged reply formatting and parsing.
  expectEquals(
    formatFpsReply(FpsMeasurement(0.0, 0, 0L), null),
    "OK fps=0 frames=0 span_ms=0 layer=-",
    "zero reply formatting",
  )
  expectEquals(
    formatFpsReply(FpsMeasurement(50.0, 2, 20L), "$component#1"),
    "OK fps=50.0 frames=2 span_ms=20 layer=$component#1",
    "non-zero reply formatting",
  )
  expectEquals(parseFpsReply("OK fps=59.8 frames=45 span_ms=740 layer=x"), 59.8, "reply fps parse")
  expectEquals(parseFpsReply("OK fps=0 frames=0 span_ms=0 layer=-"), 0.0, "zero reply parse")
  expectEquals(parseFpsReply("ERR no layer"), null, "ERR reply is null")
  expectEquals(parseFpsReply("OK frames=2 span_ms=20 layer=x"), null, "missing fps token is null")
  expectEquals(parseFpsReply("OK fps=abc"), null, "malformed fps is null")
  expectEquals(parseFpsReply("OK fps=-1"), null, "negative fps is null")
}
