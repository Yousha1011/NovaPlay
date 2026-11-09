# Stage 4: shared ExoPlayer stability on Android TV and Fire TV

Branch `stage3-seamless-fullscreen` (commits 0222363, 7bca514, 3edb251 and 2533274). This is analysis only: no code has been changed.

## Status (validated on LDPlayer, merged to main)

| # | Item | Status | Commit |
|---|---|---|---|
| 1 | Black or frozen picture recovery | Done. The recovery steps themselves are not tested (LDPlayer never freezes) | 7d627d8 |
| 2 | Seamless Fullscreen Playback switch (Settings › Live TV & guide › Previews) | Done | 21910b4 |
| 3 | Preview restarts if full screen never takes the player | Done, with unit tests | be61e51 |
| 4 | Unit tests for `adopt()` and the channel-sync decision | Done | 031923e |
| 5 | One preview retry after a network drop | Done | 2106e92 |
| 6 | Multiview buffer limit | Skipped: not a regression (screen 1 used the same limits on main) | n/a |
| 7 | Release the idle preview player on low memory | Done | a2f2c09 |
| 8 | Long session (200 channel changes, 50 round trips) | Passed. No code change | n/a |
| 9 | `NovaHandover` logs, with the reason for any fallback | Done | 1bfb93f |
| 10 | Recording alongside the shared player | Passed. Found and fixed a bug from before this work: saved recordings didn't play ("Malformed URL") | 40b6be2 |
| 11 | Real Fire TV hardware | **PENDING**: to be run later with ADB over Wi-Fi | n/a |

### Fire TV testing still to do (item 11)
- Fire TV Stick (low memory) and Fire TV 4K: hand over and hand back, Home, Seamless Fullscreen Playback on and off.
- The black or frozen picture recovery on a device that shows the decoder problem.
- The decoder switch from the 720p preview to 4K full screen, and audio passthrough.
- Multiview limits on the device's decoders.
- Memory after 30 minutes of use, and `onTrimMemory` under real memory pressure.

### Known issue: a recording missing from the list
In the item 10 tests, two recordings were made: one with the switch on (331 MB) and one with it off (60 MB). Both files are in `Android/data/io.github.novatv.play/files/Movies/Recordings`, but **Recordings › Recorded lists only the first**. Not investigated yet. It may come from how a recording stopped by hand is saved (the list also shows the scheduled end, 00:35–01:35, rather than the real stop time). It's unrelated to the shared player: recording uses its own connection and service.

## Already in place

| Area | Protection | Where |
|---|---|---|
| Ownership | One holder with one player, and a mode (PREVIEW or FULL) saying who has it. The preview won't take a player full screen owns. Release is ticketed and only lets go of an idle preview player | `LivePlayerHolder.kt`, `Preview.kt` (`play`, `release`) |
| Handover / handback | Hands over only a healthy player, or one still loading. Back hands it back only when it's healthy and on one screen without the HD stand-in. Every other exit releases it | `App.kt` `play()`, `PlayerActivity.kt` `canHandBack`, `handBack`, `releaseAdopted` |
| Home and background | Full screen with the shared player closes itself. A preview in the background is released at once (`App.away`) | `PlayerActivity.onStop`, `MainActivity.onStop` |
| Activity leaks | The full-screen view detaches from the player on handback | `PlayerActivity.handBack` |
| Handover that never starts | The flag expires after 5 s, and `PlayerActivity.onCreate` and `onNewIntent` always clear it | `App.kt`, `PlayerActivity.kt` |
| Errors in full screen | The existing retry, reconnect, failover and watchdog run on the shared player unchanged. A player with an error is never handed back | `PlayerActivity.kt` `mainListener`, `reconnectMain`, `watchdog` |
| Previews off and low-memory devices | No engine, or `enabled()` returns false, so behaviour is as before Stage 1 (re-tested) | `Preview.kt` `supported()`, `data/Preview.kt` |
| One-connection providers | Never two live players for one channel. The old stream is stopped before the new one opens | `Preview.kt` `play`, holder |
| Buffer and memory | Full-screen buffer capped by bytes (24 MB below 2 GB of RAM). Preview limit of 10 s. Memory levelled off over 40 round trips | `LivePlayerHolder.kt` `ModeLoadControl` |
| Channel sync | Pages follow the channel handed back | `ReturnSync.kt` |

## Missing: prioritized checklist

### P1: before shipping

