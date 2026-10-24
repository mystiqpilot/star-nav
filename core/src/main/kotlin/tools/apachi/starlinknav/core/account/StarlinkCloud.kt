package tools.apachi.starlinknav.core.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.CookieJar
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tools.apachi.starlinknav.core.dish.proto.DeviceRequest
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.GrpcErrors
import tools.apachi.starlinknav.core.dish.GrpcFrames
import tools.apachi.starlinknav.core.dish.GrpcProtocolException
import tools.apachi.starlinknav.core.dish.GrpcStatus
import tools.apachi.starlinknav.core.dish.LocationRequestMode
import tools.apachi.starlinknav.core.dish.describe
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.dish.proto.DishConfig
import tools.apachi.starlinknav.core.dish.proto.DishSetConfigRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Where the cloud calls go. [PRODUCTION] is Starlink's own sites; [at] points every call at one base URL (a local test server),
 * with the session cookies scoped to its host.
 */
class CloudEndpoints(
    /** `https://starlink.com/`: the device API, the token refresh, the account's service lines. */
    val site: HttpUrl,
    /** `https://api.starlink.com/`: the account probe. */
    val api: HttpUrl,
    /** The domain the session cookies belong to. */
    val cookieDomain: String,
    /** Whether the cookies are `Secure` (https). */
    val secureCookies: Boolean,
) {
    val origin: String get() = site.toString().trimEnd('/')

    internal fun handle(): HttpUrl = site.resolve("api/SpaceX.API.Device.Device/Handle")!!

    internal fun authUser(): HttpUrl = api.resolve("auth-rp/auth/user")!!

    internal fun refreshToken(): HttpUrl = site.resolve("api/auth/auth/refresh-token")!!

    internal fun serviceLines(): HttpUrl = site.resolve("api/webagg/v2/accounts/service-lines")!!.newBuilder()
        .addQueryParameter("limit", "100")
        .addQueryParameter("page", "0")
        .addQueryParameter("isConverting", "false")
        .addQueryParameter("serviceAddressId", "")
        .addQueryParameter("onlyActive", "false")
        .addQueryParameter("searchString", "")
        .addQueryParameter("onlyNoUts", "false")
        .build()

    companion object {
        val PRODUCTION = CloudEndpoints("https://starlink.com/".toHttpUrl(), "https://api.starlink.com/".toHttpUrl(), "starlink.com", true)

        /** Every call to [base] (tests with a local server). */
        fun at(base: HttpUrl) = CloudEndpoints(base, base, base.host, base.isHttps)
    }
}

/** What one cloud write came to. Texts are the server's own words, scrubbed of anything that looks like a cookie or token. */
sealed interface CloudOutcome {
    /** The cloud accepted the write. [updatedMode] is the `location_request_mode` it reports back (LOCAL = 1); null when the reply carried none. */
    data class Ok(val updatedMode: Int?) : CloudOutcome {
        /** The reply itself says the mode is now LOCAL. */
        val confirmedLocal: Boolean get() = updatedMode == LocationRequestMode.LOCAL
    }

    /** The sign-in was not accepted (HTTP 401/403, a redirect to the login page, or gRPC UNAUTHENTICATED), also after the one token refresh. */
    data class SessionExpired(val detail: String) : CloudOutcome

    /** The cloud cannot reach the dish (DEVICE_NOT_CONNECTED / DEVICE_CONNECTION_NOT_FOUND). */
    data class DeviceOffline(val detail: String) : CloudOutcome

    /** The cloud answered and refused: [grpcStatus] (null when the refusal is in the payload) and its message. */
    data class Refused(val grpcStatus: Int?, val message: String) : CloudOutcome

    /** The dish id is not a Starlink terminal id (it must start with "ut"); nothing was sent. */
    data object InvalidTarget : CloudOutcome

    /** The request went out and the connection ended without an answer: the write may or may not have been applied. */
    data class NoAnswer(val detail: String) : CloudOutcome

    /** Anything else: no connection, an unexpected HTTP status or an unreadable reply. */
    data class Other(val detail: String) : CloudOutcome
}

/** The signed-in account as the account probe describes it; held in memory for the session only. */
class AccountInfo(val email: String?) {
    override fun toString() = "AccountInfo(…)"
}

sealed interface SessionCheck {
    /** The cookies are a live session. */
    class Live(val account: AccountInfo) : SessionCheck {
        override fun toString() = "SessionCheck.Live"
    }

    /** 401/403 or a redirect to the login page: not signed in (any more). */
    data object Dead : SessionCheck

    data class Unreachable(val detail: String) : SessionCheck
}

/** One of the account's dishes. Identifiers of the user's hardware: kept in memory, not printed. */
class UserTerminal(val id: String, val serialNumber: String?) {
    override fun toString() = "UserTerminal(…)"
}

