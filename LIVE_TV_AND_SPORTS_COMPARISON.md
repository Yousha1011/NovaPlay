# Live TV and Sports: NovaPlay compared with NuvioPlusIPTV

- **NovaPlay** (current app): `D:\Software\1. PHONE SOFTWARE\NovaPlay`
- **NuvioPlusIPTV** (reference): `D:\Software\1. PHONE SOFTWARE\NuvioPlusIPTV`

Both folders were readable. No code was changed.

**How this was checked.** NovaPlay's files were read directly. The NuvioPlusIPTV notes come from its file names, code comments and searches. Not every reference file was read line by line, so read each one in full before copying from it.

This report also covers the extra points found in `LIVE_TV_AND_SPORTS_ANALYSIS.md`, and corrects the parts of that file that are wrong or out of date (see section 6).

---

## 1. NovaPlay: Live TV and Sports files

Shared code is in `shared/src/commonMain/kotlin/io/github/novatv/play/` (called `<S>` below).

### Live TV

| File | Lines | What it does |
|---|---|---|
| `<S>ui/LiveGuide.kt` | 268 | Live TV in guide style: focused programme, TV window, channel rows |
| `<S>ui/LiveScreen.kt` | 251 | Live TV as a list (the "LIST" setting); the shared `PreviewBox`, `TV_PREVIEW` and `sharedChannel()` |
| `<S>ui/GuideScreen.kt` | 243 | TV guide grid, with details panel and TV window |
| `<S>ui/OverlayGuide.kt` | 149 | Guide shown over the full-screen player |
| `<S>ui/phone/PhoneRoot.kt` | – | Phone Live TV: categories, favourites, history, list or grid |
| `<S>data/Preview.kt` | 185 | Small preview player, `PagePreview`, and which page shows the picture |
| `<S>data/Epg.kt` | 358 | Guide database, time shift, now/next, programme search |
| `<S>data/Repo.kt`, `data/Models.kt` | – | Channels, categories, playlist index |
| `<S>data/HdTwin.kt` | – | Pairs HD and SD versions of a channel |
| `<S>ui/HomeScreen.kt` | 328 | Home, including its Live sports row |
| `app/src/main/java/io/github/novatv/play/PlayerActivity.kt` | 1200 | Full-screen player on TV and Android; moves to the next channel if one fails |
| `app/.../RecordService.kt`, `app/.../Preview.kt` | – | Recording service; Android's preview engine |

### Sports

| File | Lines | What it does |
|---|---|---|
| `<S>ui/SportsHub.kt` | 908 | Sports screen: My Sports, All Events (with Yesterday/Today/Tomorrow), scorecard, cards |
| `<S>ui/MatchPage.kt` | 353 | Match page (head to head, channels, form, table) and team card |
| `<S>ui/SportsTablesView.kt` | 366 | Tables |
| `<S>ui/SportsPicker.kt` | 408 | Follow Teams & Leagues |
| `<S>ui/SportsScreen.kt` | 573 | Channel picker, Fix Match, older screens |
| `<S>ui/SportsNetworksView.kt`, `sports/LeagueNetworks.kt` | 248, 72 | League Networks. No longer used: the tab was removed in 1.7 |
| `<S>ui/LogoTone.kt` | 60 | Team colours taken from their logos |
| `<S>ui/phone/PhoneSports.kt` | 273 | Phone Sports |
| `<S>sports/Sports.kt` | 247 | Sports, leagues, events, what is followed |
| `<S>sports/Espn.kt` | 180 | ESPN scores and team schedules |
| `<S>sports/Teams.kt` | 106 | Team pages: standing, record, results, fixtures |
| `<S>sports/Standings.kt` | 288 | League tables |
| `<S>sports/Matcher.kt` | 211 | Finds which of your channels show a game |
| `<S>sports/Broadcasters.kt` | 157 | Broadcaster names for games |
| `<S>sports/SportsCatalog.kt`, `sports/SportsDesign.kt` | 196, 103 | Team list for the picker; Sports look and colours |
| `<S>data/SportsRepository.kt` | – | Loading and refreshing sports data |

---

## 2. NuvioPlusIPTV: the equivalent files

Everything is in `app/src/main/java/com/nuvio/tv/livetv/` (about 65,000 lines).

### Live TV

