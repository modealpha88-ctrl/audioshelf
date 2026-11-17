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

    @Test fun punctuationIsNormalized() {
        assertEquals(120, scoreCoverCandidate(
            "Alice's Adventures in Wonderland", "Lewis Carroll",
            "Alice’s Adventures in Wonderland", listOf("Lewis Carroll")))
    }
}
