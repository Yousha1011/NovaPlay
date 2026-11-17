# Seamless preview ↔ full screen on Android TV / Fire TV: implementation plan

**Scope:** the Fire TV app only. The goal is that going from the preview window to full screen, and back, does not reconnect the stream.

**Out of scope:**
- the existing continuity between Live TV, TV guide and Sports, which stays exactly as it is;
- hidden playback on Home, which is not added;
- other tabs, Windows, iPhone and the phone app.

No code has been changed. Everything below comes from reading the source files named.

---

## 1. Where things stand today

### NovaPlay: two separate players

| | Preview | Full screen |
|---|---|---|
| Owner | `ExoPreviewEngine` (`app/.../Preview.kt`), driven by `PreviewController` (`shared/.../data/Preview.kt`) | `PlayerActivity` (`app/.../PlayerActivity.kt`), its own `lateinit var player` (line 119) |
| Built | A **new ExoPlayer for every channel** in `ExoPreviewEngine.play()` | `buildPlayer(light = false)` in `onCreate` (line 197) |
| Buffer | `DefaultLoadControl` 4 s / 10 s, no byte cap | `bufferMs` setting × 3 / × 8, capped at 24 or 128 MB (`buildPlayer`, lines 243–260) |
| Decoders | `DefaultRenderersFactory` with decoder fallback | The same, **plus `EXTENSION_RENDERER_MODE_PREFER`** (FFmpeg audio) |
| Volume and quality | Muted, limited to 720p | Full volume, no limit |
| Picture | `Video()`: `PlayerView` via `AndroidView`, `update = { it.player = p }`, `onRelease = { it.player = null }` | `Screen()`: `PlayerView` with `player = this@PlayerActivity.player` (lines 794–803) |
| Lives in | The app process (`NovaApp`), shown in `MainActivity` | A separate **Activity**, started with `FLAG_ACTIVITY_NEW_TASK` (`NovaApp.kt:36`) |

### Why the stream reconnects going full screen and back

1. `App.play()` stops the preview first (`App.kt:61`): `if (App.host.previewEngine != null) App.preview.stop()`.
2. `MainActivity.onStop()` stops it again when `PlayerActivity` covers it (`MainActivity.kt:34`).
3. `PlayerActivity.onCreate` builds a new player and `startCurrent()` asks the provider for the stream address again: **a new connection**.
4. On Back, `PlayerActivity.onDestroy()` releases its player (line 772). `MainActivity` comes back with nothing playing, and the page starts its highlighted channel: **another new connection**.

### NuvioPlusIPTV: one player, handed between screens

- `LiveTvPlaybackController` is a `@Singleton` holding a single `ExoPlayer` (line 73): "shared by the guide preview window and the full-screen player, so going full screen (and back) never re-buffers the stream".
- Screens call `attach()` and `detach()` (lines 596–613). Release happens 600 ms after the last screen lets go, so the incoming screen always takes the player in time.
- The guide, full screen, search and Sports all attach (`LiveTvGuideScreen.kt:313`, `LiveTvPlayerScreen.kt:113`, `LiveTvSearch.kt:240`, `SportsScreen.kt:224`).
- The picture is a `PlayerView` re-pointed at the shared player (`LivePlayerSurface`, `LiveTvComponents.kt:633–646`).
- `onSurfaceAttached()` (lines 1453–1474) restarts the stream if no picture appears after the view moves.
- It lets the player go when its film player opens (`LiveTvLoadingHost.kt:74`) and pauses in the background (`PauseLiveTvInBackground`).

**The key difference:** Nuvio's full screen is a Compose screen inside the same Activity, so handing the player over is just a view change. NovaPlay's full screen is a **separate Activity**. The shared player therefore has to live at app level (`NovaApp`), and both Activities' lifecycles have to know when a handover is happening.

---

## 2. Design: one shared ExoPlayer, owned at app level

### New: `LivePlayerHolder` (new file `app/.../LivePlayerHolder.kt`, Android only)

An `object`, or a singleton created in `NovaApp`, using the application `Context`.

| Member | Purpose |
|---|---|
| `player: ExoPlayer?` | The one shared live player, built on demand |
| `mode: Mode` (`PREVIEW`, `FULL`) | Who has it |
| `channelId: String?` | The channel loaded |
| `acquire(): ExoPlayer` | Builds it if needed, with the **full-screen** decoder setup (decoder fallback + `EXTENSION_RENDERER_MODE_PREFER`, OkHttp source) and a **mode-aware load control** |
| `toFull()` | Volume 1, removes the 720p limit, switches the load control to the full buffer |
| `toPreview()` | Volume 0, `setMaxVideoSize(1280, 720)`, light buffer |
| `release()` | Releases and clears everything |
| `healthy(channelId)` | True if it has that channel and `playbackState` is `READY` or `BUFFERING` with no `playerError` |

