package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogueCoverTrimTest {
    private val white = 0xFFFFFFFF.toInt()
    private val artwork = 0xFF283D30.toInt()

    @Test fun removesSymmetricalWhitePaddingAroundPortraitCover() {
        assertEquals(69..130, catalogueCoverBounds(200, 100) { x, _ ->
            if (x in 70..129) artwork else white
        })
    }

    @Test fun keepsOriginalPortraitAndSquareArt() {
        assertNull(catalogueCoverBounds(60, 100) { _, _ -> artwork })
        assertNull(catalogueCoverBounds(100, 100) { _, _ -> artwork })
    }

    @Test fun keepsLandscapeArtAndRejectsBlankImage() {
        assertNull(catalogueCoverBounds(200, 100) { _, _ -> artwork })
        assertNull(catalogueCoverBounds(200, 100) { _, _ -> white })
    }

    @Test fun avoidsCroppingAsymmetricWhiteDesign() {
        assertNull(catalogueCoverBounds(200, 100) { x, _ ->
            if (x in 30..89) artwork else white
        })
    }
}
