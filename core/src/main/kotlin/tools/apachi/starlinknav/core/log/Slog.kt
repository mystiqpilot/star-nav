package tools.apachi.starlinknav.core.log

import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.buffer
import okio.sink
import okio.source
import tools.apachi.starlinknav.core.dish.CallTarget
import tools.apachi.starlinknav.core.dish.DishCallKind
import tools.apachi.starlinknav.core.dish.Redact
import tools.apachi.starlinknav.core.dish.hasRouterPayload
import tools.apachi.starlinknav.core.dish.proto.DeviceResponse
import tools.apachi.starlinknav.core.log.proto.Record
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

object Slog {
    val MAGIC = "SLNAVLG1".encodeUtf8()
    const val EXTENSION = "slog"
    const val MAX_RECORD_BYTES = 16 * 1024 * 1024

    /**
     * Router replies hold Wi-Fi passwords and keys and are never logged raw. [tools.apachi.starlinknav.core.dish.DishCallResult.toRecord]
     * already stores them redacted; the writer re-checks every dish record (redacting is idempotent), so no
     * other code path can put a router payload on disk: one that cannot be decoded is dropped.
     */
    fun withoutRouterSecrets(r: Record): Record {
        val dish = r.dish ?: return r
        if (dish.response.size == 0) return r
        val routerKind = DishCallKind.of(dish.kind)?.target == CallTarget.ROUTER
        val decoded = try {
            DeviceResponse.ADAPTER.decode(dish.response)
        } catch (_: Exception) {
            null
        }
        if (decoded == null) {
            return if (routerKind) r.copy(dish = dish.copy(response = okio.ByteString.EMPTY, error = (dish.error + " [router payload dropped: undecodable]").trim())) else r
        }
        if (!routerKind && !decoded.hasRouterPayload()) return r
        return r.copy(dish = dish.copy(response = DeviceResponse.ADAPTER.encode(Redact.response(decoded)).toByteString()))
    }

    fun payloadType(r: Record): String = when {
        r.header_ != null -> "header"
        r.dish != null -> "dish"
        r.imu != null -> "imu"
        r.phone_location != null -> "phone_location"
        r.gnss_status != null -> "gnss_status"
        r.gnss_measurements != null -> "gnss_measurements"
        r.nmea != null -> "nmea"
        r.event != null -> "event"
        r.mock_output != null -> "mock_output"
        r.nav_message != null -> "nav_message"
        else -> "unknown"
    }
}

/** Not thread-safe: feed it from one thread/coroutine. */
class SlogWriter private constructor(
    private val sink: BufferedSink,
    private val fileStream: FileOutputStream?,
    private val closeUnderlying: Boolean,
) : Closeable {
    var bytesWritten = 0L
        private set
    var recordsWritten = 0L
        private set

    init {
        sink.write(Slog.MAGIC)
        bytesWritten = Slog.MAGIC.size.toLong()
    }

    fun write(record: Record) {
        val bytes = Record.ADAPTER.encode(Slog.withoutRouterSecrets(record))
        var n = bytes.size
        var prefix = 1
        while (n and 0x7f.inv() != 0) {
            sink.writeByte((n and 0x7f) or 0x80)
            n = n ushr 7
            prefix++
        }
        sink.writeByte(n)
        sink.write(bytes)
        bytesWritten += prefix + bytes.size
        recordsWritten++
    }

    fun flush() = sink.flush()

    /** Flush and fsync, so a power cut loses at most what came after this call. */
    fun sync() {
        sink.flush()
        fileStream?.fd?.sync()
    }

    override fun close() {
        try {
            sync()
        } finally {
            if (closeUnderlying) sink.close()
        }
    }

    companion object {
        fun create(file: File): SlogWriter {
            val fos = FileOutputStream(file)
            return SlogWriter(fos.sink().buffer(), fos, closeUnderlying = true)
        }

        /** Writes into a stream it does not own (e.g. a zip entry); close() only flushes. */
        fun into(out: OutputStream): SlogWriter = SlogWriter(out.sink().buffer(), null, closeUnderlying = false)
    }
}

/** Reads records up to the first truncated or unparseable framing; skips undecodable records. */
class SlogReader(private val src: BufferedSource) : Closeable {
    var truncatedTail = false
        private set
    var corruptRecords = 0
        private set

    init {
        if (!src.request(Slog.MAGIC.size.toLong()) || src.readByteString(Slog.MAGIC.size.toLong()) != Slog.MAGIC) {
            throw IOException("not a starlink-nav session log (bad magic)")
        }
    }

    fun next(): Record? {
        while (true) {
            if (src.exhausted()) return null
            val len = readVarint()
            if (len == null) {
                truncatedTail = true
                return null
            }
            if (len < 0 || len > Slog.MAX_RECORD_BYTES) {
                corruptRecords++
                truncatedTail = true
                return null
            }
            if (!src.request(len.toLong())) {
                truncatedTail = true
                return null
            }
            val bytes = src.readByteArray(len.toLong())
            try {
                return Record.ADAPTER.decode(bytes)
            } catch (_: Exception) {
                corruptRecords++
            }
        }
    }

    fun records(): Sequence<Record> = generateSequence { next() }

    private fun readVarint(): Int? {
        var result = 0
        var shift = 0
        while (shift <= 28) {
            if (src.exhausted()) return null
            val b = src.readByte().toInt() and 0xff
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return -1
    }

    override fun close() = src.close()

    companion object {
        fun open(file: File): SlogReader = SlogReader(file.source().buffer())
    }
}
