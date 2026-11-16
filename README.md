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
file using Android's document picker (with retained local URI access),
or paste a public podcast RSS feed for chapter playback. Podcast feeds
must contain publisher-distributed HTTPS audio enclosures. This allows
compatible free Scribl/Podiobooks podcasts to play inside AudioShelf
without scraping Scribl's protected player.

Repository URL:
`https://raw.githubusercontent.com/modealpha88-ctrl/audioshelf/main/sources/manifest.json`

See [provider setup](sources/README.md) for access limitations and
how to import locally owned audio.

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
