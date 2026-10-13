package com.agani.syncup.group

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.agani.syncup.walkie.AudioRoute
import com.agani.syncup.walkie.Beeps
import com.agani.syncup.walkie.TalkMode
import com.agani.syncup.walkie.Voice
import com.agani.syncup.walkie.VoiceIn
import com.agani.syncup.walkie.VoiceOut
import com.agani.syncup.walkie.WalkieWire
import com.agani.syncup.walkie.idOf
import org.json.JSONObject

/**
 * A group's Walkie talkie mode: talk to everyone in the group. The host picks how when switching —
 * **Hold to talk** (one voice at a time; the host's phone gives the turn, up to a minute) or **Open
 * talk** (hands-free; voice goes out only while someone speaks). Every voice goes through the host's
 * phone, which passes it on. Uses Walkie Talkie's sound ([Voice]: 16 kHz speech, AMR-WB). Voice is
 * live only — never recorded or saved.
 *
 * Main thread for everything public.
 */
object GroupWalkie {
    /** Hold to talk: the longest one turn can be. */
    const val TALK_LIMIT_MS = 60_000L
    /** Voice frames per message (3 × 20 ms). */
    private const val FRAMES_PER_SEND = 3
    /** Open talk: louder than this (RMS) counts as speaking… */
    private const val VAD_LEVEL = 600.0
    /** …and the voice keeps going out this long after (so words aren't clipped). */
    private const val VAD_HANG_MS = 500L

    private const val PREFS = "group_walkie"
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    // ------------------------------------------------------------------ what the screen shows
    /** The group is in Walkie talkie mode on this phone. */
    var active by mutableStateOf(false)
        private set
    var talkMode by mutableStateOf(TalkMode.HOLD)
        private set
    /** Hold to talk: who has the turn (a name), or null. */
    var floor by mutableStateOf<String?>(null)
        private set
    /** When the turn started (elapsed realtime) — the timer ring. */
    var floorSince by mutableLongStateOf(0L)
        private set
    /** This phone's voice is going out. */
    var talking by mutableStateOf(false)
        private set
    /** Open talk: this phone's microphone is on. */
    var micOn by mutableStateOf(true)
        private set
    /** Loudspeaker (else the earpiece; a headset always wins). */
    var loud by mutableStateOf(true)
        private set
    /** Who was heard just now. */
    val speaking = mutableStateListOf<String>()
    /** Hold to talk: this person holds a volume key to talk (just them, this group; off at first). */
    var volumeKeyTalk by mutableStateOf(false)
        private set
    /** The group's Walkie talkie screen is on display (volume-key talk works only then). */
    var screenVisible = false
    /** The microphone permission is needed (the screen asks). */
    var needsMic by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    // ------------------------------------------------------------------ inside
    private var voiceIn: VoiceIn? = null
    private var voiceOut: VoiceOut? = null
    private var beeps: Beeps? = null
    @Volatile private var sending = false
    private var seq = 0
    private val pending = ArrayList<ByteArray>()
    @Volatile private var lastLoudAt = 0L
    /** Guest: the talk button is held down. */
    private var holding = false
    private var floorTimer: Runnable? = null
    private var ticker: Runnable? = null

    private val myName get() = GroupLink.myName
    private val myId get() = idOf(GroupLink.myToken)

    fun init(context: Context) {
        if (ready) return
        ready = true
        app = context.applicationContext
        GroupLink.init(app)
        GroupLink.register(GroupMode.WALKIE, channel)
    }

