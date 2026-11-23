// Independent acceptance checks for the expanded overlay card's record toggle
// (feat/record-overlay).
//
// Expected copy is the exact text from the task spec, hand-copied here; it is
// never read back from recordingControl or from the implementer's unit test.
// Compile against the real app core sources and run RecordingControlAcceptanceKt;
// it exits non-zero on any failure.
import dev.rsensors.android.core.RecordingControl
import dev.rsensors.android.core.recordingControl
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
  // Idle (spec): full-width button label `Record session`, accessibility
  // description `Start a recording session`.
  val idle = recordingControl(false)
  expectEquals(idle.label, "Record session", "idle label")
  expectEquals(idle.description, "Start a recording session", "idle description")
  expectEquals(
    idle,
    RecordingControl("Record session", "Start a recording session"),
    "idle control pair",
  )

  // Active (spec): label `Stop recording`, description
  // `Stop the current recording`.
  val recording = recordingControl(true)
  expectEquals(recording.label, "Stop recording", "recording label")
  expectEquals(recording.description, "Stop the current recording", "recording description")
  expectEquals(
    recording,
    RecordingControl("Stop recording", "Stop the current recording"),
    "recording control pair",
  )

  // The toggle is only meaningful if the two states differ, so a helper that
  // returns one label for both states must fail even if a single assertion is
  // later loosened.
  expect(idle.label != recording.label, "labels differ between idle and recording")
  expect(
    idle.description != recording.description,
    "descriptions differ between idle and recording",
  )

  println("checks=$checks failures=$failures")
  if (failures != 0) {
    println("ACCEPTANCE FAILED")
    exitProcess(1)
  }
  println("ACCEPTANCE PASSED")
}
