package io.embodit.kinetica.domain

import io.embodit.kinetica.ui.Lex
import io.embodit.kinetica.ui.Line
import io.embodit.kinetica.ui.statusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interaction rules (design verification.unitTests "交互", "试试看", D38, D43, D52) through the same BenchFlow calls the input uses. */
class BenchFlowTest {
    private var now = 0L
    private var saved = Settings()
    private val sounds = ArrayList<Sound>()

    private fun flow(s: Settings = Settings()): BenchFlow {
        saved = s
        return BenchFlow(clock = { now }, initial = s, onSettings = { saved = it }, onSound = { snd, _ -> sounds += snd }).also { it.ready() }
    }

    /** Runs [seconds] of frames at 72 fps. */
    private fun BenchFlow.play(seconds: Double) {
        repeat((seconds * 72).toInt()) { now += 14; frame(1f / 72) }
    }

    @Test fun tapUnder8mmCyclesMaterialAndReleasesInPlace() {
        val f = flow()
        val b = f.bench.stations[0].bodies[0]
        val p0 = b.p.copy()
        f.pressBob(0, 0)
        f.dragBob(0.005f, 0f, 0f) // 5 mm: still a tap
        f.releaseBob()
        assertEquals(BobMat.BRASS, b.mat) // steel -> brass
        assertFalse(b.held)
        assertTrue(b.p.dist(p0) < 1e-9)
        f.tapBob(0, 0); assertEquals(BobMat.WOOD, b.mat)
        f.tapBob(0, 0); assertEquals(BobMat.STEEL, b.mat) // three taps: back to the start
    }

    @Test fun dragPastTapThresholdReleasesWithHandSpeed() {
        val f = flow()
        f.pressBob(0, 0)
        repeat(8) { i -> now += 10; f.dragBob(0.01f * (i + 1), 0.005f * (i + 1), 0.01f * (i + 1)) }
        f.releaseBob()
        val b = f.bench.stations[0].bodies[0]
        assertFalse(b.held)
        assertTrue("released with speed ${b.v.len()}", b.v.len() > 0.1)
        assertTrue(f.bench.stations[0].rec != null)
        assertEquals(Phase.MOVING, f.snap.value.phase)
    }

    @Test fun pressingAMovingBobStopsItInTheHand() {
        val f = flow()
        f.pressBob(0, 0); f.dragBob(0.08f, 0.05f, 0f); f.releaseBob()
        f.play(0.3)
        f.pressBob(0, 0)
        assertTrue(f.bench.stations[0].bodies[0].held)
        assertEquals(Phase.HOLDING, f.snap.value.phase)
        assertEquals(Line.S2, statusLine(f.snap.value))
        f.cancelBob()
    }

    @Test fun pressingAnotherBobSwitchesTheSelection() {
        // D52: a press on another ball takes it; the first one is let go
        val f = flow()
        f.pressBob(0, 0)
        f.pressBob(1, 0)
        assertFalse(f.bench.stations[0].bodies[0].held)
        assertTrue(f.bench.stations[1].bodies[0].held)
        assertEquals(1 to 0, f.holding)
    }

    @Test fun tagTapCyclesHangerKeepingMaterial() {
        val f = flow()
        f.tapBob(0, 0) // steel -> brass
        f.tapTag(0); assertEquals(Hanger.SPRING, f.bench.stations[0].type); assertEquals(BobMat.BRASS, f.bench.stations[0].bodies[0].mat)
        f.tapTag(0); assertEquals(Hanger.DOUBLE, f.bench.stations[0].type); assertEquals(listOf(BobMat.BRASS, BobMat.STEEL), f.bench.stations[0].mats)
        f.tapTag(0); assertEquals(Hanger.ROD, f.bench.stations[0].type); assertEquals(BobMat.BRASS, f.bench.stations[0].bodies[0].mat)
        assertTrue(sounds.count { it == Sound.CLICK } >= 3)
    }

