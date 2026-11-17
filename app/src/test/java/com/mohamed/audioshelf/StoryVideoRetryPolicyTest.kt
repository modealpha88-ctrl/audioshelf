package com.mohamed.audioshelf

import org.junit.Assert.*
import org.junit.Test

class StoryVideoRetryPolicyTest {
    @Test fun respectsRetryAfterFromServer() {
        assertEquals(600_000L, StoryVideoRetryPolicy.delayMs(429, "600"))
        assertEquals(30_000L, StoryVideoRetryPolicy.delayMs(429, "30"))
        assertEquals(3_600_000L, StoryVideoRetryPolicy.delayMs(429, "9999"))
    }
    @Test fun rateLimitWithoutHeaderHasPoliteBackoff() {
        assertEquals(600_000L, StoryVideoRetryPolicy.delayMs(429, null))
        assertEquals(120_000L, StoryVideoRetryPolicy.delayMs(503, null))
        assertTrue(StoryVideoRetryPolicy.waitMessage(600_000L).contains("10 min"))
    }
}
