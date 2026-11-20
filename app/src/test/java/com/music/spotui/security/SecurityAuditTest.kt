package com.music.spotui.security

import app.wavebits.extensions.ExtensionRuntime
import app.wavebits.extensions.PageNetwork
import com.music.spotui.data.BetaLogs
import com.music.spotui.data.api.InviteLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditTest {

    @Test
    fun betaLogsRedactsTokensCookiesAndSapisidHash() {
        val raw = """
            Authorization: Bearer secret_jwt_token_123456
            Authorization: SAPISIDHASH 1700000000_abcdef0123456789abcdef0123456789abcdef01
            Cookie: SAPISID=my_secret_sapisid; __Secure-3PAPISID=secret3p; LOGIN_INFO=login_secret
            {"discord_token":"mfa.secret_discord_token_value","email":"user@example.com"}
        """.trimIndent()

        val sanitized = BetaLogs.sanitize(raw)
        assertFalse(sanitized.contains("secret_jwt_token_123456"))
        assertFalse(sanitized.contains("abcdef0123456789abcdef0123456789abcdef01"))
        assertFalse(sanitized.contains("my_secret_sapisid"))
        assertFalse(sanitized.contains("secret3p"))
        assertFalse(sanitized.contains("login_secret"))
        assertFalse(sanitized.contains("mfa.secret_discord_token_value"))
        assertFalse(sanitized.contains("user@example.com"))
        assertTrue(sanitized.contains("Bearer <hidden>"))
        assertTrue(sanitized.contains("SAPISIDHASH <hidden>"))
        assertTrue(sanitized.contains("SAPISID=<hidden>"))
        assertTrue(sanitized.contains("\"discord_token\":\"<hidden>\""))
    }

    @Test
    fun extensionRuntimeDetectsPrivateAndLoopbackHosts() {
        assertTrue(ExtensionRuntime.isPrivate("localhost"))
        assertTrue(ExtensionRuntime.isPrivate("sub.localhost"))
        assertTrue(ExtensionRuntime.isPrivate("printer.local"))
        assertTrue(ExtensionRuntime.isPrivate("127.0.0.1"))
        assertTrue(ExtensionRuntime.isPrivate("10.0.0.1"))
        assertTrue(ExtensionRuntime.isPrivate("192.168.1.50"))
        assertTrue(ExtensionRuntime.isPrivate("172.16.0.1"))
        assertTrue(ExtensionRuntime.isPrivate("169.254.169.254"))
        assertTrue(ExtensionRuntime.isPrivate("[::1]"))
        assertFalse(ExtensionRuntime.isPrivate("api.github.com"))
        assertFalse(ExtensionRuntime.isPrivate("discord.com"))
    }

    @Test
    fun pageNetworkBlocksSpotifyPrivateAndNonHttpsRequests() {
        val allowedHosts = listOf("cdn.example.com")
        assertTrue(PageNetwork.allows("https://cdn.example.com/image.png", "GET", allowedHosts, networkGranted = true, anyGranted = false))
        assertFalse(PageNetwork.allows("http://cdn.example.com/image.png", "GET", allowedHosts, networkGranted = true, anyGranted = false))
        assertFalse(PageNetwork.allows("https://cdn.example.com/image.png", "POST", allowedHosts, networkGranted = true, anyGranted = false))
        assertFalse(PageNetwork.allows("https://127.0.0.1/secret", "GET", listOf("*"), networkGranted = true, anyGranted = true))
        assertFalse(PageNetwork.allows("https://open.spotify.com/track/123", "GET", listOf("*"), networkGranted = true, anyGranted = true))
    }

    @Test
    fun inviteLinksRejectsOversizedOrCleartextShortLinks() {
        assertFalse(InviteLinks.handle("http://spotify.link/abc12345"))
        val validInvite = InviteLinks.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?pt=valid_token_123")
        assertNotNull(validInvite)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", validInvite?.playlistId)
        assertEquals("valid_token_123", validInvite?.token)

        val oversized = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?pt=" + "a".repeat(3000)
        assertNull(InviteLinks.parse(oversized))
    }

    @Test
    fun extensionRuntimeAllowsPublicIpv6AndBlocksPrivateIpv4AndIpv6Addresses() {
        // Public IPv4 and IPv6 addresses MUST be allowed on dual-stack/5G networks
        assertFalse(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("1.1.1.1")))
        assertFalse(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("2606:4700:4700::1111")))
        assertFalse(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("2001:4860:4860::8888")))

        // Private, loopback, link-local, CGNAT, ULA, and IPv4-mapped private addresses MUST be blocked
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("127.0.0.1")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("10.0.0.5")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("100.64.0.1")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("169.254.169.254")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("::1")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("fc00::1")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("fd12:3456:789a::1")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("fe80::1")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("::ffff:127.0.0.1")))
        assertTrue(ExtensionRuntime.isPrivateAddress(java.net.InetAddress.getByName("::ffff:192.168.1.1")))
    }
}
