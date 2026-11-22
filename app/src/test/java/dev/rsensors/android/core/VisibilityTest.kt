package dev.rsensors.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pill visibility state machine behind the CPU/GPU, BAT and FPS chips
 * (ANDROID.md §3, §9 T15i): every kind defaults on, a toggle flips exactly one
 * flag, and the pill can never be emptied — the last enabled kind refuses to
 * turn off.
 */
class VisibilityTest {

  private val kinds = listOf(VisibilityKind.CPUGPU, VisibilityKind.BAT, VisibilityKind.FPS)

  @Test
  fun `every kind is on by default`() {
    val visibility = PillVisibility()
    assertTrue(visibility.cpuGpu)
    assertTrue(visibility.bat)
    assertTrue(visibility.fps)
    kinds.forEach { assertTrue(it.name, visibility.isOn(it)) }
  }

  @Test
  fun `toggle turns a kind off and leaves the others alone`() {
    val noCpuGpu = PillVisibility().toggle(VisibilityKind.CPUGPU)
    assertFalse(noCpuGpu.cpuGpu)
    assertTrue(noCpuGpu.bat)
    assertTrue(noCpuGpu.fps)

    val noBat = PillVisibility().toggle(VisibilityKind.BAT)
    assertTrue(noBat.cpuGpu)
    assertFalse(noBat.bat)
    assertTrue(noBat.fps)

    val noFps = PillVisibility().toggle(VisibilityKind.FPS)
    assertTrue(noFps.cpuGpu)
    assertTrue(noFps.bat)
    assertFalse(noFps.fps)
  }

  @Test
  fun `turning off the last enabled kind is a no-op`() {
    val cpuGpuOnly = PillVisibility(cpuGpu = true, bat = false, fps = false)
    assertEquals(cpuGpuOnly, cpuGpuOnly.toggle(VisibilityKind.CPUGPU))

    val batOnly = PillVisibility(cpuGpu = false, bat = true, fps = false)
    assertEquals(batOnly, batOnly.toggle(VisibilityKind.BAT))

    val fpsOnly = PillVisibility(cpuGpu = false, bat = false, fps = true)
    assertEquals(fpsOnly, fpsOnly.toggle(VisibilityKind.FPS))
  }

  @Test
  fun `normalised repairs an all-off state to cpu-gpu only`() {
    val repaired = PillVisibility(cpuGpu = false, bat = false, fps = false).normalised()
    assertEquals(PillVisibility(cpuGpu = true, bat = false, fps = false), repaired)
    assertTrue(repaired.isOn(VisibilityKind.CPUGPU))
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
