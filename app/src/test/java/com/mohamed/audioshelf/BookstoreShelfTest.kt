package com.mohamed.audioshelf

import com.mohamed.audioshelf.model.AudioBook
import org.junit.Assert.assertEquals
import org.junit.Test

class BookstoreShelfTest {
    private fun book(title: String, art: String = "") = AudioBook(
        id = title, sourceId = "test", title = title, authors = "Author", coverUrl = art,
    )

    @Test fun faceOutUsesOnlyBooksWithExistingArtwork() {
        val books = listOf(book("Harry Potter"), book("Peter Pan", "https://example.org/peter.jpg"),
            book("Sherlock Holmes", "https://example.org/sherlock.jpg"))
        assertEquals(2, bookstoreFaceOutIndex(books, faceOnLeft = false))
        assertEquals(1, bookstoreFaceOutIndex(books, faceOnLeft = true))
        assertEquals(-1, bookstoreFaceOutIndex(books.map { it.copy(coverUrl = "") }, false))
        assertEquals(-1, bookstoreFaceOutIndex(emptyList(), true))
    }

    @Test fun missingArtworkNeverForcesAFakeEditionToBeFeatured() {
        assertEquals(-1, bookstoreFaceOutIndex(listOf(book("Harry Potter Complete Audiobook Collection")), false))
        assertEquals(0, bookstoreFaceOutIndex(listOf(book("The Final Empire", "file:///realcover.jpg")), true))
    }

    @Test fun spinesShortenMetadataButDoNotChangeSavedTitles() {
        assertEquals("Harry Potter", bookstoreSpineTitle("Harry Potter Complete Audiobook Collection"))
        assertEquals("The Final Empire", bookstoreSpineTitle("The Final Empire (Unabridged)"))
        assertEquals("The Memoirs of Sherlock Holmes", bookstoreSpineTitle("The Memoirs of Sherlock Holmes"))
    }
}
