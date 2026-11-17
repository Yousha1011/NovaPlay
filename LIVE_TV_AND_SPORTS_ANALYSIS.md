I have verified that **both projects are accessible**:
- **NovaPlay** (current workspace): `D:\Software\1. PHONE SOFTWARE\NovaPlay`
- **NuvioPlusIPTV** (reference project): `D:\Software\1. PHONE SOFTWARE\NuvioPlusIPTV`

No files or code have been modified. Below is the detailed breakdown.

---

### 1. Live TV and Sports Source Files in NovaPlay

All core shared logic and Compose UI in NovaPlay reside in `shared/src/commonMain/kotlin/io/github/novatv/play/`:

#### **Live TV:**
- [`ui/LiveGuide.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/LiveGuide.kt): TiviMate-style EPG guide screen (preview player, group bar, channel column, 2-hour timeline).
- [`ui/LiveScreen.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/LiveScreen.kt): Classic two-column Live TV screen (categories on left, channels with now/next and detail pane on right).
- [`ui/GuideScreen.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/GuideScreen.kt): Dedicated grid EPG guide screen.
- [`ui/OverlayGuide.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/OverlayGuide.kt): In-stream overlay channel guide.
- [`ui/phone/PhoneRoot.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/phone/PhoneRoot.kt) (`PhoneLive`, `ChannelGroupPage`, `ChannelList`): Phone-specific Live TV UI (categories, favorites, history, grid/list view).
- [`data/Epg.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/Epg.kt): EPG XMLTV parsing, program data structures, and memory/disk caching.
- [`data/Repo.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/Repo.kt) & [`data/Models.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/Models.kt): Channels, categories, playlist index, and provider bindings.
- [`data/Preview.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/Preview.kt): Video preview controller for guide and hub screens.
- [`data/HdTwin.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/HdTwin.kt): HD/SD channel pair matching.

#### **Sports:**
- [`ui/SportsHub.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/SportsHub.kt): Main TV Sports Hub (`SportsScreen`), Hero game card in team colors, My Sports row, and sport selector chips.
- [`ui/SportsScreen.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/SportsScreen.kt): Sports event details, match popups, and channel pickers.
- [`ui/SportsPicker.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/SportsPicker.kt): Sport & league picker modal.
- [`ui/SportsNetworksView.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/SportsNetworksView.kt): 24/7 sports networks view (NFL RedZone, ESPN, etc.).
- [`ui/SportsTablesView.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/SportsTablesView.kt): Standings and league tables view.
- [`ui/MatchPage.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/MatchPage.kt): Match details and scorecard screen.
- [`ui/phone/PhoneSports.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/phone/PhoneSports.kt): Phone-specific Sports tab.
- [`sports/Sports.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/Sports.kt), [`sports/SportsCatalog.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/SportsCatalog.kt), [`sports/SportsDesign.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/SportsDesign.kt): Domain models, sport catalogs, team colors, and theme styling.
- [`sports/Matcher.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/Matcher.kt): Channel matching algorithm between sports fixtures and playlist channels.
- [`sports/Espn.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/Espn.kt) & [`data/SportsRepository.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/SportsRepository.kt): ESPN score fetcher and sports repository.

---

### 2. Equivalent Source Files in NuvioPlusIPTV

In NuvioPlusIPTV, the implementation is organized under `app/src/main/java/com/nuvio/tv/livetv/`:

| Feature Area | NovaPlay (KMP) | NuvioPlusIPTV (Reference) |
| :--- | :--- | :--- |
| **Live TV Guide** | `ui/LiveGuide.kt`, `ui/GuideScreen.kt` | `livetv/ui/LiveTvGuideScreen.kt` (4,841 lines)<br>`livetv/ui/GuideRowStrip.kt`<br>`livetv/ui/GuideGearPanel.kt` |
| **Live TV Player & Bar** | `ui/LiveScreen.kt`, `ui/OverlayGuide.kt` | `livetv/ui/LiveTvPlayerScreen.kt` (1,700 lines)<br>`livetv/ui/LiveTvPlayerPanel.kt`<br>`livetv/ui/LiveTvOverlayMode.kt` (tucked channel row & backup flyout) |
| **EPG & Channels** | `data/Epg.kt`, `data/Repo.kt` | `livetv/data/EpgDatabase.kt`, `XmltvParser.kt`<br>`livetv/data/LiveTvRepository.kt`, `LiveTvChannelStore.kt` |
| **Catch-up & Timeshift**| *(Not yet implemented)* | `livetv/data/CatchupUrlBuilder.kt`<br>`livetv/player/LiveTvTimeshift.kt` |
| **Sports Hub & Screen** | `ui/SportsHub.kt`, `ui/SportsScreen.kt` | `livetv/sports/SportsScreen.kt` (1,837 lines)<br>`livetv/sports/SportsHost.kt`<br>`livetv/sports/SportsViewModel.kt` |
| **Sports Matcher** | `sports/Matcher.kt` (211 lines) | `livetv/sports/SportsMatcher.kt` (1,582 lines) |
| **Sports Catalog & Design**| `sports/SportsCatalog.kt`, `SportsDesign.kt` | `livetv/sports/SportsCatalog.kt` (758 lines)<br>`livetv/sports/SportsDesign.kt` (957 lines) |
| **Sports Tables & Networks**| `ui/SportsTablesView.kt`, `SportsNetworksView.kt` | `livetv/sports/SportsTables.kt`<br>`livetv/sports/SportsLeagueNetworks.kt` |
| **Score Delay & Feeds** | *(Direct ESPN calls in `Espn.kt`)* | `livetv/sports/SportsScoreDelay.kt`<br>`livetv/sports/SportsFeeds.kt` (1,937 lines)<br>`livetv/sports/SportsFastScore.kt` |
| **Pre-Game & Player Stats**| `ui/MatchPage.kt` | `livetv/sports/SportsPreGame.kt`<br>`livetv/sports/SportsPlayers.kt`, `SportsGameExtras.kt` |

---

### 3. Architecture & Functionality Comparison

1. **Platform Architecture:**
   - **NovaPlay** is a **Kotlin Multiplatform (Compose Multiplatform)** project supporting Android (TV and Phone), iOS (SwiftUI + KMP + MobileVLCKit), and Windows Desktop (JVM Compose + VLC). All shared logic must remain pure Kotlin / KMP compatible (no direct Android `Context`, `Room`, `Hilt`, or Android-only system APIs in `commonMain`).
   - **NuvioPlusIPTV** is an **Android TV only** application built using Android Jetpack (`Hilt`, `Room`, Android `ViewModel`, Media3 / ExoPlayer).
   - **Key Requirement:** Reference features must be translated to NovaPlay's clean, idiomatic KMP Compose architecture.

2. **Live TV Functionality Gaps in NovaPlay:**
   - **Tucked Channel Row:** NuvioPlusIPTV tucks a customizable quick channel strip under the player bar (press Down in full screen) to browse Favorites, History, or custom groups without exiting the stream.
   - **Backup Channels Flyout:** Pressing Right twice in full screen opens stream quality / alternate provider sources for that channel.
   - **Catch-up Integration:** NuvioPlusIPTV can seek backwards and play past EPG items directly via `CatchupUrlBuilder` (for Xtream / Flussonic / Stalker archives).
   - **EPG Guide Refinements:** NuvioPlusIPTV handles day-jumping with midnight alignments, hourly placeholders for missing EPG gaps, right-aligned channel numbers, and an extensive hold-OK contextual menu.

3. **Sports Functionality Gaps in NovaPlay:**
   - **Score Delay (`SportsScoreDelay`):** Prevents real-time scores from spoiling live IPTV streams (configurable delay from 30s to 5 mins).
   - **Advanced Matcher (`SportsMatcher`):** The reference project's 1,582-line matching engine contains hundreds of team aliases, tournament bracket recognition (Olympics, tennis majors, golf rounds), regional network variations, and user-facing channel override ("Fix Match"). NovaPlay currently has a 211-line heuristic matcher.
   - **Background Delta Feeds (`SportsFeeds`):** The reference project caches scores and loads updates via fast delta sync, ensuring the TV UI never stutters during score refreshes.
   - **Pre-Game & Head-to-Head Stats:** NuvioPlusIPTV includes rich pre-game summaries, form guides, and player stats cards.

---

### 4. Files Requiring Modification in NovaPlay

To replicate the reference features into NovaPlay while preserving Multiplatform compatibility:

#### **Live TV Modifications:**
1. [`shared/src/commonMain/kotlin/io/github/novatv/play/ui/LiveGuide.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/LiveGuide.kt):
   - Upgrade guide navigation, hourly program placeholders, day jumping, right-aligned number columns, and enhanced hold-OK actions.
