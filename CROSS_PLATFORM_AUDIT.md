# NovaPlay cross-platform audit: seamless playback (Stages 1–4)

**Audited baseline:** commit `7b49b79` on `main`, tag `seamless-playback-ldplayer-validated`. No production code was changed for this audit.

## Two different checks

- **BUILD PASS:** the code compiles for that platform, and the shared automated tests pass. It does **not** mean the feature works on a device.
- **RUNTIME TESTED:** the feature was run on a device or emulator and its behaviour checked. LDPlayer, an Android emulator set up as a TV, is the only runtime used so far.

## Summary

| Platform | BUILD PASS | RUNTIME TESTED | What Stages 1–4 do here |
|---|---|---|---|
| Android TV / Fire TV | Yes | Yes, on LDPlayer. **Physical Fire TV: pending** | All of it |
| Android phone / tablet (APK) | Yes | Launch only (no playlist, so no playback) | Almost nothing (no preview player). The recording playback fix applies, but there is no way to reach recordings |
| Windows | Yes (compiles) | Launch and navigation only (Windows Test 1). **Playback and multiview: pending** | None in practice: the shared screens include the new code, but it stays switched off without a preview player |
| iOS | **Not built** (needs a Mac) | **No** | None: phone screens, no preview player, no recording |

## Build and test results (at `7b49b79`)

| Check | Result |
|---|---|
| `:app:assembleTvDebug` (Android TV APK) | BUILD PASS |
| `:app:assemblePhoneDebug` (Android phone APK) | BUILD PASS |
| `:desktop:compileKotlin` (Windows) | BUILD PASS (compile only; no installer built) |
| `:shared:desktopTest` | **122 tests, 0 failures, 0 errors, 0 skipped** (21 test classes) |
| `:shared:testDebugUnitTest` | **101 tests, 0 failures, 0 errors, 0 skipped** (18 test classes) |
| iOS (`iosArm64`, `iosSimulatorArm64`) | NOT BUILT: these targets only exist when the build runs on a Mac (`shared/build.gradle.kts`) |

The test results were cleared before the run, so the tests really ran and weren't reused from an earlier build. The tests cover shared logic only: the preview controller, `adopt()`, the hand-over wait and the channel-sync decision (`returnPlace`). Passing them does not show that any platform's player works.

## Why most changes are Android TV only

| Platform | Preview player | Recording | Main screens | Full-screen player |
|---|---|---|---|---|
| Android TV / Fire TV | Yes (`ExoPreviewEngine`, not on devices flagged low-RAM) | Yes (`AndroidRecorder`) | TV (`Root`) | `PlayerActivity` (ExoPlayer) |
| Android phone / tablet | No (`!PHONE`) | Yes (`AndroidRecorder`) | Phone (`PhoneRoot`) | `PlayerActivity` (ExoPlayer) |
| Windows | No (`Host.previewEngine` = null) | No | TV (`Root`) | VLC (`desktop/Player.kt`, `desktop/MultiView.kt`) |
| iOS | No | No | Phone (`PhoneRoot`) | VLC (Swift side) |

The shared player, handover, handback and every Stage 4 preview protection need a preview player. So they only act in the Android TV app.

## Platform-by-platform feature comparison

Key:
- **PASS**: built and runtime tested.
- **N/A**: doesn't apply on this platform.
- **NOT IMPL**: would apply, but isn't implemented.
- **NOT TESTED**: present and builds, but not runtime tested.
- **NOT BUILT**: couldn't be compiled here.

