package com.mohamed.audioshelf.data.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentDownloadsTest {
    @Test fun recognizesSupportedAudioTypes() {
        for (name in listOf("chapter01.mp3", "Book.m4b", "disc/chapter.m4a", "audio.opus",
            "disc/part.flac", "chapter.wav", "a.ogg", "part.aac")) {
            assertTrue("Expected audio file: $name", TorrentDownloads.isAudioPath(name))
        }
    }

    @Test fun rejectsUnwantedAndUnsafePaths() {
        for (name in listOf("../escape.mp3", "/absolute.mp3", "disc/../../escape.m4b",
            "disc\\escape.mp3", "disc//part.mp3", "cover.jpg", "install.apk",
            "archive.zip", "video.mp4", "audio.exe", "", "a/./b.mp3")) {
            assertFalse("Rejected torrent payload: $name", TorrentDownloads.isAudioPath(name))
        }
    }
}
