package com.music.spotui.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloaderTest {
    @Test fun contentRangeTotalReadsTheFullSize() {
        assertEquals(1234L, OfflineDownloader.contentRangeTotal("bytes 0-99/1234"))
        assertNull(OfflineDownloader.contentRangeTotal("bytes 0-99/*"))
        assertNull(OfflineDownloader.contentRangeTotal(null))
    }

    @Test fun normalizeIsrcAcceptsDashesAndCase() {
        assertEquals("USUM71703861", OfflineDownloader.normalizeIsrc("us-um7-17-03861"))
        assertEquals("FRZ039800212", OfflineDownloader.normalizeIsrc("FR Z03 98 00212"))
    }

    @Test fun normalizeIsrcRejectsOtherIds() {
        assertNull(OfflineDownloader.normalizeIsrc(""))
        assertNull(OfflineDownloader.normalizeIsrc("0602557362806")) // a UPC
        assertNull(OfflineDownloader.normalizeIsrc("USUM7170386"))   // one digit short
    }

    @Test fun lengthMismatchRefusesPreviewsAndOtherVersions() {
        assertNull(OfflineDownloader.lengthMismatch(163, 163_400))
        assertNull(OfflineDownloader.lengthMismatch(160, 163_400))   // a few seconds trimmed
        assertNull(OfflineDownloader.lengthMismatch(175, 163_400))   // trailing silence
        assertNull(OfflineDownloader.lengthMismatch(30, 0))          // the song's length unknown
        assertTrue(OfflineDownloader.lengthMismatch(30, 163_400)!!.contains("preview"))
        assertTrue(OfflineDownloader.lengthMismatch(312, 163_400)!!.contains("another version"))
    }

    @Test fun localIdsNeverCollideWithMediaStoreIds() {
        for (id in listOf("4uLU6hMCjMI75M1A2tKUQC", "0", "", "zzzzzzzzzzzzzzzzzzzzzz")) {
            assertTrue(OfflineStore.localIdOf(id) < 0)
        }
    }

    @Test fun orderedJioSaavnUrlsRespectsDownloadQuality() {
        val primary = "https://aac.saavncdn.com/123/abc_320.mp4"
        val smallOrder = OfflineDownloader.orderedJioSaavnUrls(primary, com.music.spotui.data.preferences.DownloadQuality.SMALL)
        assertEquals(
            listOf(
                "https://aac.saavncdn.com/123/abc_96.mp4",
                "https://aac.saavncdn.com/123/abc_160.mp4",
                "https://aac.saavncdn.com/123/abc_320.mp4",
            ),
            smallOrder,
        )
        val cdOrder = OfflineDownloader.orderedJioSaavnUrls(primary, com.music.spotui.data.preferences.DownloadQuality.CD)
        assertEquals(
            listOf(
                "https://aac.saavncdn.com/123/abc_320.mp4",
                "https://aac.saavncdn.com/123/abc_160.mp4",
                "https://aac.saavncdn.com/123/abc_96.mp4",
            ),
            cdOrder,
        )
    }

    @Test fun rankYouTubeStreamUrlsRespectsDownloadQuality() {
        val url139 = "https://rr1.googlevideo.com/videoplayback?itag=139&mime=audio%2Fmp4"
        val url140 = "https://rr1.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4"
        val url249 = "https://rr1.googlevideo.com/videoplayback?itag=249&mime=audio%2Fwebm"
        val url251 = "https://rr1.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm"

        val smallOrder = OfflineDownloader.rankYouTubeStreamUrls(
            listOf(url251, url140, url249, url139),
            com.music.spotui.data.preferences.DownloadQuality.SMALL,
        )
        assertEquals(url139, smallOrder.first())

        val cdOrder = OfflineDownloader.rankYouTubeStreamUrls(
            listOf(url139, url249, url251, url140),
            com.music.spotui.data.preferences.DownloadQuality.CD,
        )
        assertEquals(url140, cdOrder.first())
    }
}
