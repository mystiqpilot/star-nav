package tools.apachi.starlinknav.core.account

/** Whether the dish being switched on is one of the signed-in account's (the service-lines call, best effort). */
enum class Ownership { MATCH, MISMATCH, UNKNOWN }

/**
 * What the whole sign-in operation came to. [email] is the account the user signed in as, for the screen to show during the
 * session; it is never printed by [toString].
 */
class AccountResult(val outcome: CloudOutcome, val email: String?, val ownership: Ownership) {
    override fun toString() = "AccountResult($outcome, $ownership)"
}

/**
 * The opt-in "enable local location through my Starlink account" operation as a sequence that cannot be entered at the
 * wrong end: the cloud is contacted only inside [finishSignIn], which works only after the consent ([accept]) and only once;
 * [begin] and [accept] send nothing. The session cookies live in a [SessionCookies] owned by the flow and are cleared when
 * the operation ends, whatever came of it ([finishSignIn]'s `finally`, [cancel]). Nothing persists.
 *
 * Plain JVM (no Android): the screen's consent gate is tested here. The app drives the WebView sign-in between [accept] and
 * [finishSignIn] and hands over the cookie text the WebView holds.
 */
class AccountEnableFlow(
    private val endpoints: CloudEndpoints = CloudEndpoints.PRODUCTION,
    private val userAgent: String = StarlinkCloud.DEFAULT_USER_AGENT,
) {
    enum class Stage {
        /** Nothing started. */
        IDLE,

        /** The consent screen is up; nothing has been sent. */
        CONSENT,

        /** The user agreed: the sign-in page may open. Still nothing sent by this app. */
        SIGN_IN,

        /** Signed in: the cloud calls are running. */
        WORKING,

        /** Over; the cookies are gone. */
        DONE,
    }

    private val lock = Any()
    private var cookies: SessionCookies? = null
    private var cloud: StarlinkCloud? = null

    @Volatile var stage: Stage = Stage.IDLE
        private set

    /** The dish the operation is for (its own `device_info.id`, which must start with "ut"). */
    @Volatile var targetId: String? = null
        private set

    /** Shows the consent screen for [targetId]. False (and no change) when it is not a terminal id or an operation is already running. */
    fun begin(targetId: String): Boolean = synchronized(lock) {
        if ((stage != Stage.IDLE && stage != Stage.DONE) || !TerminalIds.isTargetId(targetId)) return false
        this.targetId = targetId
        stage = Stage.CONSENT
        true
    }

    /** The user agreed on the consent screen. Only from [Stage.CONSENT]. */
    fun accept(): Boolean = synchronized(lock) {
        if (stage != Stage.CONSENT) return false
        val jar = SessionCookies()
        cookies = jar
        cloud = StarlinkCloud(jar, endpoints, userAgent)
        stage = Stage.SIGN_IN
        true
    }

    /**
     * Leaves the operation before the cloud calls start (the user backed out, the page was left): wipes the session and returns
     * to idle. While the calls run ([Stage.WORKING]) it does nothing: they finish by themselves and wipe the session then.
     * After the result was shown it just goes back to idle.
     */
    fun cancel() = synchronized(lock) {
        if (stage == Stage.WORKING) return@synchronized
        wipeLocked()
        stage = Stage.IDLE
        targetId = null
    }

    /**
     * The user signed in on Starlink's page; [cookieHeader] is the cookie text the WebView holds for it. Checks the session,
     * (best effort) that the dish is the account's, and sends the write, blocking: run it on a background thread. The session
     * is wiped when it returns. Refuses with [IllegalStateException] unless the consent was given ([accept]) and this is the
     * first call for it.
     */
    fun finishSignIn(cookieHeader: String): AccountResult {
        val (jar, api, target) = synchronized(lock) {
            check(stage == Stage.SIGN_IN) { "the cloud is reached only after the consent and the sign-in" }
            stage = Stage.WORKING
            Triple(cookies!!, cloud!!, targetId!!)
        }
        try {
            jar.importCookieHeader(cookieHeader, endpoints.cookieDomain, endpoints.secureCookies)
            if (!jar.contains(StarlinkCloud.SESSION_COOKIE)) {
                return AccountResult(CloudOutcome.SessionExpired("no Starlink sign-in found"), null, Ownership.UNKNOWN)
            }
            val account = when (val s = api.checkSession()) {
                is SessionCheck.Live -> s.account
                SessionCheck.Dead -> return AccountResult(CloudOutcome.SessionExpired("Starlink did not accept the sign-in"), null, Ownership.UNKNOWN)
                is SessionCheck.Unreachable -> return AccountResult(CloudOutcome.Other(s.detail), null, Ownership.UNKNOWN)
            }
            val ownership = when (val t = api.listTerminals()) {
                is TerminalsResult.Listed -> if (t.terminals.any { TerminalIds.same(it.id, target) }) Ownership.MATCH else Ownership.MISMATCH
                else -> Ownership.UNKNOWN
            }
            return AccountResult(api.setLocalLocation(target), account.email, ownership)
        } finally {
            synchronized(lock) {
                wipeLocked()
                stage = Stage.DONE
            }
        }
    }

    private fun wipeLocked() {
        cloud?.wipe()
        cookies?.clear()
        cloud = null
        cookies = null
    }

    override fun toString() = "AccountEnableFlow($stage)"
}
