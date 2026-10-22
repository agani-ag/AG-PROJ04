package com.agani.syncup.decide

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.random.Random

/** A wheel someone spun for the whole group: shown in place of the saved wheel until closed. */
internal data class SharedWheel(val wheel: Wheel, val by: String, val mine: Boolean, val spinMs: Int)

/** The First-choice winner: its position on the wheel, and the option. */
internal data class WheelWin(val pos: Int, val option: WheelOption)

/**
 * The spinner's moving parts: the wheel's angle, the spin itself (ticks, landing), Last-choice
 * knock-outs, Automatic rounds and following a spin played for everyone. One per open screen.
 */
@Stable
internal class WheelGame(private val context: Context, private val scope: CoroutineScope) {
    /** The wheel's angle in degrees, clockwise; 0 = slice 0 starts at the pointer. */
    var rotation by mutableFloatStateOf(-15f)
        private set
    /** From the moment a spin is asked for until it lands (a shared one waits for its start time). */
    var spinning by mutableStateOf(false)
        private set
    /** The slice (in what's left) kept bright while the others dim. */
    var highlight by mutableStateOf<Int?>(null)
        private set
    var shared by mutableStateOf<SharedWheel?>(null)
        private set
    var winner by mutableStateOf<WheelWin?>(null)
        private set
    /** Last choice: the one left, while its card shows. */
    var lastOne by mutableStateOf<WheelOption?>(null)
        private set
    var confetti by mutableIntStateOf(0)
        private set
    /** Automatic rounds keep coming (here, or — on the phone that started it — for everyone). */
    var autoRunning by mutableStateOf(false)
        private set
    /** Another phone runs Automatic for everyone: waiting for its next round. */
    var following by mutableStateOf(false)
        private set

    private var job: Job? = null
    private val played = HashSet<Long>()

    init {
        // A spin from before this screen opened (already over) is not replayed.
        Together.latest?.let { p ->
            val ms = wheelFromPayload(p.payload)?.second ?: 0
            if (p.tool != Together.TOOL_WHEEL || System.nanoTime() > p.startAtNs + ms * 1_000_000L) played += p.id
        }
    }

    /** The wheel on screen: the shared one, or the saved one. */
    val wheel: Wheel get() = shared?.wheel ?: WheelStore.current

    val cardOpen: Boolean get() = winner != null || lastOne != null

    fun spinMs(): Int = WheelStore.spinSeconds(context) * 1000

    /** Changes the wheel on screen: the shared one stays on this phone, the saved one is saved. */
    fun setWheel(w: Wheel) {
        val s = shared
        if (s != null) shared = s.copy(wheel = w) else WheelStore.update(w)
    }

    fun closeCards() {
        winner = null
        lastOne = null
        highlight = null
    }

    /** One spin on this phone only. */
    fun spin() {
        val w = wheel
        if (spinning || w.left.size < 2) return
        closeCards()
        job?.cancel()
        job = scope.launch { spinRound(Random.nextLong(), System.nanoTime(), spinMs()) }
    }

    /** Last choice · Automatic on this phone: spins round after round until one is left. */
    fun startAuto() {
        if (wheel.left.size < 2) return
        if (job?.isActive == true && shared == null) {
            // Paused during a spin: that spin is still finishing — just carry on after it.
            autoRunning = true
            return
        }
        closeCards()
        autoRunning = true
        job?.cancel()
        job = scope.launch {
            while (autoRunning && wheel.left.size > 1) {
                spinRound(Random.nextLong(), System.nanoTime(), spinMs())
                if (!autoRunning || wheel.left.size <= 1) break
                delay(AUTO_GAP_MS)
            }
            autoRunning = false
        }
    }

    /** Stops Automatic after the spin that's going (a wheel can't stop mid-turn). */
    fun pause() {
        autoRunning = false
    }

    /** Spin for everyone in the group (the caller checked [Together.available]). */
    fun spinForEveryone() {
        val w = wheel
        if (spinning || w.left.size < 2) return
        closeCards()
        autoRunning = w.mode == WheelMode.LAST && w.auto
        val ms = shared?.spinMs ?: spinMs()
        if (!Together.play(Together.TOOL_WHEEL, w.toPayload(ms), Random.nextLong())) autoRunning = false
    }

    /** Back to the saved wheel. */
    fun leaveShared() {
        job?.cancel()
        spinning = false
        autoRunning = false
        following = false
        closeCards()
        shared = null
    }

