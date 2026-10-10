package com.techazsure.deskless

import com.techazsure.deskless.link.Pairing
import com.techazsure.deskless.link.Seal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SealTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val engineer = Seal.newKeys()
    private val manager = Seal.newKeys()
    private val relay = Seal.newKeys()

    /** A report of a few hundred kilobytes, sealed by the engineer for the manager. */
    private fun sealed(): Pair<ByteArray, java.io.File> {
        val report = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val plain = folder.newFile().apply { writeBytes(report) }
        val out = folder.newFile()
        Seal.seal(plain, out, "0123456789abcdef01", "DL-ENGINEER", "Asha & Co", "DL-MANAGER1", 1_760_000_000_000L, engineer, manager.public)
        return report to out
    }

    @Test
    fun onlyThePhoneItIsForCanOpenIt() {
        val (report, sealed) = sealed()
        val opened = folder.newFile()
        assertTrue(Seal.open(sealed, opened, manager, engineer.public))
        assertArrayEquals(report, opened.readBytes())

        // A phone carrying it has the file and both public keys, and still cannot open it.
        val tried = folder.newFile()
        assertFalse(Seal.open(sealed, tried, relay, engineer.public))
        assertFalse(tried.exists())
    }

    @Test
    fun whatIsInsideCannotBeReadOffTheFile() {
        val (report, sealed) = sealed()
        val bytes = sealed.readBytes()
        // No run of the report shows through, and the file is barely larger than the report.
        val run = report.copyOfRange(1000, 1032)
        assertFalse((0..bytes.size - run.size).any { at -> run.indices.all { bytes[at + it] == run[it] } })
        assertTrue(bytes.size - report.size < 1024)
    }

    @Test
    fun theOutsideSaysWhereItIsGoingAndNoMore() {
        val label = Seal.label(sealed().second)!!
        assertEquals("0123456789abcdef01", label.id)
        assertEquals("DL-ENGINEER", label.from)
        assertEquals("Asha & Co", label.fromName)
        assertEquals("DL-MANAGER1", label.to)
        assertEquals(1_760_000_000_000L, label.made)
        assertNull(Seal.label(folder.newFile().apply { writeText("not a sealed report") }))
    }

    @Test
    fun aChangeOnTheWayIsCaught() {
        val (_, sealed) = sealed()
        val bytes = sealed.readBytes()

        // One bit of the contents flipped.
        val body = folder.newFile().apply { writeBytes(bytes.copyOf().also { it[it.size - 5000] = (it[it.size - 5000].toInt() xor 1).toByte() }) }
        assertFalse(Seal.open(body, folder.newFile(), manager, engineer.public))

        // The label rewritten to name another sender: the contents no longer open.
        val text = String(bytes, Charsets.ISO_8859_1).replace("DL-ENGINEER", "DL-IMPOSTOR")
        val relabelled = folder.newFile().apply { writeBytes(text.toByteArray(Charsets.ISO_8859_1)) }
        assertEquals("DL-IMPOSTOR", Seal.label(relabelled)!!.from)
        assertFalse(Seal.open(relabelled, folder.newFile(), manager, engineer.public))
    }

    @Test
    fun theReceiverCanTellWhoSealedIt() {
        // Sealed by the relay but claiming to come from the engineer: the manager checks against the
        // engineer's key, and it does not open.
        val plain = folder.newFile().apply { writeBytes(ByteArray(2000) { 7 }) }
        val forged = folder.newFile()
        Seal.seal(plain, forged, "aaaaaaaaaaaaaaaaaa", "DL-ENGINEER", "Asha", "DL-MANAGER1", 1L, relay, manager.public)
        assertFalse(Seal.open(forged, folder.newFile(), manager, engineer.public))
        assertTrue(Seal.open(forged, folder.newFile(), manager, relay.public))
    }

    @Test
    fun aReceiptIsSignedByThePhoneThatOpenedIt() {
        val text = "deskless-receipt:0123456789abcdef01"
        val signature = Seal.sign(manager, text)
        assertTrue(Seal.signedBy(manager.public, text, signature))
        assertFalse(Seal.signedBy(relay.public, text, signature))
        assertFalse(Seal.signedBy(manager.public, "deskless-receipt:ffffffffffffffffff", signature))
        assertFalse(Seal.signedBy(manager.public, text, "00"))
    }

    @Test
    fun theProofCoversThePublicKey() {
        val key = Pairing.newKey()
        val proof = Pairing.proof(key, "DL-AAAAAAAA", "DL-BBBBBBBB", engineer.public)
        assertEquals(proof, Pairing.proof(key, "DL-AAAAAAAA", "DL-BBBBBBBB", engineer.public))
        // Somebody in between who swaps in a key of their own breaks the proof.
        assertNotEquals(proof, Pairing.proof(key, "DL-AAAAAAAA", "DL-BBBBBBBB", relay.public))
    }
}
