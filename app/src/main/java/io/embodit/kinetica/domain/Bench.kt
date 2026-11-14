package io.embodit.kinetica.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class Hanger { ROD, SPRING, DOUBLE; fun next() = entries[(ordinal + 1) % 3] }

enum class BobMat(val massKg: Double, val visualDiaM: Double, val restitution: Double) {
    WOOD(0.05, 0.04, 0.55), STEEL(0.1, 0.045, 0.9), BRASS(0.2, 0.05, 0.75);
    fun next() = entries[(ordinal + 1) % 3]
}

/** Moon / Mars / Earth / Jupiter (user 2026-11-13: add Mars); the selector steps through them with ◀ ▶. */
/**
 * The gravity selector (user 2026-11-14: the whole solar system), ordered by distance from the Sun; the Moon sits right
 * after the Earth (lesson 2 uses it). Pluto is a dwarf planet (its label says so). g in m/s² from [BenchConfig].
 */
enum class Planet(val g: Double, val dwarf: Boolean = false) {
    MERCURY(BenchConfig.G_MERCURY), VENUS(BenchConfig.G_VENUS), EARTH(BenchConfig.G_EARTH), MOON(BenchConfig.G_MOON),
    MARS(BenchConfig.G_MARS), JUPITER(BenchConfig.G_JUPITER), SATURN(BenchConfig.G_SATURN), URANUS(BenchConfig.G_URANUS),
    NEPTUNE(BenchConfig.G_NEPTUNE), PLUTO(BenchConfig.G_PLUTO, dwarf = true);
    fun next() = entries[(ordinal + 1) % entries.size]
    fun prev() = entries[(ordinal + entries.size - 1) % entries.size]
}

/**
 * Every physics number in one place (design-package physicsSpec / spatialLayout / container.volume.math). Metres, seconds,
 * kilograms; origin = volume centre, +Y up, +Z toward the user. Locked by BenchPhysicsTest.
 */
object BenchConfig {
    // surface gravity, m/s² (user 2026-11-14 list; NASA planetary fact sheet values)
    const val G_MERCURY = 3.70
    const val G_VENUS = 8.87
    const val G_EARTH = 9.81
    const val G_MOON = 1.62
    const val G_MARS = 3.71
    const val G_JUPITER = 24.79
    const val G_SATURN = 10.44
    const val G_URANUS = 8.69
    const val G_NEPTUNE = 11.15
    const val G_PLUTO = 0.62

    const val H = 1.0 / 240.0
    const val MAX_SUBSTEPS = 4
    const val SLOW = 0.25
    val PIVOT_X = doubleArrayOf(-0.12, 0.0, 0.12)
    const val PIVOT_Y = 0.05
    const val FELT_Y = -0.25
    /** Size index 0 / 1 / 2 = short / mid / long (rod, double) = stiff / mid / soft (spring): dragging a tag down = +1. */
    val ROD_L = doubleArrayOf(0.12, 0.18, 0.24)
    val DOUBLE_L = doubleArrayOf(0.085, 0.10, 0.12)
    val SPRING_K = doubleArrayOf(80.0, 50.0, 32.0)
    const val SPRING_L0 = 0.06
    const val SPRING_ZETA = 0.005
    const val COIL_STOP = 0.035
    const val COIL_E = 0.3
    const val AIR_DRAG = 0.05
    const val SETTLE_DRAG = 12.0
    const val SETTLE_S = 1.2

    const val POST_X = 0.36
    const val POST_Z = -0.05
    const val POST_R = 0.012
    const val POST_BOTTOM = -0.25
    const val BAR_Y = 0.06
    const val BAR_Z = -0.05
    const val BAR_R = 0.008
    const val ARM_R = 0.005
    const val ROD_R = 0.0025
    const val SPRING_R = 0.008
    const val FELT_E = 0.30
    const val BRASS_E = 0.60

    const val CLAMP_X = 0.375
    const val CLAMP_Y_LO = -0.225
    const val CLAMP_Y_HI = 0.315
    const val CLAMP_Z = 0.275

    const val HOLD_MAX_DEG = 80.0
    const val REACH = 0.265
    const val RELEASE_VMAX = 1.5
    const val RELEASE_WINDOW_S = 0.080
    const val TAP_MAX_M = 0.008
    const val TAG_STEP_M = 0.025
    /** Input collider of every bob (bigger than the visible ball, D17). */
    const val BOB_HIT_D = 0.065
    const val IMPULSE_DASH = 0.02
    const val SOUND_MIN_V = 0.05
    const val SOUND_FULL_V = 1.2
    const val TIP_DIVERGE_M = 0.05
    const val TIP_DIVERGE_S = 10.0
    /** ▶ keys (D43): default amplitudes. */
    const val AUTO_ROD_DEG = 20.0
    const val AUTO_SPRING_M = 0.05
    const val AUTO_DOUBLE_TH1_DEG = 90.0
    const val AUTO_DOUBLE_TH2_DEG = 150.0

    fun rodL(size: Int) = ROD_L[size.coerceIn(0, 2)]
    fun doubleL(size: Int) = DOUBLE_L[size.coerceIn(0, 2)]
    fun springK(size: Int) = SPRING_K[size.coerceIn(0, 2)]
    fun springDamping(k: Double, m: Double) = 2 * SPRING_ZETA * sqrt(k * m)
}

class Body(var mat: BobMat) {
    val p = Vec3()
    val v = Vec3()
    var held = false
    /** Hand velocity while held (80 ms average), used as the held body's velocity in collisions. */
    val heldV = Vec3()
    val m: Double get() = mat.massKg
    val r: Double get() = mat.visualDiaM / 2
    val e: Double get() = mat.restitution
}

/** One real release: positions / velocities of every body of the station (design physicsSpec.replay). */
class Release(val p: List<Vec3>, val v: List<Vec3>)

