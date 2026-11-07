package com.ruta.app.util

import com.ruta.app.util.LivenessLogic.Frame
import com.ruta.app.util.LivenessLogic.Step
import org.junit.Assert.*
import org.junit.Test

class LivenessLogicTest {
    private var clock = 0L
    private fun machine(order: List<Step> = LivenessLogic.DEFAULT_ORDER) = LivenessLogic.Machine(order) { clock }

    private fun face(
        eyes: Float = 0.9f, smile: Float = 0.1f, yaw: Float = 0f,
        centered: Boolean = true, big: Boolean = true, count: Int = 1
    ) = Frame(count, centered, big, eyes, eyes, smile, yaw)

    private fun feed(m: LivenessLogic.Machine, f: Frame, n: Int) = repeat(n) { m.onFrame(f) }

    @Test fun fullHappyPath() {
        val m = machine()
        feed(m, face(), 6);                 assertEquals(Step.BLINK, m.step)
        feed(m, face(eyes = 0.9f), 1)
        feed(m, face(eyes = 0.1f), 1)
        feed(m, face(eyes = 0.9f), 1);      assertEquals(Step.TURN, m.step)
        feed(m, face(yaw = 30f), 3);        assertEquals(Step.SMILE, m.step)
        feed(m, face(smile = 0.9f), 3);     assertEquals(Step.CENTER, m.step)
        feed(m, face(), 4);                 assertEquals(Step.DONE, m.step)
        assertEquals(1f, m.progress, 0.001f)
    }

    @Test fun aStillPhotoCannotPassTheBlink() {
        val m = machine()
        feed(m, face(), 6)
        feed(m, face(eyes = 0.9f), 200)     // never blinks
        assertEquals(Step.BLINK, m.step)
    }

    @Test fun blinkNeedsOpenThenClosedThenOpen() {
        val m = machine()
        feed(m, face(), 6)
        feed(m, face(eyes = 0.1f), 5)       // closed first does not count
        assertEquals(Step.BLINK, m.step)
        feed(m, face(eyes = 0.9f), 1)
        feed(m, face(eyes = 0.1f), 1)
        feed(m, face(eyes = 0.9f), 1)
        assertEquals(Step.TURN, m.step)
    }

    @Test fun faceMustBeCenteredAndBigToStart() {
        val m = machine()
        feed(m, face(centered = false), 30)
        feed(m, face(big = false), 30)
        assertEquals(Step.FACE, m.step)
    }

    @Test fun twoFacesOrNoneNeverAdvance() {
        val m = machine()
        feed(m, face(count = 2), 50)
        feed(m, face(count = 0), 50)
        assertEquals(Step.FACE, m.step)
    }

    @Test fun losingTheFaceMidwayRestarts() {
        val m = machine()
        feed(m, face(), 6)
        assertEquals(Step.BLINK, m.step)
        feed(m, face(count = 0), LivenessLogic.LOST_FACE_FRAMES)
        assertEquals(Step.FACE, m.step)
        assertEquals(1, m.restarts)
    }

    @Test fun timingOutRestartsFromTheTop() {
        val m = machine()
        feed(m, face(), 6)
        clock += LivenessLogic.STEP_TIMEOUT_MS + 1
        m.onFrame(face())
        assertEquals(Step.FACE, m.step)
        assertEquals(1, m.restarts)
    }

    @Test fun challengeOrderFollowsTheShuffle() {
        val m = machine(listOf(Step.SMILE, Step.TURN, Step.BLINK))
        feed(m, face(), 6)
        assertEquals(Step.SMILE, m.step)
        feed(m, face(smile = 0.9f), 3)
        assertEquals(Step.TURN, m.step)
    }

    @Test fun selfieNeedsOpenEyesAndAStraightHead() {
        val m = machine(listOf(Step.SMILE))
        feed(m, face(), 6)
        feed(m, face(smile = 0.9f), 3)
        assertEquals(Step.CENTER, m.step)
        feed(m, face(yaw = 25f), 10)
        feed(m, face(eyes = 0.1f), 10)
        assertEquals(Step.CENTER, m.step)
        feed(m, face(), 4)
        assertEquals(Step.DONE, m.step)
    }

    @Test fun gateRules() {
        assertTrue(LivenessLogic.needsCheck("PASSENGER", verified = false, skipped = false))
        assertTrue(LivenessLogic.needsCheck("DRIVER", verified = false, skipped = false))
        assertFalse(LivenessLogic.needsCheck("DRIVER", verified = true, skipped = false))
        assertFalse(LivenessLogic.needsCheck("PASSENGER", verified = false, skipped = true))
        assertFalse(LivenessLogic.needsCheck("ADMIN", verified = false, skipped = false))
        assertTrue(LivenessLogic.needsCheck(null, verified = false, skipped = false))
    }
}
