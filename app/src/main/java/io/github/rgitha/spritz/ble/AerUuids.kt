package io.github.rgitha.spritz.ble

import java.util.UUID

object AerUuids {
    private fun nus(n: Int): UUID = UUID.fromString("6e40000$n-b5a3-f393-e0a9-e50e24dcca9e")

    val SERVICE: UUID = nus(0)
    /** Replies arrive here as notifications. It has no CCCD. */
    val NOTIFY: UUID = nus(1)
    val COMMAND: UUID = nus(2)
    /** Schedule uploads go here. */
    val SCHEDULE: UUID = nus(4)

    const val NAME_MATCH = "smart matic"

    fun isAerName(name: String?) = name?.contains(NAME_MATCH, ignoreCase = true) == true
}