**Mode-aware load control.** ExoPlayer's load control is fixed when the player is built. So the holder uses one `ModeLoadControl`, which extends `DefaultLoadControl`, built with the full-screen settings (it needs `App.store.bufferMs` and a RAM check, both taken from `PlayerActivity.buildPlayer`). It overrides `shouldContinueLoading()` to stop loading beyond about 10 s of buffer while `mode == PREVIEW`. Preview stays as light on memory as today, and full screen gets its normal buffer the moment it takes over, without rebuilding the player.

**Why at app level:** Activities are created and destroyed around the handover. An app-level holder outlives both, as Nuvio's singleton does.

### Changes to existing methods

| File | Method / state | Change |
|---|---|---|
| `app/.../Preview.kt` | `ExoPreviewEngine.play()` | Instead of building a new ExoPlayer: `holder.acquire()`, `stop()`, `setMediaItem`, `prepare`, `toPreview()`. Its listener is registered **once** and ignores events while `mode == FULL` |
| | `ExoPreviewEngine.release()` | `holder.release()`, **unless a handover is in progress** (see `App.handingOver`) |
| | `ExoPreviewEngine.Video()` | Binds `PlayerView.player` only while `mode == PREVIEW`, and sets it to null otherwise. This stops the hidden `MainActivity` from taking the picture back while full screen is showing |
| | New: frozen-picture check | After binding, if no picture arrives within about 4 s, `stop()`, `prepare()`, `play()`. This is Nuvio's `onSurfaceAttached` idea |
| | New: `canHandOver(channel)` | True if `holder.healthy(channel.id)` |
| `shared/.../data/Preview.kt` | `PreviewEngine` interface | Add `fun canHandOver(c: Channel): Boolean = false` (default implementation, so no other platform changes) |
| | `PreviewController` | Add `fun adopt(c: Channel, by: String)`: sets `owner` and `state = Playing(c)` **without** calling `engine.play`. Used when full screen hands the player back, possibly on another channel |
| `shared/.../App.kt` | New `@Volatile var handingOver = false` | Set while the player is passing to or from full screen |
| | `play(item)` | If `item` is `PlayItem.Live` and `previewEngine?.canHandOver(item.channels[item.channelIndex]) == true`: set `handingOver = true` and **don't** call `preview.stop()`. Otherwise behave exactly as today |
| `app/.../MainActivity.kt` | `onStop()` | `if (!App.handingOver) App.preview.stop()`. Going to the background still stops it, because `handingOver` is false then |
| `app/.../PlayerActivity.kt` | New `private var adopted = false` | Whether the main player is the shared one |
| | `onCreate` (line 197) | If `App.handingOver` and the item is Live and `holder.healthy(channel.id)`: `player = holder.player`, `holder.toFull()`, `adopted = true`, `loading = false`, and **skip `startCurrent()`'s new connection** (only load the now/next info). Otherwise `buildPlayer(false)` as today. Always set `App.handingOver = false` here |
| | Main listener (lines 198–225) | Unchanged, but **removed** when handing back, so the preview never sees full screen's error handling |
| | `startCurrent()`, `zap()`, `reconnectMain()`, `watchdog()` | Unchanged: they work on `player`, which is now the shared one. Zapping in full screen re-uses it (`setMediaItem`, the same as today) |
| | Multiview (`addScreen`, `Slot`, `removeScreen`, `endMulti`) | Unchanged. Extra screens keep their own players; screen 1 is the shared player. `removeScreen(0)` and `putMain` already re-use `player` |
| | `onStop()` (lines 755–762) | `if (isFinishing && adopted)`: don't pause (Back to the guide). Otherwise pause as today (Home button / background) |
| | `onStart()` | Unchanged |
| | `onDestroy()` (line 772) | If `adopted` and Live: remove the main listener, `holder.toPreview()`, `App.preview.adopt(currentChannel, TV_PREVIEW)` on the main thread. If `player.playerError != null` or the state is idle, `holder.release()` and let the preview stop instead. Else (VOD or not adopted): `player.release()` as today. Multiview slots are released as today |
| | VOD | **Never shared**: films and episodes keep their own player and are handled exactly as today |

### After the handback

