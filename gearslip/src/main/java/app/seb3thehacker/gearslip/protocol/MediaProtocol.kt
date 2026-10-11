package app.seb3thehacker.gearslip.protocol

import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.Wire

/** Projection media messages shared by audio and video sinks. */
internal object MediaProtocol {
    const val DATA = 0
    const val CODEC_CONFIG = 1
    const val SETUP = 0x8000
    const val START = 0x8001
    const val STOP = 0x8002
    const val CONFIG = 0x8003
    const val ACK = 0x8004
    const val MICROPHONE_REQUEST = 0x8005
    const val MICROPHONE_RESPONSE = 0x8006
    const val UNEXPECTED_MESSAGE = 0xff
    const val READY = 2
    const val MAX_ACK_COUNT = 4096

    data class Config(val status: Int, val maxUnacked: Int, val indices: List<Int>)
    data class Ack(val session: Int, val count: Long)

    fun setup(codec: Int): ByteArray = Protobuf.varintField(1, codec.toLong())

    fun start(session: Int, index: Int): ByteArray =
        Protobuf.varintField(1, session.toLong()) + Protobuf.varintField(2, index.toLong())

    fun ack(session: Int): ByteArray =
        Protobuf.varintField(1, session.toLong()) + Protobuf.varintField(2, 1)

    fun config(body: ByteArray): Config? {
        val fields = Wire.fields(body)
        val status = Wire.varint(fields, 1)?.toInt() ?: return null
        val window = Wire.varint(fields, 2) ?: return null
        if (status !in 1..2 || window !in 1..Int.MAX_VALUE.toLong()) return null
        return Config(status, window.toInt(), Wire.repeatedInts(fields, 3))
    }

    fun ack(body: ByteArray): Ack? {
        val fields = Wire.fields(body)
        val session = Wire.varint(fields, 1)?.toInt() ?: return null
        // Android Auto uses the receiver's timestamp list when present, even if count differs.
        val timestamps = Wire.repeatedLongs(fields, 3) ?: return null
        val count = if (timestamps.isNotEmpty()) timestamps.size.toLong() else Wire.varint(fields, 2) ?: 0
        if (count !in 1..MAX_ACK_COUNT.toLong()) return null
        return Ack(session, count)
    }

    fun data(timestampUs: Long, bytes: ByteArray, size: Int = bytes.size): ByteArray {
        require(size in 0..bytes.size)
        val out = ByteArray(8 + size)
        for (i in 0..7) out[i] = (timestampUs ushr ((7 - i) * 8)).toByte()
        bytes.copyInto(out, 8, 0, size)
        return out
    }
}
