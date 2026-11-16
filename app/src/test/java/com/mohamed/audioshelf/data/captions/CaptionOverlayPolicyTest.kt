package com.mohamed.audioshelf.data.captions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionOverlayPolicyTest {
    @Test fun firstTimeWithNoSubtitlesCanSeeHint() {
        assertTrue(CaptionOverlayPolicy.showSetupHint(false, false, false, false))
    }
    @Test fun importedSubtitleFileHidesHintEvenDuringSilence() {
        assertFalse(CaptionOverlayPolicy.showSetupHint(true, false, false, false))
    }
    @Test fun afterFirstCaptionNoHintCanReappearInGaps() {
        assertFalse(CaptionOverlayPolicy.showSetupHint(false, true, false, false))
    }
    @Test fun liveTranscriptionOrFullBookJobSuppressesHint() {
        assertFalse(CaptionOverlayPolicy.showSetupHint(false, false, true, false))
        assertFalse(CaptionOverlayPolicy.showSetupHint(false, false, false, true))
    }
}