    @Test fun tagDragSteps25mmAndStopsAtTheEnds() {
        val f = flow()
        f.tagDragStart(0)
        f.tagDragMove(-0.026f); assertEquals(2, f.bench.stations[0].size) // down one step: long
        f.tagDragMove(-0.20f); assertEquals(2, f.bench.stations[0].size) // past the end: just stops
        f.tagDragMove(0.026f); assertEquals(0, f.bench.stations[0].size)
        f.tagDragEnd()
        assertEquals(0, f.bench.stations[0].size)
        assertEquals(Hanger.ROD, f.bench.stations[0].type) // a drag is not a tap
    }

    @Test fun replayAndStopButtonsGreyWithReasons() {
        val f = flow()
        assertFalse(f.snap.value.canReplay)
        assertFalse(f.snap.value.canStop)
        assertFalse(f.replay())
        assertFalse(f.stopAll())
        f.pressBob(0, 0); f.dragBob(0.08f, 0.05f, 0f); f.releaseBob()
        assertTrue(f.snap.value.canReplay)
        assertTrue(f.snap.value.canStop)
        assertTrue(f.stopAll())
        assertEquals(Phase.SETTLING, f.snap.value.phase)
        f.play(2.9)
        assertEquals(Phase.IDLE, f.snap.value.phase)
    }

    @Test fun swapClearsReleaseRecordAndResizeToo() {
        val f = flow()
        f.pressBob(0, 0); f.dragBob(0.08f, 0.05f, 0f); f.releaseBob()
        assertTrue(f.bench.stations[0].rec != null)
        f.tapTag(0)
        assertTrue(f.bench.stations[0].rec == null)
        f.pressBob(1, 0); f.dragBob(0f, -0.03f, 0f); f.releaseBob()
        f.setSize(1, 0)
        assertTrue(f.bench.stations[1].rec == null)
    }

    @Test fun planetSelectorStepsBothWaysThroughFourPlanetsAndPersists() {
        val f = flow()
        f.planetNext(); assertEquals(Planet.JUPITER, f.bench.planet)
        f.planetNext(); assertEquals(Planet.MOON, f.bench.planet)
        f.planetNext(); assertEquals(Planet.MARS, f.bench.planet)
        f.planetNext(); assertEquals(Planet.EARTH, f.bench.planet)
        f.planetPrev(); assertEquals(Planet.MARS, f.bench.planet)
        f.planetPrev(); assertEquals(Planet.MOON, f.bench.planet)
        f.planetPrev(); assertEquals(Planet.JUPITER, f.bench.planet)
        assertEquals(Planet.JUPITER, saved.planet)
    }

    // ------------------------------------------------------------------ ▶ swing keys (D43, user 2026-11-13)

    @Test fun swingKeyStartsEachHangerWithItsDefaultAmplitudeAndStopsOnSecondTap() {
        for (t in Hanger.entries) {
            val f = flow()
            f.bench.crossStation = false
            for (i in 0..2) f.setStation(i, t, 1, if (t == Hanger.DOUBLE) listOf(BobMat.WOOD, BobMat.STEEL) else listOf(BobMat.STEEL))
            val st = f.bench.stations[0]
            f.tapSwing(0)
            assertTrue("$t moving after ▶", f.snap.value.stations[0].moving)
            assertTrue("$t has a release record for [再放一次]", st.rec != null)
            when (t) {
                Hanger.ROD -> {
                    val d = st.rec!!.p[0] - st.pivot
                    assertEquals(20.0, Math.toDegrees(Math.acos(-d.y / d.len())), 0.01)
                    assertTrue("left station swings outward", d.x < 0)
                }
                Hanger.SPRING -> {
                    val pulled = st.pivot.y - f.bench.springEq(st) - st.rec!!.p[0].y
                    // 5 cm, clamped by the coil-stop rule of the hold (min(0.265, 2 L_eq - 0.04))
                    val cap = 2 * f.bench.springEq(st) - 0.04 - f.bench.springEq(st)
                    assertEquals(minOf(0.05, cap), pulled, 1e-6)
                    assertEquals(st.pivot.x, st.rec!!.p[0].x, 1e-9)
                }
                Hanger.DOUBLE -> {
                    val lower = st.rec!!.p[1]
                    assertTrue("lower bob out to the side", lower.x < st.pivot.x - st.L)
                    assertTrue("lower bob above the pivot", lower.y > st.pivot.y)
                }
            }
            f.play(0.5)
            assertTrue(f.snap.value.stations[0].moving)
            f.tapSwing(0) // ■ Stop
            assertFalse("key reads ▶ again while settling", f.snap.value.stations[0].moving)
            f.play(3.5)
            assertFalse("$t settled ${f.dump()}", f.bench.stationMoving(0))
            assertTrue("only station 0 was touched", !f.bench.stationMoving(1) && !f.bench.stationMoving(2))
        }
    }

