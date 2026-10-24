package tools.apachi.starlinknav.core.dish

import tools.apachi.starlinknav.core.dish.proto.WifiConfig

/** A config as if read unredacted from a router (the only base a config write takes). */
internal fun WifiConfig.read(): RouterConfigRead = checkNotNull(RouterConfigRead.of(this)) { "looks redacted" }

internal val EMPTY_BASE: RouterConfigRead = WifiConfig().read()

/** A tiny protobuf scanner for tests: which field numbers sit at the top level of a message. */
internal object TestWire {
    fun topLevelTags(bytes: ByteArray): List<Int> {
        val tags = ArrayList<Int>()
        var i = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = bytes[i++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        while (i < bytes.size) {
            val key = varint()
            tags += (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> i += 8
                2 -> { val len = varint().toInt(); i += len }
                5 -> i += 4
                else -> error("wire type ${key and 7}")
            }
        }
        return tags
    }

    /** The oneof arm (a field number of 1000 or more) of a request or response; null if none. */
    fun armTag(bytes: ByteArray): Int? = topLevelTags(bytes).filter { it >= 1000 }.also { check(it.size <= 1) { "several arms: $it" } }.firstOrNull()

    fun contains(haystack: ByteArray, needle: String): Boolean {
        val n = needle.encodeToByteArray()
        if (n.isEmpty() || haystack.size < n.size) return false
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }
}
