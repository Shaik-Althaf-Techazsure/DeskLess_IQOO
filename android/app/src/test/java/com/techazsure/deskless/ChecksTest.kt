package com.techazsure.deskless

import com.techazsure.deskless.account.Checks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {
    // Made-up numbers in the 9999 range kept for testing, each with its correct check digit; the account
    // server's own tests make them the same way.
    private val good = listOf("999900000016", "999900000028", "999900000037")

    @Test
    fun aadhaarNumbersWithTheRightCheckDigitPass() {
        good.forEach { assertTrue(it, Checks.aadhaar(it)) }
    }

    @Test
    fun anyOneWrongDigitIsCaught() {
        val number = good[0]
        for (place in number.indices) {
            for (digit in '0'..'9') {
                val changed = number.substring(0, place) + digit + number.substring(place + 1)
                assertEquals(changed, changed == number, Checks.aadhaar(changed))
            }
        }
    }

    @Test
    fun whatCannotBeAnAadhaarNumber() {
        assertFalse(Checks.aadhaar("99990000001"))        // eleven digits
        assertFalse(Checks.aadhaar("9999 0000 0016"))     // the spaces are taken out before checking
        assertFalse(Checks.aadhaar("099900000016"))       // none starts with 0 or 1
        assertFalse(Checks.aadhaar("abcdefghijkl"))
    }

    @Test
    fun aCodeToSignUpWithIsTheSameHoweverItIsTyped() {
        assertEquals("YV4DAQUD", Checks.cleanInvite("yv4d-aqud"))
        assertEquals("YV4DAQUD", Checks.cleanInvite(" YV4D AQUD "))
        assertTrue(Checks.invite("yv4d-aqud"))
        assertFalse(Checks.invite("YV4D-AQU"))            // one short
        assertFalse(Checks.invite("YV4D-AQU0"))           // no code has a 0, 1, I or O: they are too easily confused
        assertFalse(Checks.invite(""))
    }

    @Test
    fun mobileNumbersAndEmailAddresses() {
        assertTrue(Checks.mobile("9876543210"))
        assertTrue(Checks.mobile("+919876543210"))
        assertTrue(Checks.mobile("09876543210"))
        assertFalse(Checks.mobile("12345"))
        assertFalse(Checks.mobile("5876543210"))
        assertTrue(Checks.email("asha@example.com"))
        assertFalse(Checks.email("asha@example"))
        assertFalse(Checks.email("not an email"))
    }
}
