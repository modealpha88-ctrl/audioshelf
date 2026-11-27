package com.mohamed.audioshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpineFontCatalogTest {
    @Test fun onlyApprovedPathShapeIsAccepted() {
        val catalog = "almendra\tAlmendra-Regular.ttf\n../evil\tBad.ttf\nvalid\t../../Bad.ttf\nvalid\thttps://example.org/a.ttf"
        assertEquals(listOf(SpineFontSource("almendra", "Almendra-Regular.ttf")), parseSpineFontCatalog(catalog))
    }

    @Test fun catalogDeduplicatesAndBoundsEntries() {
        val repeated = "almendra\tAlmendra-Regular.ttf\n".repeat(30)
        assertEquals(1, parseSpineFontCatalog(repeated).size)
        val many = (1..50).joinToString("\n") { "font$it\tFont.ttf" }
        assertTrue(parseSpineFontCatalog(many).size <= 16)
    }
}
