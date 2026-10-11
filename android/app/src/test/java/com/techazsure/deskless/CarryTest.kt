package com.techazsure.deskless

import com.techazsure.deskless.link.Carried
import com.techazsure.deskless.link.Carry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CarryTest {
    @get:Rule
    val disk = TemporaryFolder()

    private val entry = """{"place":"p1","kind":"issue","title":"Corridor light not working","client_id":"abc123","severity":"MEDIUM"}"""

    private fun carry(name: String) = Carry(disk.newFolder(name), disk.newFolder("$name-cache"))

    @Test
    fun theSignatureIsTheOneTheServerWorksOut() {
        // HMAC-SHA256 as RFC 4231 gives it for its second test case, so both ends can be held to one answer.
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", Carry.sign("Jefe", "what do ya want for nothing?"))
        // A word changed on the way, or another person's key, gives another signature.
        assertNotEquals(Carry.sign("key-one", entry), Carry.sign("key-one", entry.replace("MEDIUM", "LOW")))
        assertNotEquals(Carry.sign("key-one", entry), Carry.sign("key-two", entry))
    }

    @Test
    fun anEntryCrossesWithItsPictureAndIsKeptUntilItHasGoneOn() {
        val sender = carry("sender")
        val picture = disk.newFile("picture.jpg").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        val pack = sender.wrap("abc123", "DL-USR-0001", "Nisha Rao", entry, Carry.sign("k", entry), "Corridor light not working", "Orchid Residency", picture)

        val keeper = carry("keeper")
        val held = keeper.unwrap(pack, from = "Nisha's phone")!!
        assertEquals("abc123", held.clientId)
        assertEquals("DL-USR-0001", held.sender)
        assertEquals("Nisha Rao", held.senderName)
        // Not a word of what was signed is changed by the carrying.
        assertEquals(entry, held.entry)
        assertEquals(Carry.sign("k", entry), held.signature)
        assertEquals("Orchid Residency", held.place)
        assertEquals("entry-abc123", held.report)
        assertEquals(Carry.fingerprint(picture), Carry.fingerprint(keeper.photoOf(held)!!))

        // It is still there after the app has been closed and opened, and gone once it has been sent on.
        assertEquals(listOf(held), keeper.load())
        keeper.drop(held)
        assertTrue(keeper.load().isEmpty())
        assertNull(keeper.photoOf(held))
    }

    @Test
    fun anEntryWithNoPictureKeepsNone() {
        val pack = carry("sender").wrap("n1", "DL-USR-0002", "Arun", entry, "sig", "A question", "Northgate College", photo = null)
        val keeper = carry("keeper")
        val held = keeper.unwrap(pack, from = "Arun's phone")
        assertNotNull(held)
        assertNull(keeper.photoOf(held!!))
    }

    @Test
    fun whatIsNotAnEntryIsLeftForWhatItIs() {
        val keeper = carry("keeper")
        // A report pack is a zip, a spoken message has its own mark: neither is taken for an entry.
        val zip = disk.newFile("report.zip").apply { writeBytes(byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(200)) }
        val talk = disk.newFile("talk.bin").apply { writeBytes("DLTALK1\n".toByteArray() + ByteArray(50)) }
        val cut = disk.newFile("cut.bin").apply { writeBytes("DLENTRY1\n".toByteArray() + byteArrayOf(0, 0)) }
        assertNull(keeper.unwrap(zip, "x"))
        assertNull(keeper.unwrap(talk, "x"))
        assertNull(keeper.unwrap(cut, "x"))
        assertTrue(keeper.load().isEmpty())
    }

    @Test
    fun aNameFromAnotherPhoneCannotLeaveTheFolder() {
        val folder = disk.newFolder("held")
        val keeper = Carry(folder, disk.newFolder("held-cache"))
        val pack = carry("sender").wrap("../../escape", "../DL-USR-0003", "Someone", entry, "sig", "T", "P", photo = null)
        val held: Carried = keeper.unwrap(pack, "x")!!
        assertEquals(listOf(held), keeper.load())
        // Whatever it calls itself, it is kept inside the folder.
        assertFalse(File(folder.parentFile, "escape.json").exists())
        assertTrue(folder.listFiles()!!.all { it.parentFile == folder && ".." !in it.name })
    }
}
