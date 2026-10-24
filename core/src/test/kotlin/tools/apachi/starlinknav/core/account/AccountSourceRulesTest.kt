package tools.apachi.starlinknav.core.account

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the rules of the Starlink sign-in feature in the sources that carry them (the app module is Android and cannot run
 * unit tests here, so the rules are checked on the text): no bridge from the page to the app, no script run in the page, no
 * logging anywhere in the feature, and the WebView's hardening switches in place. Comments are not code and are skipped.
 */
class AccountSourceRulesTest {
    private val appAccount = File("../app/src/main/kotlin/tools/apachi/starlinknav/account")
    private val coreAccount = File("src/main/kotlin/tools/apachi/starlinknav/core/account")

    /** The file's code without comments (block, line and trailing ones). */
    private fun stripped(f: File): String {
        var inBlock = false
        return f.readLines().filter { raw ->
            val t = raw.trim()
            when {
                inBlock -> { if ("*/" in t) inBlock = false; false }
                t.startsWith("/*") -> { if ("*/" !in t) inBlock = true; false }
                t.startsWith("//") -> false
                else -> true
            }
        }.joinToString("\n") { it.substringBefore(" // ") }
    }

    private fun kotlinFiles(dir: File): List<File> {
        assertTrue(dir.isDirectory, "${dir.absolutePath} (tests run from the module directory)")
        return dir.walk().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun code(dir: File): Map<String, String> = kotlinFiles(dir).associate { it.name to stripped(it) }

    @Test
    fun thePageGetsNoBridgeAndRunsNoScriptOfOurs() {
        val files = code(appAccount)
        assertTrue(files.isNotEmpty())
        for ((name, text) in files) {
            for (forbidden in listOf("addJavascriptInterface", "evaluateJavascript", "javascript:", "JavascriptInterface", "WebMessage", "postWebMessage", "addDocumentStartJavaScript", "setWebContentsDebuggingEnabled")) {
                assertTrue(forbidden !in text, "$name uses $forbidden")
            }
        }
    }

    @Test
    fun nothingInTheFeatureLogsOrPrints() {
        for (dir in listOf(appAccount, coreAccount)) {
            for ((name, text) in code(dir)) {
                for (forbidden in listOf("android.util.Log", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "Log.v(", "println(", "print(", "printStackTrace", "Timber", "Logger", "HttpLoggingInterceptor", "addInterceptor", "addNetworkInterceptor")) {
                    assertTrue(forbidden !in text, "$name uses $forbidden")
                }
            }
        }
    }

    @Test
    fun theWebViewIsHardened() {
        val page = code(appAccount).getValue("StarlinkSignInPage.kt")
        for (required in listOf(
            "allowFileAccess = false", "allowContentAccess = false", "javaScriptCanOpenWindowsAutomatically = false",
            "setSupportMultipleWindows(false)", "MIXED_CONTENT_NEVER_ALLOW", "LOAD_NO_CACHE", "safeBrowsingEnabled = true",
            "setAcceptThirdPartyCookies(web, false)", "setGeolocationEnabled(false)", "StarlinkHosts.allows", "handler.cancel()",
            "request.deny()", "onShowFileChooser", "onCreateWindow", "setDownloadListener", "removeAllCookies", "deleteAllData", "clearCache(true)",
        )) assertTrue(required in page, "StarlinkSignInPage.kt lacks $required")
        // JavaScript is on, and only here.
        assertEquals(1, Regex("javaScriptEnabled = true").findAll(page).count())
    }

    @Test
    fun theCookieStoreIsReadOnlyByTheSignInPage() {
        val readers = kotlinFiles(File("../app/src/main/kotlin"))
            .filter { stripped(it).let { t -> "getCookie(" in t || "CookieManager" in t } }
            .map { it.name }.toSortedSet()
        assertEquals(sortedSetOf("StarlinkSignInPage.kt"), readers)
    }

    @Test
    fun theCloudIsReachedFromTheFlowOnly() {
        // StarlinkCloud is constructed in AccountEnableFlow (after the consent) and nowhere else in the app or the tools.
        val users = listOf("../app/src/main/kotlin", "../tools/src/main/kotlin", "src/main/kotlin")
            .flatMap { kotlinFiles(File(it)) }
            .filter { it.name != "StarlinkCloud.kt" && "StarlinkCloud(" in stripped(it) }
            .map { it.name }
        assertEquals(listOf("AccountEnableFlow.kt"), users)
    }
}
