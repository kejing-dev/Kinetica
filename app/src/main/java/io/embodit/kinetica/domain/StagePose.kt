package io.embodit.kinetica.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Where the bench stands in the Stage (design container.placement, input.benchMove, S7 / S8). Pure Kotlin, unit-tested in
 * StagePoseTest. Stage space: metres, +Y up. The bench's own frame is the old volume frame (origin = content-box centre,
 * +Z toward the user), so [origin] is that centre in the Stage and [yaw] turns the bench about +Y (0 = its front faces +Z).
 * Only the head's horizontal heading and eye height are used (D9 / D29), never its pitch or roll.
 */
data class BenchPose(val origin: Vec3, val yaw: Double) {
    fun toStage(local: Vec3): Vec3 = origin + Layout.rotY(local, yaw)
    fun toLocal(stage: Vec3): Vec3 = Layout.rotY(stage - origin, -yaw)
    /** A Stage-space displacement (a drag) in bench axes. */
    fun dirToLocal(d: Vec3): Vec3 = Layout.rotY(d, -yaw)
    fun dirToStage(d: Vec3): Vec3 = Layout.rotY(d, yaw)
}

object StagePose {
    /**
     * Design container.placement.default: hanging plane 0.50 m ahead of the eye, origin 0.25 m below it. v0.1.2 (QA #2
     * q2-1, D14 default view): the control window the system opens ahead (800 x 420 dp ≈ 1.39 x 0.73 m at 1.75 m, 1 dp ≈
     * 1.74 mm measured) covers the view down to ~15° under the eye incl. its system controls, and the emulator view ends
     * ~40° under the eye. 0.66 m ahead and 0.35 m down puts the tag tops at −17° and the resting bobs at <= −38°, the wings
     * (keys, readouts, selector, plaque) beside the window at −8…−22° (deviation DV-K13; DV-K8 / DV-K9 withdrawn).
     */
    const val AHEAD_M = 0.66
    const val DROP_M = 0.35
    /** Felt height window while moving the bench: felt = E − 0.85 … E − 0.20 (felt is 0.25 m under the origin). */
    const val FELT_LOW = 0.85
    const val FELT_HIGH = 0.20
    const val MIN_HORIZONTAL_M = 0.30
    /** Snap to the dashed frame: in at 6 cm / 10°, out at 10 cm / 15° (design input.benchMove). */
    const val SNAP_IN_M = 0.06
    const val SNAP_IN_DEG = 10.0
    const val SNAP_OUT_M = 0.10
    const val SNAP_OUT_DEG = 15.0
    /** The stick turns the bench at 90°/s; inside ±0.2 the stick is at rest. */
    const val TURN_DEG_S = 90.0
    const val STICK_DEAD = 0.2
    /** Head inside the content box + 15 cm: the whole bench goes to 40 % (D14). */
    const val NEAR_PAD_M = 0.15
    const val NEAR_OPACITY = 0.4f

    /** Horizontal unit heading of a forward vector (null when it points straight up / down). */
    fun flat(forward: Vec3): Vec3? {
        val f = Vec3(forward.x, 0.0, forward.z)
        val l = f.len()
        return if (l < 1e-6) null else f * (1 / l)
    }

    /** Yaw that makes the bench front (+Z) point back at a viewer looking along [heading]. */
    fun yawFacing(heading: Vec3): Double = atan2(-heading.x, -heading.z)

    /** S8: the default pose for a head at [eye] looking along [forward] (pitch / roll ignored). */
    fun defaultPose(eye: Vec3, forward: Vec3): BenchPose {
        val f = flat(forward) ?: Vec3(0.0, 0.0, -1.0)
        return BenchPose(Vec3(eye.x + f.x * AHEAD_M, eye.y - DROP_M, eye.z + f.z * AHEAD_M), yawFacing(f))
    }