/** Period meter state (design physicsSpec.periodMeter). Times are physics seconds. */
class Meter {
    val crosses = ArrayDeque<Double>()
    var prev: Double? = null
    var axisX = 1.0
    var axisZ = 0.0
    var hasAxis = false
    var vA = 0.0
    var hA = 0.0
    var swing = false
    var flips = 0
    var prevAng: Double? = null
    var releasedAt: Double? = null
    /**
     * q2-3 / q2-4 (S4): physics time the station was told to stop (its ■ key, [全停下]) or came to rest. From then on the
     * meter is frozen: no crossings, no flips, the "since release" clock stops; the last full period stays on the plate.
     */
    var stoppedAt: Double? = null
    /** Crossings needed before a period shows: 2 normally, 3 right after a planet change (the first bounce is half a swing). */
    var minCrosses = 2

    fun reset() {
        crosses.clear(); prev = null; hasAxis = false; vA = 0.0; hA = 0.0; swing = false; flips = 0; prevAng = null; releasedAt = null; stoppedAt = null; minCrosses = 2
    }

    val frozen: Boolean get() = stoppedAt != null

    /** Seconds since the release, stopped at [stoppedAt] (q2-4). */
    fun since(now: Double): Double? = releasedAt?.let { (stoppedAt ?: now) - it }

    /** Last 2 periods averaged (one period as soon as there are two crossings); null before that. */
    val period: Double? get() = if (crosses.size >= minCrosses) (crosses.last() - crosses.first()) / (crosses.size - 1) else null
}

class Station(val index: Int, type: Hanger, size: Int, mats: List<BobMat>) {
    var type = type; internal set
    var size = size; internal set
    val pivot = Vec3(BenchConfig.PIVOT_X[index], BenchConfig.PIVOT_Y, 0.0)
    val bodies = ArrayList<Body>()
    // double pendulum angles (from straight down, in the x-y plane) and angular velocities
    var th1 = 0.0; var th2 = 0.0; var w1 = 0.0; var w2 = 0.0
    var rec: Release? = null
    var replayN = 0
    val meter = Meter()
    /** Tip bookkeeping: material changed / made longer since the last real release. */
    var matChanged = false
    var longer = false
    /** Bumped on every change of type / size / material (scene tweens and texture refresh). */
    var version = 0
    // tip 6: the lower bob 10 s after each release / replay
    var runStart: Double? = null
    var runIsReplay = false
    var runAt10: Vec3? = null
    var prevRunAt10: Vec3? = null
    var lastDivergeM: Double? = null

    init { setup(type, size, mats) }

    val L: Double get() = when (type) { Hanger.ROD -> BenchConfig.rodL(size); Hanger.DOUBLE -> BenchConfig.doubleL(size); Hanger.SPRING -> 0.0 }
    val k: Double get() = BenchConfig.springK(size)
    val mats: List<BobMat> get() = bodies.map { it.mat }

    internal fun setup(t: Hanger, s: Int, m: List<BobMat>) {
        type = t; size = s.coerceIn(0, 2)
        bodies.clear()
        bodies += Body(m.getOrElse(0) { BobMat.STEEL })
        if (t == Hanger.DOUBLE) bodies += Body(m.getOrElse(1) { BobMat.STEEL })
        version++
    }
}

enum class ContactKind { BOB_BOB, METAL, FELT, COIL }

/** A collision this substep, for sounds (speed = closing normal speed before the bounce). */
class Contact(val kind: ContactKind, val a: BobMat, val b: BobMat?, val speed: Double, val key: String) {
    /** false while the same pair stayed in touch since the last substep (resting / rolling contact makes no new knock). */
    var fresh = true
}

/**
 * The bench physics (design physicsSpec): a fixed 1/240 s step, at most 4 per frame (time beyond that is dropped). Rods are
 * integrated as spherical pendulums (RK4 on the sphere), the double as the classic planar double pendulum (RK4 on the two
 * angles), springs with semi-implicit Euler (Hooke + axial damping). Collisions: bob x bob, bob x other stations'
 * rods / springs (capsules), posts, crossbar, arms, felt; restitution = the smaller of the two. Deterministic: no
 * randomness, fixed iteration order. Pure Kotlin, unit-tested in BenchPhysicsTest.
 */
class Bench(defaults: List<Triple<Hanger, Int, List<BobMat>>> = DEFAULT) {
    val stations = defaults.mapIndexed { i, (t, s, m) -> Station(i, t, s, m) }
    var planet = Planet.EARTH
    /** Physics time (s). Readouts use it, so slow motion does not change a period. */
    var time = 0.0; private set
    var drag = BenchConfig.AIR_DRAG
    var settleLeft = 0.0; private set
    /** Per-station settle (the station's ■ Stop key): seconds left of the critical damping. */
    val stationSettle = DoubleArray(3)
    private val settleExtra = IntArray(3)
    var frozen = false
    /** false = stations never touch each other (unit tests run the stations "apart", design physicsSpec.replay). */
    var crossStation = true
    private var acc = 0.0
    val contacts = ArrayList<Contact>()
    /** Station index -> physics time of its last hit above the dash threshold. */
    val g: Double get() = planet.g

    init { stations.forEach { rest(it) } }

    val settling: Boolean get() = settleLeft > 0

    fun allBodies(): List<Body> = stations.flatMap { it.bodies }

    // ------------------------------------------------------------------ rest / equilibrium

    fun springEq(s: Station): Double = BenchConfig.SPRING_L0 + s.bodies[0].m * g / s.k

    fun eqPos(s: Station, k: Int): Vec3 = when (s.type) {
        Hanger.ROD -> Vec3(s.pivot.x, s.pivot.y - s.L, 0.0)
        Hanger.SPRING -> Vec3(s.pivot.x, s.pivot.y - springEq(s), 0.0)
        Hanger.DOUBLE -> Vec3(s.pivot.x, s.pivot.y - s.L * (k + 1), 0.0)
    }

    fun rest(s: Station) {
        s.bodies.forEachIndexed { k, b -> b.p.set(eqPos(s, k)); b.v.set(0.0, 0.0, 0.0); b.held = false }
        s.th1 = 0.0; s.th2 = 0.0; s.w1 = 0.0; s.w2 = 0.0
        s.meter.reset()
    }

    fun anyMoving(threshold: Double = 0.002): Boolean = allBodies().any { it.held || it.v.len() > threshold }

