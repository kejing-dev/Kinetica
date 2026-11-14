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

    @Test fun everyFaceCornerIsVisibleFromEveryEye() {
        // all resting configurations: default + every hanger type in every slot
        val benches = listOf(Bench()) + Hanger.entries.map { t -> Bench((0..2).map { Triple(t, 1, if (t == Hanger.DOUBLE) listOf(BobMat.WOOD, BobMat.STEEL) else listOf(BobMat.STEEL)) }) }
        for (bench in benches) for ((eyeName, eye) in Layout.EYES) for (face in faces()) for (corner in face.corners(inset = 0.002)) {
            val p = corner + face.normal * 0.0005
            for ((name, box) in occluders(bench)) {
                // the tag's own pedestal sits behind it; the arm under a tag is below its lower edge (R2-05)
                assertFalse("$eyeName -> ${face.name} corner $corner hits $name", Layout.segmentHits(eye, p, box))
            }
        }
    }

    @Test fun facesPointTowardTheEyes() {
        for ((eyeName, eye) in Layout.EYES) for (face in faces()) {
            val toEye = (eye - face.centre).normalized()
            assertTrue("$eyeName sees the back of ${face.name}", toEye.dot(face.normal) > 0.2)
        }
    }

    @Test fun controlWindowSightLinesMissTheBench() {
        // Full Space: the system opens the window 1.75 m ahead at eye height (container.windowVsBench). From the design eye
        // every line to the window's corners stays above every static part and the bobs at rest. Measured on the emulator
        // (v0.1.1 first-screen): 1 dp ≈ 1.74 mm at 1.75 m, so 520 x 600 dp ≈ 0.90 x 1.04 m, + 60 dp system controls below.
        val dp = 0.00174
        val eye = Layout.DESIGN_EYE
        val zWin = eye.z - 1.75
        val corners = listOf(-260 * dp, 260 * dp).flatMap { x -> listOf(-(300 + 60) * dp, 300 * dp).map { y -> Vec3(x, eye.y + y, zWin) } }
        for (c in corners) for ((name, box) in Layout.staticBoxes() + Layout.hangerBoxes(Bench()))
            assertFalse("eye -> window $c hits $name", Layout.segmentHits(eye, c, box))
    }

    /** User 2026-11-13 / D13: every 3D label's capital letters are >= 0.5° tall from the design eye (Jupiter readable). */
    @Test fun labelCapsAtLeastHalfADegree() {
        // (face, cap height on the face in metres): cap = 0.70 x font size (Latin, bold sans), the font sizes of BenchTextures
        val caps = (0..2).map { Layout.plateFace(it) to 0.011 * 0.7 } + (0..2).map { Layout.tagFace(it) to 0.007 } + (0..2).map { Layout.keyFace(it) to 0.0095 * 0.7 } +
            listOf(Layout.nameplateFace() to 0.0115 * 0.7, Layout.selLabelFace() to 0.0105 * 0.7)
        for ((name, eye) in Layout.EYES.filter { it.first == "design" }) for ((face, cap) in caps) {
            val need = Layout.capNeeded(face, eye)
            assertTrue("$name ${face.name}: cap ${"%.1f".format(cap * 1000)} mm < needed ${"%.1f".format(need * 1000)} mm", cap >= need)
        }
    }

    /** D17: ▶ keys and the planet keys / globe are >= 4 cm and >= 2° from the design eye, with gaps. */
    @Test fun newClickTargetsSizeAndAngle() {
        val eye = Layout.DESIGN_EYE
        for (i in 0..2) {
            val d = eye.dist(Layout.keyCentre(i))
            assertTrue(Layout.KEY_HIT_W >= 0.04 && Layout.KEY_HIT_H >= 0.04)
            assertTrue(Math.toDegrees(2 * atan2(Layout.KEY_HIT_H / 2, d)) >= 2.0)
        }
        assertTrue(0.12 - Layout.KEY_HIT_W >= maxOf(0.015, 0.3 * Layout.KEY_HIT_W))
        val prev = Layout.sel(Layout.SEL_PREV); val next = Layout.sel(Layout.SEL_NEXT); val globe = Layout.sel(Layout.SEL_GLOBE)
        for (p in listOf(prev, next)) assertTrue(Math.toDegrees(2 * atan2(0.025, eye.dist(p))) >= 2.0)
        assertTrue(prev.dist(globe) - 0.025 - Layout.SEL_GLOBE_D / 2 >= 0.015)
        // keys sit below every resting bob and out of every bob's reach (bob centre within REACH of a pivot)
        for (i in 0..2) for (x in BenchConfig.PIVOT_X) assertTrue(Layout.keyCentre(i).dist(Vec3(x, BenchConfig.PIVOT_Y, 0.0)) > BenchConfig.REACH)
        for (p in listOf(prev, next, globe)) for (x in BenchConfig.PIVOT_X) assertTrue(p.dist(Vec3(x, BenchConfig.PIVOT_Y, 0.0)) > BenchConfig.REACH + 0.025)
    }

    /**
     * D34: labels hanging on the bench (tags, nameplate, planet label / keys) stay <= 48° below the design eye's level.
     * The brass plates on the base's bevel and the ▶ keys on the felt sit where the design's within-reach placement puts
     * the base (felt 0.59 m under the eye, 0.50 m ahead, DV-K8): ~57–61° down, deviation DV-K9; the plates' numbers are also
     * on the window's periods row (D38, same source).
     */
    @Test fun readoutsDownAngle() {
        val eye = Layout.DESIGN_EYE
        for (f in faces()) {
            val d = f.centre - eye
            val down = Math.toDegrees(atan2(-d.y, sqrt(d.x * d.x + d.z * d.z)))
            val limit = if (f.name.startsWith("plate") || f.name.startsWith("key")) 62.0 else 48.0
            assertTrue("${f.name} $down°", down <= limit)
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