    /** S7: keep a dragged origin inside the felt-height window and at least 0.30 m (horizontal) from the eye. */
    fun clampOrigin(o: Vec3, eye: Vec3): Vec3 {
        val y = o.y.coerceIn(eye.y - FELT_LOW + 0.25, eye.y - FELT_HIGH + 0.25)
        val dx = o.x - eye.x; val dz = o.z - eye.z
        val d = sqrt(dx * dx + dz * dz)
        if (d >= MIN_HORIZONTAL_M) return Vec3(o.x, y, o.z)
        val ux = if (d < 1e-6) 0.0 else dx / d; val uz = if (d < 1e-6) -1.0 else dz / d
        return Vec3(eye.x + ux * MIN_HORIZONTAL_M, y, eye.z + uz * MIN_HORIZONTAL_M)
    }

    fun angleDiffDeg(a: Double, b: Double): Double {
        var d = (a - b) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return abs(Math.toDegrees(d))
    }

    /** The bench is near the eye: true when the eye is inside the content box grown by [NEAR_PAD_M]. */
    fun headInside(pose: BenchPose, eye: Vec3): Boolean {
        val l = pose.toLocal(eye)
        val p = NEAR_PAD_M
        return l.x > -Layout.VOL_W / 2 - p && l.x < Layout.VOL_W / 2 + p && l.y > -Layout.VOL_H / 2 - p && l.y < Layout.VOL_H / 2 + p &&
            l.z > -Layout.VOL_D / 2 - p && l.z < Layout.VOL_D / 2 + p
    }

    /** One stick sample turns the bench by this much (radians, + = counter-clockwise from above, stick right turns right). */
    fun stickTurn(stickX: Double, dt: Double): Double = if (abs(stickX) < STICK_DEAD) 0.0 else -stickX * Math.toRadians(TURN_DEG_S) * dt
}

/** Snap with hysteresis to the dashed frame (the default pose), only while the bench is being dragged. */
class BenchSnapper {
    var snapped = false; private set

    fun reset() { snapped = false }

    /** The pose to show for a drag at [free] when the frame is at [frame]. */
    fun step(free: BenchPose, frame: BenchPose): BenchPose {
        val d = Vec3(free.origin.x - frame.origin.x, free.origin.y - frame.origin.y, free.origin.z - frame.origin.z).len()
        val a = StagePose.angleDiffDeg(free.yaw, frame.yaw)
        snapped = if (snapped) d <= StagePose.SNAP_OUT_M && a <= StagePose.SNAP_OUT_DEG else d <= StagePose.SNAP_IN_M && a <= StagePose.SNAP_IN_DEG
        return if (snapped) frame else free
    }
}

/**
 * S8「看正前方」: wait until the head is level (|pitch| <= 10°) and still (yaw rate < 10°/s) for 300 ms, then take that
 * heading; after 3 s take whatever heading the head has (never stuck). Pure: the caller feeds time, pitch and yaw.
 */
class PlaceWait(val maxPitchDeg: Double = 10.0, val maxYawRateDegS: Double = 10.0, val dwellMs: Long = 300L, val timeoutMs: Long = 3000L) {
    var waiting = false; private set
    var why = ""; private set
    private var askedMs = 0L
    private var steadySince = -1L
    private var lastYaw: Double? = null
    private var lastMs = 0L

    fun ask(reason: String, now: Long) { waiting = true; why = reason; askedMs = now; steadySince = -1L; lastYaw = null }
    fun cancel() { waiting = false; steadySince = -1L; lastYaw = null }

    /** Returns true when the bench should be placed now (with the head pose of this frame). Null pitch = no head yet. */
    fun step(now: Long, pitchDeg: Double?, yaw: Double?): Boolean {
        if (!waiting) return false
        if (pitchDeg == null || yaw == null) { steadySince = -1L; lastYaw = null; return false }
        val prev = lastYaw
        val rate = if (prev == null || now <= lastMs) 0.0 else StagePose.angleDiffDeg(yaw, prev) / ((now - lastMs) / 1000.0)
        lastYaw = yaw; lastMs = now
        val steady = abs(pitchDeg) <= maxPitchDeg && rate < maxYawRateDegS && prev != null
        if (!steady) steadySince = -1L else if (steadySince < 0) steadySince = now
        val ok = (steadySince >= 0 && now - steadySince >= dwellMs) || now - askedMs >= timeoutMs
        if (ok) waiting = false
        return ok
    }

    /** Show「看正前方」while waiting. */
    val hint: Boolean get() = waiting
}
