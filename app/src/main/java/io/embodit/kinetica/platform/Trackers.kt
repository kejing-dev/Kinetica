package io.embodit.kinetica.platform

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.kinetica.domain.Vec3
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/** Head pose in Stage space: eye position, forward (unit), pitch in degrees (+ up) and heading yaw (radians). */
data class Head(val eye: Vec3, val forward: Vec3) {
    val pitchDeg: Double get() = Math.toDegrees(asin(forward.y.coerceIn(-1.0, 1.0)))
    val yaw: Double get() = atan2(-forward.x, -forward.z)
}

/**
 * HMD pose (HMDTrackingProvider, Full Space only). Uses (headpose-audit, profile D1 / D9): the bench's default pose (eye
 * height + horizontal heading only, S8 cold start / [叫回面前]), the window level gate (pitch), the「看正前方」line,
 * the eye-hand drag gain (eye→bob / eye→hand distance), the near-head fade (D14). Never to aim, place by gaze or select.
 * A pose at the origin with the identity rotation is the tracker's "not yet" and counts as invalid (手语老师 lesson).
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: Head? = null
    @Volatile var started = "not started"; private set

    fun start() {
        if (provider != null) return
        runCatching {
            val p = HMDTrackingProvider()
            val r = p.start()
            started = "start=$r support=${p.supportState}"
            Log.i(TAG, "TRACK hmd $started")
            provider = p
        }.onFailure { started = "failed $it"; Log.w(TAG, "TRACK hmd unavailable: $it") }
    }

    fun stop() { runCatching { provider?.stop() }; provider = null; last = null }

    /** Latest valid head pose (null until the tracker delivers one). */
    fun head(): Head? {
        val p = provider ?: return last
        if (runCatching { p.state }.getOrNull() != DataProvider.State.STARTED) return last
        // gate:allow head-pose eye height + heading for the bench placement / level gate / fade, never aims or selects (D1)
        val pose = runCatching { p.latestData?.hmdPose }.getOrNull() ?: return last
        val q = pose.rotation; val pos = pose.position
        if (pos.x == 0f && pos.y == 0f && pos.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) return last
        val f = q.rotateVector(Vector3(0f, 0f, -1f))
        val h = Head(Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), Vec3(f.x.toDouble(), f.y.toDouble(), f.z.toDouble()).normalized())
        if (!h.eye.x.isFinite() || !h.eye.y.isFinite()) return last
        last = h
        return h
    }
}

/**
 * Controller thumbstick (input.benchMove: stick left / right turns the bench while the ray points at the base or the
 * bench is held). Buttons and poses are not used: the system draws the ray (D41 更正) and the system pointer delivers
 * the trigger as a SpatialUI drag. The action listener runs on its own thread → an atomic, read once per frame.
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    private val stickBits = AtomicInteger(0)
    @Volatile var started = "not started"; private set

    private val listener = ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
        val r = runCatching { a.right.thumbstickValue.x }.getOrDefault(0f)
        val l = runCatching { a.left.thumbstickValue.x }.getOrDefault(0f)
        stickBits.set(java.lang.Float.floatToIntBits(if (abs(r) >= abs(l)) r else l))
    }

    fun start() {
        if (provider != null) return
        runCatching {
            val p = ControllerTrackingProvider()
            p.addControllerActionListener(listener)
            val r = p.start()
            started = "start=$r support=${p.supportState}"
            Log.i(TAG, "TRACK controller $started")
            provider = p
        }.onFailure { started = "failed $it"; Log.w(TAG, "TRACK controller unavailable: $it") }
    }

    fun stop() { runCatching { provider?.removeControllerActionListener(listener); provider?.stop() }; provider = null; stickBits.set(0) }

    /** Debug: a stick value that holds until cleared (emulator has no stick). */
    @Volatile var debugStick: Float? = null

    val stickX: Float get() = debugStick ?: java.lang.Float.intBitsToFloat(stickBits.get())
}

