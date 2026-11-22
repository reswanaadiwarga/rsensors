package dev.rsensors.android.core

/**
 * Versioned CSV session log shared by the recorder and the sessions screens
 * (ANDROID.md §3, §9, §8 T12/T14).
 *
 * One file per recorded session holds a wall-clock header and one row per poll:
 *
 * ```
 * # rsensors session v2
 * # started=1795000000000
 * t,cpu,cpuMax,cores,gpu,gpuHot,battery,thermal,board,source,fps
 * 1795000005000,42.1,45.2,40.1;41.2,38.5,40.0,33.5,LIGHT,SOC=43.1;SKIN=35.2,dumpsys+battery+thermal,59.8
 * ```
 *
 * Encoding rules: an empty cell is a null value, `cores` and `board` are
 * semicolon lists, board entries are `name=value`, `thermal` is the
 * [ThermalStatus] enum name, and `fps` is the foreground frame rate or empty
 * when the shell route was unavailable. Board sensor names are stored verbatim
 * (the card abbreviates `POWER_AMPLIFIER` for display only), so a saved log
 * never loses which HAL zone produced a value.
 *
 * Decoding is forgiving row by row and strict about the frame: the v2 magic and
 * column header identify the current format, the v1 pair is still accepted and
 * decodes every row with a null fps, and any other magic or a header that does
 * not match its magic makes [decodeSession] return null. A row with the wrong
 * column count, a bad number (including a bad fps cell), an unknown thermal name
 * or a malformed list entry is skipped. A header-only file is a valid, empty
 * session.
 */

/**
 * One stored poll; the session-shaped twin of [Reading].
 *
 * @property t sample time, epoch millis
 * @property thermal coarse thermal pressure name as written to the `thermal` cell
 * @property board board sensors in HAL order, names verbatim
 * @property source chain name that produced the sample, empty when unknown
 * @property fps foreground frames per second, null in v1 files and on polls
 *   where the shell route was unavailable
 */
data class SessionSample(
  val t: Long,
  val cpu: Double? = null,
  val cpuMax: Double? = null,
  val cores: List<Double> = emptyList(),
  val gpu: Double? = null,
  val gpuHot: Double? = null,
  val battery: Double? = null,
  val thermal: ThermalStatus = ThermalStatus.UNKNOWN,
  val board: List<Pair<String, Double>> = emptyList(),
  val source: String = "",
  val fps: Double? = null,
)

/**
 * One saved session.
 *
 * @property startedAt wall-clock start in epoch millis, from the header, not
 *   from the first row (a session may sit paused with no samples yet)
 * @property samples recorded polls in file order
 */
data class SessionLog(
  val startedAt: Long,
  val samples: List<SessionSample>,
)

/** First line of every v2 session file; a format change bumps this string. */
const val SESSION_MAGIC = "# rsensors session v2"

/** Column header; the order and spelling are part of the v2 file format. */
const val SESSION_COLUMNS = "t,cpu,cpuMax,cores,gpu,gpuHot,battery,thermal,board,source,fps"

/** Legacy v1 frame, still accepted by [decodeSession] (fps is null). */
private const val SESSION_MAGIC_V1 = "# rsensors session v1"
private const val SESSION_COLUMNS_V1 =
  "t,cpu,cpuMax,cores,gpu,gpuHot,battery,thermal,board,source"

private const val STARTED_PREFIX = "# started="
private const val COLUMN_COUNT_V1 = 10
private const val COLUMN_COUNT_V2 = 11

/**
 * Projects a live [Reading] onto the stored shape.
 *
 * Pure field mapping: a null sensor stays null (and becomes an empty cell), and
 * `thermalStatus` / `sourceName` are renamed for the file's vocabulary.
 */
fun Reading.toSessionSample(): SessionSample = SessionSample(
  t = t,
  cpu = cpu,
  cpuMax = cpuMax,
  cores = cores,
  gpu = gpu,
  gpuHot = gpuHot,
  battery = battery,
  thermal = thermalStatus,
  board = board,
  source = sourceName,
  fps = fps,
)

/** Magic, start time and column header, each terminated by `\n`. */
fun sessionHeader(startedAt: Long): String =
  "$SESSION_MAGIC\n$STARTED_PREFIX$startedAt\n$SESSION_COLUMNS\n"

/** One CSV row without a trailing newline; empty cells stand for nulls. */
fun SessionSample.encodeRow(): String = listOf(
  t.toString(),
  csvCell(cpu),
  csvCell(cpuMax),
  cores.joinToString(";") { it.toString() },
  csvCell(gpu),
  csvCell(gpuHot),
  csvCell(battery),
  thermal.name,
  board.joinToString(";") { (name, value) -> "$name=$value" },
  source,
  csvCell(fps),
).joinToString(",")