2. [`shared/src/commonMain/kotlin/io/github/novatv/play/ui/OverlayGuide.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/OverlayGuide.kt) & [`ui/LiveScreen.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/LiveScreen.kt):
   - Add the tucked Channel Row (press Down while playing) and Backup Channels drawer (press Right twice).
3. [`shared/src/commonMain/kotlin/io/github/novatv/play/data/Epg.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/Epg.kt) & new Catchup builder:
   - Add Catchup URL generation to allow launching past EPG programs.
4. [`shared/src/commonMain/kotlin/io/github/novatv/play/data/Store.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/Store.kt) & [`ui/SettingsScreen.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/SettingsScreen.kt):
   - Add settings for Channel Row configuration and Live TV default view modes.

#### **Sports Modifications:**
1. [`shared/src/commonMain/kotlin/io/github/novatv/play/sports/Matcher.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/Matcher.kt):
   - Port the comprehensive alias catalog, tournament parsing, and broadcaster matching rules from `SportsMatcher.kt`.
2. [`shared/src/commonMain/kotlin/io/github/novatv/play/sports/SportsCatalog.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/SportsCatalog.kt) & [`sports/SportsDesign.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/SportsDesign.kt):
   - Expand sport catalogs, tournament support, team color palettes, and logo handling.
3. [`shared/src/commonMain/kotlin/io/github/novatv/play/sports/Espn.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/sports/Espn.kt) & [`data/SportsRepository.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/data/SportsRepository.kt):
   - Add the **Score Delay** feature, background feed caching, and pre-game / player statistics extraction.
4. [`shared/src/commonMain/kotlin/io/github/novatv/play/ui/SportsHub.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/SportsHub.kt) & [`ui/MatchPage.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/MatchPage.kt):
   - Enhance the TV Sports Hub with day-tab switching (Yesterday / Today / Tomorrow), enhanced team cards, scorecard layouts, and pre-game stats cards.
5. [`shared/src/commonMain/kotlin/io/github/novatv/play/ui/phone/PhoneSports.kt`](file:///D:/Software/1.%20PHONE%20SOFTWARE/NovaPlay/shared/src/commonMain/kotlin/io/github/novatv/play/ui/phone/PhoneSports.kt):
   - Keep the phone UI in sync with the delayed score timing and enriched game details.

---

### Next Steps

Standing by for your direction and approval. When you are ready, let me know which area you would like to tackle first (e.g., Sports Matcher & Score Delay, Live TV Tucked Channel Row / Catch-up, or Guide enhancements).
