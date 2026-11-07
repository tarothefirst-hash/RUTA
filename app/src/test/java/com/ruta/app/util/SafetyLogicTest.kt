package com.ruta.app.util

import org.junit.Assert.*
import org.junit.Test

class SafetyLogicTest {
    @Test fun summaryCoversEveryCombination() {
        assertEquals("Waiting for both", SafetyLogic.summary(null, null))
        assertEquals("Both confirmed safe", SafetyLogic.summary(true, true))
        assertEquals("Passenger safe, waiting for driver", SafetyLogic.summary(true, null))
        assertEquals("Driver safe, waiting for passenger", SafetyLogic.summary(null, true))
        assertEquals("Needs attention", SafetyLogic.summary(true, false))
        assertEquals("Needs attention", SafetyLogic.summary(false, null))
    }

    @Test fun contactNoticeNamesThePerson() {
        assertTrue(SafetyLogic.contactNotice("Ana", true).first.contains("Ana"))
        assertTrue(SafetyLogic.contactNotice("Ana", false).first.contains("needs help"))
    }

    @Test fun roleLookup() {
        assertEquals(SafetyLogic.ROLE_PASSENGER, SafetyLogic.roleOn("p", "p", "d"))
        assertEquals(SafetyLogic.ROLE_DRIVER, SafetyLogic.roleOn("d", "p", "d"))
        assertNull(SafetyLogic.roleOn("x", "p", "d"))
    }

    @Test fun emergencyCancelGetsItsOwnNotification() {
        val n = NotificationLogic.bookingEventText("CANCELLED", SafetyLogic.REASON_EMERGENCY_STOP)!!
        assertTrue(n.first.contains("emergency"))
        assertEquals("Ride cancelled", NotificationLogic.bookingEventText("CANCELLED")!!.first)
        assertEquals("Ride cancelled", NotificationLogic.bookingEventText("CANCELLED", "NO_SHOW")!!.first)
    }
}
