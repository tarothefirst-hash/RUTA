package com.ruta.app.util

/**
 * The editable parts of a user's profile, and the exact database fields they map to.
 *
 * Sign-up stores firstName / lastName / name / phoneNumber, and the rest of the app
 * (bookings, chat, the driver's passenger card) reads those. The profile screens used
 * to write only "name" and "phone", so edits never reached the places that use them.
 * Everything now goes through [updatesFor], so one edit updates every field.
 *
 * The email is deliberately NOT here: it cannot be changed from the profile.
 *
 * Pure Kotlin, so it is unit tested on the JVM.
 */
object ProfileFields {

    private const val MIN_PHONE_DIGITS = 10
    private const val MAX_PHONE_DIGITS = 13

    /** "Maria Clara Santos" -> ("Maria Clara", "Santos"); a single word has no last name. */
    fun splitName(fullName: String): Pair<String, String> {
        val clean = fullName.trim().replace(Regex("\\s+"), " ")
        val cut = clean.lastIndexOf(' ')
        return if (cut < 0) clean to "" else clean.substring(0, cut) to clean.substring(cut + 1)
    }

    fun isValidName(fullName: String): Boolean = fullName.trim().length >= 2

    /** Keeps digits and a leading "+", drops spaces, dashes and brackets. */
    fun normalizePhone(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    fun isValidPhone(raw: String): Boolean =
        normalizePhone(raw).count { it.isDigit() } in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS

    /** Every field a name/phone edit must write. Both phone spellings are kept in step. */
    fun updatesFor(fullName: String, rawPhone: String): Map<String, Any> {
        val name = fullName.trim().replace(Regex("\\s+"), " ")
        val (first, last) = splitName(name)
        val phone = normalizePhone(rawPhone)
        return mapOf(
            "firstName" to first,
            "lastName" to last,
            "name" to name,
            "phoneNumber" to phone,
            "phone" to phone
        )
    }
}
