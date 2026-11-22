// Independent acceptance checks for T18 StopPolicy (ANDROID.md §3 "Stop app",
// §7 "Stop policy", §8 T18, §9 "Stop app decisions").
//
// Expected tiers/reason categories are hand-derived from the spec, never from
// the implementation's test file or output. Compile against the compiled app
// classes and run StopPolicyAcceptance; it exits non-zero on any failure.
//
// The dispatch forbids editing production code, so this harness is the
// verifier's independent oracle for the pure policy. AIDL/service/UI criteria
// are checked separately (generated transaction, source review, device logs).
import dev.rsensors.android.core.StopDecision;
import dev.rsensors.android.core.StopRisk;
import dev.rsensors.android.core.StopPolicyKt;
import dev.rsensors.android.core.StopTarget;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class StopPolicyAcceptance {
  private static int failures = 0;
  private static int checks = 0;

  private static void expect(boolean condition, String message) {
    checks++;
    if (!condition) {
      failures++;
      System.out.println("FAIL: " + message);
    } else {
      System.out.println("ok: " + message);
    }
  }

  private static StopTarget target(
      String pkg, Integer uid, Set<String> groups, boolean known, boolean system,
      boolean updatedSystem, boolean persistent, boolean hidden, boolean launcher,
      boolean ime, boolean self, boolean shizuku) {
    return new StopTarget(
        pkg, uid, groups, known, system, updatedSystem, persistent, hidden,
        launcher, ime, self, shizuku);
  }

  /** Ordinary known cached user app: uid 10308, one Cached group. */
  private static StopTarget app(String pkg) {
    return target(pkg, 10308, Collections.singleton("Cached"), true, false, false,
        false, false, false, false, false, false);
  }

  private static StopDecision decide(StopTarget t) {
    return StopPolicyKt.stopDecision(t);
  }

  private static void expectRisk(String label, StopTarget t, StopRisk expected) {
    StopDecision d = decide(t);
    expect(d.getRisk() == expected,
        label + ": expected " + expected + ", got " + d.getRisk()
            + " (reason=" + d.getReason() + ")");
  }

  private static void expectReasonMentions(String label, StopTarget t, String... words) {
    String reason = decide(t).getReason();
    boolean hit = reason != null;
    if (hit) {
      String lower = reason.toLowerCase();
      hit = false;
      for (String word : words) {
        if (lower.contains(word.toLowerCase())) {
          hit = true;
          break;
        }
      }
    }
    expect(hit, label + ": reason should name the cause, got <" + reason + ">");
  }

  private static void expectNoReason(String label, StopTarget t) {
    expect(decide(t).getReason() == null, label + ": normal tier has no reason");
  }

  private static Set<String> groups(String... values) {
    Set<String> out = new LinkedHashSet<>();
    Collections.addAll(out, values);
    return out;
  }

  public static void main(String[] args) {
    // Protected: self wins over everything, including Shizuku/system flags.
    expectRisk("self", target("dev.rsensors.android", 1000, Collections.singleton("Visible"),
        true, true, true, true, false, true, true, true, true), StopRisk.PROTECTED);
    // Protected: Shizuku, the shell route itself.
    expectRisk("Shizuku", target("moe.shizuku.privileged.api", 10308,
        Collections.emptySet(), true, false, false, false, false, false, false, false, true),
        StopRisk.PROTECTED);
    // Protected: no package (native/unresolved).
    expectRisk("null package", target(null, null, Collections.emptySet(), false,
        false, false, false, false, false, false, false, false), StopRisk.PROTECTED);
    // Protected: unresolved package even when uid is ordinary.
    expectRisk("unresolved package", target("vendor.example.npu", 10308,
        Collections.emptySet(), false, false, false, false, false, false, false, false, false),
        StopRisk.PROTECTED);
    // Protected: unknown wins over the low-UID reason; either way protected.
    expectRisk("unknown low uid", target("kernel.thread", 1000, Collections.emptySet(),
        false, false, false, false, false, false, false, false, false), StopRisk.PROTECTED);
    // Protected: every UID below the first app id (10000).
    for (int uid : new int[]{0, 1000, 2000, 9999}) {
      expectRisk("uid " + uid, target("com.example.x", uid, Collections.singleton("Cached"),
          true, false, false, false, false, false, false, false, false), StopRisk.PROTECTED);
    }
    // Normal: uid 10000 is the first ordinary app id.
    expectRisk("uid 10000", target("com.example.x", 10000, Collections.singleton("Cached"),
        true, false, false, false, false, false, false, false, false), StopRisk.NORMAL);
    // Protected: persistent flag.
    expectRisk("persistent flag", target("com.example.x", 10308,
        Collections.singleton("Cached"), true, false, false, true, false, false, false,
        false, false), StopRisk.PROTECTED);
    // Protected: core denylist from §9.
    String[] core = {
        "android", "com.android.systemui", "com.android.phone", "com.android.bluetooth",
        "com.android.nfc", "com.android.telephony", "com.android.server.telecom",
        "com.android.providers.telephony", "com.android.providers.settings",
    };
    for (String pkg : core) {
      expectRisk("core " + pkg, app(pkg), StopRisk.PROTECTED);
    }
    // Core package stays protected even with system/updated-system flags and
    // in-use groups.
    expectRisk("core with flags", target("com.android.systemui", 10308,
        groups("Visible", "Persistent"), true, true, true, false, false, false, false,
        false, false), StopRisk.PROTECTED);
    // Clarified §9: when both match, the core-package reason outranks the
    // persistent reason (tier is PROTECTED either way).
    StopTarget persistentCore = target("com.android.phone", 10308,
        Collections.singleton("Cached"), true, true, false, true, false, false, false,
        false, false);
    expectRisk("core+persistent", persistentCore, StopRisk.PROTECTED);
    expectReasonMentions("core+persistent reason", persistentCore, "core system package");
    // Protected: Native / System / Persistent / Persistent Service OOM groups,
    // case-insensitive and whitespace-tolerant.
    String[] protectedGroups = {
        "Native", "System", "Persistent", "Persistent Service",
        "native", "PERSISTENT SERVICE", "  Persistent Service  ",
    };
    for (String group : protectedGroups) {
      expectRisk("group " + group, target("com.example.x", 10308,
          Collections.singleton(group), true, false, false, false, false, false, false,
          false, false), StopRisk.PROTECTED);
    }
    // Protected group wins next to a cached group.
    expectRisk("Cached+native", target("com.example.x", 10308,
        groups("Cached", "native"), true, false, false, false, false, false, false,
        false, false), StopRisk.PROTECTED);

    // Warning: AICore is an updated-system, visible app and must stay stoppable.
    StopTarget aicore = target("com.google.android.aicore", 10308,
        Collections.singleton("Visible"), true, true, true, false, false, false, false,
        false, false);
    expectRisk("AICore", aicore, StopRisk.WARNING);
    expectReasonMentions("AICore reason", aicore, "ai", "artificial", "on-device");
    // Warning: GMS.
    StopTarget gms = target("com.google.android.gms", 10155, Collections.emptySet(),
        true, true, true, false, false, false, false, false, false);
    expectRisk("GMS", gms, StopRisk.WARNING);
    expectReasonMentions("GMS reason", gms, "play", "google");
    // Clarified §9: a protected OOM group outranks the GMS warning. On this
    // phone GMS is Persistent/System, so both groups must yield PROTECTED.
    StopTarget gmsPersistent = target("com.google.android.gms", 10155,
        Collections.singleton("Persistent"), true, true, true, false, false, false,
        false, false, false);
    expectRisk("GMS persistent group", gmsPersistent, StopRisk.PROTECTED);
    expectReasonMentions("GMS persistent reason", gmsPersistent, "persistent");
    StopTarget gmsSystem = target("com.google.android.gms", 10155,
        Collections.singleton("System"), true, true, true, false, false, false,
        false, false, false);
    expectRisk("GMS system group", gmsSystem, StopRisk.PROTECTED);
    expectReasonMentions("GMS system reason", gmsSystem, "system");
    // Warning: launcher, even when it is also a system app.
    StopTarget launcher = target("com.mi.android.globallauncher", 10308,
        Collections.singleton("Cached"), true, true, false, false, false, true, false,
        false, false);
    expectRisk("launcher", launcher, StopRisk.WARNING);
    expectReasonMentions("launcher reason", launcher, "home", "launcher");
    // Warning: current IME.
    StopTarget ime = target("com.example.ime", 10308, Collections.singleton("Cached"),
        true, false, false, false, false, false, true, false, false);
    expectRisk("IME", ime, StopRisk.WARNING);
    expectReasonMentions("IME reason", ime, "keyboard", "ime", "input");
    // Warning: system / updated-system app not in the denylist.
    expectRisk("system app", target("com.example.system", 10308,
        Collections.singleton("Cached"), true, true, false, false, false, false, false,
        false, false), StopRisk.WARNING);
    expectRisk("updated system app", target("com.example.system", 10308,
        Collections.singleton("Cached"), true, false, true, false, false, false, false,
        false, false), StopRisk.WARNING);
    // Warning: details hidden (Apps row whose ApplicationInfo is invisible).
    StopTarget hidden = target("com.example.hidden", null, Collections.singleton("Cached"),
        true, false, false, false, true, false, false, false, false);
    expectRisk("details hidden", hidden, StopRisk.WARNING);
    expectReasonMentions("hidden reason", hidden, "hidden", "details");
    // Warning: in-use OOM groups, case-insensitive and whitespace-tolerant.
    String[] inUse = {
        "Foreground", "Visible", "Perceptible", "Perceptible Low", "Heavy Weight",
        "foreground", "PERCEPTIBLE LOW", "  Heavy Weight  ",
    };
    for (String group : inUse) {
      expectRisk("in-use " + group, target("com.example.x", 10308,
          Collections.singleton(group), true, false, false, false, false, false, false,
          false, false), StopRisk.WARNING);
    }
    // Warning: in-use next to cached.
    expectRisk("Cached+Visible", target("com.example.x", 10308,
        groups("Cached", "Visible"), true, false, false, false, false, false, false,
        false, false), StopRisk.WARNING);

    // Normal: cached / background / service apps, and a known app with no flags.
    StopTarget normal = app("com.example.notes");
    expectRisk("cached normal", normal, StopRisk.NORMAL);
    expectNoReason("cached normal", normal);
    expectRisk("service normal", target("com.example.sync", 10308,
        Collections.singleton("Service"), true, false, false, false, false, false,
        false, false, false), StopRisk.NORMAL);
    StopTarget plain = target("com.example.plain", null, Collections.emptySet(), true,
        false, false, false, false, false, false, false, false);
    expectRisk("known no flags", plain, StopRisk.NORMAL);
    expectNoReason("known no flags", plain);

    // Case-insensitive package matching (§9 "matched case-insensitively").
    expectRisk("core upper case", app("COM.ANDROID.SYSTEMUI"), StopRisk.PROTECTED);
    expectRisk("gms mixed case", app("Com.Google.Android.Gms"), StopRisk.WARNING);
    expectRisk("aicore mixed case", app("com.google.android.AICore"), StopRisk.WARNING);

    // Every protected/warning decision names a reason; every normal one does not.
    StopTarget[] all = {
        aicore, gms, launcher, ime, hidden,
        app("android"), app("com.example.notes"),
    };
    for (StopTarget t : all) {
      StopDecision d = decide(t);
      if (d.getRisk() == StopRisk.NORMAL) {
        expect(d.getReason() == null, "normal " + t.getPackageName() + " has no reason");
      } else {
        expect(d.getReason() != null && !d.getReason().isEmpty(),
            d.getRisk() + " " + t.getPackageName() + " has a reason");
      }
    }

    System.out.println("checks=" + checks + " failures=" + failures);
    if (failures != 0) {
      System.out.println("ACCEPTANCE FAILED");
      System.exit(1);
    }
    System.out.println("ACCEPTANCE PASSED");
  }
}
