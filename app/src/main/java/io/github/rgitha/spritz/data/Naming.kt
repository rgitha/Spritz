package io.github.rgitha.spritz.data

import java.text.Collator

/**
 * Device names are unique across all devices. A device keeps the base name the user
 * typed plus, when that name is already taken, a number suffix: "Aer Smart Matic (2)".
 * The number is assigned when the device is saved and never changes afterwards.
 */
object Naming {
    const val MAX_NAME = 30

    fun display(base: String, suffix: Int?) = if (suffix == null) base else "$base ($suffix)"

    /** Compares names ignoring case and extra spaces. */
    fun normalize(name: String) = name.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * The suffix for a device named [base] among [others] (every device except this one):
     * null if the name is free, else one more than the highest suffix the others with the
     * same base have, skipping any number that would clash with another display name.
     */
    fun suffixFor(base: String, others: List<SavedDevice>): Int? {
        val key = normalize(base)
        val taken = others.map { normalize(it.displayName) }.toSet()
        val sameBase = others.filter { normalize(it.name) == key }
        if (key !in taken && sameBase.isEmpty()) return null
        var n = (sameBase.maxOfOrNull { it.suffix ?: 1 } ?: 1) + 1
        while (normalize(display(base, n)) in taken) n++
        return n
    }

    /** Gives suffixes to devices whose names clash with an earlier one, in the order added. */
    fun dedupe(devices: List<SavedDevice>): List<SavedDevice> {
        val done = mutableListOf<SavedDevice>()
        for (d in devices) {
            val clash = done.any { normalize(it.displayName) == normalize(d.displayName) }
            done += if (clash) d.copy(suffix = suffixFor(d.name, done)) else d
        }
        return done
    }

    /** Rooms in use, alphabetically. */
    fun rooms(devices: List<SavedDevice>): List<String> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return devices.mapNotNull { it.room }.distinctBy { normalize(it) }.sortedWith(collator)
    }

    /**
     * Devices grouped by room: rooms alphabetically, then devices without one (null key).
     * Within a group devices keep the order they were added.
     */
    fun grouped(devices: List<SavedDevice>): List<Pair<String?, List<SavedDevice>>> {
        val byRoom = rooms(devices).map { room ->
            room to devices.filter { it.room != null && normalize(it.room) == normalize(room) }
        }
        val noRoom = devices.filter { it.room == null }
        return if (noRoom.isEmpty()) byRoom else byRoom + (null to noRoom)
    }
}