    /** Called with every new shared play; a wheel play not seen yet is followed. */
    fun onPlay(p: SharedPlay) {
        if (p.tool != Together.TOOL_WHEEL || !played.add(p.id)) return
        val (w, ms) = wheelFromPayload(p.payload) ?: return
        job?.cancel()
        closeCards()
        following = false
        if (!p.mine) autoRunning = false
        // Earlier picks carry over while it's the same shared wheel.
        val before = shared?.wheel
        val recent = if (before != null && before.name == w.name) before.recent else emptyList()
        shared = SharedWheel(w.copy(recent = recent), p.by, p.mine, ms)
        job = scope.launch {
            spinRound(p.seed, p.startAtNs, ms)
            val now = wheel
            if (now.mode != WheelMode.LAST || !now.auto || now.left.size <= 1) {
                autoRunning = false
                return@launch
            }
            if (p.mine) {
                // This phone started it: it sends each next round; the others follow. Sent right
                // away — the play's own lead time (Together.LEAD_NS) is the gap between rounds.
                if (autoRunning && shared != null && Together.available) {
                    Together.play(Together.TOOL_WHEEL, wheel.toPayload(ms), Random.nextLong())
                } else {
                    autoRunning = false
                }
            } else {
                // Waiting for the starter's next round; if none comes, the buttons come back.
                following = true
                delay(Together.LEAD_NS / 1_000_000 + 2_500)
                following = false
            }
        }
    }

    /** Last choice, game over: everyone back on the wheel. */
    fun playAgain() {
        closeCards()
        autoRunning = false
        setWheel(wheel.playAgain())
    }

    /** First choice: takes the winner off the wheel. */
    fun removeWinner() {
        val win = winner ?: return
        closeCards()
        val w = wheel
        if (w.options.getOrNull(win.pos) == win.option) setWheel(w.removeAt(win.pos))
    }

    // ---- the spin

    private suspend fun spinRound(seed: Long, startNs: Long, ms: Int) {
        val n = wheel.left.size
        if (n < 1) return
        val (index, pos) = wheelTarget(seed, n)
        spinning = true
        try {
            turnTo(n, index, pos, wheelTurns(seed), startNs, ms)
            land(index)
        } finally {
            spinning = false
        }
    }

    /** Turns the wheel so the spot [pos] inside slice [index] stops under the pointer, [turns] whole turns later. */
    private suspend fun turnTo(n: Int, index: Int, pos: Float, turns: Int, startNs: Long, ms: Int) {
        val sweep = 360f / n
        val from = ((rotation % 360f) + 360f) % 360f
        rotation = from
        // The pointer is at 0°: the slice point (index + pos) · sweep must end up there.
        val want = -(index + pos) * sweep
        val delta = (((want - from) % 360f) + 360f) % 360f + turns * 360f
        val wait = (startNs - System.nanoTime()) / 1_000_000
        if (wait > 0) delay(wait)
        val dur = ms * 1_000_000.0
        var edge = floor(from / sweep)
        var lastTick = 0L
        while (true) {
            // Timed from startNs, so a phone that got the spin late still lands with the others.
            val now = withFrameNanos { System.nanoTime() }
            val t = ((now - startNs) / dur).coerceIn(0.0, 1.0).toFloat()
            val r = from + delta * easeOut(t)
            rotation = r
            val e = floor(r / sweep)
            if (e != edge) {
                edge = e
                if (now - lastTick >= TICK_GAP_NS) {
                    lastTick = now
                    DecideFx.tick(context)
                    DecideFx.buzz(context, 6, 50)
                }
            }
            if (t >= 1f) break
        }
        rotation = (from + delta) % 360f
    }

    private suspend fun land(index: Int) {
        val w = wheel
        val pick = w.left.getOrNull(index) ?: return
        DecideFx.buzz(context, 40, 180)
        if (w.mode == WheelMode.FIRST) {
            DecideFx.land(context)
            setWheel(w.copy(recent = (listOf(pick.name) + w.recent).take(10)))
            highlight = index
            winner = WheelWin(w.leftPositions[index], pick)
            confetti++
        } else {
            DecideFx.pop(context)
            // A moment to see which one goes, then it's off the wheel.
            highlight = index
            delay(650)
            highlight = null
            val after = wheel.knockOut(index)
            setWheel(after)
            if (after.left.size == 1) {
                autoRunning = false
                DecideFx.land(context)
                lastOne = after.left.first()
                confetti++
            }
        }
    }

    private fun easeOut(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private companion object {
        const val AUTO_GAP_MS = 1_200L
        const val TICK_GAP_NS = 35_000_000L
    }
}
