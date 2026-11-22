package dev.rsensors.android.core

/** Role of a [Segment] in the collapsed pill. */
enum class SegmentKind { LABEL, OK, WARN, HOT, UNKNOWN }

/** One run of text plus the colour grade it should be drawn with. */
data class Segment(val text: String, val kind: SegmentKind)

/**
 * Collapsed-pill content.
 *
 * @property segments text runs in draw order, labels alternating with values
 * @property dim true when the sample is stale; the view fades the whole strip
 */
data class Strip(val segments: List<Segment>, val dim: Boolean)

/** Projects a colour grade onto the segment vocabulary of the strip. */
private fun Level.toSegmentKind(): SegmentKind = when (this) {
  Level.OK -> SegmentKind.OK
  Level.WARN -> SegmentKind.WARN
  Level.HOT -> SegmentKind.HOT
  Level.UNKNOWN -> SegmentKind.UNKNOWN
}

/**
 * Builds the collapsed pill from the kinds the user enabled:
 * `CPU/GPU 41°  BAT 34°  FPS 60` (ANDROID.md §3, §9 T15i).
 *
 * Segments are emitted in the fixed CPU/GPU, BAT, FPS order, labels alternating
 * with values; a missing value is an em dash rather than a guess. CPU/GPU is a
 * single combined metric: it shows the hotter of the two SoC sensors and takes
 * the worse of their levels, so a WARN CPU under a HOT GPU still reads red.
 * BAT is a normal metric, not a fallback, so a battery temperature is never
 * relabelled as a SoC sensor (ANDROID.md §2.2).
 *
 * A null reading renders the enabled labels with dashes and stays dim, and an
 * all-off [visibility] is repaired to CPU/GPU-only so the strip is never empty.
 *
 * @param reading latest sample, or null before the first poll
 * @param now current epoch millis, the reference for the stale flag
 * @param visibility which kinds to draw; the default is the pre-T15i CPU/GPU
 *   strip because callers that do not read the chips' preference must keep
 *   their old output
 * @param fps frame rate to draw, defaulting to the reading's own value
 * @param staleAfterMs age at which [Strip.dim] becomes true
 */
fun stripSegments(
  reading: Reading?,
  now: Long,
  visibility: PillVisibility = PillVisibility(bat = false, fps = false),
  fps: Double? = reading?.fps,
  staleAfterMs: Long = STALE_AFTER_MS,
): Strip {
  val shown = visibility.normalised()
  val showCpuGpu = shown.isOn(VisibilityKind.CPUGPU)
  val showBat = shown.isOn(VisibilityKind.BAT)
  val showFps = shown.isOn(VisibilityKind.FPS)

  if (reading == null) {
    val segments = mutableListOf<Segment>()
    if (showCpuGpu) {
      segments += Segment("CPU/GPU", SegmentKind.LABEL)
      segments += Segment(DASH, SegmentKind.UNKNOWN)
    }
    if (showBat) {
      segments += Segment("BAT", SegmentKind.LABEL)
      segments += Segment(DASH, SegmentKind.UNKNOWN)
    }
    if (showFps) {
      segments += Segment("FPS", SegmentKind.LABEL)
      segments += Segment(DASH, SegmentKind.UNKNOWN)
    }
    return Strip(segments = segments, dim = true)
  }

  val dim = reading.stale(now, staleAfterMs)
  val segments = mutableListOf<Segment>()
  if (showCpuGpu) {
    val cpu = reading.cpu
    val gpu = reading.gpu
    // The hotter non-null sensor is the number; each sensor keeps its own
    // thresholds and the combined colour is the worse of the two levels.
    val hotter = listOfNotNull(cpu, gpu).maxOrNull()
    segments += Segment("CPU/GPU", SegmentKind.LABEL)
    segments += Segment(
      fmtTemp(hotter),
      worstLevel(
        level(cpu, CPU_WARN, CPU_HOT),
        level(gpu, GPU_WARN, GPU_HOT),
      ).toSegmentKind(),
    )
  }
  if (showBat) {
    segments += Segment("BAT", SegmentKind.LABEL)
    segments += Segment(
      fmtTemp(reading.battery),
      level(reading.battery, BATTERY_WARN, BATTERY_HOT).toSegmentKind(),
    )
  }
  if (showFps) {
    segments += Segment("FPS", SegmentKind.LABEL)
    segments += Segment(fmtFps(fps), fpsLevel(fps).toSegmentKind())
  }
  return Strip(segments = segments, dim = dim)
}