sealed interface TerminalsResult {
    class Listed(val terminals: List<UserTerminal>) : TerminalsResult {
        override fun toString() = "TerminalsResult.Listed(${terminals.size})"
    }

    data object SessionExpired : TerminalsResult

    data class Unavailable(val detail: String) : TerminalsResult
}

object TerminalIds {
    /** "ut01000000-00000000-00ABCDEF" and "01000000-00000000-00abcdef" are the same terminal. */
    fun normalize(id: String): String = id.trim().lowercase().removePrefix("ut").replace("-", "")

    fun same(a: String, b: String): Boolean = normalize(a).let { it.isNotEmpty() && it == normalize(b) }

    /** A dish's own id as the device API's `target_id` needs it: "ut" and the terminal id, sane characters only. */
    fun isTargetId(id: String): Boolean = id.startsWith("ut") && id.length in 4..64 && id.all { it.isLetterOrDigit() || it == '-' }
}

/**
 * The app's only contact with Starlink's cloud: the user's own sign-in session (cookies handed in through [cookies], which the
 * account flow keeps in memory and wipes) used for one write on the user's own dish, `location_request_mode = LOCAL`.
 *
 * Starlink publishes no API for this; these are the endpoints its web site uses.
 * Blocking calls (OkHttp): run them on a background thread. Redirects are not followed (a redirect to the login page means "not
 * signed in", and the cookies go nowhere unexpected), and nothing is retried that could have reached the dish.
 *
 * Secrets: the cookies live in [cookies]; the access token, if the cookies alone were refused and the token refresh gave one,
 * is held in a private field. Neither appears in [toString], an outcome, an exception text or any log: [scrub] removes
 * them from the server's words and from network error texts. [wipe] drops the token.
 */