/** One hand: the pinch point (thumb tip – index tip midpoint), pinch gap, palm, and whether the fingers are curled (fist). */
data class HandSample(val pinchPoint: Vec3, val pinchGap: Double, val palm: Vec3, val fist: Boolean) {
    /** Grab point for the direct grab: the pinch point while pinching, else the palm while making a fist. */
    val grabPoint: Vec3 get() = if (pinchGap < DirectGrab.PINCH_ON_M) pinchPoint else palm
}

/**
 * Hand joints (Full Space only, design input.direct「直接抓的兜底」and the eye-hand gain). Nothing is shown when hand
 * tracking is off: controllers and the system pointer are the main paths.
 */
class Hands {
    private var provider: HandTrackingProvider? = null
    @Volatile var started = "not started"; private set

    fun start() {
        if (provider != null) return
        runCatching {
            val p = HandTrackingProvider()
            val r = p.start()
            started = "start=$r support=${p.supportState}"
            Log.i(TAG, "TRACK hand $started")
            provider = p
        }.onFailure { started = "failed $it"; Log.w(TAG, "TRACK hand unavailable: $it") }
    }

    fun stop() { runCatching { provider?.stop() }; provider = null }

    /** (left, right), null when not tracked. */
    fun samples(): Pair<HandSample?, HandSample?> {
        val p = provider ?: return null to null
        if (runCatching { p.supportState }.getOrNull() != DataProvider.SupportState.SUPPORTED) return null to null
        val d = runCatching { p.latestData }.getOrNull() ?: return null to null
        fun j(h: com.pico.spatial.tracking.hand.HandPose?, i: HandJoint.Index): Vec3? = runCatching {
            h?.joint(i)?.position?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }?.takeIf { it.x.isFinite() && it.len() > 1e-6 }
        }.getOrNull()
        fun s(h: com.pico.spatial.tracking.hand.HandPose?): HandSample? {
            val thumb = j(h, HandJoint.Index.THUMB_TIP) ?: return null
            val index = j(h, HandJoint.Index.INDEX_TIP) ?: return null
            val palm = j(h, HandJoint.Index.PALM) ?: return null
            val tips = listOf(HandJoint.Index.INDEX_TIP, HandJoint.Index.MIDDLE_TIP, HandJoint.Index.RING_TIP, HandJoint.Index.LITTLE_TIP).mapNotNull { j(h, it) }
            val curl = if (tips.size == 4) tips.sumOf { it.dist(palm) } / 4 else 1.0
            return HandSample((thumb + index) * 0.5, thumb.dist(index), palm, curl < DirectGrab.FIST_CURL_M)
        }
        return s(d.left) to s(d.right)
    }
}

/**
 * Direct grab fallback (design input.direct): when the system pointer did not report a near pinch on a bob / tag / the
 * base, the hand joints do. Pure state machine per hand; [step] is fed the grab point in bench space and returns what to
 * do this frame. A grab starts only if the system did not start a press within [SYSTEM_WINDOW_MS] of the pinch edge.
 */
class DirectGrab(private val now: () -> Long = { SystemClock.uptimeMillis() }) {
    sealed interface Act
    data class Start(val target: String, val at: Vec3) : Act
    data class Move(val total: Vec3) : Act
    data object End : Act

    private var closedAt = -1L
    private var closedTarget: String? = null
    private var start: Vec3? = null
    var active: String? = null; private set
    private var wasClosed = false

    /** [closed] = pinching or fist; [target] = what the grab point is inside (bench names) or null; [systemBusy] = a system press runs. */
    fun step(closed: Boolean, point: Vec3?, target: String?, systemBusy: Boolean): Act? {
        val t = now()
        if (!closed || point == null) {
            wasClosed = false; closedAt = -1L; closedTarget = null
            if (active != null) { active = null; start = null; return End }
            return null
        }
        if (!wasClosed) { wasClosed = true; closedAt = t; closedTarget = target; start = point }
        val a = active
        if (a != null) return Move(point - start!!)
        val ct = closedTarget ?: return null
        if (systemBusy) { closedTarget = null; return null }
        if (t - closedAt < SYSTEM_WINDOW_MS) return null
        active = ct
        return Start(ct, start!!)
    }

    companion object {
        const val PINCH_ON_M = 0.02
        const val FIST_CURL_M = 0.055
        const val SYSTEM_WINDOW_MS = 150L
    }
}
