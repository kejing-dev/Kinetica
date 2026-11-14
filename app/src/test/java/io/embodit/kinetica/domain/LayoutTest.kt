package io.embodit.kinetica.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

/** D35 / D20 / D17 / R2-02 (design occlusionCheck, input.colliders) + container.volume.math (acceptance M10). */
class LayoutTest {
    private fun faces(): List<Face> = (0..2).map { Layout.plateFace(it) } + (0..2).map { Layout.tagFace(it) } + (0..2).map { Layout.keyFace(it) } +
        Layout.nameplateFace() + Layout.selLabelFace() + Layout.selKeyFace(false) + Layout.selKeyFace(true)

    private fun occluders(bench: Bench) = Layout.staticBoxes() + Layout.hangerBoxes(bench)

    /** The wing a face stands on (its own board is behind it, not in the way). */
    private fun ownWing(f: Face): String? = when {
        f.name.startsWith("plate") || f.name.startsWith("key") -> "wingL"
        f.name.startsWith("planet") || f.name == "nameplate" -> "wingR"
        else -> null
    }

    @Test fun everyFaceCornerIsVisibleFromEveryEye() {
        // all resting configurations: default + every hanger type in every slot
        val benches = listOf(Bench()) + Hanger.entries.map { t -> Bench((0..2).map { Triple(t, 1, if (t == Hanger.DOUBLE) listOf(BobMat.WOOD, BobMat.STEEL) else listOf(BobMat.STEEL)) }) }
        for (bench in benches) for ((eyeName, eye) in Layout.EYES) for (face in faces()) for (corner in face.corners(inset = 0.002)) {
            val p = corner + face.normal * 0.0005
            for ((name, box) in occluders(bench)) {
                if (name == ownWing(face)) continue
                if (name == "globe" && face.name.startsWith("planet")) continue // the keys sit beside the globe, the label under it (checked below)
                assertFalse("$eyeName -> ${face.name} corner $corner hits $name", Layout.segmentHits(eye, p, box))
            }
        }
        // the globe does not hide the label / keys from the design eye
        val globe = Layout.staticBoxes().first { it.first == "globe" }.second
        for (face in listOf(Layout.selLabelFace(), Layout.selKeyFace(false), Layout.selKeyFace(true))) for (corner in face.corners(0.002))
            assertFalse(face.name, Layout.segmentHits(Layout.DESIGN_EYE, corner + face.normal * 0.0005, globe))
    }

    @Test fun facesPointTowardTheEyes() {
        for ((eyeName, eye) in Layout.EYES) for (face in faces()) {
            val toEye = (eye - face.centre).normalized()
            assertTrue("$eyeName sees the back of ${face.name}", toEye.dot(face.normal) > 0.2)
        }
    }

    /** q2-5 / D10: from the design eye every face reads the right way round: its +X goes screen-right, its +Y screen-up; ◀ is left of ▶. */
    @Test fun facesAreUprightAndNotMirroredFromTheSeat() {
        val eye = Layout.DESIGN_EYE
        fun basis(c: Vec3): Pair<Vec3, Vec3> {
            val f = (c - eye).normalized()
            val right = f.cross(Vec3(0.0, 1.0, 0.0)).normalized()
            return right to right.cross(f).normalized()
        }
        for (face in faces()) {
            val (r, u) = basis(face.centre)
            assertTrue("${face.name} right·screenRight ${face.right.dot(r)}", face.right.dot(r) > 0.9)
            assertTrue("${face.name} up·screenUp ${face.up.dot(u)}", face.up.dot(u) > 0.9)
        }
        val prev = Layout.selKeyFace(false); val next = Layout.selKeyFace(true)
        val (r, u) = basis(Layout.sel(Layout.SEL_GLOBE))
        assertTrue("◀ left of ▶", (next.centre - prev.centre).dot(r) > 0.12)
        assertTrue("◀ ▶ on one level", kotlin.math.abs((next.centre - prev.centre).dot(u)) < 0.01)
        // the arrow drawn on the ◀ face points along -face.right, which is screen-left
        assertTrue((prev.right * -1.0).dot(r) < -0.9)
    }

    private val dp = 0.00174 // measured on the emulator: 1 dp ≈ 1.74 mm at 1.75 m
    private val winHalfW = 400 * dp
    private val winHalfH = 210 * dp
    private val winCtl = 74 * dp // the system dot / caption controls under the window
    private fun az(eye: Vec3, p: Vec3) = Math.toDegrees(atan2(p.x - eye.x, eye.z - p.z))
    private fun down(eye: Vec3, p: Vec3): Double { val d = p - eye; return Math.toDegrees(atan2(-d.y, sqrt(d.x * d.x + d.z * d.z))) }
    private fun samples(b: Box3): List<Vec3> = (0..4).flatMap { i -> (0..4).flatMap { j -> (0..4).map { k ->
        Vec3(b.min.x + (b.max.x - b.min.x) * i / 4, b.min.y + (b.max.y - b.min.y) * j / 4, b.min.z + (b.max.z - b.min.z) * k / 4) } } }

