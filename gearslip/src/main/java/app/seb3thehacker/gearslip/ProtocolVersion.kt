package app.seb3thehacker.gearslip

/** The DHU 2.0 receiver offers projection protocol 1.7; older 1.x peers negotiate their minor. */
internal object ProtocolVersion {
    data class Response(val major: Int, val minor: Int, val supported: Boolean) {
        val bytes: ByteArray get() = byteArrayOf(
            (major ushr 8).toByte(), major.toByte(), (minor ushr 8).toByte(), minor.toByte(),
            if (supported) 0 else -1, if (supported) 0 else -1,
        )
    }

    fun respond(major: Int, minor: Int): Response = Response(1, minOf(minor, 7), major == 1)
}
