package tools.apachi.starlinknav.account

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Message
import tools.apachi.starlinknav.core.account.StarlinkCloud
import tools.apachi.starlinknav.core.account.StarlinkHosts
import java.io.ByteArrayInputStream

/**
 * Starlink's own sign-in page in a WebView, and nothing more. The user types the password and the verification code into
 * Starlink's page; this class never sees them.
 *
 * What this class does NOT do, on purpose (a rule of the feature, checked by `AccountSourceRulesTest`): it has no bridge from
 * the page to the app, it runs no script of its own in the page and reads no field of it. JavaScript is switched on only so
 * that Starlink's page works. The only thing read is the browser's cookie store, once the page is the signed-in account page.
 *
 * Hardening (all in [create]): navigation only to https `starlink.com` and its sub-domains ([StarlinkHosts]), enforced for
 * redirects, links and form posts alike; no file or content access, no pop-up windows, no file chooser, no geolocation, no
 * camera or microphone, no downloads, no mixed content, no third-party cookies, no HTTP cache, TLS errors always cancel
 * the page, Safe Browsing on. The cookies, storage and cache are wiped when the page is created, when the cookies were taken
 * and when it is disposed ([wipe]).
 */
class StarlinkSignInPage(context: Context, private val onSignedIn: (String) -> Unit) {
    val view: WebView

    @Volatile private var delivered = false
    @Volatile private var disposed = false

    init {
        // A fresh sign-in every time: whatever an earlier one (or a crash) left in the WebView is gone before the page loads.
        view = create(context)
        wipe(view) { if (!disposed) view.loadUrl(StarlinkHosts.LOGIN_URL) }
    }

    /**
     * Takes the session cookies if the user is signed in (the browser's cookie store has Starlink's session cookie) and hands
     * them to [onSignedIn] once. Reads the cookie store only, not the page. False when no sign-in is there (yet).
     */
    fun captureNow(): Boolean {
        if (delivered) return true
        val header = cookieHeader() ?: return false
        delivered = true
        onSignedIn(header)
        return true
    }

    /** Stops the page and drops everything it stored. */
    fun dispose() {
        disposed = true
        view.stopLoading()
        wipe(view)
        view.destroy()
    }

    private fun cookieHeader(): String? {
        val cookies = CookieManager.getInstance()
        val site = cookies.getCookie(SITE).orEmpty()
        if (site.split(';').none { it.trim().startsWith("${StarlinkCloud.SESSION_COOKIE}=") }) return null
        val api = cookies.getCookie(API).orEmpty()
        return if (api.isEmpty()) site else "$site; $api"
    }

    private inner class Client : WebViewClient() {
        // Redirects and links. (Not called for POST requests: shouldInterceptRequest covers those.)
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !StarlinkHosts.allows(request.url.toString())

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (request.isForMainFrame && !StarlinkHosts.allows(request.url.toString())) {
                WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()

        override fun onPageFinished(view: WebView, url: String) {
            if (StarlinkHosts.isAccountPage(url)) captureNow()
        }

        // Single-page navigations that do not finish a page load.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (StarlinkHosts.isAccountPage(url)) captureNow()
        }
    }

    /** Refuses everything a page can ask of the app: windows, files, location, camera, microphone. */
    private class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean = false

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
            filePathCallback.onReceiveValue(null)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    @SuppressLint("SetJavaScriptEnabled") // Starlink's own sign-in page does not work without it; nothing of ours runs in it.
    private fun create(context: Context): WebView {
        val web = WebView(context)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { _, _, _, _, _ -> }
        return web
    }

    companion object {
        private const val SITE = "https://starlink.com"
        private const val API = "https://api.starlink.com"

        /**
         * Empties the WebView's cookies, web storage, cache and history (all of this app's WebView data); [then] runs on the
         * calling thread's looper when the cookies are gone (they are removed asynchronously).
         */
        fun wipe(view: WebView?, then: () -> Unit = {}) {
            // A device without a WebView provider throws here; the startup leftover-wipe must not crash the app.
            try {
                WebStorage.getInstance().deleteAllData()
                view?.clearCache(true)
                view?.clearHistory()
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies {
                    cookies.flush()
                    then()
                }
            } catch (_: Throwable) {
                then()
            }
        }
    }
}
