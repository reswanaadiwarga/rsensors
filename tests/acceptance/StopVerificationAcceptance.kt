// Independent acceptance checks for T19c-2 (post-stop verification).
//
// Expected values are hand-derived from the task spec, not from the
// implementation's unit tests or output:
//  - a row matches only when its NAME equals the trimmed package or begins with
//    `<trimmed package>:` (Android child process);
//  - `pkg2`, `pkg.backup`, `other.pkg` and any other package containing the
//    name are non-matches; comparison is case-sensitive;
//  - matched names are returned in input order, duplicates preserved;
//  - a blank (or whitespace-only) package and an empty row list return empty.
//
// The spec does not name the return element type; it says "matches a row name"
// and the shell side needs names for `ERR still running: <names>`, so this file
// asserts the returned names. That interpretation is stated here rather than
// inferred from the implementation.
//
// No adb/device access: this exercises the pure matcher only.
//
// Compile against the core sources (StopVerification.kt + ProcessMemory.kt) and
// run StopVerificationAcceptanceKt from the repository root. Exits non-zero on
// any failure; prints one line per check so the red run is reproducible.

import dev.rsensors.android.core.ProcessMemory
import dev.rsensors.android.core.parsePsRss
import dev.rsensors.android.core.stillRunningProcesses
import kotlin.system.exitProcess

private var checks = 0
private var failures = 0

private fun expect(condition: Boolean, label: String) {
  checks++
  if (condition) {
    println("ok: $label")
  } else {
    failures++
    println("FAIL: $label")
  }
}

private fun expectNames(actual: List<String>, expected: List<String>, label: String) {
  expect(actual == expected, "$label: expected $expected, got $actual")
}

private fun row(name: String, pid: Int = 1000) = ProcessMemory(name, pid, 1_000L, "RSS")

fun main() {
  val pkg = "com.example.notes"

  // Exact package process.
  expectNames(
    stillRunningProcesses(
      listOf(row("init", 1), row(pkg, 4100), row("system_server", 2)),
      pkg,
    ),
    listOf(pkg),
    "exact package row is reported",
  )

  // `package:`-prefixed child process.
  expectNames(
    stillRunningProcesses(
      listOf(row("$pkg:remote", 4101), row("init", 1)),
      pkg,
    ),
    listOf("$pkg:remote"),
    "colon-prefixed child row is reported",
  )

  // A bare trailing colon is still a `package:` prefix.
  expectNames(
    stillRunningProcesses(listOf(row("$pkg:")), pkg),
    listOf("$pkg:"),
    "bare package-colon row is reported",
  )

  // Input order and duplicates are preserved, and unrelated rows are dropped.
  expectNames(
    stillRunningProcesses(
      listOf(
        row(pkg, 4100),
        row("other.app", 4200),
        row("$pkg:sync", 4102),
        row(pkg, 4103),
        row("$pkg:remote", 4101),
      ),
      pkg,
    ),
    listOf(pkg, "$pkg:sync", pkg, "$pkg:remote"),
    "matches keep input order and duplicates",
  )

  // No match.
  expectNames(
    stillRunningProcesses(
      listOf(row("init", 1), row("other.app", 4200)),
      pkg,
    ),
    emptyList(),
    "unrelated rows yield no matches",
  )

  // Prefix/suffix/contains lookalikes and case variants must not match.
  expectNames(
    stillRunningProcesses(
      listOf(
        row("${pkg}2"),
        row("$pkg.backup"),
        row("com.other.$pkg"),
        row("prefix$pkg"),
        row("com.examples.notes"),
        row("COM.EXAMPLE.NOTES"),
        row("Com.Example.Notes"),
        row("$pkg.extra:remote"),
        row("other.app:remote"),
      ),
      pkg,
    ),
    emptyList(),
    "lookalikes and case variants are not matched",
  )

  // Trimming applies to the package input only; row names are untouched.
  expectNames(
    stillRunningProcesses(
      listOf(row(pkg), row("$pkg:remote", 4101)),
      " \t$pkg\n",
    ),
    listOf(pkg, "$pkg:remote"),
    "package input is trimmed before matching",
  )

  // Blank package matches nothing, even against rows that would match a naive
  // empty prefix; `parsePsRss` never emits an empty name, but the pure rule is
  // unconditional.
  expectNames(
    stillRunningProcesses(listOf(row(":"), row(":remote"), row(pkg)), ""),
    emptyList(),
    "empty package matches nothing",
  )
  expectNames(
    stillRunningProcesses(listOf(row(":"), row(":remote"), row(pkg)), " \t\n "),
    emptyList(),
    "whitespace-only package matches nothing",
  )

  // Empty rows.
  expectNames(
    stillRunningProcesses(emptyList(), pkg),
    emptyList(),
    "empty rows yield no matches",
  )

  // Dots are literal, not regex wildcards.
  expectNames(
    stillRunningProcesses(
      listOf(row("a.b"), row("axb"), row("a.b:remote"), row("aXb:remote")),
      "a.b",
    ),
    listOf("a.b", "a.b:remote"),
    "package dots are compared literally",
  )

  // A one-character package is matched as an exact name or `name:` prefix.
  expectNames(
    stillRunningProcesses(
      listOf(row("a"), row("a:x"), row("ab"), row("ba"), row("a.")),
      "a",
    ),
    listOf("a", "a:x"),
    "short package keeps exact/colon boundary",
  )

  // The shell side composes verification as parsePsRss(ps output) then this
  // matcher; pin that composition against a realistic `ps -A -o PID,RSS,NAME`
  // table (header plus rows). Values are hand-written, not parser output.
  val psTable = """
    PID RSS NAME
      1  1200 init
   4100 204800 com.example.notes
   4101  51200 com.example.notes:remote
   4200   1024 com.example.notes2
   4300   2048 com.example.notes.backup
  """.trimIndent()
  expectNames(
    stillRunningProcesses(parsePsRss(psTable), pkg),
    listOf(pkg, "$pkg:remote"),
    "ps table parsed then matched reports exact and colon rows only",
  )

  println("checks=$checks failures=$failures")
  if (failures != 0) exitProcess(1)
}