class StarlinkCloud(
    cookies: CookieJar,
    private val endpoints: CloudEndpoints = CloudEndpoints.PRODUCTION,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    private val jar = cookies
    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    @Volatile private var bearer: String? = null
    private var refreshTried = false

    override fun toString() = "StarlinkCloud"

    /**
     * Forgets the access token and closes the connections (the cookies are the jar's: the account flow clears it). Calls made
     * after this start from nothing; the instance is meant to be dropped.
     */
    fun wipe() {
        bearer = null
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    /**
     * `GET https://api.starlink.com/auth-rp/auth/user`: 200 = a live session (with the account's e-mail, if the reply names
     * one), 401/403 or a redirect = dead. A refused cookie-only call gets one token refresh and one retry with the token.
     */
    @Synchronized
    fun checkSession(): SessionCheck = guarded({ SessionCheck.Unreachable(it) }) {
        val r = withRefresh(dead = { isAuthRefusal(it.code) }) { execute(Request.Builder().url(endpoints.authUser()).get()) }
        when {
            r.code == 200 -> SessionCheck.Live(AccountInfo(emailIn(r.body)))
            isAuthRefusal(r.code) -> SessionCheck.Dead
            else -> SessionCheck.Unreachable("HTTP ${r.code}")
        }
    }

    /**
     * The account's dishes (best effort): the service-lines call, to see that the dish belongs to this account.
     * Anything unexpected is [TerminalsResult.Unavailable]; it never decides alone.
     */
    @Synchronized
    fun listTerminals(): TerminalsResult = guarded({ TerminalsResult.Unavailable(it) }) {
        val r = withRefresh(dead = { isAuthRefusal(it.code) }) { execute(Request.Builder().url(endpoints.serviceLines()).get()) }
        when {
            r.code == 200 -> terminalsIn(r.body)?.let { TerminalsResult.Listed(it) } ?: TerminalsResult.Unavailable("unreadable reply")
            isAuthRefusal(r.code) -> TerminalsResult.SessionExpired
            else -> TerminalsResult.Unavailable("HTTP ${r.code}")
        }
    }

    /**
     * The one write: `DeviceRequest{ target_id, dish_set_config { dish_config { location_request_mode = LOCAL,
     * apply_location_request_mode = true } } }` as a unary gRPC-Web call to the device API's `Handle`. Cookies first; on a
     * 401 / UNAUTHENTICATED one token refresh and one retry with `Authorization: Bearer`. A connection that ends without an
     * answer is [CloudOutcome.NoAnswer] and is never repeated.
     */
    @Synchronized
    fun setLocalLocation(targetId: String): CloudOutcome {
        if (!TerminalIds.isTargetId(targetId)) return CloudOutcome.InvalidTarget
        val frame = localLocationFrame(targetId)
        return try {
            val r = withRefresh(dead = { classify(it) is CloudOutcome.SessionExpired }) { postHandle(frame) }
            classify(r)
        } catch (e: IOException) {
            val text = scrub("network: ${e.describe()}")
            if (GrpcErrors.neverReachedDevice(e)) CloudOutcome.Other(text) else CloudOutcome.NoAnswer(text)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            CloudOutcome.Other("error: ${e.javaClass.simpleName}")
        }
    }

    // ---- plumbing

    private class Reply(val code: Int, val headers: Headers, val body: ByteArray)

    private class HandleReply(val http: Int, val grpcStatus: Int?, val grpcMessage: String?, val message: ByteArray?, val unreadable: Boolean)

    private fun execute(builder: Request.Builder): Reply {
        builder.header("User-Agent", userAgent)
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        http.newCall(builder.build()).execute().use { resp -> return Reply(resp.code, resp.headers, resp.peekBody(MAX_BODY).bytes()) }
    }

    private fun postHandle(frame: ByteArray): HandleReply {
        val r = execute(
            Request.Builder().url(endpoints.handle())
                .header("Content-Type", GRPC_WEB_PROTO)
                .header("Accept", GRPC_WEB_PROTO)
                .header("X-Grpc-Web", "1")
                .header("Origin", endpoints.origin)
                .post(frame.toRequestBody(GRPC_WEB_PROTO.toMediaType())),
        )
        var status = r.headers["grpc-status"]?.toIntOrNull()
        var message = r.headers["grpc-message"]?.let(GrpcFrames::percentDecode)
        var data: ByteArray? = null
        if (r.code == 200 && r.body.isNotEmpty()) {
            try {
                val frames = GrpcFrames.decodeAll(r.body)
                for (f in frames.filter { it.isTrailers }) {
                    val t = GrpcFrames.parseTrailerBlock(f.payload)
                    t["grpc-status"]?.toIntOrNull()?.let { status = it }
                    t["grpc-message"]?.let { message = GrpcFrames.percentDecode(it) }
                }
                data = GrpcFrames.firstDataPayload(frames)
            } catch (e: GrpcProtocolException) {
                return HandleReply(r.code, status, message, null, unreadable = true)
            }
        }
        return HandleReply(r.code, status, message?.ifEmpty { null }, data, unreadable = false)
    }

    /** What a `Handle` reply means for the write. */
    private fun classify(r: HandleReply): CloudOutcome {
        val text = scrub(r.grpcMessage.orEmpty())
        return when {
            isAuthRefusal(r.http) -> CloudOutcome.SessionExpired("HTTP ${r.http}")
            r.grpcStatus == GrpcStatus.UNAUTHENTICATED -> CloudOutcome.SessionExpired("gRPC ${GrpcStatus.name(r.grpcStatus)}")
            isOffline(text) -> CloudOutcome.DeviceOffline(text)
            r.http != 200 -> CloudOutcome.Other("HTTP ${r.http}" + if (text.isNotEmpty()) ": $text" else "")
            r.unreadable -> CloudOutcome.Other("the reply was not gRPC-Web")
            r.grpcStatus == null -> CloudOutcome.Other("the reply carried no gRPC status")
            r.grpcStatus != GrpcStatus.OK -> CloudOutcome.Refused(r.grpcStatus, text)
            else -> classifyPayload(r.message)
        }
    }

    /** gRPC status 0: the payload may still carry a refusal (its own status, or the set-config error text). */
    private fun classifyPayload(message: ByteArray?): CloudOutcome {
        if (message == null) return CloudOutcome.Ok(null)
        val response = try {
            DeviceResponse.ADAPTER.decode(message)
        } catch (e: Exception) {
            return CloudOutcome.Other("the reply could not be decoded")
        }
        val inner = response.status
        if (inner != null && inner.code != 0) {
            val text = scrub(inner.message)
            return if (isOffline(text)) CloudOutcome.DeviceOffline(text) else CloudOutcome.Refused(inner.code, text)
        }
        val set = response.dish_set_config
        val error = set?.error.orEmpty()
        if (error.isNotEmpty()) {
            val text = scrub(error)
            return if (isOffline(text)) CloudOutcome.DeviceOffline(text) else CloudOutcome.Refused(null, text)
        }
        return CloudOutcome.Ok(set?.updated_dish_config?.location_request_mode)
    }

    private fun isAuthRefusal(code: Int) = code == 401 || code == 403 || code in 300..399

    private fun isOffline(text: String) =
        text.contains("DEVICE_NOT_CONNECTED", ignoreCase = true) || text.contains("DEVICE_CONNECTION_NOT_FOUND", ignoreCase = true)

    /**
     * Runs [call]; when [dead] says the cookies alone were refused, refreshes the access token once (at most once per
     * instance: a second refresh in quick succession is answered 401) and calls again, now with the token as a bearer.
     */
    private fun <T> withRefresh(dead: (T) -> Boolean, call: () -> T): T {
        val first = call()
        if (!dead(first) || refreshTried) return first
        refreshTried = true
        if (!refreshAccessToken()) return first
        return call()
    }

    /** `GET /api/auth/auth/refresh-token` -> `{accessToken, expiresIn, tokenType}`; keeps the token, puts it into the access cookie too. */
    private fun refreshAccessToken(): Boolean {
        val r = try {
            execute(Request.Builder().url(endpoints.refreshToken()).get())
        } catch (e: Exception) {
            return false
        }
        if (r.code != 200) return false
        val token = try {
            (Json.parseToJsonElement(r.body.decodeToString()) as? JsonObject)?.get("accessToken")?.let { (it as? JsonPrimitive)?.contentOrNull }
        } catch (e: Exception) {
            null
        }
        if (token.isNullOrEmpty() || !token.all { it in '!'..'~' && it != ';' }) return false
        bearer = token
        val cookie = Cookie.Builder().name(ACCESS_COOKIE).value(token).domain(endpoints.cookieDomain).path("/").httpOnly()
            .apply { if (endpoints.secureCookies) secure() }.build()
        jar.saveFromResponse(endpoints.site, listOf(cookie))
        return true
    }

    private inline fun <T> guarded(onFail: (String) -> T, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        onFail(scrub("network: ${e.describe()}"))
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        onFail("error: ${e.javaClass.simpleName}")
    }

    /** [text] with the access token and anything shaped like a Starlink cookie or an Authorization header removed, and cut short. */
    private fun scrub(text: String): String {
        var out = (jar as? SessionCookies)?.redact(text) ?: text
        bearer?.let { if (it.isNotEmpty()) out = out.replace(it, "[token]") }
        out = SECRET_SHAPES.fold(out) { acc, re -> re.replace(acc, "[removed]") }
        return out.take(MAX_TEXT)
    }

    companion object {
        const val DEFAULT_USER_AGENT = "StarNav (own-dish interop)"
        const val ACCESS_COOKIE = "Starlink.Com.Access.V1"
        const val SESSION_COOKIE = "Starlink.Com.Sso"

        private const val GRPC_WEB_PROTO = "application/grpc-web+proto"
        private const val MAX_BODY = 1L shl 20
        private const val MAX_TEXT = 200
        private val SECRET_SHAPES = listOf(
            Regex("(?i)starlink\\.com\\.[a-z0-9.]+\\s*=\\s*[^;\\s]+"),
            Regex("(?i)(authorization|cookie|set-cookie)\\s*[:=]\\s*\\S+(\\s+\\S+)?"),
            Regex("(?i)bearer\\s+\\S+"),
        )

        /** The request body of the write: the gRPC-Web frame of the `DeviceRequest` (see the class comment). */
        fun localLocationFrame(targetId: String): ByteArray = GrpcFrames.encode(
            DeviceRequest.ADAPTER.encode(
                DeviceRequest(
                    target_id = targetId,
                    dish_set_config = DishSetConfigRequest(
                        DishConfig(location_request_mode = DishCallKind.LOCATION_MODE_LOCAL, apply_location_request_mode = true),
                    ),
                ),
            ),
        )

        // ---- reading the account's JSON, tolerantly: the shapes are unofficial

        private fun emailIn(body: ByteArray): String? = try {
            findEmail(Json.parseToJsonElement(body.decodeToString()))
        } catch (e: Exception) {
            null
        }

        private fun findEmail(e: JsonElement, depth: Int = 0): String? {
            if (depth > 4) return null
            if (e is JsonObject) {
                for ((k, v) in e) {
                    if (k.contains("mail", ignoreCase = true) && v is JsonPrimitive && v.isString && v.content.contains('@')) return v.content
                }
                for (v in e.values) findEmail(v, depth + 1)?.let { return it }
            }
            if (e is JsonArray) for (v in e) findEmail(v, depth + 1)?.let { return it }
            return null
        }

        /** `content.results[].userTerminals[].{userTerminalId, serialNumber}`; null when the shape is not that. */
        private fun terminalsIn(body: ByteArray): List<UserTerminal>? = try {
            val root = Json.parseToJsonElement(body.decodeToString()) as? JsonObject
            val results = ((root?.get("content") as? JsonObject)?.get("results") as? JsonArray)
            results?.flatMap { line ->
                ((line as? JsonObject)?.get("userTerminals") as? JsonArray).orEmpty().mapNotNull { t ->
                    val o = t as? JsonObject ?: return@mapNotNull null
                    val id = (o["userTerminalId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    UserTerminal(id, (o["serialNumber"] as? JsonPrimitive)?.contentOrNull)
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
