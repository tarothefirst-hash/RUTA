package com.ruta.app.util

import kotlin.math.abs

/**
 * Pure state machine for the face liveness check (no camera, no ML Kit in here).
 * The camera screen turns each analysed frame into a [Frame] and feeds it to [Machine].
 *
 * Flow: FACE (find a clear, centred face) -> the challenges in [order] (blink, turn head,
 * smile - shuffled by the caller so a recorded video can't predict them) -> CENTER (look
 * straight for the selfie) -> DONE.
 */
object LivenessLogic {

    enum class Step { FACE, BLINK, TURN, SMILE, CENTER, DONE }

    /** One analysed camera frame. Probabilities are null when the detector could not tell. */
    data class Frame(
        val faceCount: Int,
        val centered: Boolean,
        val bigEnough: Boolean,
        val leftEyeOpen: Float?,
        val rightEyeOpen: Float?,
        val smile: Float?,
        val yaw: Float
    )

    const val EYE_OPEN = 0.6f
    const val EYE_CLOSED = 0.35f
    const val SMILE_MIN = 0.75f
    const val TURN_MIN_DEGREES = 20f
    const val STRAIGHT_MAX_DEGREES = 10f
    const val STEP_TIMEOUT_MS = 15_000L
    const val LOST_FACE_FRAMES = 20

    /** Passengers and drivers must pass the face check; admins never see it. */
    fun needsCheck(role: String?, verified: Boolean, skipped: Boolean): Boolean =
        role != "ADMIN" && !verified && !skipped

    val DEFAULT_ORDER = listOf(Step.BLINK, Step.TURN, Step.SMILE)

    fun instruction(step: Step): String = when (step) {
        Step.FACE -> "Fit your face inside the oval"
        Step.BLINK -> "Blink your eyes"
        Step.TURN -> "Turn your head to one side"
        Step.SMILE -> "Now smile"
        Step.CENTER -> "Look straight at the camera"
        Step.DONE -> "Verified"
    }

    class Machine(order: List<Step> = DEFAULT_ORDER, private val nowMs: () -> Long = { System.currentTimeMillis() }) {

        private val plan: List<Step> = listOf(Step.FACE) + order + listOf(Step.CENTER, Step.DONE)
        private var index = 0
        private var stable = 0
        private var lost = 0
        private var stepStartedAt = nowMs()

        // BLINK needs open -> closed -> open in that order.
        private var sawOpen = false
        private var sawClosed = false

        /** How many times a step ran out of time and the check restarted. */
        var restarts = 0
            private set

        val step: Step get() = plan[index]

        /** 0.0 .. 1.0 across the whole check, for a progress bar. */
        val progress: Float get() = index.toFloat() / (plan.size - 1)

        fun onFrame(f: Frame): Step {
            if (step == Step.DONE) return step

            if (nowMs() - stepStartedAt > STEP_TIMEOUT_MS && step != Step.FACE) {
                restarts++
                goTo(0)
                return step
            }

            if (f.faceCount != 1) {
                // Nobody, or more than one person: pause, and restart if it lasts.
                if (++lost >= LOST_FACE_FRAMES && step != Step.FACE) { restarts++; goTo(0) }
                stable = 0
                return step
            }
            lost = 0

            when (step) {
                Step.FACE -> if (f.centered && f.bigEnough) bump(6) else stable = 0
                Step.BLINK -> blink(f)
                Step.TURN -> if (abs(f.yaw) >= TURN_MIN_DEGREES) bump(3) else stable = 0
                Step.SMILE -> if ((f.smile ?: 0f) >= SMILE_MIN) bump(3) else stable = 0
                Step.CENTER -> {
                    val eyesOpen = (f.leftEyeOpen ?: 0f) >= EYE_OPEN && (f.rightEyeOpen ?: 0f) >= EYE_OPEN
                    if (f.centered && abs(f.yaw) < STRAIGHT_MAX_DEGREES && eyesOpen) bump(4) else stable = 0
                }
                Step.DONE -> {}
            }
            return step
        }

        private fun blink(f: Frame) {
            val l = f.leftEyeOpen
            val r = f.rightEyeOpen
            if (l == null || r == null) return
            val open = l >= EYE_OPEN && r >= EYE_OPEN
            val closed = l <= EYE_CLOSED && r <= EYE_CLOSED
            if (!sawOpen) { if (open) sawOpen = true; return }
            if (!sawClosed) { if (closed) sawClosed = true; return }
            if (open) advance()
        }

        private fun bump(needed: Int) {
            if (++stable >= needed) advance()
        }

        private fun advance() { goTo(index + 1) }

        private fun goTo(i: Int) {
            index = i
            stable = 0
            lost = 0
            sawOpen = false
            sawClosed = false
            stepStartedAt = nowMs()
        }
    }
}
