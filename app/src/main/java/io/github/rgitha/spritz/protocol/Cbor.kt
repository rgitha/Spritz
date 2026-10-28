package io.github.rgitha.spritz.protocol

import java.io.ByteArrayOutputStream

/**
 * Just enough CBOR (RFC 8949) for the aer protocol.
 *
 * Decoded values map to Kotlin types: unsigned and negative integers → [Long],
 * byte strings → [ByteArray], text → [String], arrays → [List], maps → [Map]
 * (keys as decoded), true/false → [Boolean], null/undefined → null,
 * floats → [Double].
 */
object Cbor {

    /** Thrown when the input ends before a complete item; more fragments may follow. */
    class Incomplete : Exception("incomplete CBOR item")

    class Malformed(message: String) : Exception(message)

    /** Encodes an unsigned integer with the full 8-byte argument (major type 0, 0x1B). */
    data class U64(val value: Long)

    /** A map written with indefinite length (BF … FF), keys in insertion order. */
    class IndefiniteMap(val entries: List<Pair<String, Any>>)

    /** An array written with indefinite length (9F … FF). */
    class IndefiniteArray(val items: List<Any>)

    fun encode(value: Any): ByteArray = ByteArrayOutputStream().also { write(it, value) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, value: Any) {
        when (value) {
            is U64 -> {
                out.write(0x1B)
                for (shift in 56 downTo 0 step 8) out.write((value.value ushr shift).toInt() and 0xFF)
            }
            is Int -> writeInt(out, value.toLong())
            is Long -> writeInt(out, value)
            is Boolean -> out.write(if (value) 0xF5 else 0xF4)
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                writeHead(out, 3, bytes.size.toLong())
                out.write(bytes)
            }
            is ByteArray -> {
                writeHead(out, 2, value.size.toLong())
                out.write(value)
            }
            is IndefiniteMap -> {
                out.write(0xBF)
                for ((k, v) in value.entries) {
                    write(out, k)
                    write(out, v)
                }
                out.write(0xFF)
            }
            is IndefiniteArray -> {
                out.write(0x9F)
                value.items.forEach { write(out, it) }
                out.write(0xFF)
            }
            is List<*> -> {
                writeHead(out, 4, value.size.toLong())
                value.forEach { write(out, it ?: throw IllegalArgumentException("null in array")) }
            }
            else -> throw IllegalArgumentException("cannot encode ${value::class}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        if (v >= 0) writeHead(out, 0, v) else writeHead(out, 1, -1 - v)
    }

    private fun writeHead(out: ByteArrayOutputStream, major: Int, arg: Long) {
        val m = major shl 5
        when {
            arg < 24 -> out.write(m or arg.toInt())
            arg < 0x100 -> { out.write(m or 24); out.write(arg.toInt()) }
            arg < 0x10000 -> { out.write(m or 25); writeBe(out, arg, 2) }
            arg < 0x100000000L -> { out.write(m or 26); writeBe(out, arg, 4) }
            else -> { out.write(m or 27); writeBe(out, arg, 8) }
        }
    }

    private fun writeBe(out: ByteArrayOutputStream, v: Long, bytes: Int) {
        for (i in bytes - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF)
    }

    /**
     * Decodes one item from the start of [data].
     * @return the item and the number of bytes it used.
     * @throws Incomplete if [data] ends inside the item.
     * @throws Malformed if [data] is not valid CBOR.
     */
    fun decode(data: ByteArray): Pair<Any?, Int> {
        val r = Reader(data)
        val v = r.item()
        return v to r.pos
    }

    private object Break

    private class Reader(val d: ByteArray) {
        var pos = 0

        fun byte(): Int {
            if (pos >= d.size) throw Incomplete()
            return d[pos++].toInt() and 0xFF
        }

        fun arg(info: Int): Long = when {
            info < 24 -> info.toLong()
            info == 24 -> byte().toLong()
            info == 25 -> be(2)
            info == 26 -> be(4)
            info == 27 -> be(8)
            else -> throw Malformed("bad additional info $info")
        }

        fun be(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or byte().toLong() }
            return v
        }

        fun bytes(n: Long): ByteArray {
            if (n > Int.MAX_VALUE) throw Malformed("length too large")
            if (pos + n > d.size) throw Incomplete()
            val out = d.copyOfRange(pos, pos + n.toInt())
            pos += n.toInt()
            return out
        }

        fun item(): Any? {
            val ib = byte()
            val major = ib shr 5
            val info = ib and 0x1F
            if (info == 31) return indefinite(major)
            return when (major) {
                0 -> arg(info)
                1 -> -1 - arg(info)
                2 -> bytes(arg(info))
                3 -> String(bytes(arg(info)), Charsets.UTF_8)
                4 -> List(checkCount(arg(info))) { item() }
                5 -> {
                    val n = checkCount(arg(info))
                    val m = LinkedHashMap<Any?, Any?>()
                    repeat(n) { val k = item(); m[k] = item() }
                    m
                }
                6 -> { arg(info); item() } // tag: ignore it, keep the content
                else -> simple(info)
            }
        }

        fun checkCount(n: Long): Int {
            // Each element needs at least one byte, so a larger count can't be complete.
            if (n > d.size - pos) {
                if (n > 4096) throw Malformed("count too large")
                throw Incomplete()
            }
            return n.toInt()
        }

        fun simple(info: Int): Any? = when (info) {
            20 -> false
            21 -> true
            22, 23 -> null
            24 -> { byte(); null }
            25 -> halfToDouble(be(2).toInt())
            26 -> java.lang.Float.intBitsToFloat(be(4).toInt()).toDouble()
            27 -> java.lang.Double.longBitsToDouble(be(8))
            else -> if (info < 20) null else throw Malformed("bad simple value $info")
        }

        fun indefinite(major: Int): Any = when (major) {
            2, 3 -> {
                val buf = ByteArrayOutputStream()
                while (true) {
                    val chunk = item()
                    if (chunk === Break) break
                    when (chunk) {
                        is ByteArray -> buf.write(chunk)
                        is String -> buf.write(chunk.toByteArray(Charsets.UTF_8))
                        else -> throw Malformed("bad chunk in indefinite string")
                    }
                }
                if (major == 2) buf.toByteArray() else buf.toString(Charsets.UTF_8.name())
            }
            4 -> buildList { while (true) { val v = item(); if (v === Break) break; add(v) } }
            5 -> {
                val m = LinkedHashMap<Any?, Any?>()
                while (true) {
                    val k = item()
                    if (k === Break) break
                    val v = item()
                    if (v === Break) throw Malformed("break inside map entry")
                    m[k] = v
                }
                m
            }
            7 -> Break
            else -> throw Malformed("indefinite length on major type $major")
        }

        fun halfToDouble(h: Int): Double {
            val exp = (h shr 10) and 0x1F
            val mant = h and 0x3FF
            val v = when (exp) {
                0 -> mant * Math.pow(2.0, -24.0)
                31 -> if (mant == 0) Double.POSITIVE_INFINITY else Double.NaN
                else -> (mant + 1024) * Math.pow(2.0, exp - 25.0)
            }
            return if (h and 0x8000 != 0) -v else v
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
