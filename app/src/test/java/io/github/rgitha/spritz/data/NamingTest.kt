package io.github.rgitha.spritz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamingTest {
    private fun dev(name: String, suffix: Int? = null, room: String? = null) =
        SavedDevice(address = "$name-$suffix-$room", name = name, suffix = suffix, room = room)

    @Test fun freeNameHasNoSuffix() {
        assertNull(Naming.suffixFor("Aer", emptyList()))
        assertNull(Naming.suffixFor("Aer", listOf(dev("Hall"))))
    }

    @Test fun duplicateGetsOneMoreThanHighestExisting() {
        assertEquals(2, Naming.suffixFor("Aer", listOf(dev("Aer"))))
        assertEquals(4, Naming.suffixFor("Aer", listOf(dev("Aer"), dev("Aer", 2), dev("Aer", 3))))
        // A gap isn't refilled; the highest number freed is.
        assertEquals(4, Naming.suffixFor("Aer", listOf(dev("Aer"), dev("Aer", 3))))
        assertEquals(3, Naming.suffixFor("Aer", listOf(dev("Aer"), dev("Aer", 2))))
        assertEquals(3, Naming.suffixFor("Aer", listOf(dev("Aer", 2))))
    }

    @Test fun comparesIgnoringCaseAndSpaces() {
        assertEquals(2, Naming.suffixFor("  aer   smart matic ", listOf(dev("Aer Smart Matic"))))
    }

    @Test fun avoidsATypedSuffix() {
        // Someone named a device "Aer (2)" by hand.
        assertNull(Naming.suffixFor("Aer", listOf(dev("Aer (2)"))))
        assertEquals(3, Naming.suffixFor("Aer", listOf(dev("Aer"), dev("Aer (2)"))))
        assertEquals(2, Naming.suffixFor("Aer (2)", listOf(dev("Aer", 2))))
    }

    @Test fun dedupeKeepsOrderAndNumbersLaterDuplicates() {
        val out = Naming.dedupe(listOf(dev("Aer"), dev("Hall"), dev("Aer"), dev("Aer")))
        assertEquals(listOf("Aer", "Hall", "Aer (2)", "Aer (3)"), out.map { it.displayName })
        assertEquals(out, Naming.dedupe(out))
    }

    @Test fun groupsRoomsAlphabeticallyWithNoRoomLast() {
        val a = dev("A", room = "living room")
        val b = dev("B", room = "Bedroom")
        val c = dev("C", room = "Living room")
        val d = dev("D")
        val groups = Naming.grouped(listOf(a, b, c, d))
        assertEquals(listOf("Bedroom", "living room", null), groups.map { it.first })
        assertEquals(listOf(a, c), groups[1].second)
        assertEquals(listOf(d), groups[2].second)
        assertEquals(listOf<String?>(null), Naming.grouped(listOf(d)).map { it.first })
    }
}
