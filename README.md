# Curfew

An Android app that blocks Instagram, YouTube, TikTok, Chrome and friends after a chosen hour,
where **you are not the one who can turn it off**.

Self-control apps fail because the person they are restraining also holds the off switch. Curfew
moves the unlock authority to a second person you trust: they hold half of a shared secret and
generate a short-lived code from an offline PWA. No server, no account, no monthly cost, and no
network call at unlock time.

> **This repository is at v0.1 — phase 1 of that plan.** The blocking works; the unlock codes do
> not exist yet. See [Roadmap](#roadmap).

Kotlin · Jetpack Compose · minSdk 26 · targetSdk 35 · **no network code anywhere in the app**.

---

## What v0.1 does

A schedule, a blocklist, and a full-screen shield over anything on it.

| Piece | File |
|---|---|
| Watches the foreground package via `AccessibilityService` | [BlockerAccessibilityService.kt](app/src/main/java/dev/davidz/curfew/service/BlockerAccessibilityService.kt) |
| Draws the full-screen shield over a blocked app | [OverlayManager.kt](app/src/main/java/dev/davidz/curfew/service/OverlayManager.kt) |
| Keeps the process alive against Doze / OEM battery killers | [CurfewForegroundService.kt](app/src/main/java/dev/davidz/curfew/service/CurfewForegroundService.kt) |
| Decides "is this app allowed right now" | [Policy.kt](app/src/main/java/dev/davidz/curfew/core/Policy.kt) |
| Midnight-wrapping time-window maths (unit tested) | [ScheduleEngine.kt](app/src/main/java/dev/davidz/curfew/core/ScheduleEngine.kt) |
| Curated candidate app list | [Blocklist.kt](app/src/main/java/dev/davidz/curfew/core/Blocklist.kt) |
| State + capped activity log | [CurfewPrefs.kt](app/src/main/java/dev/davidz/curfew/core/CurfewPrefs.kt) |
| Compose control surface | [CurfewScreen.kt](app/src/main/java/dev/davidz/curfew/ui/CurfewScreen.kt) |

### The four states

- **Disarmed** — master switch off, nothing enforced.
- **Idle** — armed, outside the window.
- **Enforcing** — inside the window; blocked apps get the shield; **settings are locked**.
- **Override** — a panic grant is running; blocking is suspended and settings unlock again.

### The panic override

Tap *I really need this* → a 5-minute cooldown counts down on the shield → then
*Unlock 15 minutes*. Every step is written to the activity log.

This exists deliberately, and it was built first rather than last. A blocker with no escape hatch
gets uninstalled in week two; a blocker whose escape hatch costs five minutes of staring at a wall
and leaves a visible trace gets used. The friction is the product, not the lock.

### What is locked mid-curfew

While **Enforcing**, you cannot disarm, cannot edit the schedule, cannot un-block an app, and
cannot clear the log. The only exit is the override. An unlocked disable button would make the
rest of the app decorative.

---

## How blocking works on Android without root

Three mechanisms exist, and only three. v0.1 implements the first.

| Mechanism | What it buys | Cost |
|---|---|---|
| **AccessibilityService** | Sees `TYPE_WINDOW_STATE_CHANGED`, so it knows the foreground package and can cover it with an overlay | Must be enabled by hand in Settings — and can be disabled there too |
| **VpnService** (local, no remote endpoint) | A local DNS filter. Kills `instagram.com` *everywhere*, including inside browsers and in-app webviews | Blocks by content rather than by app. No traffic leaves the phone |
| **DevicePolicyManager** (device owner) | Real `setPackagesSuspended()`. The app cannot launch and the blocker cannot be uninstalled | Needs ADB provisioning on a device with no other accounts. Nuclear option |
| ~~UsageStatsManager~~ | Only *reports* usage | Useful for stats, useless for enforcement |

---

## Build

Requires Android Studio (Ladybug or newer) or a local Android SDK with **platform 35**, and
**JDK 17+**.

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Prebuilt debug APKs are attached to
each [release](../../releases).

Unit tests for the schedule maths:

```bash
./gradlew testDebugUnitTest
```

Install over ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

The app opens with a **Setup** card listing whatever is still missing. In order:

1. **Accessibility service** — Settings › Accessibility › Curfew blocker → on.
   Nothing is blocked until this is on.
2. **Draw over other apps** — without it the shield falls back to
   `TYPE_ACCESSIBILITY_OVERLAY`, which works but dies whenever the service restarts.
3. **Unrestricted battery** — Xiaomi, Samsung and Huawei will otherwise kill the service
   overnight. On those OEMs also pin the app in recents and enable autostart.
4. **Notifications** — optional; carries the ongoing status notification.

Curfew is meant to be **sideloaded**. It is not on Google Play and is not intended to be: the
accessibility and overlay behaviour it depends on sits in a grey area of Play policy.

## Testing it without waiting until 23:00

Set **Ends** a few minutes from now and **Starts** a minute before now, then open a blocked app.
The shield should appear within about two seconds.

Keep the test window short — once it is live you are in **Enforcing** and the schedule is locked,
so the override is your way back into the settings.

```bash
adb logcat -s CurfewBlocker CurfewOverlay CurfewFgs
```

---

## Roadmap

- **v0.1 — this release.** Schedule, blocklist, overlay, panic override.
- **v0.2** — QR pairing and the shared-secret unlock. A random 160-bit secret is generated on the
  phone and scanned into an offline PWA on the approver's device. Granting access computes
  `truncate6(HMAC-SHA1(S, T_bytes || duration_byte))` over a 30-second time step, so the *duration*
  is authenticated too and a 15-minute code cannot be passed off as a 60-minute one. Used
  `(T, duration)` pairs go into a replay blacklist. Plus `ClockGuard`: `currentTimeMillis()` versus
  `elapsedRealtime()` drift since boot, refusing to unlock past 60 seconds of skew.
- **v0.3** — local DNS `VpnService` blocklist, per-app schedules, stats from `UsageStatsManager`.
- **v1.0** — documented device-owner install path, exportable log.

## Known limits of v0.1

- **Turning off the accessibility service in Settings** defeats it. Detecting and overlaying the
  accessibility settings screen is v0.2.
- **Uninstalling** defeats it. That is what device owner mode is for.
- **Browsers** are blocked only as whole apps. Blocking a single domain inside any browser needs
  the local DNS filter.
- **The clock is trusted.** `ClockGuard` arrives with the unlock codes, where a spoofable clock
  would actually buy you something.
- **No unlock codes yet.** The shared-secret scheme and the approver's PWA are v0.2.

## Design notes

A few decisions that are not obvious from the code:

- **Kotlin + Compose over Java.** Everything Curfew depends on — `AccessibilityService`,
  `VpnService`, `WindowManager` overlays, `DevicePolicyManager`, `javax.crypto.Mac` — is
  Java-first framework API, so the project is fully buildable in Java. Compose is the only part
  genuinely closed to it, and it is the part you touch most while iterating.
- **Plain Views for the shield, Compose for the app UI.** A `ComposeView` inside a
  `WindowManager` window needs a hand-rolled `LifecycleOwner` and `SavedStateRegistryOwner`. Not
  worth the failure mode at 03:00.
- **SharedPreferences, not Room.** Every policy read happens on the accessibility service's main
  loop and must never block. Room arrives with v0.2, when there is actually relational state
  (grants, replay windows) to store.
- **`start == end` means no window, not all day.** An accidental equal pair should fail open
  rather than lock the phone forever.

## Licence

[Apache 2.0](LICENSE).
