package dev.rsensors.android.core

/**
 * Visible label and accessibility name for the overlay card's record-session
 * toggle.
 *
 * Pure data so the two states are testable off-device and so the view has a
 * single source for the button's text and content description; the helper has
 * no Android dependencies and no side effects.
 *
 * @property label the button's text, e.g. `Record session`
 * @property description the accessibility description for the same action
 */
data class RecordingControl(val label: String, val description: String)

/**
 * Picks the toggle's copy for the current recorder state.
 *
 * @param recording true while a session is being written; the button then
 *   offers to stop it
 * @return the label and description for [recording]
 */
fun recordingControl(recording: Boolean): RecordingControl =
  if (recording) {
    RecordingControl(
      label = "Stop recording",
      description = "Stop the current recording",
    )
  } else {
    RecordingControl(
      label = "Record session",
      description = "Start a recording session",
    )
  }