| Change (commit) | Android TV / Fire TV | Android phone / tablet | Windows | iOS |
|---|---|---|---|---|
| Shared live player; handover to full screen and back (`0222363`) | PASS (LDPlayer) | N/A: no preview player | N/A: no preview player | N/A |
| Home closes full screen; ownership guards; leak fix (`7bca514`, in `0222363`) | PASS | N/A: only runs for a player taken from the preview | N/A: own player | N/A |
| Live TV and TV guide follow the handed-back channel (`3edb251`) | PASS | N/A: phone screens | Code present, does nothing without a preview: NOT TESTED | N/A: phone screens |
| Focus moves to the group switched to on return (`2533274`) | PASS (both Live TV layouts) | N/A | Code present, does nothing: NOT TESTED | N/A |
| Black or frozen picture recovery (`7d627d8`) | Built; never triggered by mistake (PASS); **the recovery itself NOT TESTED** | N/A | N/A (VLC multiview has its own frozen-picture reopen) | N/A |
| Seamless Fullscreen Playback switch (`21910b4`) | PASS: on, off, and kept after a restart | N/A: hidden without a preview | N/A: hidden | N/A |
| Preview restarts if full screen never opens (`be61e51`) | PASS (unit tests, plus no false trigger on LDPlayer) | N/A | Shared code, inert | NOT BUILT (shared code) |
| Unit tests for `adopt()` and channel sync (`031923e`) | PASS (both suites) | — | — | NOT BUILT |
| Preview retry after a network drop (`2106e92`) | PASS (short and long outage) | N/A | N/A (full screen has its own "try again") | N/A |
| Idle preview player released on low memory (`a2f2c09`) | PASS (sent trim levels) | Hook present, does nothing (no preview) | N/A | N/A |
| `NovaHandover` logs with the fallback reason (`1bfb93f`) | PASS | N/A | N/A | N/A |
| Saved recordings play (`40b6be2`) | PASS: play, seek, play to the end | Applies (same player), but **recordings can't be reached** (see the outstanding issues). NOT TESTED | N/A: no recording | N/A: no recording |
| Multiview with the shared player | PASS (switch on and off, 2–3 screens) | Unchanged from before; NOT TESTED | Unchanged (`MultiView.kt`); NOT TESTED | Unchanged (VLC); NOT TESTED |
| Long session: 200 channel changes, 50 round trips (no code change) | PASS | N/A | N/A | N/A |
| App still launches after the changes | PASS | PASS (installed separately on LDPlayer, launched, no crash; removed afterwards) | PASS (Windows Test 1: launch, mouse to all 8 sections, no errors) | NOT TESTED |
| Arrow keys move focus on Windows (fix found by Windows Test 1; the gap predated Stages 1–4) | N/A (D-pad) | N/A | PASS: checked by hand in Live TV, TV guide, Sports and Settings, plus 4 key-press tests | N/A |

## Features that already existed on each platform

- **Android TV / Fire TV:**
  - full-screen retry, reconnect, channel failover and the multiview watchdog (`PlayerActivity`);
  - multiview with HD stand-ins;
  - recording and the Recordings tab;
  - the muted preview, shared across Live TV, the TV guide and Sports since 1.7.
- **Android phone / tablet:**
  - the same `PlayerActivity` retry, failover and multiview, plus picture-in-picture;
  - recording from the player's Record button;
  - no Recordings screen.
- **Windows:**
  - VLC full screen with "try again";
  - VLC multiview with reconnect after drops (with back-off) and reopening a frozen picture;
  - the TV windows show the channel logo, not video.
- **iOS:**
  - VLC full screen and multiview (Swift side);
  - no preview, no recording.

## Outstanding issues and testing still needed

### Pending tests

1. **Physical Fire TV (Stage 4 item 11).** Use a Fire TV Stick (low memory) and a Fire TV 4K over ADB Wi-Fi. Check:
   - hand over and hand back, the Home button, and the switch on and off;
   - the decoder switch from the 720p preview to 4K full screen, and audio passthrough;
   - multiview decoder limits;
   - memory after 30 minutes, and `onTrimMemory` under real memory pressure.
2. **Windows: playback and multiview at runtime.** Launch and keyboard/mouse navigation passed in Windows Test 1, after adding arrow-key focus movement (it was missing before Stages 1–4 too). Still to check, with a playlist:
   - Live TV, the TV guide and Sports;
   - full screen in and out;
   - multiview add, remove and reconnect;
   - D-pad and keyboard focus in the Live TV and TV guide lists, because the shared screens gained the `ReturnSync` focus modifiers.
3. **Android phone: playback and recording.** Needs a playlist added on the phone install (the owner enters the login). Check:
   - Live TV full screen;
   - multiview;
   - picture-in-picture;
   - a recording started from the player.
4. **iOS: build and runtime.** On a Mac:
   - build the `iosArm64` and `iosSimulatorArm64` targets with the shared code at `7b49b79`;
   - run Live TV, full screen and multiview.
5. **Black or frozen picture recovery.** It has never been triggered: LDPlayer never produced a frozen surface. It needs a Fire TV that shows the decoder problem, or a debug-only way to force it.

### Known issues (not fixed; none caused by Stages 1–4)

6. **A recording missing from the TV Recordings list.**
   - Two test recordings were made: 331 MB with the switch on and 60 MB with it off.
   - Both files are in `Android/data/io.github.novatv.play/files/Movies/Recordings`, but Recordings › Recorded lists only the first.
   - The list also shows the scheduled end time (00:35–01:35), not the real stop time.
   - Not investigated.
7. **Phone recordings can't be listed or played.**
   - The phone player shows Record, because the Android app has a recorder, so phone users can record.
   - But the phone screens have no Recordings tab, so finished recordings can't be reached.
   - This predates Stages 1–4.

### Not issues, for the record

- Seamless full screen is TV-only by design. Phones and tablets never had a preview player, to save memory and battery. Adding one would be new feature work.
- The Stage 4 multiview buffer item was skipped: screen 1 used the same buffer limits on `main` before this work.