    fun hasMic(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ================================================================== the group's walkie channel
    internal val channel = object : GroupChannel {
        override fun onReceive(endpointId: String, body: ByteArray, arrivedNs: Long) {
            if (!active) return
            when (val m = WalkieWire.parse(body)) {
                is WalkieWire.Voice -> {
                    if (GroupLink.isHost) {
                        // Hold to talk: only the one with the turn is passed on.
                        if (talkMode == TalkMode.HOLD && GroupLink.nameOf(endpointId) != floor) return
                        val others = GroupLink.guestEndpoints().filter { it != endpointId }
                        if (others.isNotEmpty()) GroupLink.send(others, GroupLink.payload(GroupMode.WALKIE, body))
                    }
                    voiceOut?.push(m.sender, m.seq, m.codec, m.frames)
                }
                is WalkieWire.Json -> if (GroupLink.isHost) onGuestMessage(endpointId, m.o) else onHostMessage(m.o)
                null -> Unit
            }
        }

        override fun onMemberIn(endpointId: String, token: String, name: String) {
            if (active && GroupLink.isHost) GroupLink.send(endpointId, json(floorMsg()))
        }

        override fun onMemberOut(endpointId: String) {
            // The phone that had the turn went: the turn is free.
            if (active && GroupLink.isHost && floor != null && GroupLink.people.none { it.name == floor }) clearFloor()
        }

        override fun onLinkDown() {
            if (active && talking) stopMic()
        }

        override fun activate(resume: Boolean) {
            talkMode = TalkMode.of(GroupLink.option("talk"))
            volumeKeyTalk = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("vkey_${GroupLink.id}", false)
            active = true
            floor = null
            beeps = Beeps()
            voiceOut = VoiceOut(onWake = { main.post { AudioRoute.backToCommunication(app, loud) } })
            AudioRoute.enter(app, loud)
            GroupService.useMic(app, hasMic())
            if (talkMode == TalkMode.OPEN && micOn) startMic()
            if (GroupLink.isHost) broadcast(floorMsg())
            startTicker()
        }

        override fun deactivate() {
            if (!active) return
            stopMic()
            voiceOut?.release()
            voiceOut = null
            beeps?.release()
            beeps = null
            AudioRoute.leave(app)
            GroupService.useMic(app, false)
            floorTimer?.let { main.removeCallbacks(it) }
            floorTimer = null
            ticker?.let { main.removeCallbacks(it) }
            ticker = null
            active = false
            floor = null
            holding = false
            main.removeCallbacks(keyStart)
            keyTalking = false
            speaking.clear()
        }

        override fun reset() {
            deactivate()
            micOn = true
        }

        override fun statusText(): String {
            val how = if (talkMode == TalkMode.HOLD) "Hold to talk" else "Open talk"
            val who = floor?.let { if (it == myName) "you're talking" else "$it is talking" } ?: speaking.firstOrNull()?.let { "$it is talking" }
            return listOfNotNull(how, who).joinToString(" · ")
        }
    }

    // ================================================================== talking
    /** Hold to talk: the button went down. */
    fun pressTalk() {
        if (!active || talkMode != TalkMode.HOLD) return
        if (!hasMic()) {
            needsMic = true
            return
        }
        holding = true
        if (GroupLink.isHost) {
            if (floor == null || floor == myName) grantFloor(myName) else beeps?.busy()
        } else {
            sendToHost(WalkieWire.msg("talk").put("on", true))
        }
    }

    /** Hold to talk: the button came up. */
    fun releaseTalk() {
        if (!active || talkMode != TalkMode.HOLD) return
        holding = false
        if (GroupLink.isHost) {
            if (floor == myName) clearFloor()
        } else {
            if (talking) stopMic()
            sendToHost(WalkieWire.msg("talk").put("on", false))
        }
    }

    /** Open talk: this phone's microphone on / off. */
    fun toggleMic() {
        if (!active || talkMode != TalkMode.OPEN) return
        if (!micOn && !hasMic()) {
            needsMic = true
            return
        }
        micOn = !micOn
        if (micOn) startMic() else stopMic()
    }

    fun toggleSpeaker() {
        loud = !loud
        if (active) AudioRoute.apply(app, loud)
    }

    /** The microphone permission was just granted. */
    fun micGranted() {
        needsMic = false
        if (!active) return
        GroupService.useMic(app, true)
        if (talkMode == TalkMode.OPEN && micOn) startMic()
    }

    private fun startMic() {
        if (voiceIn != null || !hasMic()) return
        AudioRoute.backToCommunication(app, loud)
        synchronized(pending) { pending.clear() }
        sending = true
        talking = talkMode == TalkMode.HOLD
        voiceIn = VoiceIn(app) { pcm, encoded -> onFrame(pcm, encoded) }.also { it.start() }
    }

    private fun stopMic() {
        sending = false
        voiceIn?.stop()
        voiceIn = null
        talking = false
    }

    /** The microphone's thread: every 20 ms of voice. */
    private fun onFrame(pcm: ShortArray, encoded: ByteArray) {
        if (!sending) return
        val now = SystemClock.elapsedRealtime()
        if (talkMode == TalkMode.OPEN) {
            if (Voice.rms(pcm) > VAD_LEVEL) lastLoudAt = now
            val on = now - lastLoudAt < VAD_HANG_MS
            if (on != talking) main.post { talking = on }
            if (!on) {
                synchronized(pending) { pending.clear() }
                return
            }
        }
        val frames = synchronized(pending) {
            pending.add(encoded)
            if (pending.size < FRAMES_PER_SEND) return
            pending.toList().also { pending.clear() }
        }
        val body = WalkieWire.voice(myId, seq++, Voice.sendCodec, frames)
        main.post {
            if (!active) return@post
            val p = GroupLink.payload(GroupMode.WALKIE, body)
            if (GroupLink.isHost) GroupLink.send(GroupLink.guestEndpoints(), p) else GroupLink.sendToHost(p)
        }
    }

    // ------------------------------------------------------------------ volume-key talk
    fun changeVolumeKeyTalk(on: Boolean) {
        volumeKeyTalk = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("vkey_${GroupLink.id}", on).apply()
    }

    private var keyTalking = false
    private val keyStart = Runnable {
        keyTalking = true
        pressTalk()
    }

    /** From MainActivity: a volume key. True = used here (hold ½ s = talk; a quick press still changes the volume). */
    fun onVolumeKey(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!(active && talkMode == TalkMode.HOLD && volumeKeyTalk && screenVisible) && !keyTalking) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                keyTalking = false
                main.postDelayed(keyStart, 500)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(keyStart)
                if (keyTalking) {
                    keyTalking = false
                    releaseTalk()
                } else {
                    (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        if (key == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ the turn (host)
    private fun grantFloor(name: String) {
        floor = name
        floorSince = SystemClock.elapsedRealtime()
        broadcast(floorMsg())
        if (name == myName) {
            beeps?.start()
            startMic()
        }
        floorTimer?.let { main.removeCallbacks(it) }
        floorTimer = Runnable { if (floor == name) clearFloor() }.also { main.postDelayed(it, TALK_LIMIT_MS) }
    }

    private fun clearFloor() {
        val was = floor
        floor = null
        floorTimer?.let { main.removeCallbacks(it) }
        floorTimer = null
        if (was == myName) stopMic()
        broadcast(floorMsg())
        if (was != null) beeps?.over()
    }

    private fun floorMsg(): JSONObject = WalkieWire.msg("floor").put("who", floor ?: "").put("since", floor?.let { 1 } ?: 0)

    private fun onGuestMessage(endpointId: String, o: JSONObject) {
        val name = GroupLink.nameOf(endpointId) ?: return
        when (o.optString("t")) {
            "talk" -> if (o.optBoolean("on")) {
                if (floor == null) grantFloor(name) else GroupLink.send(endpointId, json(floorMsg()))
            } else if (floor == name) {
                clearFloor()
            }
        }
    }

    private fun onHostMessage(o: JSONObject) {
        when (o.optString("t")) {
            "floor" -> {
                val who = o.optString("who").takeIf { it.isNotBlank() }
                val was = floor
                floor = who
                if (who != null && who != was) floorSince = SystemClock.elapsedRealtime()
                when {
                    who == myName && holding -> {
                        beeps?.start()
                        startMic()
                    }
                    // Given the turn after letting go: hand it back.
                    who == myName -> sendToHost(WalkieWire.msg("talk").put("on", false))
                    talking && talkMode == TalkMode.HOLD -> stopMic()
                    holding && who != null -> beeps?.busy()
                }
                if (who == null && was != null && was != myName) beeps?.over()
            }
        }
    }

    /** "Who's talking" for the screen, from the voices heard just now. */
    private fun startTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = object : Runnable {
            override fun run() {
                if (!active) return
                val ids = voiceOut?.speaking(SystemClock.elapsedRealtime()) ?: emptySet()
                val names = GroupLink.people.filter { idOf(it.token) in ids }.map { it.name } + if (talking) listOf(myName) else emptyList()
                if (names != speaking.toList()) {
                    speaking.clear()
                    speaking.addAll(names)
                    GroupService.refresh(app)
                }
                main.postDelayed(this, 250)
            }
        }.also { main.post(it) }
    }

    // ------------------------------------------------------------------ sending
    private fun json(o: JSONObject) = GroupLink.payload(GroupMode.WALKIE, WalkieWire.json(o))

    private fun broadcast(o: JSONObject) {
        val to = GroupLink.guestEndpoints()
        if (to.isNotEmpty()) GroupLink.send(to, json(o))
    }

    private fun sendToHost(o: JSONObject) {
        GroupLink.sendToHost(json(o))
    }
}