| File | Lines | What it does |
|---|---|---|
| `ui/LiveTvGuideScreen.kt` | 4841 | Guide, groups, preview window |
| `ui/LiveTvViewModel.kt` | 1577 | Guide state |
| `ui/LiveTvPlayerScreen.kt` | 1700 | Full-screen player |
| `ui/LiveTvOverlayMode.kt` | 1049 | Player overlays, including the quick channel row and the backup-streams panel |
| `ui/LiveTvPlayerPanel.kt` | 739 | Control panel: Live, Catch-up and time-shift modes; captions, audio, picture size, sleep timer, pin, reminder bell, backups |
| `ui/LiveTvSearch.kt`, `SearchResultCard.kt` | 532, 168 | Channel and programme search |
| `ui/LiveTvGearPanel.kt`, `GuideGearPanel.kt`, `GuideRowStrip.kt` | 394, 265, 231 | Quick settings panels; guide row strip |
| `data/LiveTvRepository.kt` | 2826 | Playlists and guide |
| `data/LiveTvPreferences.kt` | 1619 | Settings |
| `data/EpgDatabase.kt` | 897 | Guide database |
| `data/M3uParser.kt`, `data/XmltvParser.kt` | 235, 352 | Playlist and guide parsers |
| `data/CatchupUrlBuilder.kt` | 259 | Catch-up addresses |
| `data/LiveTvChannelStore.kt`, `RenamedChannels.kt` | 210, 58 | Renaming and hiding channels |
| `data/LiveTvPosterResolver.kt`, `LogoLibrary.kt`, `LiveTv247Matcher.kt` | 938, 261, 349 | Logos and posters |
| `player/LiveTvPlaybackController.kt` | 2183 | One player shared by the preview window and full screen |
| `player/LiveTvTimeshift.kt`, `TsSegmenter.kt` | 362, 260 | Pause and rewind live TV |
| `record/` | ~3,500 | Scheduled and manual recording, Recordings screen (uses Android alarms and a foreground service) |
| `reminders/LiveTvReminderHost.kt` | 129 | Reminders |
| `calendar/` | ~6,000 | "Tonight" calendar |
| `home/` | ~2,000 | Home rows; Android TV launcher rows |
| `sync/` | ~1,600 | Cloud and Google Drive sync |

### Sports (`sports/`)

| File | Lines | What it does |
|---|---|---|
| `SportsScreen.kt`, `SportsViewModel.kt` | 1837, 192 | Screens and their state |
| `SportsRepository.kt` | 2230 | Fetching, caching, follows |
| `SportsFeeds.kt` | 1937 | Data: ESPN, TheSportsDB, MLB |
| `SportsMatcher.kt` | 1582 | Game-to-channel matching, Fix Match (choose or reject a channel) |
| `SportsChannelRegion.kt` | 165 | "All regions" channel filter |
| `SportsMyTeams.kt` | 620 | My Sports |
| `SportsPicker.kt` | 663 | Follow teams and leagues |
| `SportsStandings.kt`, `SportsTables.kt` | 424, 963 | Tables |
| `SportsGameExtras.kt`, `SportsExtras.kt`, `SportsPreGame.kt`, `SportsPlayers.kt` | 352, 270, 135, 81 | Match-page extras: pre-game information, players |
| `SportsDesign.kt` | 957 | Look and team cards |
| `SportsLeagueNetworks.kt` | 463 | League networks |
| `SportsTvListings.kt` | 302 | Games found in the TV guide |
| `SportsHost.kt` | 472 | Plays a game's channel; opens full screen |
| `SportsRecord.kt` | 185 | Record buttons for games |
| `SportsPins.kt` | 164 | Pinned games |
| `SportsScoreDelay.kt` | 113 | Delays scores so they don't spoil the stream (choices in seconds, starting with Off) |
| Scoring alerts | – | Spread across the Sports code |

The reference has **no multiview**.

---

## 3. Architecture

| | NovaPlay | NuvioPlusIPTV |
|---|---|---|
| Platforms | Fire TV, Android phone, Windows and iPhone from one shared code base | Android TV only |
| Structure | Screens read one shared `App.repo` and `App.store`; no ViewModels | Screens → ViewModels → Repositories, with dependency injection (Hilt) |
| Storage | Own guide database and settings store | Room-style databases and Android preferences |
| Player | A small preview player separate from the full-screen one, so going full screen restarts the stream | One ExoPlayer shared by the preview and full screen, so the picture carries on without restarting |
| Background work | Simple refresh loops | Low-priority background pool; Android alarms for recordings and launcher rows |

**What this means for porting:** NovaPlay's shared code can't use Android-only parts (Context, Room, Hilt, alarms, notifications). Each reference feature has to be rewritten in NovaPlay's shared style. Anything that needs alarms or notifications also needs its own version for Android, Windows and iPhone.

---

## 4. Feature comparison

| Feature | NovaPlay | NuvioPlusIPTV |
|---|---|---|
| Guide, list, TV window | ✅ | ✅ |
| One TV window across Live TV, guide and Sports | ✅ | ✅ |
| Preview carries straight into full screen | ❌ restarts | ✅ |
| Quick channel row under the player (press Down) | ❌ | ✅ |
| Backup-streams panel (press Right twice) | ❌ (automatic fallback only) | ✅ |
| Catch-up (play past programmes) | Partial: channels marked, no archive playback | ✅ |
| Time shift (pause/rewind live) | ❌ | ✅ |
| Guide extras: day jumps, gap placeholders, numbers aligned right, more hold-OK actions | ❌ | ✅ |
| Live TV search | Only through the Search tab | ✅ dedicated |
| Player panel: captions, audio, picture size, sleep, pin | Partial | ✅ |
| Reminders | ❌ | ✅ |
| Recording | Service and Recordings tab exist; no scheduled recording or game Record buttons | ✅ |
| Tonight calendar | ❌ | ✅ |
| My Sports, All Events, Tables, match page, team card, picker | ✅ (1.7) | ✅ |
| Fix Match | ✅ choose a channel | ✅ choose or reject |
| "All regions" channel filter | ❌ | ✅ |
| Scoring alerts | ❌ | ✅ |
| Score delay | ❌ | ✅ |
| Pinned games | ❌ | ✅ |
| Match extras: pre-game, players | ❌ (head to head, form and team card exist) | ✅ |
| Matcher depth | 211 lines | 1,582 lines (contents not checked in detail) |
| More data sources (TheSportsDB, MLB) | ESPN only | ✅ |
| Multiview | ❌ | ❌ |

