package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableSpineKeyTest {
    private val book = StableSpineKey("local", "42", "Mistborn", "file:///cover.jpg", 0)

    @Test fun sameBookSharesAppearanceAcrossVirtualCopies() {
        assertEquals(book, StableSpineKey("local", "42", "Mistborn", "file:///cover.jpg", 0))
    }

    @Test fun differentSourceOrBookCannotReuseSpine() {
        assertNotEquals(book, book.copy(sourceId = "remote"))
        assertNotEquals(book, book.copy(bookId = "43"))
    }

    @Test fun coverTitleOrBindingEditInvalidatesAppearance() {
        assertNotEquals(book, book.copy(coverUrl = "file:///new.jpg"))
        assertNotEquals(book, book.copy(title = "The Final Empire"))
        assertNotEquals(book, book.copy(revision = 1))
    }
}
