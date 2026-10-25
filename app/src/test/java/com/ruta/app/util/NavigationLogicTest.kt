package com.ruta.app.util

import com.ruta.app.util.NavigationLogic.Maneuver
import com.ruta.app.util.NavigationLogic.Prompt
import com.ruta.app.util.NavigationLogic.Step
import org.junit.Assert.*
import org.junit.Test

class NavigationLogicTest {

    // ~111 m per 0.001 deg of latitude
    private fun lat(metersNorth: Double) = 15.0 + metersNorth / 111_320.0

    private val steps = listOf(
        Step(15.0, 120.0, lat(1000.0), 120.0, 1000, "", "Head <b>north</b> on <b>MacArthur Hwy</b>"),
        Step(lat(1000.0), 120.0, lat(1000.0), 120.01, 1100, "turn-right",
            "Turn <b>right</b> onto <b>Romulo Hwy</b><div style=\"font-size:0.9em\">Pass by Jollibee (on the left)</div>"),
        Step(lat(1000.0), 120.01, lat(1000.0), 120.02, 500, "turn-left", "Turn <b>left</b> onto <b>Zamora St</b>")
    )

    @Test fun stripHtmlCleansTagsAndKeepsSideNote() {
        assertEquals("Head north on MacArthur Hwy", NavigationLogic.stripHtml(steps[0].instructionHtml))
        assertEquals(
            "Turn right onto Romulo Hwy. Pass by Jollibee (on the left)",
            NavigationLogic.stripHtml(steps[1].instructionHtml)
        )
        assertEquals("Fish & Chips", NavigationLogic.stripHtml("Fish &amp; Chips"))
    }

    @Test fun maneuversSkipDepartAndEndWithArrival() {
        val m = NavigationLogic.buildManeuvers(steps)
        assertEquals(3, m.size)
        assertEquals("turn-right", m[0].type)
        assertEquals("turn-left", m[1].type)
        assertEquals("arrive", m[2].type)
        assertEquals("Head north on MacArthur Hwy", NavigationLogic.departText(steps))
    }

    @Test fun spokenAndShownDistances() {
        assertEquals("300 meters", NavigationLogic.spokenDistance(312.0))
        assertEquals("100 meters", NavigationLogic.spokenDistance(98.0))
        assertEquals("50 meters", NavigationLogic.spokenDistance(52.0))
        assertEquals("1.2 kilometers", NavigationLogic.spokenDistance(1234.0))
        assertEquals("1 kilometer", NavigationLogic.spokenDistance(1004.0))
        assertEquals("250 m", NavigationLogic.formatDistance(252.0))
        assertEquals("1.2 km", NavigationLogic.formatDistance(1190.0))
    }

    @Test fun phrasesSayTurnLeftAndRight() {
        val right = Maneuver(0.0, 0.0, "turn-right", "Turn right onto Romulo Hwy")
        assertEquals("In 300 meters, turn right onto Romulo Hwy", NavigationLogic.phraseFor(right, 300.0, Prompt.FAR))
        assertEquals("Turn right onto Romulo Hwy", NavigationLogic.phraseFor(right, 20.0, Prompt.NOW))
        val bare = Maneuver(0.0, 0.0, "turn-left", "")
        assertEquals("Turn left", NavigationLogic.phraseFor(bare, 20.0, Prompt.NOW))
        val arrive = Maneuver(0.0, 0.0, "arrive", "")
        assertEquals("You have arrived at your stop", NavigationLogic.phraseFor(arrive, 5.0, Prompt.NOW))
        assertEquals("→", NavigationLogic.arrowFor("turn-right"))
        assertEquals("←", NavigationLogic.arrowFor("turn-left"))
    }

    @Test fun trackerAnnouncesEachStageOnceThenAdvances() {
        val t = NavigationLogic.Tracker(NavigationLogic.buildManeuvers(steps))

        // 800 m before the first turn: nothing to say yet.
        var u = t.update(lat(200.0), 120.0)
        assertNull(u.prompt); assertEquals(0, u.index)

        // 280 m before: FAR fires, a second fix at the same spot does not repeat it.
        u = t.update(lat(720.0), 120.0)
        assertEquals(Prompt.FAR, u.prompt)
        assertNull(t.update(lat(730.0), 120.0).prompt)

        // 90 m before: NEAR; 20 m before: NOW.
        assertEquals(Prompt.NEAR, t.update(lat(910.0), 120.0).prompt)
        assertEquals(Prompt.NOW, t.update(lat(965.0), 120.0).prompt)

        // At the corner the turn is passed and the next maneuver becomes current.
        u = t.update(lat(1000.0), 120.0)
        assertEquals(1, u.index)
        assertEquals("turn-left", u.maneuver?.type)
    }

    @Test fun firstFixInsideNearStageSkipsFar() {
        val t = NavigationLogic.Tracker(NavigationLogic.buildManeuvers(steps))
        assertEquals(Prompt.NEAR, t.update(lat(920.0), 120.0).prompt)
    }

    @Test fun trackerReportsArrivalAtTheEnd() {
        val t = NavigationLogic.Tracker(NavigationLogic.buildManeuvers(steps))
        val last = steps.last()
        t.update(steps[1].startLat, steps[1].startLng)   // reach the right turn
        t.update(steps[2].startLat, steps[2].startLng)   // reach the left turn
        val u = t.update(last.endLat, last.endLng)
        assertTrue(u.arrived)
        assertTrue(t.isFinished)
    }
}
