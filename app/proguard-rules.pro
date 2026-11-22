# R8 / ProGuard rules for Rsensors (ANDROID.md §8 T11).
#
# Keep additions tied to a specific symptom seen only in a minified build.
# Everything else is kept by the Android Gradle plugin (manifest components,
# activities, services, receivers) or by the Shizuku AAR's consumer rules.
#
# Shizuku starts dev.rsensors.android.shizuku.DumpsysUserService by name in a
# shell-owned process. That reflective use is invisible to R8's own graph, so
# without this rule the class, its (Context)/( ) constructors or the AIDL Stub
# methods could be renamed, shrunk or dropped, and the tier-1 dumpsys path would
# fall back to battery temperature. The package also holds the generated
# IDumpsysService AIDL types, which the server-side class implements.
-keep class dev.rsensors.android.shizuku.** { *; }
