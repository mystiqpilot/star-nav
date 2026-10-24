package tools.apachi.starlinknav.core.account

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The navigation allow-list of the sign-in WebView: https on the default port to `starlink.com` or one of its sub-domains
 * (`www`, `api`, ...), nothing else. Not `http:`, not another site whose name merely contains it (`evilstarlink.com`,
 * `starlink.com.evil.example`), not a URL with a user name in front (`https://starlink.com@evil.example/`), not `javascript:`,
 * `intent:`, `file:`, `data:` or `about:`.
 */
object StarlinkHosts {
    fun allows(url: String): Boolean = url.toHttpUrlOrNull()?.let(::allows) ?: false

    fun allows(url: HttpUrl): Boolean =
        url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            (url.host == "starlink.com" || url.host.endsWith(".starlink.com"))

    /** The page the sign-in starts from; it redirects through Starlink's identity provider and returns here when signed in. */
    const val LOGIN_URL = "https://starlink.com/account"

    /** Whether [url] is the account page the sign-in ends on (the page Starlink returns to after the identity provider). */
    fun isAccountPage(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return allows(u) && (u.host == "starlink.com" || u.host == "www.starlink.com") &&
            (u.encodedPath == "/account" || u.encodedPath.startsWith("/account/"))
    }
}
