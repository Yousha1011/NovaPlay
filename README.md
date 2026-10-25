# NovaPlay

An IPTV player for Android TV and Fire TV, for playlists you already have. It does not come with any channels.

## Features

- **Sources:** Xtream Codes logins, M3U / M3U8 playlists (URL), Stalker / MAC portals. Several playlists, switch in Settings.
- **Live TV:** categories, favourites (hold OK), recently watched, now/next with progress on every channel.
- **TV guide:** XMLTV (gzipped or plain), the Xtream server's own guide, or a link you add. Three-hour grid that pages by
  half hours.
- **Movies and series:** posters, sort by newest / A–Z / rating, details, resume, seasons and episodes, next episode plays
  on its own, Continue watching on Home.
- **Sports centre:** live, today, upcoming and your teams for football (26 competitions), cricket, NBA, NFL, NHL, MLB,
  tennis, F1, IndyCar, NASCAR, UFC, golf and rugby. Scores and fixtures from ESPN's public feeds (no key). Each match
  lists where it airs **on your channels**: programmes in your TV guide around kick-off that name the teams, and
  channels named after the broadcasters ESPN lists. "On your channels" shows every sports programme in your guide.
- **Player:** Media3 ExoPlayer (HLS, MPEG-TS, DASH, MP4/MKV). Live: Up/Down zap, Left for the channel list, OK for info,
  Menu for audio, subtitles and picture size. VOD: Left/Right seek, OK pause.
- **Typing:** every text box uses the Fire TV keyboard, so you can dictate with the remote's microphone or type from the
  Amazon Fire TV phone app.

## Build

JDK 17 and the Android SDK (platform 34, build tools 34.0.0):

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (minified, signed with the debug key)
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Not yet tested against real providers

Built and checked against a public M3U playlist only. Xtream and Stalker follow the published APIs but have not been
tried with a real account; Stalker portals vary the most.
