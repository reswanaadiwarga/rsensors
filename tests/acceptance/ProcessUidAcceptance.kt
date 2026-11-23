// Independent acceptance checks for T19c-3a shell UID enrichment.
//
// Expected values are derived from the task spec and from hand-calculated
// header/column positions, never from the implementation's unit tests or from
// its output.
//
// Compile against app/src/main/java/dev/rsensors/android/core/ProcessMemory.kt
// and StopPolicy.kt, then run ProcessUidAcceptanceKt from the repository root;
// it exits non-zero on any failure.

import dev.rsensors.android.core.AppMemory
import dev.rsensors.android.core.ProcessMemory
import dev.rsensors.android.core.StopRisk
import dev.rsensors.android.core.StopTarget
import dev.rsensors.android.core.attachUids
import dev.rsensors.android.core.groupApps
import dev.rsensors.android.core.parsePsUid
import dev.rsensors.android.core.stopDecision
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

fun main() {
  standardHeader()
  reorderedHeaders()
  caseAndNameHeaders()
  numericLimits()
  malformedRows()
  duplicates()
  emptyAndMissingHeader()
  attachRules()
  groupUidRules()
  stopPolicyUsesAttachedUid()

  println("$checks checks, $failures failures")
  if (failures != 0) exitProcess(1)
}

// ---------------------------------------------------------------------------
// parsePsUid: PID -> UID by header position.
// ---------------------------------------------------------------------------

private fun standardHeader() {
  expectEquals(
    parsePsUid(
      """
      PID   UID NAME
        1     0 init
     4100 10123 com.example.notes
      """.trimIndent(),
    ),
    mapOf(1 to 0, 4100 to 10123),
    "PID UID NAME maps both columns, including UID 0",
  )

  expectEquals(
    parsePsUid(
      """
      PID UID NAME
       42 10123 com.example with space
      """.trimIndent(),
    ),
    mapOf(42 to 10123),
    "a trailing name containing spaces does not disturb the pid/uid columns",
  )

  expectEquals(
    parsePsUid("  PID\tUID\tNAME\n   7\t1000\tsystem\n"),
    mapOf(7 to 1000),
    "tab/space padding and CR-free line endings parse",
  )
}

private fun reorderedHeaders() {
  expectEquals(
    parsePsUid(
      """
      NAME UID PID
      com.example.notes 10123 4100
      system 1000 1000
      """.trimIndent(),
    ),
    mapOf(4100 to 10123, 1000 to 1000),
    "reordered NAME UID PID is read at header positions",
  )

  expectEquals(
    parsePsUid(
      """
      UID NAME PID
      10123 com.example.notes 4100
      """.trimIndent(),
    ),
    mapOf(4100 to 10123),
    "reordered UID NAME PID is read at header positions",
  )

  expectEquals(
    parsePsUid(
      """
      PID PPID UID NAME
       10    1 1000 system
      """.trimIndent(),
    ),
    mapOf(10 to 1000),
    "an added column does not shift the PID/UID positions",
  )
}

private fun caseAndNameHeaders() {
  expectEquals(
    parsePsUid(
      """
      pid uid cmd
        7 2000 com.example.a
      """.trimIndent(),
    ),
    mapOf(7 to 2000),
    "lowercase pid/uid/cmd header is accepted",
  )

  expectEquals(
    parsePsUid(
      """
      Pid Uid Command
        8 2001 com.example.b
      """.trimIndent(),
    ),
    mapOf(8 to 2001),
    "mixed-case PID/UID/COMMAND header is accepted",
  )

  expectEquals(
    parsePsUid(
      """
      PID UID CMD
        9 2002 com.example.c
      """.trimIndent(),
    ),
    mapOf(9 to 2002),
    "CMD is accepted as the name column",
  )
}

private fun numericLimits() {
  expectEquals(
    parsePsUid(
      """
      PID UID NAME
        0 1000 zero-pid
       -1 1000 negative-pid
        9 -1 negative-uid
       10 0 root-owned
      """.trimIndent(),
    ),
    mapOf(10 to 0),
    "pid must be positive and uid must be non-negative numeric",
  )
}

private fun malformedRows() {
  expectEquals(
    parsePsUid(
      """
      PID UID NAME
       10 u0_a123 username
       11 abc nonnumeric-uid
      abc 1000 nonnumeric-pid
       12
       14 1000 valid
      """.trimIndent(),
    ),
    mapOf(14 to 1000),
    "non-numeric, negative, short and username rows are skipped",
  )
}

private fun duplicates() {
  expectEquals(
    parsePsUid(
      """
      PID UID NAME
       10 1000 first
       10 10123 last
      """.trimIndent(),
    ),
    mapOf(10 to 10123),
    "last duplicate pid wins",
  )

  expectEquals(
    parsePsUid(
      """
      PID UID NAME
       10 1000 first
       10 bad malformed
      """.trimIndent(),
    ),
    mapOf(10 to 1000),
    "a malformed duplicate row does not overwrite the last valid UID",
  )
}

