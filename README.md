# AudioShelf

AudioShelf is a Tachiyomi-inspired Android audiobook reader: browse modular sources,
save books to a local library, stream chapter audio, resume progress, change speed,
and use a sleep timer.

The bundled sources are **LibriVox** and open/public-domain audio from the
**Internet Archive**. Source adapters are deliberately opt-in: only integrate a website when
its API, licence, or written permission allows third-party clients.

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
