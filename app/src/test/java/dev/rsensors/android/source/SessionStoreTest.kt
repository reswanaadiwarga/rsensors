package dev.rsensors.android.source

import dev.rsensors.android.core.SessionLog
import dev.rsensors.android.core.SessionSample
import dev.rsensors.android.core.ThermalStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * File-backed session storage (ANDROID.md §9, §8 T12).
 *
 * Uses a JUnit temp directory: one `session-<startedAt>.csv` per session, newest
 * first in [SessionStore.list], corrupt files skipped instead of thrown.
 */
class SessionStoreTest {

  @get:Rule
  val temp = TemporaryFolder()

  private fun store() = SessionStore(temp.root)

  private fun sample(t: Long) = SessionSample(
    t = t,
    cpu = 40.0,
    thermal = ThermalStatus.NONE,
    source = "demo",
  )

  @Test
  fun `create append close then read returns the session`() {
    val store = store()

    val writer = store.create(1_000L)
    assertEquals("session-1000.csv", writer.name)
    writer.append(sample(2_000L))
    writer.append(sample(3_000L))
    writer.close()

    assertEquals(
      SessionLog(1_000L, listOf(sample(2_000L), sample(3_000L))),
      store.read("session-1000.csv"),
    )
  }

  @Test
  fun `each append is flushed before close`() {
    val store = store()

    val writer = store.create(1_000L)
    writer.append(sample(2_000L))
    val onDisk = File(temp.root, writer.name).readText()

    assertTrue(onDisk.contains("2000,40.0"))
    writer.close()
  }

  @Test
  fun `list returns sessions newest first with their last sample time`() {
    val store = store()
    store.create(1_000L).apply {
      append(sample(5_000L))
      close()
    }
    store.create(9_000L).apply {
      append(sample(10_000L))
      close()
    }
    store.create(3_000L).close()

    val sessions = store.list()

    assertEquals(
      listOf("session-9000.csv", "session-3000.csv", "session-1000.csv"),
      sessions.map { it.name },
    )
    assertEquals(9_000L, sessions[0].startedAt)
    assertEquals(10_000L, sessions[0].endedAt)
    assertEquals(1, sessions[0].samples)
    assertEquals(3_000L, sessions[1].endedAt)
    assertEquals(0, sessions[1].samples)
    assertEquals(5_000L, sessions[2].endedAt)
  }

  @Test
  fun `list skips corrupt files and an absent directory is empty`() {
    val store = store()
    assertTrue(SessionStore(File(temp.root, "missing")).list().isEmpty())
    assertNull(SessionStore(File(temp.root, "missing")).read("session-1.csv"))

    File(temp.root, "session-500.csv").writeText("not a session\n")
    assertTrue(store.list().isEmpty())
    assertNull(store.read("session-500.csv"))

    store.create(1_000L).close()
    assertEquals(1, store.list().size)
  }

  @Test
  fun `create handles a name collision without overwriting`() {
    val store = store()
    val first = store.create(1_000L)
    first.append(sample(2_000L))
    first.close()
    val second = store.create(1_000L)
    second.append(sample(3_000L))
    second.close()

    assertNotEquals(first.name, second.name)
    assertEquals("session-1000.csv", first.name)
    assertEquals("session-1000-1.csv", second.name)
    assertEquals(2, store.list().size)
    assertEquals(
      SessionLog(1_000L, listOf(sample(2_000L))),
      store.read("session-1000.csv"),
    )
    assertEquals(
      SessionLog(1_000L, listOf(sample(3_000L))),
      store.read("session-1000-1.csv"),
    )
  }

  @Test
  fun `read rejects names outside the session directory`() {
    val store = store()

    assertNull(store.read("../session-1000.csv"))
    assertNull(store.read("notes.txt"))
    assertNull(store.read(""))
  }

  @Test
  fun `delete removes only the named session`() {
    val store = store()
    store.create(1_000L).close()
    store.create(2_000L).close()
    store.create(3_000L).close()

    assertTrue(store.delete("session-2000.csv"))

    assertEquals(
      listOf("session-3000.csv", "session-1000.csv"),
      store.list().map { it.name },
    )
    assertFalse(File(temp.root, "session-2000.csv").exists())
    assertTrue(File(temp.root, "session-1000.csv").exists())
    assertTrue(File(temp.root, "session-3000.csv").exists())
  }

  @Test
  fun `delete rejects invalid and traversal names without deleting anything`() {
    val dir = File(temp.root, "sessions").apply { mkdirs() }
    val store = SessionStore(dir)
    val inside = store.create(1_000L)
    inside.close()
    // Same file name one directory up: a traversal that worked would remove it.
    val outside = File(temp.root, "session-9999.csv").apply { writeText("outside") }

    assertFalse(store.delete("../session-9999.csv"))
    assertFalse(store.delete("session-9999.csv/.."))
    assertFalse(store.delete(""))
    assertFalse(store.delete("session-1000.txt"))

    assertTrue(outside.exists())
    assertTrue(File(dir, inside.name).exists())
  }

  @Test
  fun `delete returns false for a missing session`() {
    assertFalse(store().delete("session-424242.csv"))
  }

  @Test
  fun `delete leaves a non-session file untouched`() {
    val store = store()
    val notes = File(temp.root, "notes.txt").apply { writeText("keep me") }

    assertFalse(store.delete("notes.txt"))

    assertTrue(notes.exists())
    assertEquals("keep me", notes.readText())
  }
}