    /** Some bob is away from its rest position by more than [tol] (released from rest counts as moving). */
    fun anyDisplaced(tol: Double = 0.002): Boolean = stations.any { s -> s.bodies.indices.any { k -> s.bodies[k].p.dist(eqPos(s, k)) > tol } }

    fun anyHeld(): Boolean = allBodies().any { it.held }

    // ------------------------------------------------------------------ time

    /** Advances by a wall-clock [dt]; returns the number of substeps run. */
    fun advance(dt: Double, slow: Boolean): Int {
        if (frozen) return 0
        val scale = if (slow) BenchConfig.SLOW else 1.0
        acc += dt * scale
        val maxAcc = BenchConfig.MAX_SUBSTEPS * BenchConfig.H
        if (acc > maxAcc) acc = maxAcc // drop time, never spiral
        var n = 0
        while (acc >= BenchConfig.H - 1e-12 && n < BenchConfig.MAX_SUBSTEPS) {
            step()
            acc -= BenchConfig.H
            n++
        }
        return n
    }

    /** Runs [seconds] of physics now (tests, debug holds). */
    fun run(seconds: Double) { repeat((seconds / BenchConfig.H).toInt()) { step() } }

    fun step() {
        val h = BenchConfig.H
        for (s in stations) integrate(s, h, if (settleLeft > 0 || stationSettle[s.index] > 0) BenchConfig.SETTLE_DRAG else drag)
        collide()
        for (s in stations) meterStep(s)
        time += h
        // q2-4: a station that came to rest (after its ■ settle or by itself) stops its clock too
        for (s in stations) if (s.meter.releasedAt != null && !s.meter.frozen && stationSettle[s.index] <= 0 && settleLeft <= 0 && s.bodies.none { it.held } && !stationMoving(s.index, 0.0005)) s.meter.stoppedAt = time
        for (s in stations) if (stationSettle[s.index] > 0) {
            stationSettle[s.index] -= h
            if (stationSettle[s.index] <= 0 && settleExtra[s.index] < 15 && s.bodies.indices.any { k -> s.bodies[k].p.dist(eqPos(s, k)) > 0.004 }) {
                // a double that was flipped high creeps off its top slowly: keep damping (0.2 s at a time, <= 3 s more)
                stationSettle[s.index] = 0.2; settleExtra[s.index]++
            } else if (stationSettle[s.index] <= 0) {
                stationSettle[s.index] = 0.0
                // the last few millimetres: lay the bobs on their rest (an invisible < 4 mm step), the readout stays
                if (s.bodies.none { it.held }) { s.bodies.forEachIndexed { k, b -> b.p.set(eqPos(s, k)) }; s.th1 = 0.0; s.th2 = 0.0 }
                s.bodies.forEach { if (!it.held) it.v.set(0.0, 0.0, 0.0) }
                if (s.type == Hanger.DOUBLE && s.bodies.none { it.held }) { s.w1 = 0.0; s.w2 = 0.0 }
            }
        }
        if (settleLeft > 0) {
            settleLeft -= h
            if (settleLeft <= 0) {
                settleLeft = 0.0
                for (s in stations) s.bodies.forEach { if (!it.held) it.v.set(0.0, 0.0, 0.0) }
                for (s in stations) if (s.type == Hanger.DOUBLE && s.bodies.none { it.held }) { s.w1 = 0.0; s.w2 = 0.0 }
            }
        }
    }

    // ------------------------------------------------------------------ integration

    private fun integrate(s: Station, h: Double, drag: Double) {
        when (s.type) {
            Hanger.ROD -> {
                val bob = s.bodies[0]
                if (bob.held) { bob.v.set(bob.heldV); return }
                rodRk4(s.pivot, s.L, bob, h)
                bob.v.scale(1 - drag * h)
            }
            Hanger.SPRING -> {
                val bob = s.bodies[0]
                if (bob.held) { bob.v.set(bob.heldV); return }
                val d = bob.p - s.pivot
                val len = d.len()
                val n = d.normalized()
                val c = BenchConfig.springDamping(s.k, bob.m)
                val f = -s.k * (len - BenchConfig.SPRING_L0) - c * bob.v.dot(n)
                bob.v.addScaled(n, f * h / bob.m)
                bob.v.y -= g * h
                bob.v.scale(1 - drag * h)
                bob.p.addScaled(bob.v, h)
                coilStop(s, bob)
            }
            Hanger.DOUBLE -> {
                val (b1, b2) = s.bodies
                if (b1.held || b2.held) { holdDouble(s, h); return }
                doubleRk4(s, h)
                s.w1 *= (1 - drag * h); s.w2 *= (1 - drag * h)
                doubleToCartesian(s)
            }
        }
    }

    /** RK4 for a particle on a sphere of radius [l] about [pivot] under gravity; then exact re-projection. */
    private fun rodRk4(pivot: Vec3, l: Double, bob: Body, h: Double) {
        val gv = Vec3(0.0, -g, 0.0)
        fun acc(r: Vec3, v: Vec3): Vec3 {
            val rl = r.len()
            val n = r * (1.0 / rl)
            val gt = gv - n * n.dot(gv)
            return gt - n * (v.len2() / rl)
        }
        val r0 = bob.p - pivot
        val v0 = bob.v.copy()
        val a1 = acc(r0, v0)
        val r2 = r0 + v0 * (h / 2); val v2 = v0 + a1 * (h / 2); val a2 = acc(r2, v2)
        val r3 = r0 + v2 * (h / 2); val v3 = v0 + a2 * (h / 2); val a3 = acc(r3, v3)
        val r4 = r0 + v3 * h; val v4 = v0 + a3 * h; val a4 = acc(r4, v4)
        val r = r0 + (v0 + v2 * 2.0 + v3 * 2.0 + v4) * (h / 6)
        val v = v0 + (a1 + a2 * 2.0 + a3 * 2.0 + a4) * (h / 6)
        val n = r.normalized()
        bob.p.set(pivot).addScaled(n, l)
        bob.v.set(v).addScaled(n, -v.dot(n))
    }

