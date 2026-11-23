// Shell-side contract of the dumpsys UserService (ANDROID.md §9).
//
// The service runs as the shell UID under Shizuku, so `dumpsys thermalservice`
// executes with shell privileges and returns its stdout to the app. `destroy`
// uses Shizuku's reserved transaction id so the server can recycle a stale
// instance by version. `runDumpsys` returns the thermal dump; `runFps` returns
// one `OK fps=…` / `ERR …` line for the foreground SurfaceFlinger layer
// (ANDROID.md §8 T14); `runMeminfo` returns `dumpsys meminfo --oom` stdout for
// the process page, or `ps -A -o PID,RSS,NAME` stdout when the dump has no PSS
// rows or fails (version 4, ANDROID.md §8 T17, T19b-3). `stopPackage`
// runs `am force-stop` for one already-validated package and returns `OK` or
// `ERR …` (ANDROID.md §8 T18); adding it bumps the UserService registration to
// version 5.
package dev.rsensors.android.shizuku;

interface IDumpsysService {
    void destroy() = 16777114;
    String runDumpsys() = 1;
    String runFps() = 2;
    String runMeminfo() = 3;
    String stopPackage(String packageName) = 4;
}
