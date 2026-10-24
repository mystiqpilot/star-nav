package tools.apachi.starlinknav.core.account

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StarlinkHostsTest {
    @Test
    fun onlyHttpsStarlinkDotComAndItsSubdomainsMayBeNavigatedTo() {
        for (ok in listOf(
            "https://starlink.com/account",
            "https://www.starlink.com/account",
            "https://api.starlink.com/auth-rp/auth/user",
            "https://starlink.com/api/auth/connect/authorize?client_id=x&state=y",
            "https://a.b.starlink.com/",
            "https://STARLINK.com/Account",
            "https://starlink.com:443/account",
        )) assertTrue(StarlinkHosts.allows(ok), ok)
        for (bad in listOf(
            "http://starlink.com/account",
            "https://evilstarlink.com/",
            "https://starlink.com.evil.example/",
            "https://notstarlink.com/",
            "https://starlink.com@evil.example/",
            "https://user:pw@starlink.com/",
            "https://evil.example/?u=https://starlink.com",
            "https://starlink.com:8443/",
            "https://starlink.net/",
            "https://google.com/",
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///data/data/tools.apachi.starlinknav/",
            "data:text/html,hi",
            "about:blank",
            "content://x",
            "",
            "starlink.com",
        )) assertFalse(StarlinkHosts.allows(bad), bad)
    }

    @Test
    fun theSignInEndsOnTheAccountPageOnly() {
        assertTrue(StarlinkHosts.isAccountPage("https://starlink.com/account"))
        assertTrue(StarlinkHosts.isAccountPage("https://starlink.com/account/overview?x=1"))
        assertTrue(StarlinkHosts.isAccountPage("https://www.starlink.com/account"))
        assertFalse(StarlinkHosts.isAccountPage("https://starlink.com/accountant"))
        assertFalse(StarlinkHosts.isAccountPage("https://starlink.com/api/auth/connect/authorize"))
        assertFalse(StarlinkHosts.isAccountPage("https://api.starlink.com/account"))
        assertFalse(StarlinkHosts.isAccountPage("https://evil.example/account"))
        assertTrue(StarlinkHosts.allows(StarlinkHosts.LOGIN_URL))
    }
}
