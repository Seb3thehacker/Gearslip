package app.seb3thehacker.gearslip

import java.io.ByteArrayOutputStream

/** Protobuf field encoding and diagnostic rendering for the projection protocol. */
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
    fun readInt64Field(data: ByteArray, field: Int): Long? = Wire.varint(Wire.fields(data), field)

    /**
     * Shallow field dump. Used on ServiceDiscoveryResponse: its make/model/year strings
     * (fields 2-4) and head-unit info (7-10) coming out readable is the clearest possible
     * proof that the session is live and we are decrypting correctly.
     */
    fun describe(data: ByteArray, indent: String = "  "): String {
        if (indent.length > 32) return "$indent(nested message)\n"
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
                    if (len < 0 || len > data.size - afterLen) break
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
            if (shift == 63 && b > 1) return null
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
            val tag = readVarint(data, pos) ?: return emptyList()
            pos = tag.second
            val number = (tag.first ushr 3).toInt()
            if (tag.first ushr 3 !in 1..0x1fff_ffffL) return emptyList()
            when ((tag.first and 7L).toInt()) {
                0 -> {
                    val v = readVarint(data, pos) ?: return emptyList()
                    pos = v.second
                    out += Field(number, 0, v.first, null)
                }
                1 -> { if (data.size - pos < 8) return emptyList(); pos += 8 }
                2 -> {
                    val len = readVarint(data, pos) ?: return emptyList()
                    if (len.first < 0 || len.first > data.size - len.second) return emptyList()
                    val end = len.second + len.first.toInt()
                    if (end > data.size || end < len.second) return emptyList()
                    out += Field(number, 2, 0, data.copyOfRange(len.second, end))
                    pos = end
                }
                5 -> { if (data.size - pos < 4) return emptyList(); pos += 4 }
                else -> return emptyList()
            }
        }
        return out
    }

    fun varint(fields: List<Field>, number: Int): Long? =
        fields.lastOrNull { it.number == number && it.wireType == 0 }?.varint

    fun bytes(fields: List<Field>, number: Int): ByteArray? =
        fields.lastOrNull { it.number == number && it.wireType == 2 }?.bytes

    fun allBytes(fields: List<Field>, number: Int): List<ByteArray> =
        fields.filter { it.number == number && it.wireType == 2 }.mapNotNull { it.bytes }

    /** Protobuf readers accept both packed and unpacked repeated numeric fields. */
    fun repeatedInts(fields: List<Field>, number: Int): List<Int> =
        repeatedLongs(fields, number)?.map(Long::toInt).orEmpty()

    /** Null means a packed field contained an incomplete or overflowing varint. */
    fun repeatedLongs(fields: List<Field>, number: Int): List<Long>? = buildList {
        for (field in fields.filter { it.number == number }) {
            if (field.wireType == 0) add(field.varint)
            else if (field.wireType == 2) {
                val bytes = field.bytes ?: continue
                var position = 0
                while (position < bytes.size) {
                    val (value, next) = readVarint(bytes, position) ?: return null
                    add(value)
                    position = next
                }
            }
        }
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size && shift <= 63) {
            val b = data[pos].toInt() and 0xFF
            if (shift == 63 && b > 1) return null
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }
}
