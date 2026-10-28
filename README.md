# NovaPlay

An IPTV player for the playlists you already have — on **iPhone, Android phones, Fire TV / Android TV and Windows**.
NovaPlay does not provide any channels or content.

## Download

Get the files from the [latest release](../../releases/latest):

| Device | File | How to install |
| --- | --- | --- |
| iPhone | `NovaPlay.ipa` | Unsigned: sideload with SideStore, AltStore or a similar tool (signed with your own Apple ID). |
| Android phone | `NovaPlay-Phone-1.0.apk` | Open the file on the phone and allow installing from your browser or file manager. |
| Fire TV / Android TV | `NovaPlay-1.0.apk` | Use the Downloader app, or `adb install -r NovaPlay-1.0.apk`. |
| Windows 10 / 11 | `NovaPlay-Setup-1.0.0.msi` | Run the installer (VLC playback is built in). |

Updates install over the existing app and keep your playlist login (on iPhone it is also kept in the Keychain).

## Features

- **Sources:** Xtream Codes, M3U / M3U8 links and Stalker (MAC) portals; several playlists, switch any time.
- **Live TV:** Favourites, Watch History and every channel group; now/next with progress; list or grid on phones.
- **TV guide:** XMLTV (plain or gzipped), the server's own guide and extra guide links.
- **Movies and TV shows:** posters, details, resume, seasons and episodes, Continue watching; ratings, cast, trailers
  and reviews with a free TMDB key.
- **Sports centre:** live, today, upcoming and your teams across football, cricket, NBA, NFL, NHL, MLB, tennis, F1 and
  more (scores from ESPN's public feeds). Each match lists the channels **in your playlist** that show it.
- **Search:** channels, movies and shows; words in any order, accents and punctuation ignored.
- **Phones:** IM Player-style design with a floating glass tab bar, six colour themes and a full-screen player.
- **Fire TV:** remote-friendly, with the same themes; type with the Fire TV keyboard or your voice.

## Project layout

| Folder | What it is |
| --- | --- |
| `shared/` | Kotlin Multiplatform code used by every app: data, playlists, guide, sports, and the Compose screens (`ui/` for TV and Windows, `ui/phone/` for phones). |
| `app/` | Android app with two flavours: `tv` (Fire TV / Android TV) and `phone`. Player: Media3 ExoPlayer. |
| `desktop/` | Windows app (Compose Desktop, VLC player). |
| `iosApp/` | iPhone app shell (SwiftUI + MobileVLCKit player), generated with XcodeGen. |
| `.github/workflows/ios.yml` | Builds the unsigned iPhone IPA on GitHub's macOS runners. |

## Build it yourself

JDK 17 and the Android SDK (platform 35, build tools 34.0.0):

```sh
./gradlew :app:assembleTvRelease        # Fire TV APK  -> app/build/outputs/apk/tv/release/
./gradlew :app:assemblePhoneRelease     # Phone APK    -> app/build/outputs/apk/phone/release/
./gradlew :desktop:packageReleaseMsi    # Windows MSI  -> desktop/build/compose/binaries/main-release/msi/
```

The Windows build expects VLC's `libvlc` files in `desktop/resources/windows/vlc` (not stored in the repo).
The iPhone IPA is built by the **iPhone app** workflow on every push to `main`; download it from the run's artifacts.

## Notes

NovaPlay is a player only. Use it with playlists and services you are entitled to watch.
