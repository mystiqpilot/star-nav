package tools.apachi.starlinknav.core.dish

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GrpcProtocolException(message: String) : IOException(message)

/** Result of one unary call. gRPC-level errors are values here; transport failures throw IOException. */
class GrpcReply(
    val transport: String,
    val httpStatus: Int,
    val grpcStatus: Int?,
    val grpcMessage: String?,
    val message: ByteArray?,
)

interface GrpcTransport {
    val name: String
    val endpoint: String

    /**
     * [safeToRepeat] is false for a command: once the request may have reached the device (the connection
     * was made) a transport must not send it again, e.g. to another port, whatever went wrong afterwards.
     */
    @Throws(IOException::class)
    fun unary(path: String, request: ByteArray, safeToRepeat: Boolean = true): GrpcReply
}

object GrpcErrors {
    /**
     * The failure came before any connection existed (no route, refused, unknown host, connect timeout),
     * so the request cannot have reached the device and may go to another endpoint. Anything else that
     * ends a call (a read timeout, a reset or a closed connection, as a restarting device causes) may
     * come after the device got the request. An exception that is no IOException (a bad address) never sent anything.
     */
    fun neverReachedDevice(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 6) {
            when (t) {
                is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.UnknownHostException -> return true
                is java.net.SocketTimeoutException -> if (t.message?.contains("connect", ignoreCase = true) == true) return true
            }
            t = t.cause
        }
        return e !is IOException
    }
}

object GrpcStatus {
    const val OK = 0
    const val PERMISSION_DENIED = 7
    const val FAILED_PRECONDITION = 9
    const val UNIMPLEMENTED = 12
    const val UNAVAILABLE = 14
    const val UNAUTHENTICATED = 16

    private val names = listOf(
        "OK", "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "NOT_FOUND",
        "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION",
        "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE", "DATA_LOSS",
        "UNAUTHENTICATED",
    )

    fun name(code: Int?): String = when (code) {
        null -> "NO_STATUS"
        in names.indices -> names[code]
        else -> "CODE_$code"
    }
}

/** gRPC length-prefixed message framing, shared by both transports and the fake dish. */
object GrpcFrames {
    private const val FLAG_TRAILERS = 0x80
    private const val FLAG_COMPRESSED = 0x01
    private const val MAX_FRAME = 32 * 1024 * 1024

    class Frame(val flags: Int, val payload: ByteArray) {
        val isTrailers get() = flags and FLAG_TRAILERS != 0
        val isCompressed get() = flags and FLAG_COMPRESSED != 0
    }

    fun encode(payload: ByteArray): ByteArray {
        val n = payload.size
        val out = ByteArray(5 + n)
        out[1] = (n ushr 24).toByte()
        out[2] = (n ushr 16).toByte()
        out[3] = (n ushr 8).toByte()
        out[4] = n.toByte()
        payload.copyInto(out, 5)
        return out
    }

    fun decodeAll(body: ByteArray): List<Frame> {
        val frames = ArrayList<Frame>(2)
        var i = 0
        while (i < body.size) {
            if (body.size - i < 5) throw GrpcProtocolException("truncated frame header at $i of ${body.size}")
            val flags = body[i].toInt() and 0xff
            val len = ((body[i + 1].toInt() and 0xff) shl 24) or ((body[i + 2].toInt() and 0xff) shl 16) or
                ((body[i + 3].toInt() and 0xff) shl 8) or (body[i + 4].toInt() and 0xff)
            if (len < 0 || len > MAX_FRAME || body.size - i - 5 < len) {
                throw GrpcProtocolException("bad frame length $len at $i of ${body.size}")
            }
            frames += Frame(flags, body.copyOfRange(i + 5, i + 5 + len))
            i += 5 + len
        }
        return frames
    }

    fun parseTrailerBlock(payload: ByteArray): Map<String, String> =
        payload.decodeToString().split("\r\n", "\n").mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()

    /** grpc-message is percent-encoded UTF-8 (not form-encoded: '+' stays '+'). */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            val end = s.indexOf('%', i + 1).let { if (it < 0) s.length else it }
            out.write(s.substring(i, end).encodeToByteArray())
            i = end
        }
        return out.toByteArray().decodeToString()
    }

    fun firstDataPayload(frames: List<Frame>): ByteArray? {
        val data = frames.firstOrNull { !it.isTrailers } ?: return null
        if (data.isCompressed) throw GrpcProtocolException("compressed gRPC frame not supported")
        return data.payload
    }
}