/** Full file content, including the newline after the last row. */
fun SessionLog.encode(): String = buildString {
  append(sessionHeader(startedAt))
  samples.forEach { sample ->
    append(sample.encodeRow())
    append('\n')
  }
}

/**
 * Parses a session file.
 *
 * @param text whole file, `\n` or `\r\n` line endings
 * @return the parsed session, or null when the magic, start line or column
 *   header is missing or damaged; malformed data rows are skipped
 */
fun decodeSession(text: String): SessionLog? {
  val lines = text.split('\n').map { line ->
    if (line.endsWith('\r')) line.dropLast(1) else line
  }
  if (lines.size < 3) return null
  val version = when (lines[0]) {
    SESSION_MAGIC -> 2
    SESSION_MAGIC_V1 -> 1
    else -> return null
  }
  if (!lines[1].startsWith(STARTED_PREFIX)) return null
  val startedAt = lines[1].removePrefix(STARTED_PREFIX).toLongOrNull() ?: return null
  val expectedColumns = if (version == 1) SESSION_COLUMNS_V1 else SESSION_COLUMNS
  if (lines[2] != expectedColumns) return null

  val samples = ArrayList<SessionSample>(lines.size - 3)
  for (index in 3 until lines.size) {
    val line = lines[index]
    if (line.isEmpty()) continue
    val sample = parseRow(line, version) ?: continue
    samples.add(sample)
  }
  return SessionLog(startedAt, samples)
}

/**
 * Parses one data row, or null when any cell is malformed.
 *
 * @param version 1 has no fps column and yields a null fps; 2 requires the
 *   trailing fps cell to be empty or numeric
 */
private fun parseRow(line: String, version: Int): SessionSample? {
  val cells = splitCells(line, ',')
  val columnCount = if (version == 1) COLUMN_COUNT_V1 else COLUMN_COUNT_V2
  if (cells.size != columnCount) return null
  val t = cells[0].toLongOrNull() ?: return null

  // A present-but-unparseable number is damage, not a null sensor.
  if (!isBlankOrNumber(cells[1]) || !isBlankOrNumber(cells[2]) ||
    !isBlankOrNumber(cells[4]) || !isBlankOrNumber(cells[5]) ||
    !isBlankOrNumber(cells[6]) ||
    (version >= 2 && !isBlankOrNumber(cells[10]))
  ) {
    return null
  }

  val cores = if (cells[3].isEmpty()) {
    emptyList()
  } else {
    splitCells(cells[3], ';').map { value ->
      value.toDoubleOrNull() ?: return null
    }
  }

  val board = if (cells[8].isEmpty()) {
    emptyList()
  } else {
    splitCells(cells[8], ';').map { entry ->
      val equals = entry.lastIndexOf('=')
      if (equals <= 0 || equals == entry.lastIndex) return null
      val value = entry.substring(equals + 1).toDoubleOrNull() ?: return null
      entry.substring(0, equals) to value
    }
  }

  val thermal = if (cells[7].isEmpty()) {
    ThermalStatus.UNKNOWN
  } else {
    ThermalStatus.entries.firstOrNull { it.name == cells[7] } ?: return null
  }

  return SessionSample(
    t = t,
    cpu = cells[1].toDoubleOrNull(),
    cpuMax = cells[2].toDoubleOrNull(),
    cores = cores,
    gpu = cells[4].toDoubleOrNull(),
    gpuHot = cells[5].toDoubleOrNull(),
    battery = cells[6].toDoubleOrNull(),
    thermal = thermal,
    board = board,
    source = cells[9],
    fps = if (version >= 2) cells[10].toDoubleOrNull() else null,
  )
}

private fun csvCell(value: Double?): String = value?.toString().orEmpty()

private fun isBlankOrNumber(cell: String): Boolean =
  cell.isEmpty() || cell.toDoubleOrNull() != null

/**
 * Splits on [delimiter] keeping empty cells.
 *
 * `String.split` drops trailing empty strings, which would silently shift a
 * row that ends in empty cells; the manual scan preserves every column.
 */
private fun splitCells(line: String, delimiter: Char): List<String> {
  val cells = ArrayList<String>()
  var start = 0
  for (index in line.indices) {
    if (line[index] == delimiter) {
      cells.add(line.substring(start, index))
      start = index + 1
    }
  }
  cells.add(line.substring(start))
  return cells
}
