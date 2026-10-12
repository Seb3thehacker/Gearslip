package app.seb3thehacker.gearslip

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf to run the spike, hand-rolled so there is no protoc step.
 *
 * Only three messages are ever written (AuthResponse is only read):
 *   ServiceDiscoveryRequest { optional string label_text = 4; optional string device_name = 5; }
 *   PingResponse            { required int64 timestamp = 1; }
 *   AuthResponse            { required int32 status = 1; }
 */
object Protobuf {

    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return out.toByteArray()
            }
            out.write(b or 0x80)
        }
    }

    fun stringField(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return varint(((field shl 3) or 2).toLong()) + varint(bytes.size.toLong()) + bytes
    }

    fun bytesField(field: Int, bytes: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(bytes.size.toLong()) + bytes

    fun varintField(field: Int, value: Long): ByteArray =
        varint((field shl 3).toLong()) + varint(value)

    /** Reads the single int32 in AuthResponse. Negative values are 10-byte varints. */
    fun readInt32Field(data: ByteArray, field: Int): Int? = readInt64Field(data, field)?.toInt()

    /** Reads a varint field whole, for int64s such as a ping's timestamp. */
    fun readInt64Field(data: ByteArray, field: Int): Long? {
        var pos = 0
        while (pos < data.size) {
            val (tag, afterTag) = readVarint(data, pos) ?: return null
            pos = afterTag
            val number = (tag ushr 3).toInt()
            when ((tag and 7L).toInt()) {
                0 -> {
                    val (value, afterValue) = readVarint(data, pos) ?: return null
                    pos = afterValue
                    if (number == field) return value
                }
                2 -> {
                    val (len, afterLen) = readVarint(data, pos) ?: return null
                    pos = afterLen + len.toInt()
                }
                5 -> pos += 4
                1 -> pos += 8
                else -> return null
            }
        }
        return null
    }

    /**
     * Shallow field dump. Used on ServiceDiscoveryResponse: its make/model/year strings
     * (fields 2-4) and head-unit info (7-10) coming out readable is the clearest possible
     * proof that the session is live and we are decrypting correctly.
     */
    fun describe(data: ByteArray, indent: String = "  "): String {
        val sb = StringBuilder()
        var pos = 0
        while (pos < data.size) {
            val (tag, afterTag) = readVarint(data, pos) ?: break
            pos = afterTag
            val number = (tag ushr 3).toInt()
            when ((tag and 7L).toInt()) {
                0 -> {
                    val (value, after) = readVarint(data, pos) ?: break
                    pos = after
                    sb.append("$indent#$number varint = $value\n")
                }
                1 -> {
                    if (pos + 8 > data.size) break
                    sb.append("$indent#$number fixed64\n"); pos += 8
                }
                2 -> {
                    val (len, afterLen) = readVarint(data, pos) ?: break
                    val end = afterLen + len.toInt()
                    if (end > data.size) break
                    val body = data.copyOfRange(afterLen, end)
                    pos = end
                    val text = body.toString(Charsets.UTF_8)
                    if (body.isNotEmpty() && text.all { it == '\n' || it == '\t' || it.code in 32..126 }) {
                        sb.append("$indent#$number string = \"$text\"\n")
                    } else {
                        sb.append("$indent#$number message/bytes (${body.size})\n")
                        sb.append(describe(body, "$indent  "))
                    }
                }
                5 -> {
                    if (pos + 4 > data.size) break
                    sb.append("$indent#$number fixed32\n"); pos += 4
                }
                else -> break
            }
        }
        return sb.toString()
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size && shift <= 63) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }
}

/** Structured field access, for walking ServiceDiscoveryResponse without protoc. */
object Wire {

    class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?)

    fun fields(data: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var pos = 0
        while (pos < data.size) {
            val tag = readVarint(data, pos) ?: break
            pos = tag.second
            val number = (tag.first ushr 3).toInt()
            when ((tag.first and 7L).toInt()) {
                0 -> {
                    val v = readVarint(data, pos) ?: break
                    pos = v.second
                    out += Field(number, 0, v.first, null)
                }
                1 -> { if (pos + 8 > data.size) break; pos += 8 }
                2 -> {
                    val len = readVarint(data, pos) ?: break
                    val end = len.second + len.first.toInt()
                    if (end > data.size || end < len.second) break
                    out += Field(number, 2, 0, data.copyOfRange(len.second, end))
                    pos = end
                }
                5 -> { if (pos + 4 > data.size) break; pos += 4 }
                else -> break
            }
        }
        return out
    }

    fun varint(fields: List<Field>, number: Int): Long? =
        fields.firstOrNull { it.number == number && it.wireType == 0 }?.varint

    fun bytes(fields: List<Field>, number: Int): ByteArray? =
        fields.firstOrNull { it.number == number && it.wireType == 2 }?.bytes

    fun allBytes(fields: List<Field>, number: Int): List<ByteArray> =
        fields.filter { it.number == number && it.wireType == 2 }.mapNotNull { it.bytes }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size && shift <= 63) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }
}