- `nav.covered` turns false (from `App.away`, `Root.kt:224`).
- `PagePreview.update` arrives with `controller.channel` = the channel last played full screen, so it **holds** it rather than switching to the highlighted channel. This is the existing logic in `data/Preview.kt`.
- The tab in front still owns `App.preview.surface`, so `PreviewBox` shows the picture. `Video()` re-binds as `mode` returns to `PREVIEW`.

---

## 3. Feature by feature

| Feature | What happens |
|---|---|
| **Channel switching in full screen** | Unchanged: `zap()` → `startCurrent()` → `setMediaItem` on the shared player. On return, the preview shows the channel you ended on, via `adopt()` |
| **Tracks (audio, subtitles)** | `TracksPanel` works on `player`, so unchanged. Choices made in full screen persist into the preview, which is muted, so this is harmless. `toPreview()` must not reset the track selection beyond the 720p limit |
| **Error recovery** | Full screen keeps its own retry, reconnect, game-failover and watchdog logic (`onPlayerError`, `reconnectMain`, `watchdog`). The preview's listener ignores events while `FULL`. A player handed back in an error state is released, not shown |
| **Recording** | `RecordService` opens its own connection (`RecordService.kt:173`) and is untouched. There are no more simultaneous connections than today; a handover removes the short close-and-reopen |
| **One-connection providers** | Better than today: one connection throughout. The holder **never builds a second live player while one is held**. The only second players are multiview and failover standbys, as today |
| **Multiview** | Unchanged. Extra screens use their own light players |
| **4K → HD in multiview** | `startCurrent()` still switches screen 1 to the HD version (`streamFor(..., multi)`), now on the shared player |
| **Background (Home button, another app)** | From full screen: `PlayerActivity.onStop` pauses, as today. From the guide: `MainActivity.onStop` sees `handingOver == false` and stops and releases, as today |
| **Previews turned off in Settings** | `previewEngine` exists but `enabled()` is false, so `canHandOver` returns false and behaviour is exactly as today |
| **Low-memory devices** | `ExoPreviewEngine.supported()` returns false, so there's no engine and behaviour is exactly as today |
| **Phone app** | No preview engine (`!PHONE`), so it's unaffected. Picture-in-picture code is untouched |

---

## 4. Possible regressions and how each is handled

| Risk | Cause | Mitigation |
|---|---|---|
| Black or frozen picture after a handover | Moving the video surface between two `PlayerView`s; some Fire TV decoders don't redraw | Frozen-picture check (stop / prepare / play after about 4 s with no picture). `Video()` binds only in `PREVIEW` mode, so two views never fight over it |
| Preview uses more memory | Shared player built with the full-screen decoders and buffer limits | `ModeLoadControl` keeps the preview at about 10 s. The byte cap is the same as full screen's (24 MB on low-RAM devices) |
| The preview reacts to full screen's errors | Two listeners on one player | The preview listener ignores events while `FULL`; full screen's listener is removed on handback |
| Paused on Back | `PlayerActivity.onStop` runs after `MainActivity` resumes | Skip the pause when `isFinishing && adopted` |
| Preview killed during the handover | `MainActivity.onStop` runs after `PlayerActivity` starts | `App.handingOver` flag |
| `handingOver` left `true` | `PlayerActivity` fails to start | Clear it in `PlayerActivity.onCreate` in every case, and add a 3 s timeout in `App.play()` |
| Player released twice, or used after release | `onDestroy` releasing an adopted player | `adopted` decides: hand back or release, never both |
| Quality stays at 720p in full screen | Track limit not lifted | `toFull()` clears `maxVideoSize` |
| The other direction: a channel started full screen first | Nothing to adopt | Normal path: full screen builds its own player (as today), and on Back the preview starts as today. Only a preview → full screen → back sequence is shared |
| Hidden `MainActivity` recomposing | `AndroidView.update` re-binds the player | `Video()` reads `mode` (Compose state) and binds `null` while `FULL` |

**Fallback:** everything sits behind a hidden setting, `App.store.seamlessFullscreen` (default on). With it off, `canHandOver` returns false and every path is today's code.

---

## 5. Staged implementation

Each stage is built, unit-tested and checked on LDPlayer and a Fire TV before the next.

1. **Stage 1: shared holder, preview only.**
   - Add `LivePlayerHolder` and `ModeLoadControl`.
   - `ExoPreviewEngine` uses the holder (one player re-used across channels) instead of building one per channel.
   - Full screen is unchanged.
   - *Check:* preview switching between channels and tabs works as today; memory doesn't grow.
2. **Stage 2: hand the player to full screen.**
   - `App.handingOver`, `PreviewEngine.canHandOver`.
   - `App.play()` and `MainActivity.onStop()` skip the stop.
   - `PlayerActivity.onCreate` adopts the player.
   - On Back, release it as today, so stage 2 changes only the forward direction.
   - *Check:* opening full screen from the preview shows the picture with no spinner or reconnect.
