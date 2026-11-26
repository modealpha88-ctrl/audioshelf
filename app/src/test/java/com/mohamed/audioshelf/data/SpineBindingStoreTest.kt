package com.mohamed.audioshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpineBindingStoreTest {
    @Test fun everyNewBookStartsInAutomaticMode() {
        assertEquals(SpineBindingMode.AUTOMATIC, SpineBindingMode.fromStored(null))
        assertEquals(SpineBindingMode.AUTOMATIC, SpineBindingMode.fromStored("UNKNOWN"))
        assertEquals(SpineBindingMode.AUTOMATIC, SpineBindingMode.fromStored(""))
    }

    @Test fun savedAppearanceModesCanBeRestored() {
        for (mode in SpineBindingMode.entries) {
            assertEquals(mode, SpineBindingMode.fromStored(mode.name))
        }
    }

    @Test fun onlyExplicitNamedSpineImagesCountAsAuthentic() {
        for (file in listOf("spine.jpg", "Book_Spine.PNG", "jacket-spine.webp",
            "spine_art.jpeg", "spine artwork.jpg")) {
            assertTrue("Expected named spine: $file", isNamedSpineImage(file))
        }
        for (file in listOf("cover.jpg", "front.png", "folder.webp",
            "Harry Potter.jpg", "spine.gif", "spine.mp3", "cover spine.jpg")) {
            assertFalse("Not an authentic spine: $file", isNamedSpineImage(file))
        }
    }
}
