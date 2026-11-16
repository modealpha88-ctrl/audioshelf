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
- Book details and RSS-based chapter discovery
- Local library and listening-progress persistence
- Media3 background playback and notification controls
- Chapter navigation, seek, 0.5x–2.0x speed, and sleep timer
- Material 3 light/dark UI
- Source interface ready for additional authorized providers

## Build

Requirements: JDK 17+, Android SDK 35, and Gradle 8.10+.

```bash
gradle :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Adding a source

Implement `AudioSource` in `app/src/main/java/com/mohamed/audioshelf/data/source/`,
then register it in `SourceRegistry`. A source must provide catalogue search and a
chapter list whose audio URLs are permitted for third-party playback.

## Legal note

AudioShelf does not ship a scraper for `mistbornaudio.com`. Its published terms
prohibit automated scraping, data extraction, and third-party downloading. It can
be added only if the operator provides an official API or written authorization.

AudioShelf also does not integrate AudioBookBay torrent automation. Users should
only import or play recordings they are legally permitted to access.

## Licence

Apache-2.0. See `LICENSE`.
