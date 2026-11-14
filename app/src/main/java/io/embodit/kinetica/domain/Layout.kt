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

    // readout plates on the bevel (S-02): 0.11 x 0.045, centre (x, -0.275, ~+0.149), 3 mm proud
    const val PLATE_W = 0.11
    const val PLATE_H = 0.045
    const val PLATE_Y = -0.275
    const val PLATE_PX_W = 512
    const val PLATE_PX_H = 210

    // tags on the crossbar (R2-02): 0.08 x 0.04, centre (x, +0.095, -0.05), face tilted up 20°
    const val TAG_W = 0.08
    const val TAG_H = 0.04
    const val TAG_Y = 0.095
    const val TAG_Z = -0.05
    const val TAG_TILT_DEG = 20.0
    const val TAG_HIT_W = 0.09
    const val TAG_HIT_H = 0.065
    const val TAG_HIT_D = 0.02
    const val TAG_PX_W = 512
    const val TAG_PX_H = 256

    // nameplate plaque (v0.1.1): stands on the left ear, leans back and turns toward the design eye so KINETICA reads at
    // >= 0.5° (the old flat nameplate on the ear's front face was seen at 62° and its letters fell to ~0.25°)
    val NAMEPLATE = Vec3(-0.36, -0.222, 0.10)
    const val NAMEPLATE_W = 0.09
    const val NAMEPLATE_H = 0.034
    const val NAMEPLATE_TILT_DEG = 48.0
    const val NAMEPLATE_YAW_DEG = 40.0
    const val NAMEPLATE_PX_W = 512
    const val NAMEPLATE_PX_H = 194
    const val HIT_SQUARE = 0.065

    // ▶ swing keys (D43, user 2026-11-13): one per station on the felt's front strip, key face leaning back 50° to the eye
    const val KEY_Y = -0.231
    const val KEY_Z = 0.105
    const val KEY_W = 0.056
    const val KEY_H = 0.040
    const val KEY_TILT_DEG = 50.0
    const val KEY_HIT_W = 0.060
    const val KEY_HIT_H = 0.046
    const val KEY_HIT_D = 0.030
    const val KEY_PX_W = 512
    const val KEY_PX_H = 366

    // planet selector (D13, user 2026-11-13): a walnut shelf on the right ear turned toward the eye; the current planet
    // in the middle (Ø 6.4 cm globe), ◀ ▶ keys on both sides, name + g on a brass label in front, four dots for the order.
    val SEL = Vec3(0.40, -0.25, 0.07)
    const val SEL_YAW_DEG = -40.0
    val SEL_GLOBE = Vec3(0.0, 0.065, -0.005)
    const val SEL_GLOBE_D = 0.064
    val SEL_PREV = Vec3(-0.072, 0.045, 0.010)
    val SEL_NEXT = Vec3(0.072, 0.045, 0.010)
    const val SEL_KEY_D = 0.046
    const val SEL_KEY_TILT_DEG = 38.0
    val SEL_LABEL = Vec3(0.0, 0.026, 0.042)
    const val SEL_LABEL_W = 0.12
    const val SEL_LABEL_H = 0.040
    const val SEL_LABEL_TILT_DEG = 46.0
    const val SEL_LABEL_PX_W = 512
    const val SEL_LABEL_PX_H = 171

    /** Group frame (selector) -> bench frame. */
    fun sel(p: Vec3): Vec3 = SEL + rotY(p, Math.toRadians(SEL_YAW_DEG))

    fun rotY(v: Vec3, yaw: Double): Vec3 { val c = cos(yaw); val s = sin(yaw); return Vec3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c) }

    /** A face centred at [c] (bench frame), yawed [yawDeg] about +Y, leaning back [tiltDeg] (0 = upright, facing +Z). */
    fun faceAt(name: String, c: Vec3, yawDeg: Double, tiltDeg: Double, w: Double, h: Double): Face {
        val y = Math.toRadians(yawDeg); val t = Math.toRadians(tiltDeg)
        return Face(name, c, rotY(Vec3(1.0, 0.0, 0.0), y), rotY(Vec3(0.0, cos(t), -sin(t)), y), w, h)
    }

    fun keyCentre(i: Int) = Vec3(BenchConfig.PIVOT_X[i], KEY_Y, KEY_Z)
    fun keyFace(i: Int) = faceAt("key$i", keyCentre(i), 0.0, KEY_TILT_DEG, KEY_W, KEY_H)
    fun selLabelFace() = faceAt("planetLabel", sel(SEL_LABEL), SEL_YAW_DEG, SEL_LABEL_TILT_DEG, SEL_LABEL_W, SEL_LABEL_H)
    fun selKeyFace(next: Boolean) = faceAt(if (next) "planetNext" else "planetPrev", sel(if (next) SEL_NEXT else SEL_PREV), SEL_YAW_DEG, SEL_KEY_TILT_DEG, SEL_KEY_D, SEL_KEY_D)

    /** Capsules the bobs bounce off (and a held bob is pushed out of): the swing keys and the selector. (a, b, radius). */
    fun obstacleCapsules(): List<Triple<Vec3, Vec3, Double>> =
        (0..2).map { i -> Triple(keyCentre(i) + Vec3(-0.022, 0.0, 0.0), keyCentre(i) + Vec3(0.022, 0.0, 0.0), 0.02) } +
            listOf(
                Triple(sel(SEL_PREV), sel(SEL_NEXT), 0.028),
                Triple(sel(SEL_GLOBE), sel(SEL_GLOBE), SEL_GLOBE_D / 2 + 0.004),
            )

    fun plateCentre(i: Int): Vec3 {
        val t = Math.toRadians(BEVEL_DEG)
        val zMid = BASE_Z - (PLATE_Y - BASE_Y0) * kotlin.math.tan(t)
        val n = Vec3(0.0, sin(t), cos(t))
        return Vec3(BenchConfig.PIVOT_X[i], PLATE_Y, zMid) + n * 0.003
    }

    fun plateFace(i: Int): Face {
        val t = Math.toRadians(BEVEL_DEG)
        return Face("plate$i", plateCentre(i), Vec3(1.0, 0.0, 0.0), Vec3(0.0, cos(t), -sin(t)), PLATE_W, PLATE_H)
    }

    fun tagCentre(i: Int) = Vec3(BenchConfig.PIVOT_X[i], TAG_Y, TAG_Z)

    fun tagFace(i: Int): Face {
        val t = Math.toRadians(TAG_TILT_DEG)
        return Face("tag$i", tagCentre(i), Vec3(1.0, 0.0, 0.0), Vec3(0.0, cos(t), -sin(t)), TAG_W, TAG_H)
    }

    fun nameplateFace() = faceAt("nameplate", NAMEPLATE, NAMEPLATE_YAW_DEG, NAMEPLATE_TILT_DEG, NAMEPLATE_W, NAMEPLATE_H)

    /**
     * Full Space (container.placement): the bench is placed 0.50 m ahead of the eye with its origin 0.34 m below it
     * (StagePose.DROP_M, DV-K8), so the design eye is (0, +0.34, +0.50) in bench space. Leaning / sitting lower / standing up around it, and the emulator's
     * eye after a cold start (the same rule, measured: ui-audit).
     */
    val DESIGN_EYE = Vec3(0.0, 0.34, 0.50)
    val EYES: List<Pair<String, Vec3>> = listOf(
        "design" to DESIGN_EYE, "lean-L" to Vec3(-0.15, 0.34, 0.50), "lean-R" to Vec3(0.15, 0.34, 0.50),
        "lower" to Vec3(0.0, 0.26, 0.50), "stand" to Vec3(0.0, 0.48, 0.55), "back" to Vec3(0.0, 0.38, 0.75),
    )

    /** Cap height (metres) a label needs on its face to be >= [deg] tall from [eye] (distance and foreshortening). */
    fun capNeeded(face: Face, eye: Vec3, deg: Double = 0.5): Double {
        val d = eye - face.centre
        val cosA = (d.normalized()).dot(face.normal).coerceIn(0.05, 1.0)
        return d.len() * kotlin.math.tan(Math.toRadians(deg)) / cosA
    }

    /** The fixed parts of the bench as boxes (occlusion tests). */
    fun staticBoxes(): List<Pair<String, Box3>> {
        val c = BenchConfig
        return listOf(
            "base" to Box3.of(-BASE_X, BASE_X, BASE_Y0, BASE_Y1, -BASE_Z, BEVEL_TOP_Z),
            "earL" to Box3.of(-EAR_X1, -EAR_X0, EAR_Y0, BASE_Y1, -BASE_Z, BASE_Z),
            "earR" to Box3.of(EAR_X0, EAR_X1, EAR_Y0, BASE_Y1, -BASE_Z, BASE_Z),
            "globe" to Box3.around(sel(SEL_GLOBE), SEL_GLOBE_D / 2),
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
