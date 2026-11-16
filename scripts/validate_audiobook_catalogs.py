#!/usr/bin/env python3
"""Validate the hosted source repository against AudioShelf's JSON provider schema."""
import json
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://raw.githubusercontent.com/modealpha88-ctrl/audioshelf/main/"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def check_https(value):
    parsed = urlparse(value)
    assert parsed.scheme == "https" and parsed.netloc and not parsed.username, value


def main():
    manifest = read(ROOT / "sources/manifest.json")
    assert manifest["version"] == 1
    providers = manifest["providers"]
    assert len(providers) >= 2
    ids = set()
    book_count = 0
    for provider in providers:
        id = provider["id"]
        assert id not in ids
        ids.add(id)
        for name in ("browseUrl", "chaptersUrl"):
            assert provider[name].startswith(BASE)
            check_https(provider[name].replace("{id}", "sample"))
        assert "{id}" in provider["chaptersUrl"]
        assert "searchUrl" not in provider, "Static catalogue providers intentionally use local search"
        catalog_dir = ROOT / "sources/catalogs" / id
        assert catalog_dir.is_dir()
        books = read(catalog_dir / "books.json")["books"]
        seen_books = set()
        for book in books:
            book_id = book["id"]
            assert book_id and "/" not in book_id and ".." not in book_id
            assert book_id not in seen_books
            assert book["title"].strip()
            seen_books.add(book_id)
            chapters_file = catalog_dir / "chapters" / (book_id + ".json")
            assert chapters_file.is_file(), str(chapters_file)
            chapters = read(chapters_file)["chapters"]
            assert chapters
            for chapter in chapters:
                assert chapter["id"] and chapter["title"]
                check_https(chapter["audioUrl"])
            book_count += 1
        print(f"Provider {id}: {len(books)} indexed books")
    assert book_count > 0, "At least one tested, real audiobook must be present"
    print(f"Validated {len(providers)} providers, {book_count} audiobooks")


if __name__ == "__main__":
    main()
