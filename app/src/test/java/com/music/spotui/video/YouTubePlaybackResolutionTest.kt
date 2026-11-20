package com.music.spotui.video

import app.wavebits.innertube.models.YouTubeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

class YouTubePlaybackResolutionTest {

    @Test
    fun testYouTubeConstantsAndClients() {
        assertEquals("https://music.youtube.com", YouTubeClient.ORIGIN_YOUTUBE_MUSIC)
        assertEquals("https://music.youtube.com/", YouTubeClient.REFERER_YOUTUBE_MUSIC)
        assertEquals("https://www.youtube.com/youtubei/v1/", YouTubeClient.API_URL_YOUTUBE)
        assertNotNull(YouTubeClient.ANDROID_TESTSUITE)
        assertEquals("85", YouTubeClient.ANDROID_TESTSUITE.clientId)
    }

    @Test
    fun testStreamHeadersDetection() {
        val loc = Localization.DEFAULT
        val androidUrl = "https://rr1---sn-ab5sznzs.googlevideo.com/videoplayback?itag=140&c=ANDROID"
        val iosUrl = "https://rr1---sn-ab5sznzs.googlevideo.com/videoplayback?itag=140&c=IOS"
        val generalUrl = "https://rr1---sn-ab5sznzs.googlevideo.com/videoplayback?itag=140"

        assertTrue(YoutubeParsingHelper.isAndroidStreamingUrl(androidUrl))
        val androidUa = YoutubeParsingHelper.getAndroidUserAgent(loc)
        assertTrue(androidUa.isNotBlank())

        assertTrue(YoutubeParsingHelper.isIosStreamingUrl(iosUrl))
        val iosUa = YoutubeParsingHelper.getIosUserAgent(loc)
        assertTrue(iosUa.isNotBlank())

        assertTrue(generalUrl.contains("googlevideo.com"))
    }
}
