# AudioShelf provider repositories

AudioShelf 0.3 supports repository URLs that return a small JSON document. A repository lists
one or more audiobook providers. Users add the HTTPS URL on the **Sources** screen, browse the
providers, and choose which ones to install.

Provider definitions are declarative: they describe JSON catalogue endpoints and do not execute
downloaded code or install APKs.

## Repository document

```json
{
  "version": 1,
  "name": "Example open-audio sources",
  "description": "Audiobooks that this provider is authorized to distribute",
  "providers": [
    {
      "id": "example_audio",
      "name": "Example Audio",
      "description": "Openly licensed recordings",
      "attribution": "Provided by Example Audio",
      "browseUrl": "https://audio.example.org/api/books?page={page}",
      "searchUrl": "https://audio.example.org/api/search?q={query}&page={page}",
      "chaptersUrl": "https://audio.example.org/api/books/{id}/chapters"
    }
  ]
}
```

`browseUrl` and `chaptersUrl` are required. `searchUrl` is optional; when omitted, AudioShelf
filters the current browse page locally. All repository and provider endpoints must use HTTPS.

Supported placeholders:

- `{page}` — page number, starting at 1
- `{query}` — URL-encoded search text
- `{id}` — URL-encoded audiobook ID

## Book response

The browse and search endpoints may return an array directly, or an object containing a `books`,
`items`, or `results` array.

```json
{
  "books": [
    {
      "id": "book-1",
      "title": "Example Book",
      "authors": ["Example Author"],
      "narrators": ["Example Narrator"],
      "description": "Description",
      "coverUrl": "https://audio.example.org/covers/book-1.jpg",
      "language": "English"
    }
  ]
}
```

`id` and `title` are required. `authors` and `narrators` can be strings or arrays. Relative cover
URLs are resolved against the response URL, but the final URL must use HTTPS.

## Chapter response

The chapters endpoint may return an array directly, or an object containing a `chapters`, `items`,
or `results` array.

```json
{
  "chapters": [
    {
      "id": "chapter-1",
      "title": "Chapter 1",
      "audioUrl": "https://audio.example.org/books/book-1/01.mp3",
      "durationMs": 615000
    }
  ]
}
```

`audioUrl` (or `url`) is required and must resolve to HTTPS. Providers are responsible for only
listing recordings they are permitted to distribute to third-party clients.
