package tools.apachi.starlinknav.core.account

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * The Starlink sign-in session, in memory and nowhere else: an OkHttp cookie jar that is never written to disk and whose
 * [toString] says how many cookies it holds, never which or what. [clear] forgets everything; the account flow calls it when
 * the operation ends, whatever came of it.
 *
 * The cookies are a whole-account credential (billing, plans, address). Nothing here prints, logs or stores one.
 */
class SessionCookies : CookieJar {
    private val lock = Any()
    private val store = LinkedHashMap<String, Cookie>()

    /** Cookies a response sets (a refreshed access cookie); an expired one removes its entry. */
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (c in cookies) {
                if (c.expiresAt <= now) store.remove(keyOf(c)) else store[keyOf(c)] = c
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(lock) { store.values.filter { it.expiresAt > now && it.matches(url) } }
    }

    /**
     * Takes the `Cookie` header text a WebView's cookie manager gives for a site (`name=value; name2=value2`, no attributes)
     * and makes domain cookies of [domain] from it (so they go to its sub-domains too), `Secure` when [secure]. Entries with
     * no name, or a value a header cannot carry, are skipped. Returns how many were taken. The text is not kept.
     */
    fun importCookieHeader(header: String, domain: String = DEFAULT_DOMAIN, secure: Boolean = true): Int {
        var taken = 0
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty() || !name.all(::isTokenChar) || !value.all(::isValueChar)) continue
            val cookie = Cookie.Builder().name(name).value(value).domain(domain).path("/").httpOnly().apply { if (secure) secure() }.build()
            synchronized(lock) { store[keyOf(cookie)] = cookie }
            taken++
        }
        return taken
    }

    /** Whether a cookie called [name] is held (names are not secrets; values are never given out). */
    fun contains(name: String): Boolean = synchronized(lock) { store.values.any { it.name == name } }

    val size: Int get() = synchronized(lock) { store.size }

    fun clear() = synchronized(lock) { store.clear() }

    /** [text] with every cookie value held here (long enough to be a secret, not a flag) replaced by "[cookie]". */
    fun redact(text: String): String {
        val values = synchronized(lock) { store.values.map { it.value }.filter { it.length >= MIN_SECRET_LENGTH } }
        return values.fold(text) { acc, v -> acc.replace(v, "[cookie]") }
    }

    override fun toString() = "SessionCookies($size cookies)"

    private fun keyOf(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

    private fun isTokenChar(c: Char) = c in '!'..'~' && c !in "()<>@,;:\\\"/[]?={}"

    private fun isValueChar(c: Char) = c in '!'..'~' && c != ';'

    companion object {
        const val DEFAULT_DOMAIN = "starlink.com"
        private const val MIN_SECRET_LENGTH = 8
    }
}
