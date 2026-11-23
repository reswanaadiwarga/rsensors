package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Post-stop verification logic for T18 (ANDROID.md §8 T18).
 *
 * `am force-stop` can exit 0 while the package's processes are still alive, so
 * the shell side re-reads `ps -A -o PID,RSS,NAME` and looks for the package
 * again. These tests pin the matching rule: exact package name or
 * `package:`-prefixed children only, never `package2` or another package that
 * merely contains the name.
 *
 * Expected values come from the spec's match rule, not from the implementation.
 */
class StopVerificationTest {

  private fun row(name: String, pid: Int = 1000) = ProcessMemory(name, pid, 1_000L, "RSS")

  @Test
  fun `exact package process is still running`() {
    val rows = listOf(row("init", 1), row("com.example.notes"), row("system", 2))

    assertEquals(
      listOf("com.example.notes"),
      stillRunningProcesses(rows, "com.example.notes"),
    )
  }

  @Test
  fun `package colon service process is still running`() {
    val rows = listOf(row("com.example.notes:remote", 4101))

    assertEquals(
      listOf("com.example.notes:remote"),
      stillRunningProcesses(rows, "com.example.notes"),
    )
  }

  @Test
  fun `multiple matches keep the input order`() {
    val rows = listOf(
      row("com.example.notes:sync", 4102),
      row("other.app", 4200),
      row("com.example.notes", 4100),
      row("com.example.notes:remote", 4101),
    )

    assertEquals(
      listOf("com.example.notes:sync", "com.example.notes", "com.example.notes:remote"),
      stillRunningProcesses(rows, "com.example.notes"),
    )
  }

  @Test
  fun `no matching process yields an empty list`() {
    val rows = listOf(row("init", 1), row("com.example.other"))

    assertEquals(emptyList<String>(), stillRunningProcesses(rows, "com.example.notes"))
  }

  @Test
  fun `a package name with a longer suffix is not matched`() {
    // `com.example.notes2` and `com.example.notes.backup` merely start with the
    // stopped name; force-stop targets whole package names, so they must stay.
    val rows = listOf(
      row("com.example.notes2"),
      row("com.example.notes.backup"),
      row("com.example.not"),
    )

    assertEquals(emptyList<String>(), stillRunningProcesses(rows, "com.example.notes"))
  }

  @Test
  fun `a different package containing the name is not matched`() {
    val rows = listOf(
      row("com.other.com.example.notes"),
      row("prefixcom.example.notes"),
      row("com.example.notesx:remote"),
    )

    assertEquals(emptyList<String>(), stillRunningProcesses(rows, "com.example.notes"))
  }

  @Test
  fun `the package input is trimmed before matching`() {
    val rows = listOf(row("com.example.notes"), row("com.example.notes:remote", 4101))

    assertEquals(
      listOf("com.example.notes", "com.example.notes:remote"),
      stillRunningProcesses(rows, "  com.example.notes\n"),
    )
  }

  @Test
  fun `empty rows yield an empty list`() {
    assertEquals(emptyList<String>(), stillRunningProcesses(emptyList(), "com.example.notes"))
  }

  @Test
  fun `a blank package matches nothing`() {
    val rows = listOf(row("com.example.notes"), row(" "))

    assertEquals(emptyList<String>(), stillRunningProcesses(rows, "   "))
  }
}
