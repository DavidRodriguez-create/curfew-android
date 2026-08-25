# Curfew

An Android app that blocks Instagram, YouTube, TikTok, Chrome and friends after a chosen hour.

Self-control apps fail because the person they are restraining also holds the off switch. The
premise here is to move the unlock authority to a second person you trust — no server, no
account, no monthly cost, and no network call at unlock time.

> **v0.2 builds both halves of that premise.** Pairing, code verification, replay protection,
> the clock guard and the keypad on the shield are all built and running here; the other end —
> the small offline web app that turns the shared secret into a six-digit code — lives in
> [curfew-approver](https://github.com/DavidRodriguez-create/curfew-approver) and is served
> from <https://davidrodriguez-create.github.io/curfew-approver/>. The whole loop has been run
> end to end: paired by QR, a code granted on the second device, a live shield unlocked for the
> authenticated duration. The [panic override](#the-panic-override) remains the sanctioned
> escape hatch when the approver is unreachable.

Kotlin · Jetpack Compose · minSdk 26 · targetSdk 35 · **no network code anywhere in the app**.

---

## What it does

A schedule, a blocklist, a full-screen shield over anything on it, and a
[six-digit unlock](#the-partner-gated-unlock) only someone else can produce.

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
| Duration-bound TOTP verifier, against the RFC 4226 vectors | [TotpVerifier.kt](app/src/main/java/dev/davidz/curfew/core/TotpVerifier.kt) |
| Keystore-wrapped shared secret, QR and recovery string | [Pairing.kt](app/src/main/java/dev/davidz/curfew/core/Pairing.kt) |
| Refuses a code that was already spent | [ReplayWindow.kt](app/src/main/java/dev/davidz/curfew/core/ReplayWindow.kt) |
| Catches the system clock being moved | [ClockGuard.kt](app/src/main/java/dev/davidz/curfew/core/ClockGuard.kt) |
| Recognises the accessibility settings screen | [SettingsGuard.kt](app/src/main/java/dev/davidz/curfew/core/SettingsGuard.kt) |
| Six-digit entry drawn on the shield itself | [UnlockKeypad.kt](app/src/main/java/dev/davidz/curfew/service/UnlockKeypad.kt) |

### The four states

- **Disarmed** — master switch off, nothing enforced.
- **Idle** — armed, outside the window.
- **Enforcing** — inside the window; blocked apps get the shield; **settings are locked**.
- **Override** — a grant is running, panic or approved; blocking is suspended and settings
  unlock again. The log records which kind it was and for how long.

### The partner-gated unlock

A random 160-bit secret is generated on the phone, wrapped by a non-extractable Android Keystore
key, and shown once as a QR code plus a typed base32 fallback. The approver scans it, in person.
From then on their device can compute

```
code = truncate6( HMAC-SHA1( S, T || duration_byte ) )      T = floor(unix_seconds / 30)
```

and read six digits down the phone. Because the duration is inside the MAC it is authenticated
too: a 15-minute code cannot be presented as a 60-minute one — they are different messages and
produce unrelated digits. Verification sweeps `T-1, T, T+1` against every offered duration, so
the two clocks do not have to agree on the second.

Around that:

- **Replay.** Spent `(T, duration)` pairs are refused even inside their own window, so a
  screenshot of a code taken in front of the shield is worth nothing.
- **The clock.** Wall-clock time is attacker-controlled; `elapsedRealtime()` is not. The two are
  anchored together while nothing is being enforced and compared from then on. Past a minute of
  drift the phone stops believing its own clock, runs the schedule off the monotonic projection
  instead, and refuses codes until the anchor can be retaken. Winding the clock to 08:00 in
  front of the shield buys nothing.
- **Rate limiting.** Three free attempts, then a lockout doubling from 30 seconds to a
  15-minute cap.
- **Recovery.** The secret can be shown again at any time outside a curfew, so a wiped browser
  on the approver's side is not fatal. Re-pairing is refused mid-curfew — otherwise the escape
  is trivial: pair to a secret you generated yourself and approve your own unlock.

Entry is a drawn keypad rather than a text field, because the shield window is
`FLAG_NOT_FOCUSABLE` and has to stay that way — making it focusable to summon a keyboard would
stop the back key reaching the app underneath.

**The approver's app** is [curfew-approver](https://github.com/DavidRodriguez-create/curfew-approver):
a static page — WebCrypto HMAC-SHA1, a service worker for offline, the secret in local storage —
served from <https://davidrodriguez-create.github.io/curfew-approver/> and installable to the
home screen. The two codebases share no code, so agreement is enforced by vectors rather than by
reuse: nine `(counter, duration) -> code` pairs are asserted verbatim on both sides, here in
`ApproverContractTest` and there in `test/vectors.test.mjs`. Change either side and run both
suites.

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

Three mechanisms exist, and only three. Curfew implements the first.

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

Unit tests — schedule maths, base32, the RFC 4226 vectors, replay, the clock guard:

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

## Where this is going

**Nothing below is implemented.** Written down so the shape of the current code makes sense —
not a commitment to ship any of it.

**Further out.** A local DNS `VpnService` filter, so a domain is blocked inside every browser
rather than only as a whole app; per-app schedules; usage stats from `UsageStatsManager`; a
documented device-owner install path, which is the only configuration that survives an
uninstall.

## Known limits

- **A latched clock skew locks the approver out.** If the wall clock moves more than a minute
  against `elapsedRealtime` mid-curfew, every approver code is refused until the anchor is
  retaken — which only happens on a reboot or outside the window. The shield says "clock
  tampered", but not that it is unrecoverable until morning. The panic override is the way
  through. Widening or re-anchoring is an open decision for v1.0.
- **The camera path is unproven.** The QR itself decodes — the vendored jsQR read a real
  screenshot of the phone's code and recovered the exact secret — but no phone camera has yet
  read it off a screen. The typed recovery string is the fallback and works.
- **Uninstalling** defeats it. That is what device owner mode is for.
- **Browsers** are blocked only as whole apps. Blocking a single domain inside any browser needs
  the local DNS filter.
- **Safe Mode still wins.** The shield over the accessibility settings screen raises the cost of
  switching the blocker off. It is not a lock and does not pretend to be.
- **Never run on physical hardware.** Everything here is verified on an Android 15 emulator.
  What that cannot answer is whether an OEM battery manager kills the service overnight.

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
  loop and must never block. The replay window looked like the thing that would finally justify
  a database and turned out not to be — a few dozen short strings that expire after two minutes.
  Room waits for state that is actually relational.
- **The settings shield has to be an accessibility overlay.** The accessibility settings screen
  calls `setHideOverlayWindows(true)`, so the framework force-hides every ordinary overlay drawn
  over it: `addView` succeeds, the window exists, and nothing appears.
  `TYPE_ACCESSIBILITY_OVERLAY` is the exemption, so that shield always takes it while an app
  shield keeps preferring `TYPE_APPLICATION_OVERLAY`.
- **`start == end` means no window, not all day.** An accidental equal pair should fail open
  rather than lock the phone forever.

## Licence

[Apache 2.0](LICENSE).
