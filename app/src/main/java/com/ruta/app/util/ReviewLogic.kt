package com.ruta.app.util

/**
 * Admin review checklist shared by driver documents and passenger discount IDs:
 * every item gets a check (good) or an X (bad, with a comment the user will read).
 */
object ReviewLogic {

    data class ItemReview(val ok: Boolean, val comment: String = "")

    enum class Decision { INCOMPLETE, APPROVED, REJECTED }

    val PASSENGER_ID_REASONS = listOf(
        "Picture is too blurry",
        "ID is expired",
        "Name does not match your account",
        "Photo is cropped or cut off",
        "Wrong type of ID",
        "Front and back look the same"
    )

    val DRIVER_DOC_REASONS = listOf(
        "Picture is too blurry",
        "Document is expired",
        "Name does not match",
        "Photo is cropped or cut off",
        "Wrong document",
        "Glare makes it unreadable",
        "Face is not clearly visible",
        "Not a 1x1 / ID-style photo"
    )

    /** An X must always explain itself; a check needs nothing. */
    fun isValid(review: ItemReview): Boolean = review.ok || review.comment.isNotBlank()

    fun decide(reviews: Map<String, ItemReview?>): Decision {
        if (reviews.isEmpty() || reviews.values.any { it == null || !isValid(it) }) return Decision.INCOMPLETE
        return if (reviews.values.all { it!!.ok }) Decision.APPROVED else Decision.REJECTED
    }

    /** "Front ID: Picture is too blurry. Back ID: ID is expired" for the user's notification. */
    fun summary(labels: Map<String, String>, reviews: Map<String, ItemReview?>): String =
        reviews.entries
            .filter { it.value?.ok == false }
            .joinToString(". ") { (key, r) -> "${labels[key] ?: key}: ${r!!.comment.trim()}" }

    /** Firebase-friendly shape: key -> {ok, comment}. */
    fun toMap(reviews: Map<String, ItemReview?>): Map<String, Any> =
        reviews.mapNotNull { (k, v) ->
            v?.let { k to mapOf<String, Any>("ok" to it.ok, "comment" to it.comment.trim()) }
        }.toMap()
}
