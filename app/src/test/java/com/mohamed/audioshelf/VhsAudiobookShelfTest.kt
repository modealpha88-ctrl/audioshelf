package com.mohamed.audioshelf

import com.mohamed.audioshelf.model.AudioBook
import com.mohamed.audioshelf.model.ListeningProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VhsAudiobookShelfTest {
    private fun book(title: String) = AudioBook(
        id = title, sourceId = "local_files", title = title,
        authors = "Author", coverUrl = "file:///cover.jpg",
    )

    @Test fun virtualIndicesWrapInBothDirections() {
        assertEquals(0, vhsWrap(0, 6))
        assertEquals(5, vhsWrap(-1, 6))
        assertEquals(0, vhsWrap(6, 6))
        assertEquals(2, vhsWrap(14, 6))
        assertEquals(0, vhsWrap(-6, 0))
    }

    @Test fun openCoverPushesNeighbouringSpinesApartSymmetrically() {
        val ws = 40f
        val depth = 126f
        val gap = 7f
        assertEquals(0f, vhsShelfPosition(0f, ws, depth, gap), .0001f)
        val right = vhsShelfPosition(1f, ws, depth, gap)
        val left = vhsShelfPosition(-1f, ws, depth, gap)
        assertEquals(right, -left, .0001f)
        assertTrue(right > ws + gap)
        for (r in listOf(.1f, .25f, .5f, .9f)) {
            assertEquals(
                vhsShelfPosition(r, ws, depth, gap),
                -vhsShelfPosition(-r, ws, depth, gap),
                .0001f,
            )
        }
    }

    @Test fun springOpennessHasValidLimits() {
        assertEquals(0f, vhsSmoothstep(-1f), .0001f)
        assertEquals(0f, vhsSmoothstep(0f), .0001f)
        assertEquals(.5f, vhsSmoothstep(.5f), .0001f)
        assertEquals(1f, vhsSmoothstep(1f), .0001f)
        assertEquals(1f, vhsSmoothstep(9f), .0001f)
    }

    @Test fun shelfFiltersOperateOnAnyImportedTitleWithoutHardcoding() {
        val books = listOf(book("Dracula"), book("The Final Empire"),
            book("Moby Dick"), book("Sherlock Holmes"))
        val progress: (AudioBook) -> ListeningProgress = {
            if (it.title == "Dracula") ListeningProgress(positionMs = 2500L)
            else ListeningProgress()
        }
        assertEquals(4, vhsFilterBooks(books, ArchiveFilter.ALL, progress).size)
        assertEquals(listOf("Dracula", "Moby Dick"),
            vhsFilterBooks(books, ArchiveFilter.AM, progress).map { it.title })
        assertEquals(listOf("The Final Empire", "Sherlock Holmes"),
            vhsFilterBooks(books, ArchiveFilter.NZ, progress).map { it.title })
        assertEquals(listOf("Dracula"),
            vhsFilterBooks(books, ArchiveFilter.LISTENING, progress).map { it.title })
    }
}
