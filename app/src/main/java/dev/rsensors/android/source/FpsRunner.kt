package dev.rsensors.android.source

/**
 * Supplies the shell-side FPS reply line (ANDROID.md §3, §9, §8 T14).
 *
 * The overlay polls FPS through this interface so the reply parsing stays on
 * the pure [dev.rsensors.android.core.parseFpsReply] path and a missing Shizuku
 * server is a null reply instead of an exception. Implementations run on the
 * poller's worker thread and must not throw: the worker turns any failure into
 * a null FPS for that poll and keeps the temperature poll intact.
 */
interface FpsRunner {
  /**
   * Runs one FPS measurement in the shell-side UserService.
   *
   * @return the single `OK fps=…` / `ERR …` line, or null when no reply could
   *   be obtained (Shizuku stopped, permission revoked, dead binder)
   */
  fun runFps(): String?
}
