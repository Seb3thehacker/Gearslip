package app.seb3thehacker.gearslip

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Android Auto framing with plaintext fragmentation before per-frame encryption. */
object Frames {

    const val CHANNEL_CONTROL = 0

    const val TYPE_MIDDLE = 0
    const val TYPE_FIRST = 1
    const val TYPE_LAST = 2
    const val TYPE_BULK = 3

    const val ENCRYPTED = 1 shl 3

    /**
     * Message type bit. Channel-open messages are CONTROL even when sent on a service
     * channel - aasdk sets MessageType::CONTROL only in sendChannelOpenResponse and
     * MessageType::SPECIFIC everywhere else (e.g.
     * src/Channel/MediaSink/Video/VideoMediaSinkService.cpp:52 vs :66). Getting this wrong
     * makes the head unit drop the USB link outright rather than answer.
     */
    const val MESSAGE_SPECIFIC = 0
    const val MESSAGE_CONTROL = 1 shl 2

    // Android Auto's default fragment budget includes the framing header. TLS adds overhead.
    private const val FRAGMENT_SIZE = 0x3f00
    private const val HEADER_SIZE = 4
    private const val FIRST_HEADER_SIZE = 8
    const val MAX_FRAME_PAYLOAD = FRAGMENT_SIZE - HEADER_SIZE

    class Frame(
        val channel: Int,
        val frameType: Int,
        val encrypted: Boolean,
        val messageType: Int,
        val payload: ByteArray,
        val totalSize: Int? = null,
    )

    /**
     * Builds one frame. [totalSize] is the full plaintext message length and is written only
     * for FIRST frames, matching aasdk's MessageOutStream::setFrameSize
     * (`FrameSize(payloadSize, totalSize)` for FIRST, `FrameSize(payloadSize)` otherwise).
     */
    fun build(
        channel: Int,
        encrypted: Boolean,
        payload: ByteArray,
        messageType: Int = MESSAGE_SPECIFIC,
        frameType: Int = TYPE_BULK,
        totalSize: Int? = null,
    ): ByteArray {
        require(channel in 0..255 && payload.size <= 0xffff)
        require(frameType in TYPE_MIDDLE..TYPE_BULK)
        require(messageType == MESSAGE_SPECIFIC || messageType == MESSAGE_CONTROL)
        require((frameType == TYPE_FIRST) == (totalSize != null))
        require(totalSize == null || totalSize in 2..MAX_MESSAGE_SIZE)
        val flags = frameType or messageType or (if (encrypted) ENCRYPTED else 0)
        val header = byteArrayOf(
            channel.toByte(),
            flags.toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        )
        if (totalSize == null) return header + payload
        val extended = byteArrayOf(
            ((totalSize shr 24) and 0xFF).toByte(),
            ((totalSize shr 16) and 0xFF).toByte(),
            ((totalSize shr 8) and 0xFF).toByte(),
            (totalSize and 0xFF).toByte(),
        )
        return header + extended + payload
    }

    /** Splits plaintext before encryption; FIRST carries the complete plaintext length. */
    fun writeMessage(
        output: OutputStream,
        channel: Int,
        encrypted: Boolean,
        messageType: Int,
        message: ByteArray,
        encrypt: (ByteArray) -> ByteArray,
    ): Int {
        require(message.size in 2..MAX_MESSAGE_SIZE)
        val fragmented = message.size > FRAGMENT_SIZE - HEADER_SIZE
        require(messageType != MESSAGE_CONTROL || !fragmented) { "Channel control messages cannot be fragmented" }
        var offset = 0
        var frames = 0
        while (offset < message.size) {
            val first = fragmented && offset == 0
            val headerSize = if (first) FIRST_HEADER_SIZE else HEADER_SIZE
            val end = minOf(offset + FRAGMENT_SIZE - headerSize, message.size)
            val type = when {
                !fragmented -> TYPE_BULK
                first -> TYPE_FIRST
                end == message.size -> TYPE_LAST
                else -> TYPE_MIDDLE
            }
            val chunk = message.copyOfRange(offset, end)
            output.write(build(channel, encrypted, if (encrypted) encrypt(chunk) else chunk,
                messageType, type, if (type == TYPE_FIRST) message.size else null))
            offset = end
            frames++
        }
        return frames
    }

