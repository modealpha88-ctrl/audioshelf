package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverSpineLetteringTest {
    @Test fun capitalizationAndLineBreaksDoNotChangeTheTitle() {
        assertEquals(spineLetteringKey("THE FINAL\nEMPIRE"), spineLetteringKey("The Final Empire"))
    }

    @Test fun punctuationDoesNotChangeTheTitle() {
        assertEquals(spineLetteringKey("Harry Potter: Book 1"), spineLetteringKey("HARRY POTTER — BOOK 1"))
    }

    @Test fun unrelatedTextDoesNotMatch() {
        assertNotEquals(spineLetteringKey("The Final Empire"), spineLetteringKey("The Final Empire by Brandon Sanderson"))
    }
}
