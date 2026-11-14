package io.embodit.kinetica.domain

import kotlin.math.cos
import kotlin.math.sin

/** Axis-aligned box (metres, volume frame). */
data class Box3(val min: Vec3, val max: Vec3) {
    fun contains(p: Vec3, eps: Double = 0.0) =
        p.x > min.x + eps && p.x < max.x - eps && p.y > min.y + eps && p.y < max.y - eps && p.z > min.z + eps && p.z < max.z - eps

    companion object {
        fun of(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double) = Box3(Vec3(minOf(x0, x1), minOf(y0, y1), minOf(z0, z1)), Vec3(maxOf(x0, x1), maxOf(y0, y1), maxOf(z0, z1)))
        fun around(c: Vec3, half: Double) = of(c.x - half, c.x + half, c.y - half, c.y + half, c.z - half, c.z + half)
    }
}

/** A flat rectangle (plate, tag, dial face): centre, the unit right / up vectors on its face, size. */
class Face(val name: String, val centre: Vec3, val right: Vec3, val up: Vec3, val w: Double, val h: Double) {
    val normal: Vec3 get() = right.cross(up).normalized()
    fun corners(inset: Double = 0.0): List<Vec3> {
        val hw = w / 2 - inset; val hh = h / 2 - inset
        return listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (a, b) -> centre + right * (a * hw) + up * (b * hh) }
    }
}

/**
 * Every fixed position of the bench (design spatialLayout, container.volume.math, sample revisions S-01 / S-02). Origin =
 * volume centre, +Y up, +Z toward the user, metres. The GLBs (tools/build_bench.py) use the same numbers.
 */
object Layout {
    const val VOL_W = 0.80
    const val VOL_H = 0.64
    const val VOL_D = 0.60

    // base: walnut 0.68 x 0.05 x 0.32, 30° front bevel, felt on top, ears at both ends
    const val BASE_X = 0.34
    const val BASE_Y0 = -0.30
    const val BASE_Y1 = -0.25
    const val BASE_Z = 0.16
    const val BEVEL_DEG = 30.0
    /** z of the bevel's top edge: the base is a box back of this, the bevel in front of it. */
    val BEVEL_TOP_Z = BASE_Z - (BASE_Y1 - BASE_Y0) * kotlin.math.tan(Math.toRadians(BEVEL_DEG))
    const val EAR_X0 = 0.325
    const val EAR_X1 = 0.395
    const val EAR_Y0 = -0.28
    const val FOOT_Y = -0.31

    // v0.1.2 (QA #2 q2-1, D14 default view): the control window the system opens straight ahead covers the middle of the
    // view down to ~15° under the eye, so everything you read or tap sits on two walnut WINGS standing on the ears, beside
    // the window (azimuth 24–38°) and 17–33° under the eye (below the window's bottom edge): the ▶ keys + readout plates on the left wing (one row per
    // station, top = left station), the planet selector + KINETICA plaque on the right wing. Each wing yaws to face the
    // design eye; its faces lean back WING_TILT_DEG. Wing frame: origin = board front-face centre, +X right, +Y up, +Z to the eye.
    const val WING_X = 0.385
    const val WING_Y = 0.004
    const val WING_Z = 0.0
    const val WING_W = 0.20
    const val WING_H = 0.22
    const val WING_T = 0.014
    const val WING_TILT_DEG = 20.0
    /** Rows of the left wing (station i): y in the wing frame. */
    val ROW_Y = doubleArrayOf(0.075, 0.0, -0.075)

    // readout plates (left wing, left part of each row): 0.115 x 0.048, 3 mm in front of a brass backing
    const val PLATE_W = 0.115
    const val PLATE_H = 0.048
    const val PLATE_X = -0.035
    const val PLATE_Z = 0.014
    const val PLATE_PX_W = 512
    const val PLATE_PX_H = 214

    // tags on the crossbar (R2-02, q2-2: bigger, two full lines): 0.09 x 0.05, centre (x, +0.095, -0.05), face tilted up 20°;
    // the canvas floats TAG_LIFT in front of the brass backing (no z-fighting at 25° side views)
    const val TAG_W = 0.09
    const val TAG_H = 0.05
    const val TAG_Y = 0.095
    const val TAG_Z = -0.05
    const val TAG_TILT_DEG = 20.0
    const val TAG_LIFT = 0.0025
    const val TAG_HIT_W = 0.09
    const val TAG_HIT_H = 0.065
    const val TAG_HIT_D = 0.02
    const val TAG_PX_W = 512
    const val TAG_PX_H = 284

