package dev.rsensors.android.source

import dev.rsensors.android.core.Reading
import dev.rsensors.android.core.ThermalStatus
import kotlin.math.sin

/**
 * Synthetic source for previews and demos.
 *
 * Each [poll] advances an internal tick and returns two out-of-phase sine
 * ramps so the pill visibly moves without any hardware or permission. The
 * shape is a CPU ramp at `55 ± 22 °C` over a 20-poll period, GPU at
 * `48 ± 18 °C` over 40 polls, plus fixed battery and board values.
 *
 * Stateful and single-threaded: one instance per poller.
 */
class DemoSource : SensorSource {
  override val id: String = ID

  private var tick = 0

  override fun poll(now: Long): Reading {
    tick += 1
    val phase = tick / 20.0
    val cpu = 55.0 + 22.0 * sin(phase)
    val gpu = 48.0 + 18.0 * sin(phase / 2.0)
    return Reading(
      t = now,
      cpu = cpu,
      cpuMax = cpu + 3.0,
      cores = List(4) { cpu + 2.0 },
      gpu = gpu,
      gpuHot = gpu + 12.0,
      battery = 34.0,
      thermalStatus = ThermalStatus.NONE,
      board = listOf("VRM MOS" to 51.0, "PCH" to 57.0),
      sourceName = ID,
    )
  }

  companion object {
    const val ID = "demo"
  }
}
