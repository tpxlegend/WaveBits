package com.music.spotui.data

import app.wavebits.host.android.youtube.YouTubeAccount
import app.wavebits.innertube.InnerTube
import app.wavebits.innertube.YouTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaveBitsAuditTest {

    @Test
    fun normalizeDataSyncId_handlesDelegatedAndPersonalTokens() {
        // Delegated format: "delegatedPageId||primaryAccountId" -> returns primaryAccountId after ||
        assertEquals("109823456", InnerTube.normalizeDataSyncId("104284111||109823456"))
        assertEquals("109823456", YouTubeAccount.cleanDataSyncId("104284111||109823456"))

        // Trailing separator: "109823456||" -> falls back to token before ||
        assertEquals("109823456", InnerTube.normalizeDataSyncId("109823456||"))
        assertEquals("109823456", YouTubeAccount.cleanDataSyncId("109823456||"))

        // Leading separator: "||109823456" -> returns token after ||
        assertEquals("109823456", InnerTube.normalizeDataSyncId("||109823456"))
        assertEquals("109823456", YouTubeAccount.cleanDataSyncId("||109823456"))

        // Plain ID without separator
        assertEquals("109823456", InnerTube.normalizeDataSyncId("109823456"))
        assertEquals("109823456", YouTubeAccount.cleanDataSyncId("109823456"))

        // Blank or null inputs
        assertNull(InnerTube.normalizeDataSyncId(null))
        assertNull(InnerTube.normalizeDataSyncId("   "))
        assertNull(InnerTube.normalizeDataSyncId("||"))
        assertEquals("", YouTubeAccount.cleanDataSyncId("   "))
        assertEquals("", YouTubeAccount.cleanDataSyncId("||"))
    }

    @Test
    fun adoptSessionScope_updatesInnerTubeSessionFields() {
        // 1. Brand Account session scope (has delegated pageId)
        YouTube.adoptSessionScope(
            visitorData = "Cgt2aXNpdG9yMTIz",
            dataSyncId = "104284111||109823456",
            pageId = "104284111",
            authUser = "2",
            clientVersion = "1.20260415.03.00",
        )

        assertEquals("Cgt2aXNpdG9yMTIz", YouTube.visitorData)
        assertEquals("109823456", YouTube.dataSyncId)
        assertEquals("104284111", YouTube.pageId)
        assertEquals("2", YouTube.authUser)
        assertEquals("1.20260415.03.00", YouTube.clientVersion)

        // 2. Switching to a Personal Gmail Account (pageId = null) must clear YouTube.pageId
        YouTube.adoptSessionScope(
            visitorData = "Cgt2aXNpdG9yNDU2",
            dataSyncId = "109823456||",
            pageId = null,
            authUser = "0",
            clientVersion = "1.20260416.01.00",
        )

        assertEquals("Cgt2aXNpdG9yNDU2", YouTube.visitorData)
        assertEquals("109823456", YouTube.dataSyncId)
        assertNull(YouTube.pageId)
        assertEquals("0", YouTube.authUser)
        assertEquals("1.20260416.01.00", YouTube.clientVersion)
    }
}