    // Local resource limits, not protocol constants. No supported media packet needs more.
    private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024
    private const val MAX_BUFFERED_SIZE = 32 * 1024 * 1024

    /**
     * Pulls whole frames out of an accumulating buffer.
     *
     * A read() on the accessory descriptor returns one bulk transfer, which is usually one
     * frame - but nothing guarantees it, so this never assumes alignment.
     */
    class Parser {
        private var pending = ByteArray(0)

        fun append(data: ByteArray, length: Int) {
            require(length in 0..data.size)
            if (length > MAX_BUFFERED_SIZE - pending.size) throw IOException("Frame buffer limit exceeded")
            val grown = ByteArray(pending.size + length)
            System.arraycopy(pending, 0, grown, 0, pending.size)
            System.arraycopy(data, 0, grown, pending.size, length)
            pending = grown
        }

        fun next(): Frame? {
            if (pending.size < 4) return null
            val channel = pending[0].toInt() and 0xFF
            val flags = pending[1].toInt() and 0xFF
            val frameType = flags and TYPE_BULK
            // Extended size is written only for FIRST - BULK (3) keeps the short header.
            val headerLength = if (frameType == TYPE_FIRST) 8 else 4
            if (pending.size < headerLength) return null

            val frameSize = ((pending[2].toInt() and 0xFF) shl 8) or (pending[3].toInt() and 0xFF)
            val total = headerLength + frameSize
            if (pending.size < total) return null

            val messageSize = if (frameType == TYPE_FIRST) {
                var size = 0L
                for (i in 4..7) size = (size shl 8) or (pending[i].toLong() and 0xff)
                if (size !in 2..MAX_MESSAGE_SIZE.toLong()) throw IOException("Invalid fragmented message length: $size")
                size.toInt()
            } else null
            val payload = pending.copyOfRange(headerLength, total)
            pending = pending.copyOfRange(total, pending.size)
            return Frame(
                channel, frameType, (flags and ENCRYPTED) != 0, flags and MESSAGE_CONTROL, payload, messageSize,
            )
        }
    }

    /** Rejoins messages on independent channels, validating their plaintext length and flags. */
    class Assembler {
        private class Partial(val first: Frame, val data: ByteArrayOutputStream = ByteArrayOutputStream())
        private val partial = HashMap<Int, Partial>()
        private var buffered = 0

        fun offer(frame: Frame, payload: ByteArray): ByteArray? {
            if (frame.frameType == TYPE_BULK) {
                if (partial.containsKey(frame.channel)) fail("Complete message interrupted fragments")
                return payload
            }
            val message = if (frame.frameType == TYPE_FIRST) {
                if (frame.totalSize == null || partial.containsKey(frame.channel)) fail("Invalid first fragment")
                Partial(frame).also { partial[frame.channel] = it }
            } else partial[frame.channel] ?: fail("Fragment without a first frame")
            val first = message.first
            if (first.encrypted != frame.encrypted || first.messageType != frame.messageType) fail("Fragment flags changed")
            val expected = first.totalSize ?: fail("Missing total size")
            if (expected !in 2..MAX_MESSAGE_SIZE || payload.size > expected - message.data.size()) fail("Fragment exceeds message length")
            if (payload.size > MAX_BUFFERED_SIZE - buffered) fail("Fragment buffer limit exceeded")
            message.data.write(payload)
            buffered += payload.size
            if (frame.frameType != TYPE_LAST) return null
            if (message.data.size() != expected) fail("Truncated fragmented message")
            partial.remove(frame.channel)
            buffered -= message.data.size()
            return message.data.toByteArray()
        }

        private fun fail(message: String): Nothing {
            partial.clear()
            buffered = 0
            throw IOException(message)
        }
    }
}