    @Test fun swingKeyReadoutGoesFromLetGoToTimingToAPeriod() {
        val f = flow()
        f.bench.crossStation = false
        assertEquals(Big.LET_GO, f.snap.value.stations[0].readout.big)
        f.tapSwing(0)
        assertEquals(Big.TIMING, f.snap.value.stations[0].readout.big)
        f.play(2.5)
        assertEquals(Big.PERIOD, f.snap.value.stations[0].readout.big)
        // rod mid on Earth at 20°: ideal 0.851 s, the 20° swing runs ~0.8 % slow
        assertEquals(0.851, f.snap.value.stations[0].readout.bigValue, 0.851 * 0.03)
    }

    @Test fun swingKeyIsPartOfReplay() {
        val f = flow()
        f.bench.crossStation = false
        f.tapSwing(0)
        f.play(1.0)
        assertTrue(f.replay())
        val st = f.bench.stations[0]
        assertTrue(st.bodies[0].p.dist(st.rec!!.p[0]) < 1e-9)
    }

    @Test fun benchGrabRestsAndPausesThePhysics() {
        val f = flow()
        f.tapSwing(0); f.play(0.3)
        f.benchGrab()
        assertEquals(Phase.BENCH, f.snap.value.phase)
        assertEquals(Line.S7, statusLine(f.snap.value))
        val t = f.bench.time
        f.play(0.5)
        assertEquals(t, f.bench.time, 1e-9)
        assertFalse(f.bench.stationMoving(0))
        f.benchRelease()
        assertEquals(Phase.IDLE, f.snap.value.phase)
        f.setPlacing(true); assertEquals(Line.S8, statusLine(f.snap.value)); f.setPlacing(false)
    }

    @Test fun restoreDefaultBench() {
        val f = flow()
        f.tapTag(0); f.tapTag(1); f.setSize(2, 0); f.planetNext()
        f.restoreDefault()
        assertEquals(Bench.DEFAULT.map { it.first }, f.bench.stations.map { it.type })
        assertEquals(Planet.EARTH, f.bench.planet)
        assertEquals(Settings.DEFAULT_STATIONS, saved.stations)
    }

    @Test fun slowMotionRunsQuarterPhysicsTime() {
        val f = flow()
        f.setSlow(true)
        val t0 = f.bench.time
        repeat(4 * 60) { now += 16; f.frame(1f / 60) }
        assertEquals(1.0, f.bench.time - t0, 0.05)
    }

    @Test fun backgroundRestsTheBobs() {
        val f = flow()
        f.pressBob(0, 0); f.dragBob(0.08f, 0.05f, 0f); f.releaseBob()
        f.onBackground()
        assertFalse(f.bench.anyMoving())
    }

    @Test fun coach() {
        val f = flow()
        assertEquals(Line.S1, statusLine(f.snap.value)) // Full Space: no near hint (the bench starts within reach)
        assertEquals(1, f.snap.value.coach)
        f.pressBob(0, 0); f.dragBob(0.08f, 0.05f, 0f); f.releaseBob()
        assertEquals(2, f.snap.value.coach)
        assertEquals(Line.COACH2, statusLine(f.snap.value))
        f.tapTag(2)
        assertEquals(0, f.snap.value.coach)
        assertTrue(saved.coachDone)
        // a later cold start: no coach
        val g = flow(saved)
        assertEquals(0, g.snap.value.coach)
    }

    // ------------------------------------------------------------------ tips (content.tips, T13)

    private fun releaseRod(f: BenchFlow, si: Int = 0) { f.pressBob(si, 0); f.dragBob(0.03f, 0.004f, 0f); f.releaseBob() }