---

## 5. Files to change in NovaPlay

### Live TV

| Feature | Files |
|---|---|
| Preview carries into full screen | `<S>data/Preview.kt`, `app/.../Preview.kt`, `app/.../PlayerActivity.kt`, and the Windows and iPhone players. The biggest change: the full-screen player is a separate screen |
| Quick channel row and backup-streams panel | `app/.../PlayerActivity.kt`, `<S>ui/OverlayGuide.kt`, the Windows and iPhone players |
| Catch-up and time shift | `app/.../PlayerActivity.kt`, `<S>data/Epg.kt`, `<S>ui/GuideScreen.kt`, `<S>ui/LiveGuide.kt`, a new catch-up address builder in `<S>data/` |
| Guide extras | `<S>ui/GuideScreen.kt`, `<S>ui/LiveGuide.kt` |
| Live TV search | New `<S>ui/LiveSearch.kt`; `<S>ui/LiveGuide.kt`, `<S>ui/GuideScreen.kt` |
| Player panel controls | `app/.../PlayerActivity.kt`, `<S>ui/OverlayGuide.kt` |
| Reminders | New reminder code in `<S>data/`, with a version per platform (Android alarms and notifications, Windows, iPhone); `<S>data/Store.kt`; bell buttons in `<S>ui/GuideScreen.kt` and `<S>ui/MatchPage.kt` |
| Scheduled recording and Record buttons | `app/.../RecordService.kt`, a new scheduler, `<S>ui/GuideScreen.kt`, `<S>ui/MatchPage.kt` |
| Settings for the above | `<S>data/Store.kt`, `<S>ui/SettingsScreen.kt`, `<S>ui/phone/PhoneSettings.kt` |

### Sports

| Feature | Files |
|---|---|
| "All regions" filter | `<S>sports/Matcher.kt`, `<S>ui/MatchPage.kt`, `<S>ui/SportsScreen.kt`, `<S>data/Store.kt` |
| Reject a channel in Fix Match | `<S>sports/Matcher.kt`, `<S>ui/SportsScreen.kt` |
| Stronger matcher (aliases, tournaments) | `<S>sports/Matcher.kt`, `<S>sports/Broadcasters.kt` |
| Scoring alerts | `<S>sports/Espn.kt` (score changes), a new alerts file, a notification part per platform, `<S>data/Store.kt` |
| Score delay | `<S>ui/SportsHub.kt`, `<S>ui/MatchPage.kt`, `<S>data/Store.kt`, a setting in `<S>ui/SettingsScreen.kt` |
| Pinned games | `<S>ui/SportsHub.kt`, `<S>data/Store.kt` |
| Match extras (pre-game, players) | `<S>sports/Espn.kt` (ESPN's per-game summary feed), `<S>ui/MatchPage.kt` |
| More data sources | New files in `<S>sports/`; `<S>sports/Sports.kt`, `<S>data/SportsRepository.kt` |
| Phone versions of all of the above | `<S>ui/phone/PhoneSports.kt` |

**Most work:** the features that need alarms or notifications (reminders, scheduled recording, scoring alerts) and the shared preview/full-screen player, because each needs work on every platform.

---

## 6. Corrections to `LIVE_TV_AND_SPORTS_ANALYSIS.md`

| Claim there | Finding |
|---|---|
| `SportsFastScore.kt` exists in the reference | No such file |
| "Background delta feeds" | Nothing found to back this up. The reference uses a low-priority background pool, not delta sync |
| Score delay "30s to 5 mins" | The reference offers choices in seconds, starting with Off; exact range not checked |
| Add Yesterday/Today/Tomorrow tabs | NovaPlay's All Events already has them |
| `SportsNetworksView.kt` listed as a current screen | League Networks was removed in 1.7 |
| Pre-game and head-to-head listed as missing | Head to head, form and the team card exist since 1.7; only pre-game information and player stats are missing |
| Matcher has "hundreds of aliases, tournament brackets" | Possible (1,582 lines), but its contents weren't checked |
| Missing from that file | Reminders, scheduled recording and game Record buttons, scoring alerts, the "All regions" filter, pinned games, rejecting a channel in Fix Match, the Tonight calendar, and sharing one player between the preview and full screen |