    private fun doubleDeriv(s: Station, t1: Double, t2: Double, o1: Double, o2: Double): DoubleArray {
        val m1 = s.bodies[0].m; val m2 = s.bodies[1].m; val l = s.L
        val d = t1 - t2
        val den = 2 * m1 + m2 - m2 * cos(2 * t1 - 2 * t2)
        val a1 = (-g * (2 * m1 + m2) * sin(t1) - m2 * g * sin(t1 - 2 * t2) - 2 * sin(d) * m2 * (o2 * o2 * l + o1 * o1 * l * cos(d))) / (l * den)
        val a2 = (2 * sin(d) * (o1 * o1 * l * (m1 + m2) + g * (m1 + m2) * cos(t1) + o2 * o2 * l * m2 * cos(d))) / (l * den)
        return doubleArrayOf(o1, o2, a1, a2)
    }

    private fun doubleRk4(s: Station, h: Double) {
        val y = doubleArrayOf(s.th1, s.th2, s.w1, s.w2)
        fun f(z: DoubleArray) = doubleDeriv(s, z[0], z[1], z[2], z[3])
        fun add(a: DoubleArray, b: DoubleArray, k: Double) = DoubleArray(4) { a[it] + b[it] * k }
        val k1 = f(y); val k2 = f(add(y, k1, h / 2)); val k3 = f(add(y, k2, h / 2)); val k4 = f(add(y, k3, h))
        s.th1 += h / 6 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0])
        s.th2 += h / 6 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1])
        s.w1 += h / 6 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2])
        s.w2 += h / 6 * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3])
    }

    internal fun doubleToCartesian(s: Station) {
        val (b1, b2) = s.bodies; val l = s.L; val P = s.pivot
        b1.p.set(P.x + l * sin(s.th1), P.y - l * cos(s.th1), 0.0)
        b2.p.set(b1.p.x + l * sin(s.th2), b1.p.y - l * cos(s.th2), 0.0)
        b1.v.set(l * s.w1 * cos(s.th1), l * s.w1 * sin(s.th1), 0.0)
        b2.v.set(b1.v.x + l * s.w2 * cos(s.th2), b1.v.y + l * s.w2 * sin(s.th2), 0.0)
    }

    /** Positions -> angles; velocities -> angular velocities (projection on each link's tangent). */
    internal fun doubleFromCartesian(s: Station) {
        val (b1, b2) = s.bodies; val l = s.L; val P = s.pivot
        s.th1 = atan2(b1.p.x - P.x, -(b1.p.y - P.y))
        s.th2 = atan2(b2.p.x - b1.p.x, -(b2.p.y - b1.p.y))
        s.w1 = (b1.v.x * cos(s.th1) + b1.v.y * sin(s.th1)) / l
        val rx = b2.v.x - b1.v.x; val ry = b2.v.y - b1.v.y
        s.w2 = (rx * cos(s.th2) + ry * sin(s.th2)) / l
        doubleToCartesian(s)
    }

    /** One of the double's bobs is held: the held one follows the hand, the other follows the links. */
    private fun holdDouble(s: Station, h: Double) {
        val (b1, b2) = s.bodies; val l = s.L; val P = s.pivot
        if (b2.held) {
            b2.v.set(b2.heldV)
            val up = elbow(P, b2.p, l, b1.p)
            b1.v.set((up - b1.p) * (1.0 / h)); b1.p.set(up)
        } else {
            b1.v.set(b1.heldV)
            // the lower bob swings from the held upper bob (simple pendulum, damped)
            s.th2 += s.w2 * h
            s.w2 += -(g / l) * sin(s.th2) * h
            s.w2 *= (1 - 2.0 * h)
            val np = Vec3(b1.p.x + l * sin(s.th2), b1.p.y - l * cos(s.th2), 0.0)
            b2.v.set((np - b2.p) * (1.0 / h)); b2.p.set(np)
        }
    }

    /** The double's upper bob when the lower one is at [lower]: the elbow solution closest to [current]. */
    fun elbow(P: Vec3, lower: Vec3, l: Double, current: Vec3): Vec3 {
        val dx = lower.x - P.x; val dy = lower.y - P.y
        var d = sqrt(dx * dx + dy * dy)
        if (d < 1e-6) return Vec3(P.x, P.y - l, 0.0)
        d = d.coerceAtMost(2 * l * 0.999)
        val ux = dx / sqrt(dx * dx + dy * dy); val uy = dy / sqrt(dx * dx + dy * dy)
        val mx = P.x + ux * d / 2; val my = P.y + uy * d / 2
        val hh = sqrt((l * l - d * d / 4).coerceAtLeast(0.0))
        val c1 = Vec3(mx - uy * hh, my + ux * hh, 0.0); val c2 = Vec3(mx + uy * hh, my - ux * hh, 0.0)
        return if (c1.dist(current) <= c2.dist(current)) c1 else c2
    }

    private fun coilStop(s: Station, bob: Body) {
        val d = bob.p - s.pivot
        val len = d.len()
        if (len >= BenchConfig.COIL_STOP) return
        val n = d.normalized()
        bob.p.set(s.pivot).addScaled(n, BenchConfig.COIL_STOP)
        val vn = bob.v.dot(n)
        if (vn < 0) {
            bob.v.addScaled(n, -(1 + BenchConfig.COIL_E) * vn)
            contacts += Contact(ContactKind.COIL, bob.mat, null, -vn, "coil${s.index}")
        }
    }

    // ------------------------------------------------------------------ collisions

    private class Seg(val a: Vec3, val b: Vec3, val r: Double, val station: Int, val kind: ContactKind, val e: Double, val skipBody: Body? = null, val armOf: Int = -1)

    private fun staticSegments(): List<Seg> {
        val c = BenchConfig
        val out = ArrayList<Seg>(8)
        for (sg in intArrayOf(-1, 1)) out += Seg(Vec3(sg * c.POST_X, c.POST_BOTTOM, c.POST_Z), Vec3(sg * c.POST_X, c.BAR_Y, c.POST_Z), c.POST_R, -1, ContactKind.METAL, c.BRASS_E)
        out += Seg(Vec3(-c.POST_X, c.BAR_Y, c.BAR_Z), Vec3(c.POST_X, c.BAR_Y, c.BAR_Z), c.BAR_R, -1, ContactKind.METAL, c.BRASS_E)
        for (s in stations) {
            // the double's lower bob is exempt from its own arm (R2-07)
            val skip = if (s.type == Hanger.DOUBLE) s.bodies[1] else null
            out += Seg(Vec3(s.pivot.x, c.PIVOT_Y, c.BAR_Z), Vec3(s.pivot.x, c.PIVOT_Y, 0.0), c.ARM_R, -1, ContactKind.METAL, c.BRASS_E, skip, s.index)
        }
        // the three ▶ swing keys on the felt and the planet selector on the right ear (bobs bounce off, never pass through)
        for (seg in Layout.obstacleCapsules()) out += Seg(seg.first, seg.second, seg.third, -1, ContactKind.METAL, c.BRASS_E)
        return out
    }

    /** Rods / springs of every station as capsules; a bob never hits its own station's links. */
    private fun linkSegments(): List<Seg> {
        val c = BenchConfig
        val out = ArrayList<Seg>(6)
        for (s in stations) when (s.type) {
            Hanger.ROD -> out += Seg(s.pivot.copy(), s.bodies[0].p.copy(), c.ROD_R, s.index, ContactKind.METAL, c.BRASS_E)
            Hanger.SPRING -> out += Seg(s.pivot.copy(), s.bodies[0].p.copy(), c.SPRING_R, s.index, ContactKind.METAL, c.BRASS_E)
            Hanger.DOUBLE -> {
                out += Seg(s.pivot.copy(), s.bodies[0].p.copy(), c.ROD_R, s.index, ContactKind.METAL, c.BRASS_E)
                out += Seg(s.bodies[0].p.copy(), s.bodies[1].p.copy(), c.ROD_R, s.index, ContactKind.METAL, c.BRASS_E)
            }
        }
        return out
    }

    private val stationOf = HashMap<Body, Station>()

    private var touching = HashSet<String>()
    private var touchingNow = HashSet<String>()

    private fun collide() {
        val c = BenchConfig
        val firstContact = contacts.size
        stationOf.clear()
        for (s in stations) s.bodies.forEach { stationOf[it] = s }
        val touched = HashSet<Station>()
        val bodies = allBodies()
        // bob x bob (other stations)
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            val sa = stationOf[a]!!; val sb = stationOf[b]!!
            if (sa === sb || !crossStation) continue
            val d = b.p - a.p
            val dist = d.len()
            val md = a.r + b.r
            if (dist >= md || dist < 1e-9) continue
            val wa = if (a.held) 0.0 else 1 / a.m
            val wb = if (b.held) 0.0 else 1 / b.m
            if (wa + wb == 0.0) continue
            touchingNow += "bb${minOf(i, j)}-${maxOf(i, j)}"
            val n = d * (1 / dist)
            val pen = md - dist
            a.p.addScaled(n, -pen * wa / (wa + wb)); b.p.addScaled(n, pen * wb / (wa + wb))
            val vn = (b.v - a.v).dot(n)
            if (vn < 0) {
                val e = min(a.e, b.e)
                val j0 = -(1 + e) * vn / (wa + wb)
                a.v.addScaled(n, -j0 * wa); b.v.addScaled(n, j0 * wb)
                hit(sa, j0); hit(sb, j0)
                contacts += Contact(ContactKind.BOB_BOB, a.mat, b.mat, -vn, "bb${minOf(i, j)}-${maxOf(i, j)}")
            }
            touched += sa; touched += sb
        }
        // bob x static parts and other stations' links
        val segs = staticSegments() + linkSegments()
        for (b in bodies) {
            if (b.held) continue
            val s = stationOf[b]!!
            for ((si, sg) in segs.withIndex()) {
                if (sg.station == s.index || sg.skipBody === b || (!crossStation && (sg.station >= 0 || (sg.armOf >= 0 && sg.armOf != s.index)))) continue
                val q = closestOnSegment(b.p, sg.a, sg.b)
                val d = b.p - q
                val dist = d.len()
                val md = b.r + sg.r
                if (dist >= md || dist < 1e-9) continue
                val n = d * (1 / dist)
                touchingNow += "m${bodies.indexOf(b)}-$si"
                b.p.addScaled(n, md - dist)
                val vn = b.v.dot(n)
                if (vn < 0) {
                    val e = min(b.e, sg.e)
                    b.v.addScaled(n, -(1 + e) * vn)
                    hit(s, (1 + e) * -vn * b.m)
                    contacts += Contact(sg.kind, b.mat, null, -vn, "m${bodies.indexOf(b)}-$si")
                }
                touched += s
            }
            // felt
            if (b.p.y < c.FELT_Y + b.r) {
                touchingNow += "felt${bodies.indexOf(b)}"
                b.p.y = c.FELT_Y + b.r
                val vn = b.v.y
                if (vn < 0) {
                    val e = min(b.e, c.FELT_E)
                    b.v.y = -e * vn
                    hit(s, (1 + e) * -vn * b.m)
                    contacts += Contact(ContactKind.FELT, b.mat, null, -vn, "felt${bodies.indexOf(b)}")
                }
                touched += s
            }
            // hard clamp inside the volume
            b.p.x = b.p.x.coerceIn(-c.CLAMP_X, c.CLAMP_X)
            b.p.y = b.p.y.coerceIn(c.CLAMP_Y_LO, c.CLAMP_Y_HI)
            b.p.z = b.p.z.coerceIn(-c.CLAMP_Z, c.CLAMP_Z)
        }
        for (k in firstContact until contacts.size) contacts[k].fresh = contacts[k].key !in touching
        val t = touching; touching = touchingNow; touchingNow = t; touchingNow.clear()
        // re-apply the links after the pushes
        for (s in touched) when (s.type) {
            Hanger.ROD -> { val b = s.bodies[0]; if (!b.held) projectRod(s, b) }
            Hanger.SPRING -> { val b = s.bodies[0]; if (!b.held) coilStop(s, b) }
            Hanger.DOUBLE -> if (s.bodies.none { it.held }) { s.bodies.forEach { it.p.z = 0.0; it.v.z = 0.0 }; doubleFromCartesian(s) }
        }
    }

    private fun projectRod(s: Station, b: Body) {
        val n = (b.p - s.pivot).normalized()
        b.p.set(s.pivot).addScaled(n, s.L)
        b.v.addScaled(n, -b.v.dot(n))
    }

    private fun hit(s: Station, impulse: Double) {
        if (impulse > BenchConfig.IMPULSE_DASH && !s.meter.frozen) { s.meter.crosses.clear(); s.meter.prev = null }
    }

    // ------------------------------------------------------------------ period meter

    private fun meterStep(s: Station) {
        val m = s.meter
        if (m.releasedAt == null || m.frozen) return
        val t = time + BenchConfig.H
        when (s.type) {
            Hanger.ROD -> {
                val b = s.bodies[0]
                val dx = b.p.x - s.pivot.x; val dz = b.p.z - s.pivot.z
                if (!m.hasAxis) {
                    val l = sqrt(dx * dx + dz * dz)
                    if (l > 1e-4) { m.axisX = dx / l; m.axisZ = dz / l } else { m.axisX = 1.0; m.axisZ = 0.0 }
                    m.hasAxis = true
                }
                cross(m, dx * m.axisX + dz * m.axisZ, t)
            }
            Hanger.SPRING -> {
                val b = s.bodies[0]
                val y = b.p.y - (s.pivot.y - springEq(s))
                val hz = sqrt((b.p.x - s.pivot.x).let { it * it } + b.p.z * b.p.z)
                m.vA = maxOf(m.vA * 0.9995, abs(y)); m.hA = maxOf(m.hA * 0.9995, hz)
                m.swing = !(m.vA > 2 * m.hA)
                if (!m.swing) cross(m, y, t) else { m.crosses.clear(); m.prev = y }
            }
            Hanger.DOUBLE -> {
                val ang = s.th2.let { a -> atan2(sin(a), cos(a)) }
                val p = m.prevAng
                if (p != null && abs(ang) > 2.5 && abs(p) > 2.5 && (ang > 0) != (p > 0)) m.flips++
                m.prevAng = ang
                val st = s.runStart
                if (st != null && s.runAt10 == null && time - st >= BenchConfig.TIP_DIVERGE_S) {
                    s.runAt10 = s.bodies[1].p.copy()
                    val prev = s.prevRunAt10
                    if (s.runIsReplay && prev != null) s.lastDivergeM = prev.dist(s.runAt10!!)
                }
            }
        }
    }

    private fun cross(m: Meter, x: Double, t: Double) {
        val p = m.prev
        if (p != null && p < 0 && x >= 0) {
            val frac = if (x - p > 1e-12) -p / (x - p) else 0.0
            m.crosses.addLast(t - BenchConfig.H + frac * BenchConfig.H)
            while (m.crosses.size > 3) m.crosses.removeFirst()
        }
        m.prev = x
    }

    fun idealPeriod(s: Station): Double? = when (s.type) {
        Hanger.ROD -> 2 * PI * sqrt(s.L / g)
        Hanger.SPRING -> 2 * PI * sqrt(s.bodies[0].m / s.k)
        Hanger.DOUBLE -> null
    }

    // ------------------------------------------------------------------ holding (design physicsSpec.hold)

    /** Grab body [k] of station [si]: it stops in the hand; the station's meter clears. */
    fun grab(si: Int, k: Int) {
        val s = stations[si]
        val b = s.bodies.getOrNull(k) ?: return
        b.held = true
        b.v.set(0.0, 0.0, 0.0); b.heldV.set(0.0, 0.0, 0.0)
        if (s.type == Hanger.DOUBLE) { s.bodies.forEach { it.v.set(0.0, 0.0, 0.0) }; s.w1 = 0.0; s.w2 = 0.0 }
        s.meter.reset()
        settleLeft = 0.0
        stationSettle[si] = 0.0
    }

    /** Moves a held body toward [target], constrained (sphere / spring range / plane) and pushed out of static parts. */
    fun moveHeld(si: Int, k: Int, target: Vec3, handV: Vec3? = null) {
        val s = stations[si]
        val b = s.bodies.getOrNull(k) ?: return
        if (!b.held) return
        b.p.set(constrainHeld(s, k, target))
        handV?.let { b.heldV.set(it) }
        if (s.type == Hanger.DOUBLE) {
            if (k == 1) s.bodies[0].p.set(elbow(s.pivot, b.p, s.L, s.bodies[0].p))
            else {
                val l = s.L
                s.bodies[1].p.set(b.p.x + l * sin(s.th2), b.p.y - l * cos(s.th2), 0.0)
            }
        }
    }

    /** Where a held body may go (design physicsSpec.hold). Pure: used by the unit tests too. */
    fun constrainHeld(s: Station, k: Int, target: Vec3): Vec3 {
        val c = BenchConfig
        var t = target.copy()
        repeat(3) { t = pushOutStatic(s, k, t) }
        val P = s.pivot
        return when (s.type) {
            Hanger.ROD -> P + limitAngle(t - P) * s.L
            Hanger.SPRING -> {
                val d = t - P
                val maxLen = min(c.REACH, 2 * springEq(s) - 0.04)
                val len = d.len().coerceIn(0.05, maxOf(0.05, maxLen))
                val p = P + limitAngle(d) * len
                p.y = maxOf(p.y, c.FELT_Y + s.bodies[0].r)
                p
            }
            Hanger.DOUBLE -> {
                val p = t.copy().also { it.z = 0.0 }
                val d = p - P
                if (k == 0) P + d.normalized() * s.L
                else {
                    val lim = min(2 * s.L * 0.999, c.REACH)
                    if (d.len() > lim) P + d.normalized() * lim else if (d.len() < 0.02) P + Vec3(0.0, -0.02, 0.0) else p
                }
            }
        }
    }

    /** Unit direction of [d] limited to <= 80° from straight down. */
    private fun limitAngle(d: Vec3): Vec3 {
        val n = d.normalized()
        val down = Vec3(0.0, -1.0, 0.0)
        val ang = acos(n.dot(down).coerceIn(-1.0, 1.0))
        val max = Math.toRadians(BenchConfig.HOLD_MAX_DEG)
        if (ang <= max) return n
        val horiz = Vec3(n.x, 0.0, n.z)
        val hl = horiz.len()
        val hd = if (hl < 1e-9) Vec3(1.0, 0.0, 0.0) else horiz * (1 / hl)
        return Vec3(hd.x * sin(max), -cos(max), hd.z * sin(max))
    }

    private fun pushOutStatic(s: Station, k: Int, t: Vec3): Vec3 {
        val b = s.bodies[k]
        val p = t.copy()
        for (sg in staticSegments() + linkSegments()) {
            if (sg.station == s.index || sg.skipBody === b) continue
            val q = closestOnSegment(p, sg.a, sg.b)
            val d = p - q
            val dist = d.len()
            val md = b.r + sg.r
            if (dist < md && dist > 1e-9) p.addScaled(d * (1 / dist), md - dist)
        }
        if (p.y < BenchConfig.FELT_Y + b.r) p.y = BenchConfig.FELT_Y + b.r
        return p
    }

    /**
     * Lets go of a held body with the hand velocity [vel] (null = from rest), capped at 1.5 m/s; a rod is capped so it can
     * swing no higher than horizontal; the double moves only in its plane. Records the release for [replay].
     */
    fun release(si: Int, k: Int, vel: Vec3?, real: Boolean = true) {
        val s = stations[si]
        val b = s.bodies.getOrNull(k) ?: return
        b.held = false
        val v = vel?.copy() ?: Vec3()
        if (v.len() > BenchConfig.RELEASE_VMAX) v.scale(BenchConfig.RELEASE_VMAX / v.len())
        when (s.type) {
            Hanger.ROD -> {
                val n = (b.p - s.pivot).normalized()
                v.addScaled(n, -v.dot(n))
                // energy cap: the highest point stays at or below the pivot (<= 90°)
                val vmax = sqrt(maxOf(0.0, 2 * g * (s.pivot.y - b.p.y)))
                if (v.len() > vmax) v.scale(if (v.len() > 1e-12) vmax / v.len() else 0.0)
                b.v.set(v)
            }
            Hanger.SPRING -> b.v.set(v)
            Hanger.DOUBLE -> {
                v.z = 0.0
                s.bodies.forEach { it.held = false; it.p.z = 0.0 }
                if (k == 1) {
                    // solve v2 = l w1 t1 + l w2 t2 for the two angular velocities
                    doubleFromCartesian(s.also { it.bodies[0].v.set(0.0, 0.0, 0.0); it.bodies[1].v.set(0.0, 0.0, 0.0) })
                    val l = s.L
                    val t1x = cos(s.th1); val t1y = sin(s.th1); val t2x = cos(s.th2); val t2y = sin(s.th2)
                    val det = t1x * t2y - t1y * t2x
                    if (abs(det) > 0.3) {
                        s.w1 = (v.x * t2y - v.y * t2x) / (l * det)
                        s.w2 = (t1x * v.y - t1y * v.x) / (l * det)
                    } else {
                        // links nearly in line: share the hand speed between the two joints
                        s.w1 = (v.x * t1x + v.y * t1y) / (2 * l); s.w2 = (v.x * t2x + v.y * t2y) / (2 * l)
                    }
                    // neither bob leaves faster than the hand cap
                    val fast = maxOf(abs(s.w1) * l, sqrt((l * s.w1 * t1x + l * s.w2 * t2x).let { it * it } + (l * s.w1 * t1y + l * s.w2 * t2y).let { it * it }))
                    if (fast > BenchConfig.RELEASE_VMAX) { val k2 = BenchConfig.RELEASE_VMAX / fast; s.w1 *= k2; s.w2 *= k2 }
                    doubleToCartesian(s)
                } else {
                    s.bodies[0].v.set(v); s.bodies[1].v.set(v)
                    doubleFromCartesian(s)
                }
            }
        }
        if (s.type == Hanger.DOUBLE) doubleToCartesian(s)
        if (real) {
            s.rec = Release(s.bodies.map { it.p.copy() }, s.bodies.map { it.v.copy() })
            s.replayN = 0
            s.prevRunAt10 = null
            startRun(s, replay = false)
        }
        s.meter.reset()
        s.meter.releasedAt = time
    }

    private fun startRun(s: Station, replay: Boolean) {
        if (replay) s.prevRunAt10 = s.runAt10 ?: s.prevRunAt10
        s.runStart = time; s.runIsReplay = replay; s.runAt10 = null
        if (!replay) s.lastDivergeM = null
    }

    /** Point release from rest where it is (a tap: change material, then let go). */
    fun releaseAtRest(si: Int, k: Int) {
        val s = stations[si]
        s.bodies.forEach { it.held = false; it.v.set(0.0, 0.0, 0.0) }
        if (s.type == Hanger.DOUBLE) { s.w1 = 0.0; s.w2 = 0.0; doubleFromCartesian(s) }
        if (s.type == Hanger.SPRING) coilStop(s, s.bodies[0])
        s.meter.reset()
        s.meter.releasedAt = time
    }

    // ------------------------------------------------------------------ station edits

    fun setMaterial(si: Int, k: Int, mat: BobMat) {
        val s = stations[si]
        val b = s.bodies.getOrNull(k) ?: return
        if (b.mat == mat) return
        b.mat = mat
        if (s.type != Hanger.DOUBLE) s.matChanged = true
        s.version++
        s.meter.reset()
    }

    /** S5: rod -> spring -> double -> rod; materials are kept (a new lower bob is steel); the release record clears. */
    fun swapHanger(si: Int) {
        val s = stations[si]
        val mats = s.mats
        val nt = s.type.next()
        s.setup(nt, s.size, if (nt == Hanger.DOUBLE) listOf(mats[0], BobMat.STEEL) else listOf(mats[0]))
        s.rec = null; s.replayN = 0; s.matChanged = false; s.longer = false; s.runStart = null; s.runAt10 = null; s.prevRunAt10 = null; s.lastDivergeM = null
        rest(s)
    }

    /** S6: size 0..2 (rod / double: short..long; spring: stiff..soft). Ends only stop. Returns true when it changed. */
    fun resize(si: Int, size: Int): Boolean {
        val s = stations[si]
        val n = size.coerceIn(0, 2)
        if (n == s.size) return false
        s.longer = n > s.size && s.type == Hanger.ROD
        s.size = n
        s.version++
        s.rec = null; s.replayN = 0; s.runStart = null; s.runAt10 = null; s.prevRunAt10 = null
        rest(s)
        return true
    }

    fun setStation(si: Int, t: Hanger, size: Int, mats: List<BobMat>) {
        val s = stations[si]
        s.setup(t, size, mats)
        s.rec = null; s.replayN = 0; s.matChanged = false; s.longer = false; s.runStart = null; s.runAt10 = null; s.prevRunAt10 = null; s.lastDivergeM = null
        rest(s)
    }

    /** Changes g. Moving bobs keep moving; periods restart. A resting spring then bounces a little (it is off its new rest). */
    /**
     * New g (v0.1.3 known issue: a spring left on its old rest woke up ~35 s later, and repeated changes left a ±2 mm
     * sideways sway): every station that is not held goes to its rest under the new g with zero velocity, the settle
     * timers stop, and the meter starts over (a period measured under the old g is not this planet's: it reads "let go").
     */
    fun changePlanet(p: Planet) {
        planet = p
        settleLeft = 0.0
        for (s in stations) {
            stationSettle[s.index] = 0.0; settleExtra[s.index] = 0
            if (s.bodies.any { it.held }) { s.meter.crosses.clear(); s.meter.prev = null; s.meter.minCrosses = 3; continue }
            rest(s)
        }
    }

    /** Station [si] is moving or off its rest (its ▶ key then reads ■ Stop). */
    fun stationMoving(si: Int, tol: Double = 0.002): Boolean {
        val s = stations[si]
        return s.bodies.indices.any { k -> val b = s.bodies[k]; b.held || b.v.len() > tol || b.p.dist(eqPos(s, k)) > tol }
    }

    fun stationSettling(si: Int): Boolean = stationSettle[si] > 0 || settleLeft > 0

    /**
     * The station's ▶ key (user 2026-11-13 / profile D43): starts it with the default amplitude, from rest: rod 20° to the
     * outside, spring pulled 5 cm down (clamped by the coil-stop rule of the hold, physicsSpec.hold), double with the
     * lower bob out and above (upper link level, lower link 30° above it). A real release: [再放一次] replays it.
     */
    fun autoSwing(si: Int) {
        val s = stations[si]
        stationSettle[si] = 0.0
        val out = if (s.pivot.x < -1e-6) -1.0 else 1.0
        s.bodies.forEach { it.held = false; it.v.set(0.0, 0.0, 0.0) }
        when (s.type) {
            Hanger.ROD -> {
                val a = Math.toRadians(BenchConfig.AUTO_ROD_DEG)
                s.bodies[0].p.set(s.pivot.x + out * s.L * sin(a), s.pivot.y - s.L * cos(a), 0.0)
                release(si, 0, null)
            }
            Hanger.SPRING -> {
                val target = Vec3(s.pivot.x, s.pivot.y - springEq(s) - BenchConfig.AUTO_SPRING_M, 0.0)
                s.bodies[0].p.set(constrainHeld(s, 0, target))
                release(si, 0, null)
            }
            Hanger.DOUBLE -> {
                s.th1 = out * Math.toRadians(BenchConfig.AUTO_DOUBLE_TH1_DEG); s.th2 = out * Math.toRadians(BenchConfig.AUTO_DOUBLE_TH2_DEG)
                s.w1 = 0.0; s.w2 = 0.0
                doubleToCartesian(s)
                release(si, 1, null)
            }
        }
    }

    /** The station's ■ key: about one second of damping back to rest for this station only. */
    fun stopStation(si: Int): Boolean {
        if (!stationMoving(si)) return false
        freezeMeter(stations[si])
        stationSettle[si] = BenchConfig.SETTLE_S
        settleExtra[si] = 0
        return true
    }

    /** [全停下]: about one second of critical-ish damping back to rest, no teleport. */
    fun stopAll(): Boolean {
        if (!anyMoving() && !anyDisplaced()) return false
        for (s in stations) freezeMeter(s)
        settleLeft = BenchConfig.SETTLE_S
        return true
    }

    /** q2-3: stopping starts now, so the measurement ends now (the damped second is not a period). */
    private fun freezeMeter(s: Station) { if (s.meter.releasedAt != null && !s.meter.frozen) s.meter.stoppedAt = time }

    fun canReplay(): Boolean = stations.any { it.rec != null }

    /** [再放一次]: every station with a release record starts again from it; the double's lower bob is off by n x 1 mm. */
    fun replay(): Boolean {
        var any = false
        settleLeft = 0.0
        for (s in stations) {
            val r = s.rec ?: continue
            any = true
            s.replayN++
            s.bodies.forEachIndexed { k, b -> b.held = false; b.p.set(r.p[k]); b.v.set(r.v[k]) }
            if (s.type == Hanger.DOUBLE) {
                doubleFromCartesian(s)
                // offset the lower bob n mm (along x, kept on its link); angular velocities as recorded
                val b1 = s.bodies[0]; val b2 = s.bodies[1]
                s.th2 = atan2(b2.p.x + s.replayN * 0.001 - b1.p.x, -(b2.p.y - b1.p.y))
                doubleToCartesian(s)
            }
            s.meter.reset()
            s.meter.releasedAt = time
            startRun(s, replay = true)
        }
        return any
    }

    companion object {
        /** Design spatialLayout.defaultBench: rod · mid · steel | spring · mid · steel | double · mid · wood over steel. */
        val DEFAULT = listOf(
            Triple(Hanger.ROD, 1, listOf(BobMat.STEEL)),
            Triple(Hanger.SPRING, 1, listOf(BobMat.STEEL)),
            Triple(Hanger.DOUBLE, 1, listOf(BobMat.WOOD, BobMat.STEEL)),
        )
    }
}
