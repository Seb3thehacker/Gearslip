package app.seb3thehacker.gearslip.protocol

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reserves credit before enqueueing data, so an immediate ACK cannot race its sender. */
internal class MediaWindow(private val timeoutMs: Long = 2_000) {
    private val lock = Object()
    private var active = false
    private var session = 0
    private var limit = 0
    private var outstanding = 0

    val inFlight: Int get() = synchronized(lock) { outstanding }

    fun start(session: Int, limit: Int) = synchronized(lock) {
        require(limit > 0)
        this.session = session
        this.limit = limit
        outstanding = 0
        active = true
        lock.notifyAll()
    }

    fun stop() = synchronized(lock) {
        active = false
        outstanding = 0
        lock.notifyAll()
    }

    /** False means the stream stopped while waiting. A timeout never invents new credit. */
    fun reserve(expectedSession: Int): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (active && session == expectedSession && outstanding >= limit) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                active = false
                lock.notifyAll()
                throw IOException("Media acknowledgement timed out")
            }
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        if (!active || session != expectedSession) return false
        outstanding++
        true
    }

    fun acknowledge(ack: MediaProtocol.Ack): Boolean = synchronized(lock) {
        if (!active || ack.session != session || ack.count > outstanding || ack.count == 0L) return false
        outstanding -= ack.count.toInt()
        lock.notifyAll()
        true
    }
}
