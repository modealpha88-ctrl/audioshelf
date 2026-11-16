# Website scraper URL import

AudioShelf uses the publicly visible HTML extraction strategies from
[aviiciii/audiobook-downloader](https://github.com/aviiciii/audiobook-downloader/tree/main/scrapers).
The upstream project is written in Python; AudioShelf's importer is a native Kotlin/Jsoup
adaptation, not a Python runtime or a Tachiyomi extension.

## How it works

In **Sources → Import audiobook by URL**, paste a *specific book page* and confirm you
have permission to play its recording. AudioShelf extracts a title, optional author and
cover, and direct audio chapter URLs, then saves the book in your library. Opening the
book refreshes the chapter list from its page. No site-wide crawling or credentials are
collected. Installed JSON catalogue repositories continue to work separately.

| Site | Adapted pattern | Status |
| --- | --- | --- |
| zaudiobooks.com | JavaScript `tracks` /`chapter_link_dropbox` entries | User-provided book URLs |
| fulllengthaudiobooks.net | `.entry` MP3 source tags | User-provided book URLs |
| hdaudiobooks.net | `.entry` /`.entry-box` MP3 source tags | User-provided book URLs |
| bigaudiobooks.net | `.post-single` MP3 source tags | User-provided book URLs |
| goldenaudiobook.net / goldenaudiobook.com | `audio.wp-audio-shortcode` sources | User-provided book URLs |
| tokybook.com | Authenticated token-based HLS playlists | Not supported by direct import |

TokyBook's Python implementation requests a post-detail token and a stream token,
then uses per-request authorization headers for HLS. These media requests cannot be
played by AudioShelf's current standard MP3 playback path. The importer detects and
reports this instead of adding a broken book.

Book URLs must use HTTPS and belong to one of the listed hosts. HTML layouts can
change at any time; the importer does not bypass logins, DRM, payments or access
controls. A successful import does **not** establish that the recording is licensed
for redistribution. Use only recordings you are allowed to access and download.

## Attribution

HTML patterns adapted from the MIT-licensed
[aviiciii/audiobook-downloader](https://github.com/aviiciii/audiobook-downloader)
(Copyright © 2025 aviiciii). See [third-party notice](THIRD_PARTY_NOTICES.md).
