package com.ruta.app.util

import com.ruta.app.util.ReviewLogic.Decision
import com.ruta.app.util.ReviewLogic.ItemReview
import org.junit.Assert.*
import org.junit.Test

class ReviewLogicTest {
    private val ok = ItemReview(true)

    @Test fun allChecksApprove() =
        assertEquals(Decision.APPROVED, ReviewLogic.decide(mapOf("front" to ok, "back" to ok)))

    @Test fun anyXRejects() =
        assertEquals(Decision.REJECTED, ReviewLogic.decide(mapOf("front" to ok, "back" to ItemReview(false, "blurry"))))

    @Test fun unreviewedOrCommentlessXIsIncomplete() {
        assertEquals(Decision.INCOMPLETE, ReviewLogic.decide(mapOf("front" to ok, "back" to null)))
        assertEquals(Decision.INCOMPLETE, ReviewLogic.decide(mapOf("front" to ItemReview(false, "  "))))
        assertEquals(Decision.INCOMPLETE, ReviewLogic.decide(emptyMap()))
    }

    @Test fun summaryListsOnlyTheBadItems() {
        val s = ReviewLogic.summary(
            mapOf("front" to "Front ID", "back" to "Back ID"),
            mapOf("front" to ok, "back" to ItemReview(false, " Picture is too blurry "))
        )
        assertEquals("Back ID: Picture is too blurry", s)
    }

    @Test fun toMapKeepsCommentsTrimmed() {
        val m = ReviewLogic.toMap(mapOf("a" to ItemReview(false, " x "), "b" to null))
        assertEquals(1, m.size)
        assertEquals("x", (m["a"] as Map<*, *>)["comment"])
    }
}