private val GRPC_WEB_PROTO = "application/grpc-web+proto".toMediaType()
private val GRPC_PROTO = "application/grpc".toMediaType()

/** gRPC-Web (binary) over HTTP/1.1 — the dish's port 9201, used by the official Starlink app. */
class GrpcWebTransport(client: OkHttpClient, private val baseUrl: HttpUrl) : GrpcTransport {
    private val client = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    override val name = "grpc-web"
    override val endpoint = baseUrl.toString()

    override fun unary(path: String, request: ByteArray, safeToRepeat: Boolean): GrpcReply {
        val req = Request.Builder()
            .url(baseUrl.resolve(path) ?: throw IllegalArgumentException(path))
            .header("Content-Type", GRPC_WEB_PROTO.toString())
            .header("Accept", GRPC_WEB_PROTO.toString())
            .header("X-Grpc-Web", "1")
            .post(GrpcFrames.encode(request).toRequestBody(GRPC_WEB_PROTO))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body.bytes()
            var status = resp.header("grpc-status")?.toIntOrNull()
            var message = resp.header("grpc-message")?.let(GrpcFrames::percentDecode)
            var data: ByteArray? = null
            if (resp.code == 200 && body.isNotEmpty()) {
                val frames = GrpcFrames.decodeAll(body)
                for (f in frames.filter { it.isTrailers }) {
                    val t = GrpcFrames.parseTrailerBlock(f.payload)
                    t["grpc-status"]?.toIntOrNull()?.let { status = it }
                    t["grpc-message"]?.let { message = GrpcFrames.percentDecode(it) }
                }
                data = GrpcFrames.firstDataPayload(frames)
            }
            return GrpcReply(name, resp.code, status, message?.ifEmpty { null }, data)
        }
    }
}

/** Plain gRPC over cleartext HTTP/2 with prior knowledge — the dish's port 9200. */
class GrpcH2cTransport(client: OkHttpClient, private val baseUrl: HttpUrl) : GrpcTransport {
    private val client = client.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
    override val name = "grpc-h2c"
    override val endpoint = baseUrl.toString()

    override fun unary(path: String, request: ByteArray, safeToRepeat: Boolean): GrpcReply {
        val req = Request.Builder()
            .url(baseUrl.resolve(path) ?: throw IllegalArgumentException(path))
            .header("Content-Type", GRPC_PROTO.toString())
            .header("TE", "trailers")
            .post(GrpcFrames.encode(request).toRequestBody(GRPC_PROTO))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body.bytes()
            val trailers = resp.trailers()
            val status = (resp.header("grpc-status") ?: trailers["grpc-status"])?.toIntOrNull()
            val message = (resp.header("grpc-message") ?: trailers["grpc-message"])?.let(GrpcFrames::percentDecode)
            val data = if (body.isEmpty()) null else GrpcFrames.firstDataPayload(GrpcFrames.decodeAll(body))
            return GrpcReply(name, resp.code, status, message?.ifEmpty { null }, data)
        }
    }
}

/**
 * Uses [primary] until it fails at the transport level, then sticks to whichever works. A call that is
 * not safe to repeat (a command) goes to the other endpoint only when the first failure came before
 * any connection existed: a rebooting router drops the connection after it got the command, and
 * sending that again (factory reset, set_config, update, a speed test that costs data) is not safe.
 */
class FallbackTransport(private val primary: GrpcTransport, private val secondary: GrpcTransport) : GrpcTransport {
    @Volatile private var active = primary
    override val name get() = active.name
    override val endpoint get() = active.endpoint

    override fun unary(path: String, request: ByteArray, safeToRepeat: Boolean): GrpcReply {
        val first = active
        try {
            return first.unary(path, request, safeToRepeat)
        } catch (e: IOException) {
            if (!safeToRepeat && !GrpcErrors.neverReachedDevice(e)) throw e
            val other = if (first === primary) secondary else primary
            try {
                val reply = other.unary(path, request, safeToRepeat)
                active = other
                return reply
            } catch (e2: IOException) {
                e2.addSuppressed(e)
                throw e2
            }
        }
    }
}