    // ▶ swing keys (D43): left wing, right part of each row (toward the hangers); two lines: ▶ word / hanger name
    const val KEY_X = 0.064
    const val KEY_Z = 0.026
    const val KEY_W = 0.060
    const val KEY_H = 0.052
    const val KEY_HIT_W = 0.064
    const val KEY_HIT_H = 0.056
    const val KEY_HIT_D = 0.030
    const val KEY_PX_W = 512
    const val KEY_PX_H = 444

    // right wing: planet selector (globe between ◀ ▶ keys on a ledge), the name + g label, the KINETICA plaque under it
    val SEL_GLOBE = Vec3(0.0, 0.052, 0.045)
    const val SEL_GLOBE_D = 0.064
    /**
     * Ringed globes (tools/build_planets.py): Saturn's ring reaches 0.68 x the model scale from the centre, Uranus' thin
     * ring 0.62; the other bodies are unit-diameter spheres (0.5). The ring has to stay clear of the ◀ ▶ keys.
     */
    fun globeReachM(p: Planet): Double = SEL_GLOBE_D * when (p) { Planet.SATURN -> 0.68; Planet.URANUS -> 0.62; else -> 0.5 }

    /** Axis tilt of each globe as (pitch toward the user, roll), degrees: Saturn's rings open, Uranus nearly pole-on. */
    fun planetTiltDeg(p: Planet): Pair<Double, Double> = when (p) {
        Planet.SATURN -> 22.0 to -12.0
        Planet.URANUS -> 68.0 to 8.0
        else -> 0.0 to 0.0
    }

    /** One frame of the globe change (D51): offsets in metres along the selector's x, scales of the model. */
    data class GlobeSwitch(val inOffsetM: Double, val inScale: Double, val outOffsetM: Double, val outScale: Double, val done: Boolean)

    const val GLOBE_SWITCH_MS = 340.0
    const val GLOBE_SLIDE_M = 0.03

    fun globeSwitch(msSince: Long): GlobeSwitch {
        val t = (msSince / GLOBE_SWITCH_MS).coerceIn(0.0, 1.0)
        if (t >= 1.0) return GlobeSwitch(0.0, 1.0, 0.0, 0.0, true)
        // in: ease-out-back (overshoot ~6 %); out: ease-in, gone by 70 % of the time
        val c = 1.2
        val u = t - 1
        val back = 1 + (c + 1) * u * u * u + c * u * u
        val to = (t / 0.7).coerceAtMost(1.0)
        return GlobeSwitch(
            inOffsetM = GLOBE_SLIDE_M * (1 - back), inScale = 0.4 + 0.6 * back,
            outOffsetM = GLOBE_SLIDE_M * to * to, outScale = if (to >= 1.0) 0.0 else 1.0 - 0.6 * to * to,
            done = false,
        )
    }
    val SEL_PREV = Vec3(-0.072, 0.052, 0.022)
    val SEL_NEXT = Vec3(0.072, 0.052, 0.022)
    const val SEL_KEY_D = 0.046
    val SEL_LABEL = Vec3(0.0, -0.037, 0.014)
    const val SEL_LABEL_W = 0.13
    const val SEL_LABEL_H = 0.042
    const val SEL_LABEL_PX_W = 512
    const val SEL_LABEL_PX_H = 165
    val NAMEPLATE_L = Vec3(0.0, -0.085, 0.014)
    const val NAMEPLATE_W = 0.10
    const val NAMEPLATE_H = 0.036
    const val NAMEPLATE_PX_W = 512
    const val NAMEPLATE_PX_H = 184
    const val HIT_SQUARE = 0.065
    /** The「摇杆左右转动」hint card: under the right wing (wing frame), clear of the window. */
    val HINT_L = Vec3(0.0, -WING_H / 2 - 0.025, 0.012)

    /** Wing centre (bench frame). */
    fun wingCentre(right: Boolean) = Vec3(if (right) WING_X else -WING_X, WING_Y, WING_Z)
    /** Wing yaw: +Z of the wing points (horizontally) at the design eye. */
    fun wingYaw(right: Boolean): Double { val c = wingCentre(right); return kotlin.math.atan2(DESIGN_EYE.x - c.x, DESIGN_EYE.z - c.z) }
    fun wingYawDeg(right: Boolean) = Math.toDegrees(wingYaw(right))
    /** Wing frame -> bench frame. */
    fun wing(right: Boolean, p: Vec3): Vec3 = wingCentre(right) + rotY(p, wingYaw(right))
    /** Right wing (selector group) -> bench frame. */
    fun sel(p: Vec3): Vec3 = wing(true, p)
    val SEL: Vec3 get() = wingCentre(true)
    val SEL_YAW_DEG: Double get() = wingYawDeg(true)

