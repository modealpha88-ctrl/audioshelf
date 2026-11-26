package com.mohamed.audioshelf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BookCoverLookupTest {
    @Test fun exactTitleAndAuthorMatch() {
        assertEquals(120, scoreCoverCandidate(
            "The Final Empire", "Brandon Sanderson",
            "The Final Empire", listOf("Brandon Sanderson")))
    }

    @Test fun wrongAuthorCannotOverrideCover() {
        assertEquals(0, scoreCoverCandidate(
            "The Final Empire", "Brandon Sanderson",
            "The Final Empire", listOf("Someone Else")))
    }

    @Test fun noAuthorRequiresExactTitle() {
        assertEquals(100, scoreCoverCandidate(
            "Pride and Prejudice", "Local audiobook",
            "Pride and Prejudice", listOf("Jane Austen")))
        assertEquals(0, scoreCoverCandidate(
            "Pride and Prejudice", "",
            "Pride and Prejudice and Zombies", listOf("Jane Austen")))
    }

    @Test fun extendedSubtitleAllowedOnlyWithMatchingAuthor() {
        assertEquals(90, scoreCoverCandidate(
            "The War of the Worlds", "H. G. Wells",
            "The War of the Worlds: A Novel", listOf("H. G. Wells")))
    }

    @Test fun unabridgedAudiobookTitleFindsPublishedBook() {
        assertEquals(listOf("The Final Empire", "Mistborn",
            "The Final Empire: Mistborn, Book 1"), coverSearchTitles(
            "The Final Empire: Mistborn, Book 1 (Unabridged)"))
        assertEquals(120, scoreCoverCandidate(
            "The Final Empire: Mistborn, Book 1 (Unabridged)",
            "Brandon Sanderson", "The Final Empire", listOf("Brandon Sanderson")))
    }

    @Test fun wrongAuthorNeverMatchesEditionAliases() {
        assertEquals(0, scoreCoverCandidate(
            "The Final Empire: Mistborn, Book 1 (Unabridged)",
            "Brandon Sanderson", "The Final Empire", listOf("Another Author")))
    }

    @Test fun emptyOrUnrelatedTitleIsNotAccepted() {
        assertEquals(0, scoreCoverCandidate(
            "The Final Empire: Mistborn, Book 1 (Unabridged)",
            "Brandon Sanderson", "The Well of Ascension",
            listOf("Brandon Sanderson")))
    }

    @Test fun collectionCanSearchForVolumesWithoutAutoSelectingOne() {
        assertEquals(
            listOf("Harry Potter Complete Audiobook Collection", "Harry Potter"),
            coverSuggestionSearchTitles("Harry Potter Complete Audiobook Collection"),
        )
        assertEquals(0, scoreCoverCandidate(
            "Harry Potter Complete Audiobook Collection", "Local torrent audiobook",
            "Harry Potter and the Philosopher's Stone", listOf("J. K. Rowling"),
        ))
    }

    @Test fun normalBooksStillPreferTheirExactPublishedTitles() {
        assertEquals(listOf("Peter Pan"), coverSuggestionSearchTitles("Peter Pan"))
        assertEquals(120, scoreCoverCandidate(
            "Peter Pan", "J. M. Barrie", "Peter Pan", listOf("J. M. Barrie"),
        ))
        assertEquals(120, scoreCoverCandidate(
            "Dracula", "Bram Stoker", "Dracula", listOf("Bram Stoker"),
        ))
    }

    @Test fun punctuationIsNormalized() {
        assertEquals(120, scoreCoverCandidate(
            "Alice's Adventures in Wonderland", "Lewis Carroll",
            "Alice’s Adventures in Wonderland", listOf("Lewis Carroll")))
    }
}
