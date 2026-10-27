package com.ruta.app.util

import org.junit.Assert.*
import org.junit.Test

class PhotoRefTest {
    @Test fun storageReferencesParse() {
        assertEquals(PhotoRef.Storage("profiles/u1/avatar.jpg"), PhotoRef.parse("storage:profiles/u1/avatar.jpg"))
        assertEquals(PhotoRef.Storage("a/b.jpg"), PhotoRef.parse("  storage:/a/b.jpg "))
    }

    @Test fun legacyBase64StillWorks() {
        assertEquals(PhotoRef.Inline("QUJD"), PhotoRef.parse("QUJD"))
        assertEquals(PhotoRef.Inline("QUJD"), PhotoRef.parse("data:image/jpeg;base64,QUJD"))
    }

    @Test fun emptyThingsAreNone() {
        assertEquals(PhotoRef.None, PhotoRef.parse(null))
        assertEquals(PhotoRef.None, PhotoRef.parse("   "))
        assertEquals(PhotoRef.None, PhotoRef.parse("storage:"))
        assertEquals(PhotoRef.None, PhotoRef.parse("data:image/jpeg;base64, "))
    }

    @Test fun valuesRoundTrip() {
        val v = PhotoRef.toValue(PhotoPaths.avatar("u1"))
        assertEquals("storage:profiles/u1/avatar.jpg", v)
        assertEquals(PhotoRef.Storage("profiles/u1/avatar.jpg"), PhotoRef.parse(v))
    }

    @Test fun pathsMatchTheStorageRules() {
        assertEquals("ids/u/front.jpg", PhotoPaths.discountId("u", "front"))
        assertEquals("driver_docs/u/nbi.jpg", PhotoPaths.driverDoc("u", "nbi"))
        assertEquals("receipts/u/r1.jpg", PhotoPaths.receipt("u", "r1"))
    }
}