    @Test fun sixTipsTriggerInOrder() {
        val f = flow()
        f.bench.crossStation = false
        // tip 1: change a rod's ball, pull, read a period
        f.tapBob(0, 0); releaseRod(f); f.play(2.0)
        assertEquals(1, saved.tipIndex); assertEquals(0, f.snap.value.answerTip)
        // the answer also shows on that station's plate (D45)
        assertEquals(Small.ANSWER, f.snap.value.stations[0].readout.small)
        now += 5000; f.play(0.1)
        assertEquals(-1, f.snap.value.answerTip)
        // tip 2: make a rod longer
        f.tagDragStart(0); f.tagDragMove(-0.026f); f.tagDragEnd(); releaseRod(f); f.play(2.5)
        assertEquals(2, saved.tipIndex)
        // tip 3: the Moon
        f.setPlanet(Planet.MOON); releaseRod(f); f.play(6.0)
        assertEquals(3, saved.tipIndex)
        // tip 4: brass on the spring (vertical bounce)
        f.setPlanet(Planet.EARTH)
        f.tapBob(1, 0) // steel -> brass
        f.pressBob(1, 0); f.dragBob(0f, -0.02f, 0f); f.releaseBob(); f.play(2.0)
        assertEquals(4, saved.tipIndex)
        // tip 5: the spring on Jupiter
        f.setPlanet(Planet.JUPITER)
        f.pressBob(1, 0); f.dragBob(0f, -0.02f, 0f); f.releaseBob(); f.play(2.0)
        assertEquals(5, saved.tipIndex)
        // tip 6: the double, then [再放一次]: 10 s later it is somewhere else
        f.setPlanet(Planet.EARTH)
        val lower = f.bench.stations[2].bodies[1]
        f.pressBob(2, 1); f.dragBob((0.26 - lower.p.x).toFloat(), (0.20 - lower.p.y).toFloat(), 0f); f.releaseBob()
        f.play(10.3)
        assertTrue(f.replay())
        f.play(10.3)
        assertEquals(6, saved.tipIndex)
    }

    @Test fun tipNeedsItsOwnTrigger() {
        val f = flow()
        f.bench.crossStation = false
        releaseRod(f); f.play(2.0) // no material change: tip 1 stays
        assertEquals(0, saved.tipIndex)
    }

    // ------------------------------------------------------------------ D38: one state source

    @Test fun platesPanelAndTagsReadTheSameSource() {
        val f = flow()
        f.bench.crossStation = false
        releaseRod(f); f.play(2.0)
        val snap = f.snap.value
        val lex = Lex.EN
        val row = lex.periodsRow(snap)
        for (i in 0..2) assertTrue(row.contains(lex.big(snap.stations[i].readout)))
        assertTrue(lex.big(snap.stations[0].readout).endsWith(" s"))
        assertEquals("Rod" to "Mid ↕", lex.tagLines(snap.stations[0]))
        assertEquals("Spring" to "Mid ↕", lex.tagLines(snap.stations[1]))
        // q1-2 / D52: no symbol as an empty value; the double never released reads "0 flips"
        assertEquals(Big.FLIPS, snap.stations[2].readout.big)
        assertEquals("0 flips", lex.big(snap.stations[2].readout))
        assertEquals("0 s since release", lex.small(snap.stations[2].readout))
        assertFalse(row.contains("—"))
        assertEquals("Ideal 0.85 s", lex.small(snap.stations[0].readout))
        f.setSize(1, 2)
        assertEquals("Spring" to "Soft ↕", lex.tagLines(f.snap.value.stations[1]))
    }

    @Test fun springSidewaysSwingShowsSwinging() {
        val f = flow()
        f.bench.crossStation = false
        f.pressBob(1, 0); f.dragBob(0.08f, 0f, 0.05f); f.releaseBob()
        f.play(2.0)
        assertEquals(Big.SWINGING, f.snap.value.stations[1].readout.big)
    }

    @Test fun eachStatusLineMatchesThePhase() {
        val f = flow(Settings(coachDone = true))
        assertEquals(Line.S1, statusLine(f.snap.value))
        f.pressBob(0, 0); assertEquals(Line.S2, statusLine(f.snap.value))
        f.dragBob(0.08f, 0.05f, 0f); f.releaseBob(); assertEquals(Line.S3, statusLine(f.snap.value))
        f.stopAll(); assertEquals(Line.S4, statusLine(f.snap.value))
    }
}
