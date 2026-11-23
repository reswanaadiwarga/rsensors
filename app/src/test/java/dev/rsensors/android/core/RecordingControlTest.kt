package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Label and accessibility text for the overlay card's record-session toggle.
 *
 * The expected strings are the spec's exact copy, not values read back from
 * [recordingControl]; the pairing test makes a helper that returns one label
 * for both states fail even if the two individual assertions were loosened.
 */
class RecordingControlTest {

  @Test
  fun `idle state offers to start a session`() {
    assertEquals(
      RecordingControl("Record session", "Start a recording session"),
      recordingControl(false),
    )
  }

  @Test
  fun `recording state offers to stop the session`() {
    assertEquals(
      RecordingControl("Stop recording", "Stop the current recording"),
      recordingControl(true),
    )
  }

  @Test
  fun `the two labels differ so the button cannot stay on one label`() {
    assertNotEquals(
      recordingControl(false).label,
      recordingControl(true).label,
    )
  }
}
