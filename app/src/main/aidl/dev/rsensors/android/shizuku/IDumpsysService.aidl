// Shell-side contract of the dumpsys UserService (ANDROID.md §9).
//
// The service runs as the shell UID under Shizuku, so `dumpsys thermalservice`
// executes with shell privileges and returns its stdout to the app. `destroy`
// uses Shizuku's reserved transaction id so the server can recycle a stale
// instance by version. `runDumpsys` returns the thermal dump; `runFps` returns
// one `OK fps=…` / `ERR …` line for the foreground SurfaceFlinger layer
// (ANDROID.md §8 T14). Adding `runFps` bumps the UserService version to 3.
package dev.rsensors.android.shizuku;

interface IDumpsysService {
    void destroy() = 16777114;
    String runDumpsys() = 1;
    String runFps() = 2;
}
