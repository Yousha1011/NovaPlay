# Continuous Live TV playback: NuvioPlusIPTV and NovaPlay

## 1. How NuvioPlusIPTV keeps playback going

**One player for the whole app.** `livetv/player/LiveTvPlaybackController.kt:73` is a singleton described as "One ExoPlayer shared by the guide preview window and the full-screen player, so going full screen (and back) never re-buffers the stream". The guide window and the full-screen player use the same object, so going full screen is a change of view, not a new connection.

**Screens borrow it, and it's only released after a short pause.** The relevant code is `attach()` and `detach()` at lines 596–613:
```kotlin
fun attach(): ExoPlayer { releaseJob?.cancel(); attachCount++ ... }
fun detach() { attachCount--; if (attachCount == 0) releaseJob = scope.launch { delay(600); if (attachCount == 0) release() } }
```
- The screens that borrow it are the guide (`LiveTvGuideScreen.kt:312–315`), the full-screen player (`LiveTvPlayerScreen.kt:113`), search (`LiveTvSearch.kt:240`) and Sports (`SportsScreen.kt:223–225`, via `SportsViewModel.attachPlayer()`).
- When you move from one screen to another, the new screen takes the player within 600 ms, so it's never released.

**Moving the picture only moves the view.** `LivePlayerSurface` (`LiveTvComponents.kt:633–646`) is a `PlayerView` that gets pointed at the shared player. Sports keeps one `VideoLayer` and moves it into the current tab's slot (`SportsScreen.kt:445–453`).

**A check for a frozen picture.** `onSurfaceAttached()` (lines 1453–1474) restarts the stream if no picture appears after the view moves ("No picture after moving the video"). This is a known Android issue.

**When Nuvio stops it:**
- Its Home screen has no video window. `LiveTvHomeRowViewModel.playFromHome` only starts a channel. Nothing borrows the player there, so it is released 600 ms after you reach Home. That's my reading of the code; I haven't run the app.
- It lets the player go as soon as its film player opens (`LiveTvLoadingHost.kt:74`).
- It pauses in the background (`PauseLiveTvInBackground`).

So Nuvio does **not** keep playing on its Home screen either. Its continuity covers guide ↔ full screen ↔ Sports ↔ search.

## 2. How NovaPlay handles it now

- **One small preview controller:** `data/Preview.kt`. On Fire TV its engine is `ExoPreviewEngine` in `app/.../Preview.kt`: its own ExoPlayer, muted, limited to 720p, with a small buffer. It is a different player from the full-screen one in `PlayerActivity`.
- **The three TV tabs share it** (since 1.7):
  - All use the same owner name, `TV_PREVIEW = "tv"`.
  - The tab in front takes the picture through `App.preview.surface` (`GuideScreen.kt:92`, `LiveGuide.kt:95`, `LiveScreen.kt:91`, `SportsHub.kt:105`).
  - `PreviewBox` (`LiveScreen.kt:187`) draws the video only on that tab.
- **Moving between Live TV, TV guide and Sports doesn't reconnect.** The ExoPlayer keeps running and only the `PlayerView` changes (`app/.../Preview.kt`, the `Video()` function).

## 3. Exactly where NovaPlay stops or reconnects

| # | Where | What happens |
|---|---|---|
| A | `Root.kt:271`: `if (nav.tab != LIVE && != GUIDE && != SPORTS) App.preview.stop()` | **Going to Home or any other tab stops the stream.** Coming back shows nothing until you choose a channel again |
| B | `App.kt:61`, in `play()`: `App.preview.stop()` | **Going full screen closes the preview** and `PlayerActivity` opens a **new connection** with its own ExoPlayer: a reconnect and fresh buffering |
| C | `MainActivity.kt:34`, `onStop()`: `App.preview.stop()` | Also runs when `PlayerActivity` covers the app, so full screen stops the preview a second way |
| D | Coming back from full screen | Nothing is playing (B and C stopped it). Live TV and the guide start the **highlighted** channel again (another new connection), and Sports shows an empty window |
| E | `SettingsScreen.kt:269` | Stops only when you turn previews off. This is intended |

Switching the picture between tabs (one `PlayerView` closes, another opens) is not a reconnect. On some Fire TV models, though, it can briefly show a black or frozen picture. That's what Nuvio's frozen-picture check handles, and NovaPlay has no equivalent.

## 4. Smallest changes that keep NovaPlay's design

In order of size:

1. **Keep playing across Home.** Change `Root.kt:271` so leaving the TV tabs doesn't stop the stream. Either:
   - (a) keep it running muted for a short time, say 30–60 s, then stop; or
   - (b) keep it running and add a small TV window to Home.

   Without (b), the stream plays on Home with nothing showing it. One shared file.
2. **Resume after full screen.** Remember the channel in `play()` (`App.kt`) and select it again in the shared window when the app comes back, instead of letting each page pick its highlighted channel. Small changes in `App.kt` and `data/Preview.kt`. **This still reconnects**, but you get the same channel back everywhere, Sports included.
3. **Frozen-picture check.** In `app/.../Preview.kt` `Video()`: when the view is attached, wait a few seconds for a picture and restart if none arrives, as Nuvio does. Fire TV only.
4. **No reconnect when going full screen.** Hand the preview's ExoPlayer to `PlayerActivity` instead of building a new one:
   - unmute it and lift the 720p limit;
   - hand it back on exit;
   - don't stop it in `App.play()` or `MainActivity.onStop()` during the handover.

   This is the only change that removes the reconnect. All the work is in the `app` module (`Preview.kt`, `PlayerActivity.kt`, `MainActivity.kt`) plus a flag in `App.kt`. It's the biggest change, because `PlayerActivity` (1,200 lines) has its own player setup, fallback to the next channel, tracks and recording.

Steps 1–3 are small. Step 4 is what matches Nuvio's "never re-buffers".

## 5. Android TV and cross-platform effects

**Android TV / Fire TV:**
- **Connections:** a provider that allows one connection works today only because the preview stops before full screen opens. If both players run at once, that account fails, so step 4 must hand the player over, never run both.
- **Decoders and memory:** two players can't run together on a Fire TV stick. `supported()` already skips low-memory devices.
- **Picture quality on handover:** lifting the 720p limit switches to the higher-quality video track. That causes a short rebuffer of the picture, not a new connection.
- **Background:** `onStop` must still stop playback when the app goes behind another app or to the Fire TV home screen. Only the step-4 handover should skip it.
- **Kept running on Home (step 1a):** the stream keeps downloading with nothing on screen, which uses data and processing.

**Windows and iPhone:**
- These platforms have **no preview engine at all**: `Platform.kt:52` returns null, and only the Fire TV app (`NovaApp.kt:34`) supplies one. Their TV windows show the channel logo, and none of this applies.
- Changes in shared code (steps 1 and 2) must keep working when there's no preview, which they do.
- Steps 3 and 4 touch only Android code, so Windows and iPhone can't break. The phone app is also excluded (`!PHONE`).
