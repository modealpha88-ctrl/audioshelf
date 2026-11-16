# AudioShelf architecture

## Layers

- `model`: source-neutral audiobook, chapter, and progress records.
- `data/source`: the `AudioSource` contract, registry, and provider adapters.
- `data`: local library/progress preferences and app-private downloads.
- `player`: a Media3 session service for background playback and system controls.
- `MainViewModel`: UI state and user actions.
- `MainActivity`: Material 3 Compose screens.

## Source contract

Each provider implements three operations: browse, search, and resolve chapters.
Provider-specific HTML/API shapes never escape the adapter. This makes a broken
provider replaceable and prevents one source from corrupting saved-library data.

Before adding a provider, verify all of the following:

1. Its API or terms allow third-party catalogue access.
2. The provider is authorized to distribute the audio in the user's region.
3. Streaming and offline downloads are explicitly permitted.
4. Requests identify the app, respect rate limits, and cache responsibly.

## Persistence

The MVP uses app-local preferences for library metadata and listening progress.
Offline files live in the app's external-files music directory, so Android removes
them when the app is uninstalled and no broad storage permission is required.

For a larger catalogue, the next storage migration should move records to Room and
use WorkManager for queued downloads with pause/resume, integrity checks, and
storage quotas.

## Security and privacy

AudioShelf asks only for network, media playback foreground-service, and Android 13+
notification permissions. It has no accounts, ads, analytics, contacts, SMS,
location, or broad file access.
