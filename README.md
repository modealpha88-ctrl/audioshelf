# AudioShelf

AudioShelf is a Tachiyomi-inspired Android audiobook reader: browse modular sources,
save books to a local library, stream chapter audio, resume progress, change speed,
and use a sleep timer.

The bundled sources are **LibriVox** and open/public-domain audio from the
**Internet Archive**. Source adapters are deliberately opt-in: only integrate a website when
its API, licence, or written permission allows third-party clients.

## Library concepts and immersive themes (v0.5.18)

AudioShelf now implements three distinct Compose library presentations from the
approved visual concepts, alongside the existing Walnut Shelf:

- **Aurora Gallery**: animated cyan/violet atmosphere, responsive two-column
  cover gallery, luminous card edges, and optimized approved concept banner.
- **Cinema Stack**: moody, illustrated landscape cards, projector banner,
  optimized visual scenes for Dracula and The Final Empire, and a resume-playback
  button which opens the player after chapter loading.
- **Paper Library**: warm parchment background, editorial serif book titles,
  matching light bottom navigation, optimized reading-room banner, readable
  progress indicators, and a working details/resume menu.

All screens retain live search, real audiobook metadata, cover lookup, progress,
and the saved library. The Motion setting controls ambient and entrance animation;
the selected library theme persists. The original Walnut shelves remain available.
The packaged visual assets are compressed, generated artwork from the approved
concepts. Other cover art comes from the existing provider/metadata path; unknown
titles still get the app's safe fallback, rather than falsely assigning artwork.

The artistic landscapes and background motions adapt the approved static mockups
to real scrolling Android UI. They are not a pixel-perfect screenshot overlay.

## Full portrait bookshelves and complete cover art (v0.5.17)

Walnut Shelf now displays at least four continuous wood shelves in portrait mode,
even when only one or two books are saved, instead of leaving the remainder of
the screen empty. Larger collections grow to additional rows lazily. Cover
images use **Fit** rather than **Crop**, so book titles and artwork are fully
visible, and the standing-book proportions are adjusted. Book taps, progress,
and the existing Motion toggle remain intact.

## Wooden bookcases and resilient covers (v0.5.16)

The **Walnut Shelf** layout now displays upright clickable cover images inside
actual wooden bookcases with shelf depth and optional entrance animation, inspired
by traditional portrait bookshelf readers. Aurora, Cinema and Paper styles are
still available through a compact style selector. In **Paper Library**, progress
labels have readable dark contrast and progress bars no longer show an end dot.

Cover lookup now prefers *embedded cover art* from local MP3/M4B files (including
older imports) before title-and-author searching. It strips audiobook marketing
suffixes such as "Book 1 (Unabridged)" and has a **Find covers** retry action
in Library. Existing artwork and listening progress are preserved. If neither
embedded art nor a verified online cover is available, the app shows a styled
book-title placeholder.

## Animated library and automatic covers (v0.5.15)

The Library has four built-in, selectable styles: **Walnut Shelf**, **Aurora Gallery**,
**Cinema Stack**, and **Paper Library**. Each style supports subtle motion; turn
**Motion** off from the Library header to disable ambient and entry animations.
Search saved audiobooks by title or author. Opening a book shows a larger artwork
and listening-progress card before the player and chapter controls.