1. **Black or frozen picture after moving between screens.** Planned for Stage 3 but never built.
   - *Risk:* some Fire TV decoders don't redraw after the video surface moves. You'd see a black window with sound, or a still frame.
   - *Fix:* in `ExoPreviewEngine.Video()` (`app/.../Preview.kt`), when the view takes the player, wait about 4 s for `onRenderedFirstFrame`. If no frame arrives, call `prepare()` again on the same media. Do it once per attach, and only in PREVIEW mode.
   - *Test:* repeat full screen → Back and tab switches. Measure time to picture. Check that a black stream such as "Airing Break" doesn't loop.
2. **Off switch (`seamlessFullscreen`).** Not built.
   - *Risk:* no way to fall back to the pre-Stage-1 behaviour if a device misbehaves.
   - *Fix:* add `App.store.seamlessFullscreen` (default on) in `data/Store.kt`. Check it in `App.play()`; when off, the engine's `canHandOver` and `canTakeOver` count as false.
   - *Test:* with it off, every path builds its own full-screen player, as with previews off.
3. **Preview stuck on Loading if full screen never starts.** `App.play()` calls `preview.handOver()`, which cancels the lookup.
   - *Risk:* if `PlayerActivity` fails to start, the flag expires but the preview stays on Loading with no job.
   - *Fix:* when the 5 s expiry passes without `PlayerActivity` taking the player (mode still PREVIEW), `MainActivity.onResume` or `App` calls `App.preview.stop()`. Files: `App.kt`, `MainActivity.kt`.
   - *Test:* simulate it by blocking the start on a debug build. Hard to reach on LDPlayer.
4. **Unit tests for the new controller logic.** None exist.
   - *Fix:* `commonTest` tests for:
     - `PreviewController.adopt` (state, owner, counter);
     - `handOver` (cancels the lookup, keeps the state);
     - `ReturnSync` decision rules (stale, missing, other group): the pure parts can be pulled into a small function.
   - *Test:* `:shared:desktopTest`.

### P2: robustness

5. **Network loss during the preview after a handback.**
   - *Current behaviour:* the preview listener sets Failed with a message, and the player stays in error until you move channel.
   - *Fix:* one automatic retry (prepare again after 3 s) in `ExoPreviewEngine` for network error codes while in PREVIEW mode.
   - *Test:* switch LDPlayer's network off and on during the preview and during full screen.
6. **Multiview started from the shared player.**
   - *Current behaviour:* screen 1 stays the shared player, switches to the HD stand-in (`mainHd`), and isn't handed back (released on Back). Extra screens build light players.
   - *Risk:* one-connection providers fail as before (no change). Memory: the shared player keeps the full 128 MB cap alongside the extra players.
   - *Fix:* when multiview starts on an adopted player, give it the multiview byte cap (the same figure as `buildPlayer(light = false)`). Optionally log it. File: `PlayerActivity.startMulti` / `startSlot`.
   - *Test:* 2- and 4-screen multiview from a handed-over channel, Back, memory and player counts.
7. **Low-memory trim.**
   - *Current behaviour:* nothing reacts to `onTrimMemory`.
   - *Fix:* in `NovaApp`, on `TRIM_MEMORY_RUNNING_LOW` or above, release the holder's player if it's idle (PREVIEW mode, no media). Don't touch a playing or FULL player.
   - *Test:* `am send-trim-memory` during the preview, while idle, and during full screen.
8. **Long-session stability.**
   - *Covered:* 40 full-screen round trips and 6 quick Home cycles.
   - *Not covered:* 30+ minutes of zapping with repeated preview changes.
   - *Fix:* none planned; test first.
   - *Test:* scripted 200 channel changes plus 50 round trips, recording memory used, Java heap, thread count and player creation/release counts every 25.

### P3: diagnostics and coverage

9. **`NovaHandover` logs.** Not built.
   - *Fix:* one debug log line per handover, handback, release and refusal (holder, `PlayerActivity`, `App.play`). This makes field reports readable.
   - *Test:* logcat shows each step in order.
10. **Recording.**
    - `RecordService` opens its own connection and is untouched, so recording during the preview or full screen means a second connection. That's the same as before Stage 1.
    - *Fix:* none for the player. Optionally warn on one-connection providers.
    - *Test:* start and stop a recording from full screen on a handed-over channel, then Back. Check playback continues and the recording file grows.
11. **Real Fire TV.** Nothing has been tested on hardware.
    - *Test:* Fire TV Stick (low RAM) and Fire TV 4K: P1 items 1–2, decoder switch on handover (720p → 4K), audio passthrough, memory after 30 min.

## Not in scope

- Sports has no way into full screen (the TV window isn't focusable). This is a feature gap, not stability.
- Phone app, Windows and iPhone: no shared player, so nothing changes.
