#!/usr/bin/env python3
"""Build static AudioShelf JSON providers from openly shared audiobook catalogues.

An intentionally small, polite, fail-safe indexer: no logins, DRM, protected
streams, arbitrary JavaScript or universal crawling. Individual chapters
continue streaming directly from the source hosts.
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
import time
from pathlib import Path
from urllib.parse import urljoin, urlparse

import feedparser
import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1] / "sources" / "catalogs"
SESSION = requests.Session()
SESSION.headers.update({"User-Agent": "AudioShelf-Open-Catalogue/1.0 (+GitHub; public-domain metadata index)"})
TIMEOUT = 25
ALLOWED_MEDIA = (".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac", ".wav", ".mp4")
MAX_PER_PROVIDER = 60


def https_url(candidate: str, base: str = "") -> str:
    value = urljoin(base, candidate.strip())
    parts = urlparse(value)
    if parts.scheme == "http" and parts.hostname in (
        "archive.org", "www.archive.org", "loyalbooks.com", "www.loyalbooks.com"
    ):
        value = "https://" + value[len("http://"):]
        parts = urlparse(value)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        return ""
    return value


def media_url(candidate: str, base: str = "") -> str:
    url = https_url(candidate, base)
    if not url or not urlparse(url).path.lower().endswith(ALLOWED_MEDIA):
        return ""
    return url


def get(url: str) -> requests.Response:
    response = SESSION.get(url, timeout=TIMEOUT)
    response.raise_for_status()
    return response


def slug(value: str) -> str:
    value = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return value[:80] or "audiobook"


def unique_id(value: str) -> str:
    return slug(value) + "-" + hashlib.sha256(value.encode()).hexdigest()[:7]


def catalog_book(id: str, title: str, author: str = "", cover: str = "", description: str = "") -> dict:
    item = {"id": id, "title": title.strip(), "authors": [author.strip() or "Unknown author"], "language": "English"}
    if cover:
        item["coverUrl"] = cover
    if description:
        item["description"] = description[:1000]
    return item


def write_catalog(provider: str, rows: list[tuple[dict, list[dict]]]) -> None:
    if not rows:
        print(f"SKIP {provider}: no playable public media found; preserving existing index")
        return
    out = ROOT / provider
    (out / "chapters").mkdir(parents=True, exist_ok=True)
    books = []
    for book, chapters in rows[:MAX_PER_PROVIDER]:
        clean = [c for c in chapters if media_url(c.get("audioUrl", ""))]
        if not clean or not book["id"]:
            continue
        books.append(book)
        (out / "chapters" / f'{book["id"]}.json').write_text(
            json.dumps({"chapters": clean}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
    if not books:
        print(f"SKIP {provider}: no valid chapters; preserving existing index")
        return
    (out / "books.json").write_text(
        json.dumps({"books": books}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"OK {provider}: {len(books)} books with valid audio links")


def open_audiobooks() -> list[tuple[dict, list[dict]]]:
    root = "https://openaudiobooks.org/"
    home = BeautifulSoup(get(root).text, "html.parser")
    links = {}
    for a in home.select('a[href*="/books/"]'):
        href = https_url(a.get("href", ""), root)
        parts = urlparse(href)
        if parts.hostname == "openaudiobooks.org" and re.fullmatch(r"/books/[^/]+/?", parts.path):
            links[href] = parts.path.strip("/").split("/")[-1]
    result = []
    for url, book_id in list(links.items())[:MAX_PER_PROVIDER]:
        try:
            doc = BeautifulSoup(get(url).text, "html.parser")
            h1 = doc.find("h1")
            title = h1.get_text(" ", strip=True) if h1 else book_id.replace("-", " ").title()
            if not title:
                continue
            file_url = next((
                media_url(a.get("href", ""), url)
                for a in doc.select('a[href*=".m4b"]')
                if media_url(a.get("href", ""), url).lower().split("?", 1)[0].endswith(".m4b")
            ), "")
            if not file_url:
                print("No M4B on", url)
                continue
            author = ""
            if h1:
                for el in h1.find_all_next(["p", "a", "h2"], limit=6):
                    text = el.get_text(" ", strip=True)
                    if text and len(text) < 80 and text not in ("Catalog", "About", "How to Listen") and "download" not in text.lower():
                        author = text
                        break
            cover_tag = doc.select_one('meta[property="og:image"]')
            cover = https_url(cover_tag.get("content", ""), url) if cover_tag else ""
            description_tag = doc.select_one('meta[name="description"]')
            description = description_tag.get("content", "") if description_tag else ""
            result.append((
                catalog_book(book_id, title, author, cover, description),
                [{"id": book_id + "-whole", "title": "Complete audiobook (M4B)", "audioUrl": file_url}]
            ))
            time.sleep(0.25)
        except Exception as exc:
            print("Skip Open Audiobooks book", url, str(exc), file=sys.stderr)
    return result


def feed_items(feed) -> list[dict]:
    chapters = []
    for i, entry in enumerate(feed.entries):
        candidates = [
            link.get("href", "") for link in entry.get("links", [])
            if link.get("rel") == "enclosure" or link.get("type", "").startswith("audio/")
        ]
        candidates += [enclosure.get("href", "") for enclosure in entry.get("enclosures", [])]
        url = next((media_url(v) for v in candidates if media_url(v)), "")
        if not url:
            continue
        chapters.append({
            "id": "chapter-" + str(i + 1),
            "title": entry.get("title", "").strip() or "Chapter " + str(i + 1),
            "audioUrl": url
        })
    return chapters


def loyal_books() -> list[tuple[dict, list[dict]]]:
    home = "https://www.loyalbooks.com/"
    doc = BeautifulSoup(get(home).text, "html.parser")
    links = {}
    for a in doc.select('a[href*="/book/"]'):
        href = https_url(a.get("href", ""), home).split("#", 1)[0].rstrip("/")
        if urlparse(href).hostname in ("loyalbooks.com", "www.loyalbooks.com") and "/book/" in urlparse(href).path:
            links[href] = a.get_text(" ", strip=True)
    rows = []
    for url in list(links)[:22]:
        try:
            feed_response = get(url + "/feed")
            feed = feedparser.parse(feed_response.content)
            tracks = feed_items(feed)
            if not tracks:
                continue
            book_id = unique_id(url)
            title = feed.feed.get("title", "").split("|")[0].strip() or links[url] or book_id
            author = feed.feed.get("author", "")
            img = feed.feed.get("image", {})
            cover = https_url(img.get("href", "")) if isinstance(img, dict) else ""
            rows.append((
                catalog_book(book_id, title, author, cover, "Public-domain recordings listed by Loyal Books."),
                tracks
            ))
            time.sleep(0.35)
        except Exception as exc:
            print("Skip Loyal Books feed", url, str(exc), file=sys.stderr)
    return rows


def gutenberg_audio() -> list[tuple[dict, list[dict]]]:
    # Official RSS feed for the Project Gutenberg / Microsoft / MIT open audiobook project.
    feed_url = "https://marhamilresearch4.blob.core.windows.net/gutenberg-public/rss.xml"
    feed_response = get(feed_url)
    parsed = feedparser.parse(feed_response.content)
    rows = []
    for entry in parsed.entries:
        if len(rows) >= MAX_PER_PROVIDER:
            break
        candidates = [v.get("href", "") for v in entry.get("links", [])
                      if v.get("rel") == "enclosure" or v.get("type", "").startswith("audio/")]
        candidates += [v.get("href", "") for v in entry.get("enclosures", [])]
        url = next((media_url(v) for v in candidates if media_url(v)), "")
        if not url:
            continue
        title = entry.get("title", "").strip()
        if not title:
            continue
        book_id = unique_id(entry.get("id", "") or url)
        author = entry.get("author", "") or entry.get("itunes_author", "")
        description = BeautifulSoup(entry.get("summary", ""), "html.parser").get_text(" ", strip=True)
        rows.append((
            catalog_book(book_id, title, author, "", description),
            [{"id": book_id + "-whole", "title": "Complete audiobook", "audioUrl": url}]
        ))
    return rows


def main() -> None:
    for provider, fetcher in (
        ("open-audiobooks", open_audiobooks),
        ("loyal-books", loyal_books),
        ("gutenberg-open-audio", gutenberg_audio),
    ):
        try:
            write_catalog(provider, fetcher())
        except Exception as exc:
            print(f"FAILED {provider}: {exc}; leaving previous catalog intact", file=sys.stderr)


if __name__ == "__main__":
    main()
