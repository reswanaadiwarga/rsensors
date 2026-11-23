// Independent acceptance checks for T19b-6 FPS previous-app cache invalidation.
//
// Expected values are hand-derived from the task spec and the captured Android
// 16 `dumpsys window displays` shape (mCurrentFocus for NotificationShade
// precedes the indented mFocusedApp ActivityRecord; mFocusedApp is the focus
// source the drawing-cache check reads). They are never copied from the
// implementation's unit tests or from its output.
//
// Compile against `core/FpsParse.kt` and run FpsCacheFocusAcceptanceKt; it
// exits non-zero on any failure.

import dev.rsensors.android.core.parseFocusedComponent
import kotlin.system.exitProcess

private var failures = 0
private var checks = 0

private fun expectEquals(actual: Any?, expected: Any?, label: String) {
  checks++
  if (actual == expected) {
    println("ok: $label")
  } else {
    failures++
    println("FAIL: $label: expected <$expected>, got <$actual>")
  }
}

private val launcher =
  "com.mi.android.globallauncher/com.miui.home.launcher.Launcher"
private val settings = "com.android.settings/com.android.settings.MiuiSettings"

fun main() {
  // --- the real `displays` shape: mCurrentFocus (shade) must not win ---------
  // Captured on the Android 16 target: the lighter dump's display block prints
  // mCurrentFocus before an indented mFocusedApp. The shade/IME windows are
  // noise; only mFocusedApp identifies the app whose layer is cached.
  val realShape = listOf(
    "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)",
    "  Display: mDisplayId=0 root=0x1",
    "    mDisplayInfo=DisplayInfo{\"Built-in Screen\", uniqueId=\"local:0\", " +
      "app 1080 x 2400, real 1080 x 2400}",
    "    mDisplayFrames=DisplayFrames{w=1080, h=2400}",
    "    mCurrentFocus=Window{29781d2 u0 NotificationShade}",
    "    mFocusedApp=ActivityRecord{230081998 u0 $launcher t2}",
    "    mFocusedWindow=Window{29781d2 u0 NotificationShade}",
    "    mInputMethodWindow=Window{2c31385 u0 " +
      "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME}",
  ).joinToString("\n")

  expectEquals(
    parseFocusedComponent(realShape),
    launcher,
    "displays dump: mFocusedApp wins over an earlier mCurrentFocus shade",
  )

  // --- mFocusedApp still wins when mCurrentFocus is a different app ----------
  // On a real switch mCurrentFocus briefly names the outgoing/launcher window;
  // only mFocusedApp identifies the app the layer cache must invalidate.
  val switched = realShape
    .replace(
      "    mCurrentFocus=Window{29781d2 u0 NotificationShade}",
      "    mCurrentFocus=Window{81c33ba u0 $launcher}",
    )
    .replace(
      "    mFocusedApp=ActivityRecord{230081998 u0 $launcher t2}",
      "    mFocusedApp=ActivityRecord{230081998 u0 $settings t5643}",
    )
  expectEquals(
    parseFocusedComponent(switched),
    settings,
    "displays dump: mFocusedApp reports the switched-to app",
  )

  // --- leading-dot class expansion is unchanged -----------------------------
  val dotted = "    mFocusedApp=ActivityRecord{7 u0 com.example.app/.Main t1}"
  expectEquals(
    parseFocusedComponent(dotted),
    "com.example.app/com.example.app.Main",
    "displays dump: leading-dot class expands against the package",
  )

  // --- a lighter dump with only mCurrentFocus still resolves ----------------
  expectEquals(
    parseFocusedComponent(
      "  Display: mDisplayId=0\n" +
        "    mCurrentFocus=Window{81c33ba u0 $launcher}",
    ),
    launcher,
    "displays dump: mCurrentFocus fallback resolves when mFocusedApp is absent",
  )

  // --- unreadable focus returns null so the caller keeps cached FPS ---------
  expectEquals(
    parseFocusedComponent(
      "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" +
        "  Display: mDisplayId=0 root=0x1\n" +
        "    mDisplayFrames=DisplayFrames{w=1080, h=2400}\n" +
        "    mFocusedApp=null\n" +
        "    mCurrentFocus=null",
    ),
    null,
    "displays dump: no readable focus -> null (cached FPS must survive)",
  )
  expectEquals(
    parseFocusedComponent(
      "    mFocusedWindow=Window{29781d2 u0 NotificationShade}",
    ),
    null,
    "displays dump: mFocusedWindow alone is not focus",
  )

  println()
  println(if (failures == 0) "PASS: $checks checks" else "FAILED: $failures of $checks checks")
  if (failures != 0) exitProcess(1)
}
