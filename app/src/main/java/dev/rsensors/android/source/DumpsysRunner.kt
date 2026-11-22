package dev.rsensors.android.source

/**
 * Supplies a raw `dumpsys thermalservice` dump (ANDROID.md §5).
 *
 * The overlay polls through this interface instead of the Shizuku API so the
 * parsing path is testable on the JVM. An implementation runs on the poller's
 * thread and must not throw: a missing Shizuku server, a denied permission or a
 * dead binder all surface as a null dump.
 */
interface DumpsysRunner {
  /**
   * Runs one dump.
   *
   * @return the full stdout text, or null when no dump could be obtained
   */
  fun run(): String?

  /** Releases the binding. Idempotent; the default has nothing to do. */
  fun close() {}
}
