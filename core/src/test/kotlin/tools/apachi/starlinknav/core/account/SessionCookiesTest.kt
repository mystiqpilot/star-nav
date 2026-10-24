package tools.apachi.starlinknav.core.account

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionCookiesTest {
    @Test
    fun theWebViewsCookieTextBecomesDomainCookiesForTheWholeSite() {
        val jar = SessionCookies()
        // '=' inside a value (padding), a cookie with no value, junk without '=', a name with a space.
        val taken = jar.importCookieHeader("Starlink.Com.Sso=abc==; Starlink.Com.Access.V1=tok.en; empty=; junk; bad name=x")
        assertEquals(3, taken)
        assertEquals(3, jar.size)
        assertTrue(jar.contains("Starlink.Com.Sso"))
        val web = jar.loadForRequest("https://starlink.com/api/x".toHttpUrl())
        val api = jar.loadForRequest("https://api.starlink.com/auth-rp/auth/user".toHttpUrl())
        assertEquals(3, web.size)
        assertEquals(3, api.size, "a domain cookie goes to the sub-domain too")
        assertEquals("abc==", web.first { it.name == "Starlink.Com.Sso" }.value)
        assertTrue(jar.loadForRequest("http://starlink.com/".toHttpUrl()).isEmpty(), "Secure cookies never go over http")
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty(), "and never to another site")
        assertTrue(jar.loadForRequest("https://notstarlink.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun aResponseCanReplaceAndExpireACookie() {
        val jar = SessionCookies()
        jar.importCookieHeader("Starlink.Com.Access.V1=old")
        val url = "https://starlink.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "Starlink.Com.Access.V1=new; Domain=starlink.com; Path=/; Secure")!!))
        assertEquals(listOf("new"), jar.loadForRequest(url).map { it.value })
        jar.saveFromResponse(url, listOf(Cookie.parse(url, "Starlink.Com.Access.V1=; Domain=starlink.com; Path=/; Max-Age=0")!!))
        assertEquals(0, jar.size)
    }

    @Test
    fun clearForgetsEverythingAndToStringNeverShowsAValue() {
        val jar = SessionCookies()
        jar.importCookieHeader("Starlink.Com.Sso=SECRETVALUE123")
        assertFalse("SECRETVALUE123" in jar.toString() || "Sso" in jar.toString())
        assertEquals("SessionCookies(1 cookies)", jar.toString())
        jar.clear()
        assertEquals(0, jar.size)
        assertFalse(jar.contains("Starlink.Com.Sso"))
    }
}