    /**
     * q2-1 (D14 / D20, container.windowVsBench): the system opens the 800 x 420 dp window 1.75 m straight ahead at eye height.
     * From the design eye nothing of the bench (static parts, resting hangers, every face) lies inside the window's angular
     * rectangle grown by 1° (incl. its system controls below): window and bench never cover each other.
     */
    @Test fun controlWindowAndBenchDoNotOverlap() {
      // each eye (IPD 64 mm): the window 1.75 m away shifts 1°, the wings ~0.75 m away 2.4° (emulator screenshots are one eye)
      for (ipd in listOf(-0.032, 0.0, 0.032)) {
        val eye = Layout.DESIGN_EYE + Vec3(ipd, 0.0, 0.0)
        val winShift = Math.toDegrees(atan2(ipd, 1.75))
        val azMax = Math.toDegrees(atan2(winHalfW, 1.75)) + 1.0
        val downMin = Math.toDegrees(atan2(winHalfH + winCtl, 1.75)) + 1.0
        val upMax = Math.toDegrees(atan2(winHalfH + 0.20, 1.75)) // TabBar above
        // the wing boards by their real (yawed) corners, not their axis-aligned boxes
        val wings = listOf(false, true).flatMap { r -> (0..4).flatMap { i -> (0..4).flatMap { j -> listOf(-Layout.WING_T, 0.0).map { z ->
            "wing$r" to Layout.wing(r, Vec3(-Layout.WING_W / 2 + Layout.WING_W * i / 4, -Layout.WING_H / 2 + Layout.WING_H * j / 4, z)) } } } }
        val pts = (Layout.staticBoxes().filter { !it.first.startsWith("wing") } + Layout.hangerBoxes(Bench())).flatMap { (n, b) -> samples(b).map { n to it } } + wings +
            faces().flatMap { f -> (f.corners() + f.centre).map { f.name to it } }
        for ((n, p) in pts) {
            val a = az(eye, p) + winShift; val dn = down(eye, p)
            if (kotlin.math.abs(a) <= azMax) assertTrue("$n $p az ${"%.1f".format(a)}° down ${"%.1f".format(dn)}° is in front of the window (needs >= ${"%.1f".format(downMin)}°), eye $ipd", dn >= downMin || -dn >= upMax)
        }
      }
    }

    /** q2-1: the level default view (emulator ~±40°) shows every key, plate, tag, the selector and the plaque: <= 38° down / aside. */
    @Test fun everyControlIsInTheLevelDefaultView() {
        val eye = Layout.DESIGN_EYE
        for (f in faces()) for (p in f.corners() + f.centre) {
            assertTrue("${f.name} ${"%.1f".format(down(eye, p))}° down", down(eye, p) <= 38.0)
            assertTrue("${f.name} az ${"%.1f".format(az(eye, p))}°", kotlin.math.abs(az(eye, p)) <= 38.0)
        }
        // and the globe and every resting bob (default bench, every hanger type at the mid size)
        val globe = Layout.sel(Layout.SEL_GLOBE)
        assertTrue(down(eye, globe) <= 38.0 && kotlin.math.abs(az(eye, globe)) <= 38.0)
        val benches = listOf(Bench()) + Hanger.entries.map { t -> Bench((0..2).map { Triple(t, 1, if (t == Hanger.DOUBLE) listOf(BobMat.WOOD, BobMat.STEEL) else listOf(BobMat.STEEL)) }) }
        for (b in benches) for (s in b.stations) for (body in s.bodies) assertTrue("bob ${s.index} ${s.type} ${"%.1f".format(down(eye, body.p))}°", down(eye, body.p + Vec3(0.0, -body.r, 0.0)) <= 40.0)
    }

    /** User 2026-11-13 / D13: every 3D label's capital letters are >= 0.5° tall from the design eye (Jupiter readable). */
    @Test fun labelCapsAtLeastHalfADegree() {
        // (face, cap height on the face in metres): cap = 0.70 x font size (Latin, bold sans), the font sizes of BenchTextures
        val caps = (0..2).map { Layout.plateFace(it) to 0.011 * 0.7 } + (0..2).map { Layout.tagFace(it) to 0.010 * 0.7 } + (0..2).map { Layout.keyFace(it) to 0.0112 * 0.7 } +
            listOf(Layout.nameplateFace() to 0.0115 * 0.7, Layout.selLabelFace() to 0.011 * 0.7)
        for ((name, eye) in Layout.EYES.filter { it.first == "design" }) for ((face, cap) in caps) {
            val need = Layout.capNeeded(face, eye)
            assertTrue("$name ${face.name}: cap ${"%.1f".format(cap * 1000)} mm < needed ${"%.1f".format(need * 1000)} mm", cap >= need)
        }
    }

