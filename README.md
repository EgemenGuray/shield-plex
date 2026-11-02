# Lumen for SHIELD

> Formerly "Plex HDR". The package id is still `app.plexhdr.shield` and the signing key is unchanged, so an installed copy updates in place and keeps its sign-in and settings.

A Plex client for the Nvidia Shield TV (Android TV), built for a Samsung QN900B with a Q990C soundbar. It has the same look and features as the [Tizen](https://github.com/EgemenGuray/tizen-plex) and [Apple TV](https://github.com/EgemenGuray/apple-tv-plex) apps, and one main job the others can't do: send **lossless TrueHD Atmos and DTS-HD MA / DTS:X to the soundbar untouched**.

It plays the original file (direct play) with Media3/ExoPlayer. Audio the HDMI device accepts goes out as a bitstream; anything else is decoded on the Shield with FFmpeg.

## What the hardware allows

| Goal | On the Shield | Why |
|---|---|---|
| **TrueHD / TrueHD Atmos** | **Bitstream** | ExoPlayer passes TrueHD through when the Shield's HDMI output reports it |
| **DTS, DTS-HD MA, DTS:X** | **Bitstream** | DTS:X travels inside DTS-HD MA |
| E-AC3 / DD+ Atmos, AC3 | Bitstream | |
| AAC, FLAC, PCM, Opus… | Decoded to multichannel PCM | |
| HDR10, HLG | Yes | The Shield switches the TV to HDR automatically |
| **HDR10+** | **Plays as HDR10** | Nvidia's display driver can't send HDR10+ metadata. No app can change that (see [RESEARCH.md](RESEARCH.md)) |
| **Dolby Vision** | HDR10 base layer | The QN900B has no Dolby Vision. Profile 7/8 files use the plain HEVC decoder so the TV gets clean HDR10. Profile 5 has no HDR10 layer |
| **8K** | **Converted to 4K by the server** | The Tegra X1+ decodes up to 4K60. The audio is still copied untouched. Use the Tizen app for 8K on the QN900B |

## Wiring and Shield settings for lossless audio

- **Shield → TV → soundbar over eARC**, or Shield → Q990C HDMI in → TV. The second avoids Samsung's eARC quirks with DTS.
- Shield: *Settings → Display & Sound → Advanced sound settings → Surround sound: **Always*** and enable the formats (Dolby TrueHD, DTS-HD, etc.).
- Samsung TV: *Sound → Expert Settings → HDMI-eARC Mode: Auto*, *Digital Output Audio Format: Passthrough*.
- In the app, **Settings → Sound check** shows exactly which formats the Shield reports it can bitstream. If TrueHD isn't listed, the Shield/TV settings above are the fix.

## Features

Same as the Tizen app, plus what the Shield adds:

- **Startup intro** (3.6 s, once per launch): in the dark the light in the corner of the L ignites, the Lumen chime plays, then the camera zooms straight into the middle of the L and Home opens behind it. Any remote button skips it; Settings → Startup turns the intro or just the chime off. The chime (`res/raw/lumen_chime.ogg`) is an original synthesized sound.
- **Sign-in**: 4-character code at plex.tv/link, or scan a QR code with your phone.
- **Home**: full-screen top shelf (artwork, title logo, ratings, summary, Play/Resume, More Info; moves on every 9 s; ◀ ▶ past the buttons flips titles), Continue Watching, Recently Added for each library. The background cross-fades to the focused title.
- **Libraries**: poster grid sorted by **release date** by default; Sort (release date, date added, title, rating, recently watched), Unwatched, Genre, Collections.
- **Show page**: Play/Resume next episode, Mark watched, season pills, episodes, Cast & Crew, More Like This.
- **Details**: logo, badges (8K/4K, HDR10+, Dolby Vision, Dolby Atmos, DTS:X, Lossless, CC/SDH), ratings, one line saying exactly what Play will do (bitstream or PCM and why), Resume, Start Over, Trailer, Audio & Subtitles, Version, Mark Watched, Info (full technical sheet). Rows: Chapters, the rest of the season, Cast & Crew, Extras, More Like This.
- **Ratings with logos** in this order: IMDb, Letterboxd, Tomatometer, RT Audience, Metacritic, Metascore. The Tomatometer and audience logos change with the score.
- **Cast & Crew** and a person page with everything in your libraries they're credited on.
- **TMDB artwork** for missing logos, backdrops and posters.
- **Search** with the Shield keyboard (and voice through the keyboard's mic).
- **Settings**: Plex Home users (with PIN), server, audio output (passthrough / decode to PCM), sound check, preferred languages, subtitles (mode, language, size, colour, style, position), frame-rate and resolution matching, Dolby Vision handling, 8K handling, autoplay, auto-skip intro/credits, skip lengths, online service keys.

### Player
| Remote | Action |
|---|---|
| Select | Play / pause. Presses Skip Intro or Up Next when shown |
| ◀ / ▶ | Back 10 s / forward 30 s. **While paused**: scrub with preview thumbnails (holding speeds up), Select jumps there |
| ▼ | Panel: **Info**, **Chapters**, **Audio**, **Subtitles**, **Picture** |
| ▲ | Show the timeline |
| ⏪ / ⏩ | Previous / next chapter |
| Back | Close the panel, cancel scrubbing, dismiss Up Next, or stop |

- Timeline with chapter ticks and intro/credits segments, elapsed/remaining, chapter name, "Ends at".
- **Frame-rate matching**: the TV switches to 23.976/24/25/50/60 Hz for the video (Settings → Match frame rate).
- **Skip Intro**, **Up Next** with a 10 s countdown (keeps your subtitle language).
- **Audio** track switching; the panel says which tracks bitstream and which are decoded.
- **Subtitles**: embedded text and PGS (image) subtitles, external SRT/ASS/VTT, styled from Settings. **Search online…** searches OpenSubtitles (exact-file hash first) and your server. Subtitle delay in 0.1 s steps (external and downloaded files both ways; embedded tracks can only be delayed).
- **Picture**: original, zoom to fill, stretch. Playback speed when audio is decoded (a bitstream can't change speed).
- If a file fails to open, the app tries once more, then falls back to decoding the audio on the Shield.
- Progress goes to Plex, and the Shield also remembers position, version, audio and subtitle per title.

## Live TV (IPTV)

The **Live TV** tab works with or without Plex. Without a Plex account, choose *Live TV only* on the sign-in screen.

- **Sources:** M3U playlists and Xtream Codes logins. Several can be on at once, and each gets a coloured dot. To avoid typing with the remote, fill in `iptv/IptvKeys.kt` before building.
- **Sections:** Home, Live TV, TV Guide, Movies, Series, Favorites (Live TV, Movies and Series), Search, Downloads, Recordings and Playlists.
- **Home:** Continue Watching, recently watched channels, trending channels (your most watched this week), favourite channels, and trending movies and series. Movie and series trends come from TMDB's weekly lists matched to your sources when a TMDB key is set, and are the best-rated new additions otherwise.
- **Live TV:** groups, channels with what's on now, and a muted preview of the focused channel.
  - Quality tags (4K, FHD, HD, SD, HDR, 5.1, Stereo, HEVC) come from channel names and are corrected from the real stream once a channel has played. You can filter by them.
  - Menu, Info or holding Select opens favourite, rename, record, hide group and PIN lock.
- **TV Guide:** XMLTV (gzip too) and catch-up (default, append, shift, flussonic and Xtream timeshift), plus reminders, recording and downloads.
- **Player:**
  - Live controls: ▲▼ or CH± zap, number keys tune, ◀ opens the channel list, and ▶ opens audio, subtitles, record and picture options.
  - It reconnects on its own when a stream drops or stalls.
  - Audio passthrough follows the same Settings as Plex playback.
- **Search:** by channel name or by TV programme, combined with quality tags.
- **Downloads and Recordings:** saved to the app's Movies folder while Lumen is open. HLS channels are recorded segment by segment.

## Online services (optional, free)

Enter keys in **Settings → Online services**, or in `app/src/main/java/app/plexhdr/shield/services/Keys.kt` before building.

| Key | Get it at | Used for |
|---|---|---|
| TMDB | themoviedb.org → Settings → API | Logos, backdrops, posters |
| OMDb | omdbapi.com → API Key | IMDb, Metascore, Rotten Tomatoes when Plex lacks them |
| OpenSubtitles | opensubtitles.com → Profile → API consumers | Subtitle search and download |

Letterboxd and the Metacritic user score have no API; the app reads their public pages, so a layout change there just hides that score.

## Install on the Shield

1. Download **Lumen-Shield.apk** from the latest successful [Build APK run](../../actions/workflows/build.yml) (Artifacts).
2. On the Shield: *Settings → Device Preferences → About*, press *Build* 7 times; then *Developer options → Network debugging: On*. Note the IP address.
3. From a computer with adb:
   ```bash
   ./scripts/install.sh <shield-ip> Lumen-Shield.apk
   ```
   Accept the "Allow network debugging" prompt on the TV the first time. **Lumen** appears in the Shield's apps row.

Every CI build is signed with the same key (committed on purpose, `app/plexhdr.keystore`), so a new APK installs over the old one and keeps your settings.

### Build it yourself
JDK 17 and the Android SDK (platform 36): `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.

## What has and hasn't been checked
- Live TV: the M3U, Xtream Codes, XMLTV and catch-up code passed unit tests on a desktop JVM. The screens, player and recorder were compiled by CI only and haven't been run on a Shield yet.

Built on GitHub Actions only; **not yet run on a Shield**. Things to check on the device:
- TrueHD Atmos and DTS-HD MA arrive at the Q990C as a bitstream (the soundbar's display should say Dolby Atmos / DTS:X).
- Audio and embedded subtitle track mapping between Plex and ExoPlayer.
- Dolby Vision profile 7/8 files play their HDR10 layer with correct colours.
- Frame-rate switching, and the 8K → 4K server conversion.