    fun rotY(v: Vec3, yaw: Double): Vec3 { val c = cos(yaw); val s = sin(yaw); return Vec3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c) }

    /** A face centred at [c] (bench frame), yawed [yawDeg] about +Y, leaning back [tiltDeg] (0 = upright, facing +Z). */
    fun faceAt(name: String, c: Vec3, yawDeg: Double, tiltDeg: Double, w: Double, h: Double): Face {
        val y = Math.toRadians(yawDeg); val t = Math.toRadians(tiltDeg)
        return Face(name, c, rotY(Vec3(1.0, 0.0, 0.0), y), rotY(Vec3(0.0, cos(t), -sin(t)), y), w, h)
    }

    /** Key / plate centres in the left wing frame and bench frame. */
    fun keyLocal(i: Int) = Vec3(KEY_X, ROW_Y[i], KEY_Z)
    fun keyCentre(i: Int) = wing(false, keyLocal(i))
    fun keyFace(i: Int) = faceAt("key$i", keyCentre(i), wingYawDeg(false), WING_TILT_DEG, KEY_W, KEY_H)
    fun plateLocal(i: Int) = Vec3(PLATE_X, ROW_Y[i], PLATE_Z)
    fun plateCentre(i: Int) = wing(false, plateLocal(i))
    fun plateFace(i: Int) = faceAt("plate$i", plateCentre(i), wingYawDeg(false), WING_TILT_DEG, PLATE_W, PLATE_H)
    fun selLabelFace() = faceAt("planetLabel", sel(SEL_LABEL), SEL_YAW_DEG, WING_TILT_DEG, SEL_LABEL_W, SEL_LABEL_H)
    fun selKeyFace(next: Boolean) = faceAt(if (next) "planetNext" else "planetPrev", sel(if (next) SEL_NEXT else SEL_PREV), SEL_YAW_DEG, WING_TILT_DEG, SEL_KEY_D, SEL_KEY_D)
    val NAMEPLATE: Vec3 get() = sel(NAMEPLATE_L)
    fun nameplateFace() = faceAt("nameplate", NAMEPLATE, SEL_YAW_DEG, WING_TILT_DEG, NAMEPLATE_W, NAMEPLATE_H)

    /**
     * Capsules the bobs bounce off (and a held bob is pushed out of): the wing boards, the ▶ keys and the globe. A capsule
     * that passes within 6 cm of a post is left out: there the post already stops a bob (post + bob radius ~3.2 cm), and two
     * obstacles that close would squeeze a bob between them (BenchPhysicsTest.randomStartsNeverPenetrateOrLeave).
     */
    fun obstacleCapsules(): List<Triple<Vec3, Vec3, Double>> {
        val all = listOf(false, true).flatMap { r ->
            listOf(-0.09, -0.03, 0.03, 0.09).map { x -> Triple(wing(r, Vec3(x, -WING_H / 2, -WING_T / 2)), wing(r, Vec3(x, WING_H / 2, -WING_T / 2)), 0.012) }
        } + (0..2).map { i -> Triple(keyCentre(i), keyCentre(i), 0.03) } + listOf(Triple(sel(SEL_GLOBE), sel(SEL_GLOBE), SEL_GLOBE_D / 2 + 0.004))
        fun nearPost(a: Vec3, b: Vec3): Boolean = listOf(-1.0, 1.0).any { sx ->
            val px = sx * BenchConfig.POST_X; val pz = BenchConfig.POST_Z
            (0..10).any { k -> val p = a + (b - a) * (k / 10.0); kotlin.math.hypot(p.x - px, p.z - pz) < 0.06 && p.y < BenchConfig.BAR_Y + 0.03 }
        }
        return all.filter { (a, b, _) -> !nearPost(a, b) }
    }

    fun tagCentre(i: Int) = Vec3(BenchConfig.PIVOT_X[i], TAG_Y, TAG_Z)

    fun tagFace(i: Int): Face {
        val t = Math.toRadians(TAG_TILT_DEG)
        return Face("tag$i", tagCentre(i), Vec3(1.0, 0.0, 0.0), Vec3(0.0, cos(t), -sin(t)), TAG_W, TAG_H)
    }

    /**
     * Full Space (container.placement, v0.1.2): the bench is placed 0.66 m ahead of the eye with its origin 0.35 m below
     * it (StagePose), so the design eye is (0, +0.35, +0.66) in bench space: the hangers sit under the window (tag tops
     * 17° down, bobs <= 38°), the wings beside it. Leaning / sitting lower / standing up around it.
     */
    val DESIGN_EYE = Vec3(0.0, 0.35, 0.66)
    val EYES: List<Pair<String, Vec3>> = listOf(
        "design" to DESIGN_EYE, "lean-L" to Vec3(-0.15, 0.35, 0.66), "lean-R" to Vec3(0.15, 0.35, 0.66),
        "lower" to Vec3(0.0, 0.27, 0.66), "stand" to Vec3(0.0, 0.49, 0.70), "back" to Vec3(0.0, 0.39, 0.85),
    )

    /** Cap height (metres) a label needs on its face to be >= [deg] tall from [eye] (distance and foreshortening). */
    fun capNeeded(face: Face, eye: Vec3, deg: Double = 0.5): Double {
        val d = eye - face.centre
        val cosA = (d.normalized()).dot(face.normal).coerceIn(0.05, 1.0)
        return d.len() * kotlin.math.tan(Math.toRadians(deg)) / cosA
    }

    /** Axis-aligned box around a wing board (+ its ledge), bench frame. */
    fun wingBox(right: Boolean): Box3 {
        val pts = listOf(-1.0, 1.0).flatMap { a -> listOf(-1.0, 1.0).flatMap { b -> listOf(-WING_T, 0.0).map { z -> wing(right, Vec3(a * WING_W / 2, b * WING_H / 2, z)) } } }
        return Box3(Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }), Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z }))
    }

    /** The fixed parts of the bench as boxes (occlusion tests). */
    fun staticBoxes(): List<Pair<String, Box3>> {
        val c = BenchConfig
        return listOf(
            "base" to Box3.of(-BASE_X, BASE_X, BASE_Y0, BASE_Y1, -BASE_Z, BEVEL_TOP_Z),
            "earL" to Box3.of(-EAR_X1, -EAR_X0, EAR_Y0, BASE_Y1, -BASE_Z, BASE_Z),
            "earR" to Box3.of(EAR_X0, EAR_X1, EAR_Y0, BASE_Y1, -BASE_Z, BASE_Z),
            "globe" to Box3.around(sel(SEL_GLOBE), SEL_GLOBE_D / 2),
            "wingL" to wingBox(false), "wingR" to wingBox(true),
            "postL" to Box3.of(-c.POST_X - c.POST_R, -c.POST_X + c.POST_R, c.POST_BOTTOM, c.BAR_Y, c.POST_Z - c.POST_R, c.POST_Z + c.POST_R),
            "postR" to Box3.of(c.POST_X - c.POST_R, c.POST_X + c.POST_R, c.POST_BOTTOM, c.BAR_Y, c.POST_Z - c.POST_R, c.POST_Z + c.POST_R),
            "bar" to Box3.of(-c.POST_X, c.POST_X, c.BAR_Y - c.BAR_R, c.BAR_Y + c.BAR_R, c.BAR_Z - c.BAR_R, c.BAR_Z + c.BAR_R),
        ) + (0..2).map { i ->
            "arm$i" to Box3.of(c.PIVOT_X[i] - c.ARM_R, c.PIVOT_X[i] + c.ARM_R, c.PIVOT_Y - c.ARM_R, c.PIVOT_Y + c.ARM_R, c.BAR_Z, 0.0)
        }
    }

    /** Boxes around the resting hangers of [bench] (bob spheres + link capsules). */
    fun hangerBoxes(bench: Bench): List<Pair<String, Box3>> {
        val out = ArrayList<Pair<String, Box3>>()
        for (s in bench.stations) {
            var top = s.pivot
            s.bodies.forEachIndexed { k, b ->
                out += "bob${s.index}.$k" to Box3.around(b.p, b.r)
                val r = if (s.type == Hanger.SPRING) BenchConfig.SPRING_R else BenchConfig.ROD_R
                out += "link${s.index}.$k" to Box3.of(minOf(top.x, b.p.x) - r, maxOf(top.x, b.p.x) + r, minOf(top.y, b.p.y), maxOf(top.y, b.p.y), -r, r)
                top = b.p
            }
        }
        return out
    }

    /** Segment [a, b] crosses the open box (slab test). */
    fun segmentHits(a: Vec3, b: Vec3, box: Box3, eps: Double = 1e-4): Boolean {
        var t0 = 0.0; var t1 = 1.0
        val d = b - a
        val mins = doubleArrayOf(box.min.x + eps, box.min.y + eps, box.min.z + eps)
        val maxs = doubleArrayOf(box.max.x - eps, box.max.y - eps, box.max.z - eps)
        val o = doubleArrayOf(a.x, a.y, a.z); val dd = doubleArrayOf(d.x, d.y, d.z)
        for (k in 0..2) {
            if (kotlin.math.abs(dd[k]) < 1e-12) { if (o[k] <= mins[k] || o[k] >= maxs[k]) return false; continue }
            var ta = (mins[k] - o[k]) / dd[k]; var tb = (maxs[k] - o[k]) / dd[k]
            if (ta > tb) { val x = ta; ta = tb; tb = x }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 >= t1) return false
        }
        return true
    }
}