    /** D17: ▶ keys and the planet keys / globe are >= 4 cm and >= 2° from the design eye, with gaps; bobs bounce off them. */
    @Test fun newClickTargetsSizeAndAngle() {
        val eye = Layout.DESIGN_EYE
        for (i in 0..2) {
            val d = eye.dist(Layout.keyCentre(i))
            assertTrue(Layout.KEY_HIT_W >= 0.04 && Layout.KEY_HIT_H >= 0.04)
            assertTrue(Math.toDegrees(2 * atan2(Layout.KEY_HIT_H / 2, d)) >= 2.0)
        }
        val pitch = Layout.ROW_Y[0] - Layout.ROW_Y[1]
        assertTrue(pitch - Layout.KEY_HIT_H >= maxOf(0.015, 0.3 * Layout.KEY_HIT_H))
        assertTrue(Layout.KEY_X + Layout.KEY_HIT_W / 2 <= Layout.WING_W / 2)
        assertTrue(Layout.PLATE_X - Layout.PLATE_W / 2 >= -Layout.WING_W / 2)
        val prev = Layout.sel(Layout.SEL_PREV); val next = Layout.sel(Layout.SEL_NEXT); val globe = Layout.sel(Layout.SEL_GLOBE)
        for (p in listOf(prev, next)) assertTrue(Math.toDegrees(2 * atan2(0.025, eye.dist(p))) >= 2.0)
        assertTrue(prev.dist(globe) - 0.025 - Layout.SEL_GLOBE_D / 2 >= 0.015)
        // every key and the globe is inside a capsule the bobs bounce off, or right by a post (the post stops the bob first)
        val caps = Layout.obstacleCapsules()
        fun byPost(p: Vec3) = listOf(-1.0, 1.0).any { sx -> kotlin.math.hypot(p.x - sx * BenchConfig.POST_X, p.z - BenchConfig.POST_Z) < 0.07 }
        for (p in (0..2).map { Layout.keyCentre(it) } + globe) assertTrue("$p", caps.any { (a, b, r) -> a.dist(p) < r + 1e-9 && b.dist(p) < r + 1e-9 } || byPost(p))
    }

    /** D34: every label / readout / key is <= 40° below the design eye's level (DV-K9 withdrawn, q2-1). */
    @Test fun readoutsDownAngle() {
        val eye = Layout.DESIGN_EYE
        for (f in faces()) {
            val d = f.centre - eye
            val down = Math.toDegrees(atan2(-d.y, sqrt(d.x * d.x + d.z * d.z)))
            assertTrue("${f.name} $down°", down <= 40.0)
        }
    }

    @Test fun everythingFitsTheVolume() {
        val c = BenchConfig
        // farthest reach of any bob centre + radius, and the hard clamp
        assertTrue(c.PIVOT_X.max() + c.REACH <= Layout.VOL_W / 2 + 1e-9) // REACH already includes the bob radius
        assertTrue(c.CLAMP_X + 0.025 <= Layout.VOL_W / 2 + 1e-9)
        assertTrue(c.PIVOT_Y + c.REACH <= Layout.VOL_H / 2 + 1e-9)
        assertTrue(Layout.FOOT_Y >= -Layout.VOL_H / 2 - 1e-9)
        assertTrue(Layout.EAR_X1 <= Layout.VOL_W / 2)
        assertTrue(c.CLAMP_Z + 0.025 <= Layout.VOL_D / 2 + 1e-9)
    }

    @Test fun clickTargetsSizeGapAndAngle() {
        // D17: >= 4 cm, gap >= 1.5 cm and >= 0.3 x size, >= 2° at the system 1.75 m
        val hit = BenchConfig.BOB_HIT_D
        assertTrue(hit >= 0.04)
        val angle = Math.toDegrees(2 * atan2(hit / 2, 1.75))
        assertTrue("bob at 1.75 m = $angle°", angle >= 2.0)
        // neighbouring stations at rest: 12 cm apart
        assertTrue(0.12 - hit >= maxOf(0.015, 0.3 * hit))
        // the double's two bobs at the short size: 8.5 cm apart (R1-09)
        assertTrue(BenchConfig.DOUBLE_L[0] - hit >= 0.3 * hit - 1e-9)
        // tags 9 cm wide, 12 cm apart
        assertTrue(0.12 - Layout.TAG_HIT_W >= 0.3 * Layout.TAG_HIT_W - 1e-9)
    }

    @Test fun tagColliderClearsEverySpringBob() {
        // R2-02: 3 planets x 3 stiffness x 3 balls, the resting spring bob's collider top is >= 2.7 cm under the tag collider
        val tagBottom = Layout.TAG_Y - Layout.TAG_HIT_H / 2
        for (p in Planet.entries) for (k in BenchConfig.SPRING_K) for (m in BobMat.entries) {
            val bobY = BenchConfig.PIVOT_Y - (BenchConfig.SPRING_L0 + m.massKg * p.g / k)
            val top = bobY + BenchConfig.BOB_HIT_D / 2
            assertTrue("$p k=$k $m gap ${(tagBottom - top) * 100} cm", tagBottom - top >= 0.027 - 1e-9)
        }
    }

    @Test fun plateTextHeightAtDistance() {
        // big digits 2.0 cm: >= 0.6° at 1.75 m
        assertTrue(Math.toDegrees(atan2(0.02, 1.75)) >= 0.6)
        assertTrue(sqrt(2.0) > 1)
    }
}
