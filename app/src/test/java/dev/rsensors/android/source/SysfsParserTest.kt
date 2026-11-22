package dev.rsensors.android.source

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DirectReader path and trimming behaviour (ANDROID.md §5). */
class SysfsParserTest {

  @Test
  fun `open trims surrounding whitespace`() {
    val file = File.createTempFile("sysfs-trim", ".txt")
    try {
      file.writeText("  62000 \n")
      assertEquals("62000", DirectReader().open(file.path))
    } finally {
      file.delete()
    }
  }

  @Test
  fun `open reads a fixture value without its trailing newline`() {
    val typePath = fixture("qualcomm") + "/thermal/thermal_zone0/type"
    assertEquals("cpu-0-0-usr", DirectReader().open(typePath))
  }

  @Test
  fun `missing open returns null and records ENOENT`() {
    val reader = DirectReader()
    val path = "/no/such/sysfs/node"
    assertNull(reader.open(path))
    assertEquals(listOf(SysfsError(path, "ENOENT")), reader.errors())
  }

  @Test
  fun `permission denied read failure maps to EACCES`() {
    val denied =
      FileNotFoundException(
        "/sys/class/thermal/thermal_zone0/temp: open failed: EACCES (Permission denied)",
      )
    assertEquals("EACCES", DirectReader.reasonFor(denied))
  }

  @Test
  fun `access denied read failure maps to EACCES`() {
    val denied = FileNotFoundException("/sys/class/thermal/thermal_zone0/temp (Access is denied)")
    assertEquals("EACCES", DirectReader.reasonFor(denied))
  }

  @Test
  fun `plain file not found maps to ENOENT`() {
    assertEquals("ENOENT", DirectReader.reasonFor(FileNotFoundException("/no/such/node")))
  }

  @Test
  fun `security exception maps to EACCES`() {
    assertEquals("EACCES", DirectReader.reasonFor(SecurityException("denied")))
  }

  @Test
  fun `other io failure maps to the exception simple name`() {
    assertEquals("IOException", DirectReader.reasonFor(IOException("boom")))
  }

  @Test
  fun `missing listDir returns empty and records ENOENT`() {
    val reader = DirectReader()
    val path = "/no/such/sysfs/dir"
    assertTrue(reader.listDir(path).isEmpty())
    assertEquals(listOf(SysfsError(path, "ENOENT")), reader.errors())
  }

  @Test
  fun `clearErrors empties the log while a snapshot is unaffected`() {
    val reader = DirectReader()
    reader.open("/no/such/sysfs/node")
    val snapshot = reader.errors()
    reader.clearErrors()
    assertEquals(1, snapshot.size)
    assertTrue(reader.errors().isEmpty())
  }

  private fun fixture(name: String): String =
    File(javaClass.classLoader!!.getResource("fixtures/sysfs/$name")!!.toURI()).path
}
