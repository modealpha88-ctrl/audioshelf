package com.mohamed.audioshelf

import org.junit.Assert.*
import org.junit.Test

class StoryLicensedVideosTest {
    @Test fun catalogUsesIndependentLicensingAndSources() {
        assertEquals(3, StoryLicensedVideos.clips.size)
        assertEquals(3, StoryLicensedVideos.clips.map { it.id }.distinct().size)
        for (item in StoryLicensedVideos.clips) {
            assertEquals("CC0 1.0", item.licenseName)
            assertTrue(item.fileUrl.startsWith("https://upload.wikimedia.org/wikipedia/commons/"))
            assertTrue(item.sourceUrl.startsWith("https://commons.wikimedia.org/wiki/File:"))
            assertTrue(item.fileUrl.endsWith(".webm"))
            assertTrue(item.expectedBytes > 0L)
        }
    }

    @Test fun usesSmallerCompatibleVideoTranscodes() {
        val waterfall = StoryLicensedVideos.clips.first { it.id == "waterfall" }
        val lake = StoryLicensedVideos.clips.first { it.id == "lake_drift" }
        assertTrue(waterfall.fileUrl.contains("/transcoded/"))
        assertTrue(waterfall.fileUrl.endsWith(".480p.vp9.webm"))
        assertTrue(lake.fileUrl.endsWith(".480p.vp9.webm"))
    }

    @Test fun obsoleteStaticThemesMigrateWithoutClearingPreferences() {
        assertEquals("Rainy City", StoryLicensedVideos.persistedStyle("Forest"))
        assertEquals("Rainy City", StoryLicensedVideos.persistedStyle("Castle"))
        assertEquals("Rainy City", StoryLicensedVideos.persistedStyle("Parkour"))
        assertEquals("Waterfall", StoryLicensedVideos.persistedStyle("Waterfall"))
        assertEquals("My video", StoryLicensedVideos.persistedStyle("My video"))
    }
}
