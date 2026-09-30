package com.antigravity.client

import com.antigravity.client.data.remote.NetworkClient
import com.antigravity.client.security.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClientTest {

    @Test
    fun testNormalizeBaseUrl_emptyAndBlank() {
        assertEquals(TokenStore.DEFAULT_SERVER_URL, NetworkClient.normalizeBaseUrl(""))
        assertEquals(TokenStore.DEFAULT_SERVER_URL, NetworkClient.normalizeBaseUrl("   "))
    }

    @Test
    fun testNormalizeBaseUrl_http() {
        assertEquals("http://127.0.0.1:8765", NetworkClient.normalizeBaseUrl("http://127.0.0.1:8765"))
        assertEquals("http://127.0.0.1:8765", NetworkClient.normalizeBaseUrl("http://127.0.0.1:8765/"))
        assertEquals("http://127.0.0.1:8765", NetworkClient.normalizeBaseUrl("  http://127.0.0.1:8765///  "))
    }

    @Test
    fun testNormalizeBaseUrl_https() {
        assertEquals("https://api.example.com", NetworkClient.normalizeBaseUrl("https://api.example.com"))
        assertEquals("https://api.example.com", NetworkClient.normalizeBaseUrl("https://api.example.com/"))
        assertEquals("https://api.example.com:8444", NetworkClient.normalizeBaseUrl("https://api.example.com:8444/"))
    }

    @Test
    fun testNormalizeBaseUrl_caseInsensitiveScheme() {
        // Mobile keyboards often capitalize the first character
        assertEquals("https://api.example.com", NetworkClient.normalizeBaseUrl("Https://api.example.com"))
        assertEquals("https://api.example.com:8444", NetworkClient.normalizeBaseUrl("HTTPS://api.example.com:8444/"))
        assertEquals("http://api.example.com:8765", NetworkClient.normalizeBaseUrl("Http://api.example.com:8765"))
    }

    @Test
    fun testNormalizeBaseUrl_noSchemeDefaultsToHttp() {
        assertEquals("http://192.168.1.100:8765", NetworkClient.normalizeBaseUrl("192.168.1.100:8765"))
        assertEquals("http://api.example.com", NetworkClient.normalizeBaseUrl("api.example.com/"))
    }

    @Test
    fun testNormalizeBaseUrl_withSubpath() {
        assertEquals("https://api.example.com/proxy", NetworkClient.normalizeBaseUrl("https://api.example.com/proxy/"))
    }
}
