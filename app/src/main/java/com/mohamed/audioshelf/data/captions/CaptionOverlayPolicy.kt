package com.mohamed.audioshelf.data.captions

/** Visible once before captions are configured, never again between spoken cues. */
object CaptionOverlayPolicy {
    fun showSetupHint(
        hasSavedCaptions: Boolean,
        hasEverShownCue: Boolean,
        autoTranscribing: Boolean,
        fullBookRunning: Boolean,
    ): Boolean = !hasSavedCaptions && !hasEverShownCue && !autoTranscribing && !fullBookRunning
}
