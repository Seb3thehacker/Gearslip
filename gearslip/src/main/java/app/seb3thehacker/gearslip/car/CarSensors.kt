package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Raw sensor readings, keyed by SensorBatch field number, which matches the sensor type id. */
object CarSensors {

    data class Reading(val typeId: Int, val typeName: String, val raw: String, val updatedAtMs: Long)

    private val readingsFlow = MutableStateFlow<List<Reading>>(emptyList())
    val readings: StateFlow<List<Reading>> = readingsFlow

    /** Seeds one row per sensor type the head unit advertised, so the screen shows what's on
     * offer even before the first event for it arrives. */
    fun onDiscovered(types: List<Int>) {
        readingsFlow.value = types.map { id ->
            Reading(id, SensorType.name(id), "(waiting for data)", 0L)
        }
    }

    /** Each SensorBatch field contains repeated readings of one sensor type. */
    fun onEvent(body: ByteArray) {
        val now = System.currentTimeMillis()
        val fields = Wire.fields(body).filter { it.wireType == 2 }
        val byType = fields.groupBy { it.number }
        val updated = byType.mapValues { (number, group) ->
            Reading(
                number,
                SensorType.name(number),
                group.joinToString("; ") { field ->
                    field.bytes?.let { Protobuf.describe(it, indent = "").trim().replace("\n", ", ") }
                        ?: "varint=${field.varint}"
                },
                now,
            )
        }
        val existing = readingsFlow.value.associateBy { it.typeId }
        readingsFlow.value = (existing + updated).values.sortedBy { it.typeId }
    }

    fun clear() {
        readingsFlow.value = emptyList()
    }
}

/** Sensor type values shared by the discovery service and SensorBatch. */
object SensorType {
    private val NAMES = mapOf(
        1 to "Location (GPS)",
        2 to "Compass",
        3 to "Speed",
        4 to "RPM",
        5 to "Odometer",
        6 to "Fuel",
        7 to "Parking brake",
        8 to "Gear",
        9 to "Diagnostics",
        10 to "Night mode",
        11 to "Environment",
        12 to "HVAC",
        13 to "Driving status",
        14 to "Dead reckoned location",
        15 to "Passenger",
        16 to "Door",
        17 to "Light",
        18 to "Tire pressure",
        19 to "Accelerometer",
        20 to "Gyroscope",
        21 to "GPS satellites",
        22 to "Toll card",
        23 to "Vehicle energy model",
        24 to "Trailer",
        25 to "Raw vehicle energy model",
        26 to "Raw EV trip settings",
    )

    fun name(id: Int): String = NAMES[id]?.let { "$it (#$id)" } ?: "Sensor #$id"
}