3. **Stage 3: hand it back.**
   - `PlayerActivity.onDestroy` / `onStop`.
   - `PreviewController.adopt()`.
   - `Video()` mode binding and the frozen-picture check.
   - *Check:* Back returns to the same picture, including after zapping in full screen.
4. **Stage 4: edge cases and the setting.**
   - Multiview from an adopted player, errors in full screen, the Home button from full screen, previews off, a handover that never completes.
   - The hidden `seamlessFullscreen` setting.
   - Logs under the tag `NovaHandover`.

Unit tests (shared): `PreviewController.adopt()`, and that `PagePreview` keeps the adopted channel on return. These go alongside the existing `PagePreviewTest`.

---

## 6. Android TV testing

**Devices:**
- LDPlayer (Android 9);
- a Fire TV Stick (low memory, about 1.7 GB);
- a Fire TV Stick 4K Max;
- if available, a Google TV device.

| # | Test | Expected |
|---|---|---|
| 1 | Live TV: preview playing → OK to full screen | Picture continues with no spinner; sound comes on; logcat shows no new stream request |
| 2 | Full screen → Back | Preview shows the same picture without reconnecting |
| 3 | Zap Up/Down in full screen, then Back | Preview shows the last channel; the highlight in the list doesn't change it until you move |
| 4 | TV guide and Sports (the match page "Watch in window", then OK) | Same as 1–3 |
| 5 | Full screen → audio or subtitle change → Back → full screen again | Settings kept; no crash |
| 6 | Full screen from a channel that **isn't** previewing (e.g. Home's recent channels) | Today's behaviour: a new player, normal start |
| 7 | Multiview: add a screen, remove screen 1, end multiview, then Back | Works as today; the preview resumes or stops cleanly |
| 8 | Unplug the network in full screen, plug it back in | Full screen's reconnect works; Back afterwards shows a picture or a clean stop |
| 9 | Game channel list with "next channel on failure" | Switching still happens in full screen |
| 10 | Home button in full screen, then reopen the app | Paused and resumed as today; no player left running in the background |
| 11 | Home button in the guide | Preview stops and frees the connection (`adb shell dumpsys media.player`, network idle) |
| 12 | A one-connection provider account | No "too many connections" in either direction |
| 13 | Recording running + preview → full screen → back | Recording continues; playback works as far as the provider's limit allows |
| 14 | Settings: previews off | Behaviour identical to today |
| 15 | 4K channel: preview → full screen | Full resolution appears within a few seconds (the limit lifted) |
| 16 | 20 rounds of preview ↔ full screen in a row | No growth in memory (`adb shell dumpsys meminfo io.github.novatv.play`), no frozen picture |
| 17 | Rotate between all three TV tabs during playback | Continuity as in 1.7 (unchanged) |
| 18 | Phone app | Unchanged (no preview engine) |

**Logs:** `adb logcat -s NovaHandover NovaMulti ExoPlayerImpl` around every handover.

---

## 7. Files touched

| File | Kind |
|---|---|
| `app/src/main/java/io/github/novatv/play/LivePlayerHolder.kt` | **New** (holder + `ModeLoadControl`) |
| `app/src/main/java/io/github/novatv/play/Preview.kt` | `ExoPreviewEngine`: re-use the holder, mode-aware `Video()`, frozen-picture check, `canHandOver` |
| `app/src/main/java/io/github/novatv/play/PlayerActivity.kt` | `onCreate`, `onStop`, `onDestroy`, an `adopted` flag; the rest unchanged |
| `app/src/main/java/io/github/novatv/play/MainActivity.kt` | `onStop` checks `App.handingOver` |
| `shared/src/commonMain/kotlin/io/github/novatv/play/App.kt` | `handingOver` flag; `play()` skips the stop on a handover |
| `shared/src/commonMain/kotlin/io/github/novatv/play/data/Preview.kt` | `PreviewEngine.canHandOver` (default false), `PreviewController.adopt()` |
| `shared/src/commonTest/.../PagePreviewTest.kt` | New tests for `adopt()` |
| `shared/src/commonMain/kotlin/io/github/novatv/play/data/Store.kt` | Hidden `seamlessFullscreen` setting |

**Not touched:** the Live TV, TV guide and Sports screens, `Root.kt`, Home, the Windows (`desktop/`) and iPhone (`iosApp/`, `screenMain`) code, and the phone UI. The shared changes add a default `false` method and an unused-by-default `adopt()`, so other platforms behave identically.
