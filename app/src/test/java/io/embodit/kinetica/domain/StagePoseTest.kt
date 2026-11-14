package io.embodit.kinetica.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Full Space placement (design container.placement, S7 / S8, T15 / T16, M19; profile D9 / D10 / D14 / D29). */
class StagePoseTest {
    private fun fwd(yawDeg: Double, pitchDeg: Double = 0.0): Vec3 {
        val y = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg)
        // heading yaw 0 = looking along -Z, + turns left (counter-clockwise from above)
        return Vec3(-sin(y) * cos(p), sin(p), -cos(y) * cos(p))
    }

    @Test fun defaultPoseIsAheadAtArmsLengthFacingYouInFourDirections() {
        val eye = Vec3(0.3, 1.25, -0.4)
        for (yaw in listOf(0.0, 90.0, 180.0, 270.0, 37.0)) for (pitch in listOf(-30.0, 0.0, 30.0)) {
            val p = StagePose.defaultPose(eye, fwd(yaw, pitch))
            val toBench = p.origin - eye
            assertEquals("horizontal distance", 0.50, hypot(toBench.x, toBench.z), 0.01)
            assertEquals("origin 0.34 m under the eye (felt 0.59 m under, DV-K8)", -0.34, toBench.y, 1e-9)
            // the bench front (+Z local) points back at the eye within 10° (D10 四方位), pitch ignored (D9)
            val front = p.dirToStage(Vec3(0.0, 0.0, 1.0))
            val back = Vec3(-toBench.x, 0.0, -toBench.z).normalized()
            assertTrue("yaw $yaw pitch $pitch front off by ${Math.toDegrees(Math.acos(front.dot(back)))}°", front.dot(back) > cos(Math.toRadians(2.0)))
            // the design eye is (0, +0.34, +0.50) in bench space
            assertTrue(p.toLocal(eye).dist(Layout.DESIGN_EYE) < 1e-9)
        }
    }

    @Test fun recallAfterTurning40DegreesLandsInTheNewFront() {
        val eye = Vec3(0.0, 1.2, 0.0)
        val first = StagePose.defaultPose(eye, fwd(0.0))
        val again = StagePose.defaultPose(eye, fwd(40.0))
        assertEquals(40.0, StagePose.angleDiffDeg(again.yaw, first.yaw), 0.5)
        assertEquals(0.50, hypot(again.origin.x - eye.x, again.origin.z - eye.z), 0.01)
    }

    /** D29 / T16: pressed while looking at the button, then the head turns to the front and stops: the bench follows < 2°. */
    @Test fun placeWaitTakesTheHeadingAfterTheHeadSettles() {
        val w = PlaceWait()
        var t = 0L
        w.ask("recall", t)
        var placedYaw: Double? = null
        // looking down 25° at the panel for 0.4 s, then turning 50° over 0.5 s, then still at yaw 50°
        while (placedYaw == null && t < 5000) {
            t += 14
            val (pitch, yaw) = when {
                t < 400 -> -25.0 to 0.0
                t < 900 -> -25.0 * (1 - (t - 400) / 500.0) to 50.0 * (t - 400) / 500.0
                else -> 0.0 to 50.0
            }
            if (w.step(t, pitch, Math.toRadians(yaw))) placedYaw = yaw
        }
        assertEquals(50.0, placedYaw!!, 2.0)
        assertTrue("waited for the dwell, $t ms", t in 1200..1500)
    }

    @Test fun placeWaitNeverStuck() {
        val w = PlaceWait()
        w.ask("start", 0)
        var t = 0L; var done = false
        while (!done && t < 4000) { t += 14; done = w.step(t, 35.0, Math.toRadians(t / 10.0)) } // head pitched and turning
        assertTrue(done); assertTrue(t in 3000..3100)
        // no head pose at all: stays waiting (the scene then uses the fallback after the timeout only with a pose)
        val w2 = PlaceWait(); w2.ask("start", 0)
        assertFalse(w2.step(100, null, null))
    }

    @Test fun snapHysteresis() {
        val frame = BenchPose(Vec3(0.0, 1.0, -0.5), 0.0)
        val s = BenchSnapper()
        assertEquals(frame, s.step(BenchPose(Vec3(0.05, 1.0, -0.5), Math.toRadians(8.0)), frame)); assertTrue(s.snapped)
        // still snapped at 9 cm / 14°, released at 11 cm
        assertEquals(frame, s.step(BenchPose(Vec3(0.09, 1.0, -0.5), Math.toRadians(14.0)), frame))
        val free = BenchPose(Vec3(0.11, 1.0, -0.5), 0.0)
        assertEquals(free, s.step(free, frame)); assertFalse(s.snapped)
        // not in at 7 cm
        val near = BenchPose(Vec3(0.07, 1.0, -0.5), 0.0)
        assertEquals(near, s.step(near, frame))
        // not in at 11° even when close
        val turned = BenchPose(Vec3(0.0, 1.0, -0.5), Math.toRadians(11.0))
        assertEquals(turned, s.step(turned, frame))
    }

    @Test fun draggedBenchStaysUprightInTheHeightWindowAndOffTheFace() {
        val eye = Vec3(0.0, 1.2, 0.0)
        val high = StagePose.clampOrigin(Vec3(0.0, 2.0, -0.6), eye)
        assertEquals(eye.y - 0.20 + 0.25, high.y, 1e-9) // felt at E - 0.20
        val low = StagePose.clampOrigin(Vec3(0.0, -1.0, -0.6), eye)
        assertEquals(eye.y - 0.85 + 0.25, low.y, 1e-9) // felt at E - 0.85
        val close = StagePose.clampOrigin(Vec3(0.05, 1.0, -0.1), eye)
        assertEquals(0.30, hypot(close.x - eye.x, close.z - eye.z), 1e-9)
    }

    @Test fun stickTurnsNinetyDegreesPerSecondWithADeadZone() {
        assertEquals(0.0, StagePose.stickTurn(0.15, 1.0), 1e-12)
        assertEquals(-Math.PI / 2, StagePose.stickTurn(1.0, 1.0), 1e-9) // right = clockwise from above
        assertEquals(Math.PI / 4, StagePose.stickTurn(-1.0, 0.5), 1e-9)
    }

    @Test fun headInsideTheBenchFades() {
        val p = StagePose.defaultPose(Vec3(0.0, 1.2, 0.0), fwd(0.0))
        assertFalse(StagePose.headInside(p, Vec3(0.0, 1.2, 0.0))) // design eye: 0.50 ahead, outside box + 15 cm (0.45)
        assertTrue(StagePose.headInside(p, p.origin + Vec3(0.0, 0.2, 0.3)))
    }
}
