# Universal audiobook URL importer

AudioShelf provides a user-initiated single-page importer based on HTML extraction patterns
from [aviiciii/audiobook-downloader](https://github.com/aviiciii/audiobook-downloader/tree/main/scrapers).
The original Python project is MIT-licensed; this is a native Kotlin / Jsoup adaptation.

## Usage

Open **Sources → Import audiobook by URL**, paste a full public HTTPS page URL or direct audio
file URL, confirm that you have permission to access and play the recording, and tap
**Import into library**. AudioShelf extracts a title, cover, and chapter URLs and adds the
book to your library. The library refreshes chapters from the saved page when opened.

## Supported input formats

- **Any public HTTPS site** that includes direct audio URLs in HTML5 `<audio>`,
  `<source>`, audio links, Open Graph audio tags, or simple static player scripts
- **Direct audio URLs** ending in .mp3, .m4a, .mp4, .aac, .ogg, .opus, .flac, .wav or .m3u8
- **Public HLS .m3u8 streams** playable by Media3 (stream only, no offline playlist download)
- HTML chapter extraction patterns used by ZAudiobooks, Full Length Audiobooks,
  HD Audiobooks, Big Audiobooks, and Golden Audiobook

This does **not** mean every website can be imported. Sites whose media are assembled
in JavaScript, accessed only after logging in, hidden behind payment/DRM, or require
temporary streaming tokens or special headers may not expose usable chapter links.
AudioShelf does not run arbitrary third-party script code, crawl entire websites,
or defeat access controls. Plain HTTP and local/private-network hosts are rejected,
including during page redirects; cross-domain public HTTPS redirects are allowed.

An HTML import can contain external audio URLs, and the site may change or remove
them. The importer cannot verify licenses: use only content you have permission to
stream or download and comply with applicable terms.

## Attribution

The HTML patterns are adapted from the MIT-licensed
[aviiciii/audiobook-downloader](https://github.com/aviiciii/audiobook-downloader).
Copyright © 2025 aviiciii. See [third-party notices](THIRD_PARTY_NOTICES.md).
