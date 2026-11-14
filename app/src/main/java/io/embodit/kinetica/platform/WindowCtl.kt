package io.embodit.kinetica.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.kinetica.domain.LevelGate
import io.embodit.kinetica.ui.Containers

/**
 * Opens / reopens the control window from the Stage (native-window-move.md §3.3, §3.5, §7 item 9). Every open first asks
 * the [LevelGate]; the Stage frame system feeds it the head pitch each frame. Reopen = close, wait for ON_DESTROY, open
 * again: the system then puts the window ahead of the current head (Full Space, design container.controlWindow). An open
 * asked while a close is still running waits for that ON_DESTROY (close + open back to back is swallowed, §3.5).
 * Exit closes the control window and the Stage before the tasks are removed (D44).
 */
class WindowCtl {
    val gate = LevelGate()
    var levelHint by mutableStateOf(false)
        private set
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY"

    private var navigator: SpatialNavigator? = null
    private var panelNavigator: SpatialNavigator? = null
    /** Closes the Stage (set by the Stage screen; exit closes both containers, D44). */
    var closeStage: (() -> Unit)? = null
    private var closing = false
    private var openAfterDestroy: String? = null
    private var lastStepMs = 0L
    private val main = Handler(Looper.getMainLooper())

    /** The Stage screen hands over its navigator (the window's own composition can't reopen itself). */
    fun attach(nav: SpatialNavigator) { navigator = nav }

    fun detach() {
        navigator = null
        gate.cancel()
        main.removeCallbacks(watchdog)
    }

    fun attachPanel(nav: SpatialNavigator) { panelNavigator = nav }

    /** Ask for the window; it opens once the head is level (or the fallback fires). */
    fun request(why: String) {
        if (closing) { openAfterDestroy = why; Log.i(TAG, "WINDOW ask why=$why (after the close finishes)"); return }
        gate.ask(why, SystemClock.elapsedRealtime())
        Log.i(TAG, "WINDOW ask why=$why state=$panelState")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /** Nameplate (T11): close (if open), wait for ON_DESTROY, then a level-gated open ahead of the head. */
    fun reopen(why: String = "nameplate") {
        if (!panelOpen) request(why)
        else { close(); openAfterDestroy = why }
    }

    /** [叫回面前] first step (S8): close the window; the Stage reopens it once the bench is placed. */
    fun close() {
        if (!panelOpen) return
        closing = true
        Log.i(TAG, "WINDOW close")
        runCatching { (navigator ?: panelNavigator)?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { closing = false; Log.w(TAG, "WINDOW close: $it") }
        main.postDelayed({ if (closing) { Log.w(TAG, "WINDOW close: no ON_DESTROY in 4 s"); closing = false; openAfterDestroy?.let { openAfterDestroy = null; request(it) } } }, 4000)
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (event == "ON_DESTROY") {
            closing = false
            openAfterDestroy?.let { openAfterDestroy = null; request(it) }
        }
    }

    /** Exit (D26 / D44): close the control window and the Stage itself. */
    fun closeAll() {
        gate.cancel()
        openAfterDestroy = null
        main.removeCallbacks(watchdog)
        runCatching { (navigator ?: panelNavigator)?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { closeStage?.invoke() }.onFailure { Log.w(TAG, "EXIT close stage: $it") }
        Log.i(TAG, "EXIT closed the control window and the Stage")
    }

    /** Called every frame by the Stage's ECS system with the head pitch (degrees, null without a valid head pose). */
    fun stepFrame(pitch: Float?) {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (!gate.waiting) { if (levelHint) levelHint = false; return }
        levelHint = gate.hintWanted(pitch)
        gate.step(now, pitch)?.let { why -> levelHint = false; openNow(why, pitch) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        Log.i(TAG, "WINDOW open why=$why pitch=$pitch")
        runCatching { (navigator ?: panelNavigator)?.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG, android.os.Bundle()) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }
}
