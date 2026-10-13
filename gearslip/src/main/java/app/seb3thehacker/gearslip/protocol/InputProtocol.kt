package app.seb3thehacker.gearslip.protocol

import app.seb3thehacker.gearslip.TouchPoint
import app.seb3thehacker.gearslip.Wire

/** Decodes the touch coordinates and pointer index carried by an InputReport. */
internal object InputProtocol {
    data class Touch(val action: Int, val actionIndex: Int, val points: List<TouchPoint>)

    fun touch(body: ByteArray, size: Pair<Int, Int>?): Touch? {
        val fields = Wire.fields(body)
        val action = Wire.varint(fields, 3)?.toInt() ?: 0
        if (action !in setOf(0, 1, 2, 5, 6)) return null
        val pointers = Wire.allBytes(fields, 1)
        if (pointers.isEmpty() || pointers.size > 32) return null
        val actionIndex = Wire.varint(fields, 2)?.toInt() ?: 0
        if (actionIndex !in pointers.indices) return null
        val points = pointers.map { pointer ->
            val values = Wire.fields(pointer)
            val x = Wire.varint(values, 1)?.toInt() ?: return null
            val y = Wire.varint(values, 2)?.toInt() ?: return null
            val id = Wire.varint(values, 3) ?: return null
            if (id !in 0L..31L) return null
            TouchPoint(id.toInt(), clamp(x, size?.first), clamp(y, size?.second))
        }
        if (points.map { it.id }.toSet().size != points.size) return null
        return Touch(action, actionIndex, points)
    }

    private fun clamp(value: Int, limit: Int?): Float = when {
        limit == null -> value.toFloat()
        limit <= 0 -> 0f
        else -> value.coerceIn(0, limit - 1).toFloat()
    }
}