Books missing artwork are matched by title and author against
[Open Library's public Search API](https://openlibrary.org/dev/docs/api/search).
Only covers with a sufficiently strong match are used. Provider artwork is
never replaced, no existing library record or listening progress is deleted, and
network failures leave a placeholder visible. Matched Open Library cover IDs
are cached in the saved library. Searching a remote catalogue also enriches a
limited number of visible results. Book titles and author names are sent to
Open Library for searches; do not enable Internet access if you want a fully
offline-only experience.

## Story Mode seeking (v0.5.11)

Story Mode now includes a touch seek bar with elapsed and total chapter time,
four skip controls (**−30 seconds**, **−10 seconds**, **+10 seconds**, and
**+30 seconds**), and compact previous/next-chapter controls. Skip actions
cross known chapter boundaries correctly, including clipped embedded chapters
of a local M4B file, while preserving the chapter-relative Media3 seek position,
full-book subtitle alignment, and saved listening progress. The slider works
when the playback engine reports a known chapter duration. All other Story Mode
video loops, caption imports, and offline transcription remain unchanged.

## Current MVP

- Debounced live search across all enabled catalogues
- Browse LibriVox and open/public-domain Internet Archive audiobooks
- Open Internet Archive item torrents in an installed torrent client
- Book details and RSS-based chapter discovery
- Local library and listening-progress persistence
- Media3 background playback and notification controls
- Chapter navigation, seek, 0.5x–2.0x speed, and sleep timer
- Material 3 light/dark UI
- Source interface ready for additional authorized providers
- Add provider repositories by HTTPS URL, browse their providers, and install or remove them
- Import from any public HTTPS audiobook page exposing audio links, or a direct audio file/HLS URL (requires permission to access the recording)

## New sources and personal audio import (v0.5.0)

The optional GitHub provider repository contains 12 provider entries:
four existing open/public-domain and official-edition providers, plus
**Scribl/Podiobooks, Libro.fm, Digitalbook.io, LearnOutLoud, Lit2Go,
Hoopla, Everand and Spotify Audiobooks**. The eight new entries are
clearly labeled **official external discovery links** rather than fake
in-app chapter streams. No credentials, subscription cookies, or DRM
workarounds are collected.

In **Sources**, you can also import a purchased DRM-free MP3/M4B audio
file using Android's document picker (with retained local URI access). M4B files
with embedded Nero or Apple/QuickTime chapter markers are now divided into
named, seekable chapters while playing the original file without copying it.
Existing single-track progress is migrated when chapter markers become available;
files without supported chapter metadata continue to play as one track.
You can also paste a public podcast RSS feed for chapter playback. Podcast feeds
must contain publisher-distributed HTTPS audio enclosures. This allows
compatible free Scribl/Podiobooks podcasts to play inside AudioShelf
without scraping Scribl's protected player.

Repository URL:
`https://raw.githubusercontent.com/modealpha88-ctrl/audioshelf/main/sources/manifest.json`

See [provider setup](sources/README.md) for access limitations and
how to import locally owned audio.

## Story Mode: word captions + animated backgrounds

On an audiobook details screen, tap **Story mode** to view large, pop-up
words over a moving background. Use **Import SRT / VTT / LRC captions** to
synchronize the text with narration; the app does not yet transcribe speech
itself. A subtitle cue containing several words is divided evenly over its
time span (an approximation unless individual words are timestamped).

Original offline procedural backgrounds: **Parkour**, **Starfield**, **Orbs**.
Choose **My video** to loop a user-selected, properly licensed gameplay/video
clip silently behind the captions. The app does not ship or scrape gameplay
from TikTok or Minecraft. Captions and video references remain on the device.
M4B users can switch between full-book and chapter-relative timestamps.

**Free offline speech-to-text:** AudioShelf now supports opt-in Vosk transcription for
locally imported MP3/M4B files, using a small English model (about 40 MB, downloaded
once from the Vosk project). Enable **Auto-transcribe as you listen** to generate
word-aligned captions in 30-second windows without sending recordings to servers.
Generated captions are cached privately, and user-imported captions take priority.
The release APK includes Vosk and JNA Android native libraries, while release
R8 keep rules protect their reflection and native method declarations.
Transcription is not yet available for remote streams and is not equivalent to
text-to-speech. Long books may require significant processing time.

## Full-book M4B transcription (v0.5.5)

Story Mode now includes **Transcribe entire audiobook** for a local, DRM-free
MP3/M4B selected through Android's document picker. Transcription uses the
downloaded on-device Vosk English model and processes audio in one-minute
windows. It writes each completed minute to app-private storage before moving
to the next, so pause, resume, app relaunch and recoverable system interruptions
do not discard completed work. A background WorkManager foreground worker shows
ongoing notification progress and works while Story Mode is closed.

Pause/Resume are in Story Mode. Once any windows have finished, **Export SRT**
or **Export VTT** opens Android's Save As picker. Timecodes are absolute to
the original M4B audio, not chapter-relative. The export is generated from the
saved one-minute files and does not require loading the full transcript into RAM.
Transcription may be slow, uses substantial CPU/battery/storage, and can be
interrupted by OS power restrictions or Android 15+ foreground-service quotas;
keep the phone powered and return to Story Mode to resume if necessary. If
partially finished, exports contain only completed sections. Speech recognition
can mishear names and invented vocabulary; the generated file is not an
official audiobook transcript.

## Video availability and caption-safe loading (v0.5.10)

Wikimedia Commons can return HTTP 429 when its servers restrict downloads.
AudioShelf now identifies its client with a project URL, respects the
`Retry-After` response (or conservatively cools down for ten minutes), and
stores that cooldown across screens and app launches to avoid repeated
requests. The status and retry/local-file options are located directly
below the video choices, **never across the center captions**. Video playback
and speech captions remain independent if a background cannot load.
Preview cards extract frames from locally cached videos and no longer trigger
separate remote thumbnail requests that could worsen rate limiting.
Existing cached files remain in app-private storage; all audiobook progress,
transcripts, and custom videos are preserved.

## Licensed portrait video backgrounds (v0.5.9)

The old static Forest, Castle, Desert, Autumn, Parkour, Starfield and Orbs
presets have been retired in favor of **real moving video** options:

- **Rainy City**: Andy Li's "Rainy night in Sham Shui Po", CC0, 6 seconds
- **Waterfall**: Pohled 111's "Vodopád Hejlovka (Želivka)", CC0, 16 seconds
- **Lake Drift**: Sdsecret's "Meikarta Lake", CC0, 6 seconds
- **My video**: pick a local video that you own or have permission to use

All three publicly hosted video files have portrait (608×1080 original) framing,
are sourced from Wikimedia Commons with verified CC0 pages, and are downloaded
**only on first selection** to an app-private folder (up to 24 MB per file).
They are played muted and looped via the existing Media3 player in a caption-safe
portrait layout. A source/license link is shown inside Story Mode Tools.
An interrupted download leaves no partial final asset and can be retried.
The previous saved background names migrate to Rainy City without clearing
book progress, captions, or other settings. The background dimming and
pause-motion options still work. Android video-codec availability and network
access can vary between devices; after download, the video plays offline.

## Openly licensed scenic backgrounds (v0.5.8)

Story Mode now includes four additional picture-based backgrounds: **Forest,
Castle, Desert, and Autumn**. The images are existing artwork from
[Kenney Background Elements Remastered](https://kenney.nl/assets/background-elements-remastered),
released under **Creative Commons CC0 1.0**. The PNGs are served over HTTPS
from the CC0 [game-assets archive](https://github.com/eturner58/game-assets/tree/main/kenney/2D%20assets/Background%20Elements%20Remastered).
They are **not bundled** in the APK: the first display requires internet,
after which Coil caches the small images locally and can display them offline.
When the download is unavailable, the player keeps an animated gradient fallback.

Visual changes: a compact scrolling selector with image previews, slow optional
parallax over the licensed backgrounds, gentler contrast around center captions,
and a background-dimming slider plus reduced-motion toggle in Story Mode's
Tools panel. Background choice and motion settings are remembered per audiobook.
Existing Parkour, Starfield, Orbs, and user-selected local videos remain available.
No copyrighted gameplay footage is included.

## Story Mode controls and caption display

Story Mode keeps audiobook playback controls and an always-accessible **Import/Replace
captions** action in a compact, opaque bottom card. Offline speech recognition,
full-book background transcription, export and M4B timing options are under
**Tools**, collapsed by default. The introductory caption instruction appears
only before any captions have been imported or generated; once captions exist
it never overlays the animation during normal gaps between spoken lines.
Changing subtitle files does not clear library data or playback progress.

## Full-length subtitle import

**Story Mode → Import SRT / VTT / LRC** accepts subtitle files up to 64 MiB
and as many as 300,000 timestamped cues, rather than rejecting files over 2 MB.
It builds a private, on-disk five-minute index while importing. Playback reads
only the index portion around the current audio position; no whole-book
subtitle parse occurs on chapter changes. Caption files and existing library
data are preserved if a new import fails. Full-length M4B subtitles use the
**M4B transcript uses full-book timestamps** setting by default.

An EPUB or estimated subtitle file does not guarantee accurate Michael Kramer
word timing; for precise synchronization, align the transcript to the actual
narration. Importing text as subtitles does not transcribe audio automatically.

## Build

Requirements: JDK 17+, Android SDK 35, and Gradle 8.10+.

```bash
gradle :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Official GitHub releases are built by `.github/workflows/release.yml`, signed
with the private release certificate stored in encrypted repository secrets,
and published with a SHA-256 checksum.

## Adding a source

Implement `AudioSource` in `app/src/main/java/com/mohamed/audioshelf/data/source/`,
then register it in `SourceRegistry`. A source must provide catalogue search and a
chapter list whose audio URLs are permitted for third-party playback.

AudioShelf also supports declarative provider repositories. Open **Sources**, paste an HTTPS
repository URL, then install any provider listed by that repository. See
[`docs/PROVIDER_REPOSITORIES.md`](docs/PROVIDER_REPOSITORIES.md) for the JSON format.

For user-initiated single-book imports, open **Sources → Import audiobook by URL** and paste a
public HTTPS book page or direct audio file/playlist URL (not a repository URL). The universal
HTML importer adapts extraction patterns from the MIT-licensed `aviiciii/audiobook-downloader`
Python scrapers, but is not restricted to those sites. Direct audio and open HLS URLs are
supported; JavaScript-only sites and streams requiring login, DRM or custom tokens may not work.
See [scraper import instructions](docs/SCRAPER_IMPORT.md) and
[third-party notices](docs/THIRD_PARTY_NOTICES.md).

## Legal note

AudioShelf does not ship a scraper for `mistbornaudio.com`. Its published terms
prohibit automated scraping, data extraction, and third-party downloading. It can
be added only if the operator provides an official API or written authorization.

AudioShelf also does not integrate AudioBookBay torrent automation. Users should
only import or play recordings they are legally permitted to access.

Debug builds use the repository's dedicated debug certificate so future APKs can
update one another. This key is intentionally public and must never be used for a
production Play Store release.

## Licence

Apache-2.0. See `LICENSE`.
