package com.ruta.app.util

/** Pure text/decision rules for the emergency stop and the "Are you safe?" check. */
object SafetyLogic {

    const val REASON_EMERGENCY_STOP = "EMERGENCY_STOP"
    const val ROLE_PASSENGER = "PASSENGER"
    const val ROLE_DRIVER = "DRIVER"

    /** What a trusted contact reads after someone answers the check. */
    fun contactNotice(name: String, safe: Boolean): Pair<String, String> =
        if (safe) {
            "✅ $name is safe" to "$name confirmed they are safe after an emergency stop."
        } else {
            "🚨 $name needs help" to "$name says they still need help after an emergency stop. Check their last known location."
        }

    /** One line for the booking record / admin: how the check came out so far. */
    fun summary(passengerSafe: Boolean?, driverSafe: Boolean?): String = when {
        passengerSafe == false || driverSafe == false -> "Needs attention"
        passengerSafe == true && driverSafe == true -> "Both confirmed safe"
        passengerSafe == true -> "Passenger safe, waiting for driver"
        driverSafe == true -> "Driver safe, waiting for passenger"
        else -> "Waiting for both"
    }

    /** Title/body shown for a trip that ended by emergency stop. */
    fun endedNotice(stoppedByRole: String): Pair<String, String> {
        val who = if (stoppedByRole == ROLE_DRIVER) "the driver" else "the passenger"
        return "Trip ended: emergency stop" to
            "The trip was stopped by $who. Please confirm that you are safe."
    }

    /** Ask about a trusted contact only when there is none and we have not just asked. */
    fun shouldAskTrustedContact(contactCount: Int, alreadyAsked: Boolean): Boolean =
        contactCount <= 0 && !alreadyAsked

    fun isEmergencyStop(cancelReason: String?): Boolean = cancelReason == REASON_EMERGENCY_STOP

    /** The role a user plays on a booking, or null when they are not part of it. */
    fun roleOn(uid: String, passengerId: String, driverId: String): String? = when (uid) {
        passengerId -> ROLE_PASSENGER
        driverId -> ROLE_DRIVER
        else -> null
    }
}
