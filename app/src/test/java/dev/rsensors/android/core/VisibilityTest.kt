package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pill visibility state machine behind the CPU/GPU, BAT, FPS and RAM chips
 * (ANDROID.md §3, §9 T15i): every kind defaults on, a toggle flips exactly one
 * flag, and the pill can never be emptied — the last enabled kind refuses to
 * turn off.
 */
class VisibilityTest {

  private val kinds =
    listOf(VisibilityKind.CPUGPU, VisibilityKind.BAT, VisibilityKind.FPS, VisibilityKind.RAM)

  @Test
  fun `every kind is on by default`() {
    val visibility = PillVisibility()
    assertTrue(visibility.cpuGpu)
    assertTrue(visibility.bat)
    assertTrue(visibility.fps)
    assertTrue(visibility.ram)
    kinds.forEach { assertTrue(it.name, visibility.isOn(it)) }
  }

  @Test
  fun `toggle turns a kind off and leaves the others alone`() {
    val noCpuGpu = PillVisibility().toggle(VisibilityKind.CPUGPU)
    assertFalse(noCpuGpu.cpuGpu)
    assertTrue(noCpuGpu.bat)
    assertTrue(noCpuGpu.fps)
    assertTrue(noCpuGpu.ram)

    val noBat = PillVisibility().toggle(VisibilityKind.BAT)
    assertTrue(noBat.cpuGpu)
    assertFalse(noBat.bat)
    assertTrue(noBat.fps)
    assertTrue(noBat.ram)

    val noFps = PillVisibility().toggle(VisibilityKind.FPS)
    assertTrue(noFps.cpuGpu)
    assertTrue(noFps.bat)
    assertFalse(noFps.fps)
    assertTrue(noFps.ram)

    val noRam = PillVisibility().toggle(VisibilityKind.RAM)
    assertTrue(noRam.cpuGpu)
    assertTrue(noRam.bat)
    assertTrue(noRam.fps)
    assertFalse(noRam.ram)

    // The other three can all be off while RAM stays on: only the very last
    // chip is protected, not CPU/GPU specifically.
    val ramOnly = PillVisibility()
      .toggle(VisibilityKind.CPUGPU)
      .toggle(VisibilityKind.BAT)
      .toggle(VisibilityKind.FPS)
    assertFalse(ramOnly.cpuGpu)
    assertFalse(ramOnly.bat)
    assertFalse(ramOnly.fps)
    assertTrue(ramOnly.ram)
  }

  @Test
  fun `turning off the last enabled kind is a no-op`() {
    val cpuGpuOnly = PillVisibility(cpuGpu = true, bat = false, fps = false, ram = false)
    assertEquals(cpuGpuOnly, cpuGpuOnly.toggle(VisibilityKind.CPUGPU))

    val batOnly = PillVisibility(cpuGpu = false, bat = true, fps = false, ram = false)
    assertEquals(batOnly, batOnly.toggle(VisibilityKind.BAT))

    val fpsOnly = PillVisibility(cpuGpu = false, bat = false, fps = true, ram = false)
    assertEquals(fpsOnly, fpsOnly.toggle(VisibilityKind.FPS))

    val ramOnly = PillVisibility(cpuGpu = false, bat = false, fps = false, ram = true)
    assertEquals(ramOnly, ramOnly.toggle(VisibilityKind.RAM))
  }

  @Test
  fun `normalised repairs an all-off state to cpu-gpu only`() {
    val repaired =
      PillVisibility(cpuGpu = false, bat = false, fps = false, ram = false).normalised()
    assertEquals(PillVisibility(cpuGpu = true, bat = false, fps = false, ram = false), repaired)
    assertTrue(repaired.isOn(VisibilityKind.CPUGPU))
    assertFalse(repaired.isOn(VisibilityKind.RAM))
  }

  @Test
  fun `normalised leaves a usable state untouched`() {
    val partial = PillVisibility(cpuGpu = false, bat = true, fps = false)
    assertEquals(partial, partial.normalised())
    assertEquals(PillVisibility(), PillVisibility().normalised())
  }

  @Test
  fun `toggling twice restores the previous state`() {
    val twice = PillVisibility().toggle(VisibilityKind.BAT).toggle(VisibilityKind.BAT)
    assertEquals(PillVisibility(), twice)

    val partial = PillVisibility(cpuGpu = true, bat = true, fps = false)
    assertEquals(partial, partial.toggle(VisibilityKind.FPS).toggle(VisibilityKind.FPS))
  }
}
