package com.ruta.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFieldsTest {

    @Test
    fun splitName_putsTheLastWordInLastName() {
        assertEquals("Juan" to "Cruz", ProfileFields.splitName("Juan Cruz"))
        assertEquals("Maria Clara" to "Santos", ProfileFields.splitName("  Maria   Clara Santos "))
        assertEquals("Cher" to "", ProfileFields.splitName("Cher"))
    }

    @Test
    fun phone_isNormalisedAndValidated() {
        assertEquals("09171234567", ProfileFields.normalizePhone("0917 123-4567"))
        assertEquals("+639171234567", ProfileFields.normalizePhone("+63 (917) 123 4567"))
        assertTrue(ProfileFields.isValidPhone("09171234567"))
        assertTrue(ProfileFields.isValidPhone("+639171234567"))
        assertFalse(ProfileFields.isValidPhone("12345"))
        assertFalse(ProfileFields.isValidPhone(""))
        assertFalse(ProfileFields.isValidPhone("abc"))
    }

    @Test
    fun name_needsAtLeastTwoCharacters() {
        assertTrue(ProfileFields.isValidName("Al"))
        assertFalse(ProfileFields.isValidName(" A "))
        assertFalse(ProfileFields.isValidName(""))
    }

    @Test
    fun updates_writeEveryFieldTheAppReads_andNeverTheEmail() {
        val updates = ProfileFields.updatesFor("  Juan   Dela Cruz ", "0917 123 4567")
        assertEquals("Juan Dela", updates["firstName"])
        assertEquals("Cruz", updates["lastName"])
        assertEquals("Juan Dela Cruz", updates["name"])
        assertEquals("09171234567", updates["phoneNumber"])
        assertEquals("09171234567", updates["phone"])
        assertFalse(updates.containsKey("email"))
    }
}
