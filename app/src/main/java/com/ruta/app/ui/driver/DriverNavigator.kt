package com.ruta.app.ui.driver

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.google.android.gms.maps.model.LatLng
import com.ruta.app.ui.DirectionsHelper
import com.ruta.app.util.MapsApi
import com.ruta.app.util.NavigationLogic
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Turn-by-turn guidance for the driver: fetches the Directions steps for the current leg,
 * follows the GPS fixes through [NavigationLogic.Tracker], speaks the prompts with the
 * phone's built-in text-to-speech (English) and tells the screen what to show.
 */
class DriverNavigator(
    context: Context,
    private val onBanner: (arrow: String, distance: String, instruction: String) -> Unit,
    private val onIdle: () -> Unit
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("DRIVER_SESSION", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var ttsReady = false
    private var pending: String? = null

    private var tracker: NavigationLogic.Tracker? = null
    private var legToken = 0

    var muted: Boolean = prefs.getBoolean("NAV_MUTED", false)
        private set

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        engine.language = Locale.US
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {}
        ttsReady = true
        pending?.let { speak(it) }
        pending = null
    }

    fun setMuted(value: Boolean) {
        muted = value
        prefs.edit().putBoolean("NAV_MUTED", value).apply()
        if (value) tts?.stop()
    }

    /** Speaks [text]. Safety messages pass force = true so they ignore the mute button. */
    fun speak(text: String, force: Boolean = false) {
        if (muted && !force) return
        if (!ttsReady) { pending = text; return }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ruta-nav-${System.nanoTime()}")
    }

    /** Starts guidance from [from] to [to]; replaces whatever leg was being followed. */
    fun startLeg(from: LatLng, to: LatLng, announceStart: Boolean = true) {
        val token = ++legToken
        tracker = null
        io.execute {
            val key = MapsApi.key(appContext)
            val json = DirectionsHelper.downloadUrl(DirectionsHelper.getDirectionsUrl(from, to, key))
            val steps = DirectionsHelper.parseSteps(json)
            main.post {
                if (token != legToken || steps.isEmpty()) return@post
                tracker = NavigationLogic.Tracker(NavigationLogic.buildManeuvers(steps))
                if (announceStart) NavigationLogic.departText(steps)?.let { speak(it) }
                onLocation(from.latitude, from.longitude)
            }
        }
    }

    fun onLocation(lat: Double, lng: Double) {
        val t = tracker ?: return
        val u = t.update(lat, lng)
        val m = u.maneuver
        if (m == null || u.arrived) {
            if (u.arrived) {
                speak("You have arrived at your stop")
                tracker = null
                onIdle()
            }
            return
        }
        onBanner(
            NavigationLogic.arrowFor(m.type),
            NavigationLogic.formatDistance(u.distanceMeters),
            m.instruction.ifBlank { NavigationLogic.fallbackInstruction(m.type) }
        )
        u.prompt?.let { speak(NavigationLogic.phraseFor(m, u.distanceMeters, it)) }
    }

    fun stop() {
        legToken++
        tracker = null
        tts?.stop()
        onIdle()
    }

    fun shutdown() {
        legToken++
        tracker = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        io.shutdownNow()
    }
}