private fun emptyAndMissingHeader() {
  expectEquals(parsePsUid(""), emptyMap<Int, Int>(), "empty input yields no entries")
  expectEquals(parsePsUid("   \n\n"), emptyMap<Int, Int>(), "blank input yields no entries")
  expectEquals(
    parsePsUid("PID UID NAME\n"),
    emptyMap<Int, Int>(),
    "a header without rows yields no entries",
  )
  expectEquals(
    parsePsUid("1 1000 init"),
    emptyMap<Int, Int>(),
    "rows without a header are not guessed",
  )
  expectEquals(
    parsePsUid("PID NAME\n1 init"),
    emptyMap<Int, Int>(),
    "a header missing UID is not usable",
  )
  expectEquals(
    parsePsUid("UID NAME\n1000 init"),
    emptyMap<Int, Int>(),
    "a header missing PID is not usable",
  )
}

// ---------------------------------------------------------------------------
// attachUids: copy, fill, preserve.
// ---------------------------------------------------------------------------

private fun attachRules() {
  val rows = listOf(
    ProcessMemory("system", 1, 500L, "System"),
    ProcessMemory("com.example.b", 2, 100L, "Cached", 555),
    ProcessMemory("com.example.c", 3, 50L, "Cached"),
  )

  val attached = attachUids(rows, mapOf(1 to 1000, 3 to 10123))

  expectEquals(
    attached,
    listOf(
      ProcessMemory("system", 1, 500L, "System", 1000),
      ProcessMemory("com.example.b", 2, 100L, "Cached", 555),
      ProcessMemory("com.example.c", 3, 50L, "Cached", 10123),
    ),
    "matching pids are filled, an existing unmatched uid is preserved and order/fields survive",
  )
  expect(
    attached !== rows,
    "attachUids returns a new list",
  )
  expect(
    attached[0] !== rows[0],
    "attachUids returns copied rows",
  )
  expectEquals(
    rows,
    listOf(
      ProcessMemory("system", 1, 500L, "System"),
      ProcessMemory("com.example.b", 2, 100L, "Cached", 555),
      ProcessMemory("com.example.c", 3, 50L, "Cached"),
    ),
    "the input rows are not mutated",
  )

  expectEquals(
    attachUids(rows, emptyMap()),
    rows,
    "an empty UID map leaves every uid unchanged",
  )
  expectEquals(
    attachUids(emptyList(), mapOf(1 to 1000)),
    emptyList<ProcessMemory>(),
    "an empty row list stays empty",
  )
}

// ---------------------------------------------------------------------------
// groupApps: first non-null member UID.
// ---------------------------------------------------------------------------

private fun groupUidRules() {
  val rows = listOf(
    ProcessMemory("com.example.a", 10, 100L, "Cached", null),
    ProcessMemory("com.example.a:remote", 11, 50L, "Cached", 10123),
    ProcessMemory("com.example.a:worker", 12, 25L, "Cached", 1001),
    ProcessMemory("com.example.b", 13, 10L, "Cached", null),
    ProcessMemory("native-helper", 14, 900L, "Native", 0),
  )

  expectEquals(
    groupApps(rows),
    listOf(
      AppMemory("com.example.a", 175L, 3, 10123),
      AppMemory("com.example.b", 10L, 1, null),
    ),
    "groupApps sums, counts and carries the first non-null member uid; hidden groups stay hidden",
  )
}

// ---------------------------------------------------------------------------
// Stop policy consumes the attached UID: a hidden core row becomes PROTECTED.
// ---------------------------------------------------------------------------

private fun stopPolicyUsesAttachedUid() {
  val hiddenCore = stopDecision(
    StopTarget(
      packageName = "com.example.hidden.core",
      uid = 1000,
      isKnownApp = true,
      detailsHidden = true,
    ),
  )
  expectEquals(
    hiddenCore,
    dev.rsensors.android.core.StopDecision(StopRisk.PROTECTED, "Core system process."),
    "a known app with a shell UID < 10000 is PROTECTED even when details are hidden",
  )

  val hiddenApp = stopDecision(
    StopTarget(
      packageName = "com.example.hidden.app",
      uid = 10123,
      isKnownApp = true,
      detailsHidden = true,
    ),
  )
  expectEquals(
    hiddenApp,
    dev.rsensors.android.core.StopDecision(
      StopRisk.WARNING,
      "App details are hidden by Android; stopping may affect it.",
    ),
    "a hidden app with an app UID stays a WARNING",
  )

  val grouped = groupApps(
    listOf(
      ProcessMemory("com.example.hidden.core", 20, 30L, "Cached", null),
      ProcessMemory("com.example.hidden.core:remote", 21, 20L, "Cached", 999),
    ),
  ).single()
  expectEquals(
    stopDecision(
      StopTarget(
        packageName = grouped.packageName,
        uid = grouped.uid,
        isKnownApp = true,
        detailsHidden = true,
      ),
    ).risk,
    StopRisk.PROTECTED,
    "a member UID carried by groupApps reaches the policy and protects a hidden core app",
  )
}
