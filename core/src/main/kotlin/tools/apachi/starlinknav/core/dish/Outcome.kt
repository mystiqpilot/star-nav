package tools.apachi.starlinknav.core.dish

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** What a call came to, from the gRPC status, its message and the transport. */
enum class CallOutcome {
    OK,

    /** The device does not offer the call (gRPC UNIMPLEMENTED, "not supported"): grey it out. */
    UNIMPLEMENTED,

    /** The device refuses this client (gRPC PERMISSION_DENIED); the Starlink app, logged in, may still do it. */
    PERMISSION_DENIED,

    UNAUTHENTICATED,

    /** Refused for now because the dish is disabled (UT disablement: moving too fast, no active plan, ...). Transient. */
    DISABLED_BY_POLICY,

    /**
     * The plan does not include this ("Failed to get location: Disabled due to policy", location for
     * non-Priority plans since 2026-05-20). Lasts until the plan changes, so the UI greys it out.
     */
    PLAN_RESTRICTED,

    /** No answer at all and nothing was sent: no route, refused connection, connect timeout, or UNAVAILABLE. */
    UNREACHABLE,

    /**
     * A command was sent and the connection ended without an answer (a rebooting router drops it): it may
     * or may not have been done. Check the device before sending it again.
     */
    SENT_NO_ANSWER,

    OTHER;

    /** The device will not do this for this client until something changes (account, firmware, plan). */
    val isUnavailable: Boolean
        get() = this == UNIMPLEMENTED || this == PERMISSION_DENIED || this == UNAUTHENTICATED || this == PLAN_RESTRICTED

    /** Says nothing about what the device implements: the capability book ignores it. */
    val isNoAnswer: Boolean get() = this == UNREACHABLE || this == SENT_NO_ANSWER

    companion object {
        fun of(r: DishCallResult): CallOutcome {
            if (r.ok) return OK
            if (r.httpStatus == 0) return if (r.sentNoAnswer) SENT_NO_ANSWER else UNREACHABLE
            val status = r.grpcStatus
            val message = r.grpcMessage.orEmpty()
            return when {
                r.error != null && status == GrpcStatus.OK -> OTHER // reply did not decode
                status == null -> OTHER // an HTTP error without a gRPC status
                status == GrpcStatus.UNIMPLEMENTED || r.unsupported -> UNIMPLEMENTED
                isDisablement(status, message) -> DISABLED_BY_POLICY
                isPlanRestriction(status, message) -> PLAN_RESTRICTED
                status == GrpcStatus.PERMISSION_DENIED -> PERMISSION_DENIED
                status == GrpcStatus.UNAUTHENTICATED -> UNAUTHENTICATED
                status == GrpcStatus.UNAVAILABLE -> UNREACHABLE
                else -> OTHER
            }
        }

        /** gRPC 7 with the UT-disablement message ("GetLocation requests disabled due to UT disablement"). */
        private fun isDisablement(status: Int, message: String): Boolean =
            status == GrpcStatus.PERMISSION_DENIED && message.contains("disablement", ignoreCase = true)

        /** gRPC 7 with the plan wording ("Failed to get location: Disabled due to policy"): persistent, not a disablement. */
        private fun isPlanRestriction(status: Int, message: String): Boolean =
            status == GrpcStatus.PERMISSION_DENIED && message.contains("due to policy", ignoreCase = true)
    }
}

@Serializable
data class CapabilityEntry(
    val outcome: CallOutcome,
    /** The dish's or router's own words (gRPC message) or the transport error. */
    val message: String = "",
    val atMs: Long,
    /** How many times in a row this outcome has been seen. */
    val count: Int = 1,
)

/**
 * What each device did the last time each call was tried, so the UI can grey out what a device does
 * not implement (with the reason) instead of failing again. Keyed by a hash of the device id (never the
 * id itself) and the [DishCallKind.code]. Immutable: every update returns a new book; the app
 * persists [toJson] in its own preferences.
 */
@Serializable
data class CapabilityBook(val devices: Map<String, Map<Int, CapabilityEntry>> = emptyMap()) {
    /**
     * Notes a result. A device that did not answer (unreachable, or a command sent without an answer) says
     * nothing about what it implements, so such results leave the book as it was; a device with no id yet
     * (empty key) is not recorded.
     */
    fun record(deviceKey: String, result: DishCallResult, nowMs: Long): CapabilityBook {
        if (deviceKey.isEmpty()) return this
        val outcome = result.outcome
        if (outcome.isNoAnswer) return this
        val message = (result.grpcMessage ?: result.error).orEmpty().take(MAX_MESSAGE)
        val old = devices[deviceKey].orEmpty()
        val previous = old[result.kind.code]
        val entry = CapabilityEntry(
            outcome, message, nowMs,
            count = if (previous?.outcome == outcome) (previous.count + 1).coerceAtMost(1_000_000) else 1,
        )
        var next = devices + (deviceKey to (old + (result.kind.code to entry)))
        if (next.size > MAX_DEVICES) {
            val oldest = next.minByOrNull { (_, m) -> m.values.maxOfOrNull { it.atMs } ?: 0L }?.key
            if (oldest != null && oldest != deviceKey) next = next - oldest
        }
        return CapabilityBook(next)
    }

    /**
     * Whether recording [result] would tell the book something new: its call was never seen, or came to another outcome class or
     * other words, or the entry is older than [staleMs] (so a device that is polled all the time keeps its place among the
     * [MAX_DEVICES]). A repeat of the same answer is not news. The app records only news, so the book (and what watches it)
     * changes when a call's state does, not with every poll; [record] itself still counts every repeat.
     */
    fun hasNews(deviceKey: String, result: DishCallResult, nowMs: Long, staleMs: Long = STALE_MS): Boolean {
        if (deviceKey.isEmpty() || result.outcome.isNoAnswer) return false
        val previous = last(deviceKey, result.kind) ?: return true
        val message = (result.grpcMessage ?: result.error).orEmpty().take(MAX_MESSAGE)
        return previous.outcome != result.outcome || previous.message != message || nowMs - previous.atMs > staleMs
    }

    fun recordAll(deviceKey: String, results: List<DishCallResult>, nowMs: Long): CapabilityBook =
        results.fold(this) { book, r -> book.record(deviceKey, r, nowMs) }

    fun last(deviceKey: String, kind: DishCallKind): CapabilityEntry? = devices[deviceKey]?.get(kind.code)

    /** The last answer says this device will not do [kind] for this client. */
    fun isUnavailable(deviceKey: String, kind: DishCallKind): Boolean = last(deviceKey, kind)?.outcome?.isUnavailable == true

    /** Why [kind] is greyed out, in the device's words; null when it is not. */
    fun reason(deviceKey: String, kind: DishCallKind): String? =
        last(deviceKey, kind)?.takeIf { it.outcome.isUnavailable }?.let { "${it.outcome}: ${it.message}".trimEnd(' ', ':') }

    fun forget(deviceKey: String): CapabilityBook = CapabilityBook(devices - deviceKey)

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MAX_DEVICES = 8

        /** An unchanged entry is refreshed after this long ([hasNews]). */
        const val STALE_MS = 10 * 60 * 1000L
        private const val MAX_MESSAGE = 200
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): CapabilityBook =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: CapabilityBook()

        /** Stable key of a device: a truncated SHA-256 of its id; empty for an unknown id. */
        fun keyOf(deviceId: String?): String {
            if (deviceId.isNullOrEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.encodeToByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
