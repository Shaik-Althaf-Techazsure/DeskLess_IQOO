package com.techazsure.deskless

import com.techazsure.deskless.link.Pairing
import com.techazsure.deskless.link.Peer
import com.techazsure.deskless.link.Qr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {
    private val asha = Peer(Pairing.newId(), "Asha & Co / site 2", Pairing.newKey())

    @Test
    fun aCodeSurvivesTheQrText() {
        assertEquals(asha, Pairing.parse(Pairing.uri(asha)))
        assertTrue(asha.id.matches(Regex("DL-[A-Z2-9]{8}")))
    }

    @Test
    fun otherCodesAreNotTakenForOurs() {
        assertNull(Pairing.parse("https://example.com/?id=DL-ABCDEFGH&key=0123456789abcdef"))
        assertNull(Pairing.parse("deskless://pair?id=XX-1&key=0123456789abcdef"))
        assertNull(Pairing.parse("deskless://pair?id=DL-ABCDEFGH&key=short"))
    }

    @Test
    fun aProofBelongsToOnePairInOneDirection() {
        val proof = Pairing.proof(asha.key, "DL-AAAAAAAA", "DL-BBBBBBBB")
        assertEquals(proof, Pairing.proof(asha.key, "DL-AAAAAAAA", "DL-BBBBBBBB"))
        assertNotEquals(proof, Pairing.proof(asha.key, "DL-BBBBBBBB", "DL-AAAAAAAA"))
        assertNotEquals(proof, Pairing.proof(Pairing.newKey(), "DL-AAAAAAAA", "DL-BBBBBBBB"))
    }

    @Test
    fun aPhoneAnnouncesItsCodeAndName() {
        assertEquals(asha.id to "Asha & Co / site 2", Pairing.fromEndpointName(Pairing.endpointName(asha)))
        assertNull(Pairing.fromEndpointName("Somebody's earbuds"))
    }

    @Test
    fun theQrCodeIsReadBack() {
        // Draw the code as a camera would see it: black squares on white, with a white border.
        val text = Pairing.uri(asha)
        val squares = Qr.squares(text)
        val scale = 6
        val border = 4
        val side = (squares.width + 2 * border) * scale
        val picture = ByteArray(side * side) { 255.toByte() }
        for (row in 0 until squares.height) {
            for (column in 0 until squares.width) {
                if (!squares.get(column, row)) continue
                for (y in 0 until scale) {
                    for (x in 0 until scale) picture[((row + border) * scale + y) * side + (column + border) * scale + x] = 0
                }
            }
        }
        assertEquals(text, Qr.read(picture, side, side))
        assertNull(Qr.read(ByteArray(side * side) { 255.toByte() }, side, side))
    }
}
