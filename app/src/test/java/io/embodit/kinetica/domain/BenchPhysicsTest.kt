package io.embodit.kinetica.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** design verification.unitTests (physics, R2) + acceptance M1 M3 M5 M6 M7. */
class BenchPhysicsTest {
    private fun bench(t: Hanger, size: Int = 1, mat: BobMat = BobMat.STEEL, si: Int = 0): Bench {
        // the other stations get rods that never touch station si's motion (tests run one station at a time)
        val list = (0..2).map { i -> if (i == si) Triple(t, size, if (t == Hanger.DOUBLE) listOf(BobMat.WOOD, mat) else listOf(mat)) else Triple(Hanger.ROD, 0, listOf(BobMat.STEEL)) }
        return Bench(list).also { it.crossStation = false }
    }

    /** Pulls station [si] bob [k] to [deg] in the x-y plane and lets go with [v]. */
    private fun pull(b: Bench, si: Int, deg: Double, v: Vec3? = null, k: Int = 0) {
        val s = b.stations[si]
        b.grab(si, k)
        val r = if (s.type == Hanger.DOUBLE) s.L * (k + 1) else s.L
        b.moveHeld(si, k, Vec3(s.pivot.x + r * sin(Math.toRadians(deg)), s.pivot.y - r * cos(Math.toRadians(deg)), 0.0))
        b.release(si, k, v)
    }

    private fun measurePeriod(b: Bench, si: Int, seconds: Double = 6.0): Double {
        b.run(seconds)
        return assertNotNullValue(b.stations[si].meter.period)
    }

    private fun assertNotNullValue(x: Double?): Double { assertNotNull(x); return x!! }

    @Test fun rodMidEarthSmallAngle() {
        val b = bench(Hanger.ROD); b.drag = 0.0
        pull(b, 0, 5.0)
        val t = measurePeriod(b, 0)
        assertEquals(0.851, t, 0.851 * 0.02)
        assertEquals(t, b.idealPeriod(b.stations[0])!!, t * 0.03) // M1: within 3 % of the ideal
    }

    @Test fun rodMoonAndJupiter() {
        val b = bench(Hanger.ROD); b.drag = 0.0; b.changePlanet(Planet.MOON)
        pull(b, 0, 5.0)
        assertEquals(2.095, measurePeriod(b, 0, 10.0), 2.095 * 0.02)
        val j = bench(Hanger.ROD); j.drag = 0.0; j.changePlanet(Planet.JUPITER)
        pull(j, 0, 5.0)
        assertEquals(0.535, measurePeriod(j, 0), 0.535 * 0.02)
    }

    /** User 2026-11-14: the whole solar system; g lives in BenchConfig and each rod period follows 2π√(L/g). */
    @Test fun rodPeriodOnEveryPlanet() {
        val g = mapOf(Planet.MERCURY to 3.70, Planet.VENUS to 8.87, Planet.EARTH to 9.81, Planet.MOON to 1.62, Planet.MARS to 3.71,
            Planet.JUPITER to 24.79, Planet.SATURN to 10.44, Planet.URANUS to 8.69, Planet.NEPTUNE to 11.15, Planet.PLUTO to 0.62)
        for ((p, want) in g) assertEquals("$p g", want, p.g, 1e-12)
        assertEquals(BenchConfig.G_SATURN, Planet.SATURN.g, 0.0)
        assertEquals(listOf(Planet.PLUTO), Planet.entries.filter { it.dwarf })
        for (p in Planet.entries) {
            val want = 2 * Math.PI * kotlin.math.sqrt(BenchConfig.rodL(1) / p.g)
            val b = bench(Hanger.ROD); b.drag = 0.0; b.changePlanet(p)
            assertEquals("$p ideal", want, b.idealPeriod(b.stations[0])!!, 0.002)
            pull(b, 0, 5.0)
            assertEquals("$p measured", want, measurePeriod(b, 0, 16.0), want * 0.02)
        }
        // the values the v0.1.2 test pinned stay the same
        assertEquals(2.095, 2 * Math.PI * kotlin.math.sqrt(0.18 / Planet.MOON.g), 0.002)
        assertEquals(0.535, 2 * Math.PI * kotlin.math.sqrt(0.18 / Planet.JUPITER.g), 0.002)
    }

    @Test fun rodThirtyDegreesRunsSlow() {
        val b = bench(Hanger.ROD); b.drag = 0.0
        pull(b, 0, 30.0)
        val t = measurePeriod(b, 0)
        val slow = t / b.idealPeriod(b.stations[0])!! - 1
        assertTrue("30° should run 1.5–2.5 % slow, got ${slow * 100} %", slow in 0.015..0.025)
    }

    @Test fun springPeriodSameOnEveryPlanet() {
        for (p in Planet.entries) {
            val b = bench(Hanger.SPRING, si = 1); b.drag = 0.0; b.changePlanet(p)
            val s = b.stations[1]
            b.rest(s)
            assertEquals(b.springEq(s) - BenchConfig.SPRING_L0, 0.1 * p.g / 50, 0.002) // equilibrium stretch mg/k
            b.grab(1, 0)
            b.moveHeld(1, 0, Vec3(0.0, s.bodies[0].p.y - 0.02, 0.0))
            b.release(1, 0, null)
            val t = measurePeriod(b, 1, 3.0)
            assertEquals("planet $p", 0.281, t, 0.281 * 0.02)
        }
    }

    @Test fun nineSpringCombosOnJupiterClearTheFelt() {
        for (k in BenchConfig.SPRING_K) for (m in BobMat.entries) {
            val low = BenchConfig.SPRING_L0 + m.massKg * Planet.JUPITER.g / k + m.visualDiaM / 2
            assertTrue("k=$k m=$m reaches ${low}", low <= 0.25 + 1e-9)
        }
    }

    @Test fun springDampingTenPeriods() {
        val b = bench(Hanger.SPRING, si = 1); b.drag = 0.0
        val s = b.stations[1]
        b.grab(1, 0); b.moveHeld(1, 0, Vec3(0.0, s.bodies[0].p.y - 0.03, 0.0)); b.release(1, 0, null)
        val eq = s.pivot.y - b.springEq(s)
        fun amp(seconds: Double): Double { var a = 0.0; repeat((seconds / BenchConfig.H).toInt()) { b.step(); a = maxOf(a, abs(s.bodies[0].p.y - eq)) }; return a }
        val a0 = amp(0.3)
        b.run(2.81 - 0.6)
        val a1 = amp(0.3)
        assertTrue("decay ${1 - a1 / a0}", 1 - a1 / a0 < 0.30)
    }

    @Test fun headOnSteelCollisionFollowsImpulseLaw() {
        // two steel bobs on springs at the same height, one pushed sideways into the other: check the step of the hit
        val b = Bench(listOf(Triple(Hanger.SPRING, 1, listOf(BobMat.STEEL)), Triple(Hanger.SPRING, 1, listOf(BobMat.STEEL)), Triple(Hanger.ROD, 0, listOf(BobMat.STEEL))))
        b.drag = 0.0
        val a = b.stations[0].bodies[0]; val c = b.stations[1].bodies[0]
        a.p.set(c.p.x - 0.0455, c.p.y, 0.0)
        a.v.set(0.3, 0.0, 0.0)
        var va = 0.0
        var hit = false
        repeat(20) {
            if (hit) return@repeat
            // the velocity a carries into the contact = its velocity after this step's spring + gravity kick
            val s0 = b.stations[0]
            val d = a.p - s0.pivot
            val f = -s0.k * (d.len() - BenchConfig.SPRING_L0) - BenchConfig.springDamping(s0.k, a.m) * a.v.dot(d.normalized())
            va = a.v.x + f * d.normalized().x * BenchConfig.H / a.m
            b.step()
            if (b.contacts.any { it.kind == ContactKind.BOB_BOB }) hit = true
        }
        assertTrue(hit)
        val e = 0.9
        assertEquals((1 + e) / 2 * va, c.v.x, (1 + e) / 2 * va * 0.03)
        assertEquals((1 - e) / 2 * va, a.v.x, 0.3 * 0.03)
    }

    @Test fun collidePresetMakesASound() {
        val b = Bench()
        pull(b, 0, -75.0)
        var bb = 0
        repeat((3.0 / BenchConfig.H).toInt()) { b.step(); bb += b.contacts.count { it.kind == ContactKind.BOB_BOB && it.speed > 0.05 }; b.contacts.clear() }
        assertTrue("left rod should hit the middle bob", bb > 0)
    }

    @Test fun randomStartsNeverPenetrateOrLeave() {
        val rnd = java.util.Random(42)
        var worst = 0.0
        repeat(10) { trial ->
            val types = Hanger.entries
            val b = Bench((0..2).map { Triple(types[rnd.nextInt(3)], rnd.nextInt(3), listOf(BobMat.entries[rnd.nextInt(3)], BobMat.entries[rnd.nextInt(3)])) }
                .map { (t, s, m) -> Triple(t, s, if (t == Hanger.DOUBLE) m else m.take(1)) })
            b.changePlanet(Planet.entries[rnd.nextInt(Planet.entries.size)])
            for (si in 0..2) {
                val s = b.stations[si]
                val k = s.bodies.size - 1
                b.grab(si, k)
                b.moveHeld(si, k, s.pivot + Vec3(rnd.nextGaussian() * 0.15, -0.1 + rnd.nextGaussian() * 0.1, if (s.type == Hanger.DOUBLE) 0.0 else rnd.nextGaussian() * 0.1))
                b.release(si, k, Vec3(rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8, rnd.nextGaussian() * 0.8))
            }
            repeat(1000) {
                b.step()
                val bodies = b.allBodies()
                for (x in bodies) {
                    assertTrue("trial $trial out of volume ${x.p}", abs(x.p.x) <= 0.375 + 1e-9 && x.p.y <= 0.315 + 1e-9 && abs(x.p.z) <= 0.275 + 1e-9)
                    worst = maxOf(worst, BenchConfig.FELT_Y + x.r - x.p.y)
                    for (sg in intArrayOf(-1, 1)) {
                        val q = closestOnSegment(x.p, Vec3(sg * 0.36, -0.25, -0.05), Vec3(sg * 0.36, 0.06, -0.05))
                        worst = maxOf(worst, x.r + BenchConfig.POST_R - x.p.dist(q))
                    }
                    val q = closestOnSegment(x.p, Vec3(-0.36, 0.06, -0.05), Vec3(0.36, 0.06, -0.05))
                    worst = maxOf(worst, x.r + BenchConfig.BAR_R - x.p.dist(q))
                }
            }
        }
        assertTrue("penetration ${worst * 1000} mm", worst < 0.001)
    }

    @Test fun energyDriftWithoutDrag() {
        val b = bench(Hanger.ROD); b.drag = 0.0
        pull(b, 0, 30.0, Vec3(0.0, 0.0, 0.2))
        val s = b.stations[0]; val x = s.bodies[0]
        fun e() = 0.5 * x.v.len2() + b.g * (x.p.y - (s.pivot.y - s.L))
        val e0 = e()
        b.run(60.0)
        assertTrue("drift ${(e() - e0) / e0 * 100} %", abs(e() - e0) / e0 < 0.01)
    }

    @Test fun deterministicAndReplayIdentical() {
        fun trace(): List<Double> {
            val b = bench(Hanger.ROD)
            pull(b, 0, 40.0, Vec3(0.0, 0.0, 0.2))
            return (0 until 600).map { b.step(); b.stations[0].bodies[0].p.x }
        }
        assertEquals(trace(), trace())
        // [再放一次]: the rod repeats sub-step by sub-step
        val b = bench(Hanger.ROD)
        pull(b, 0, 40.0, Vec3(0.0, 0.0, 0.2))
        b.stations[1].let { b.grab(1, 0); b.moveHeld(1, 0, it.bodies[0].p + Vec3(0.0, -0.03, 0.0)); b.release(1, 0, null) }
        val first = (0 until 600).map { b.step(); b.stations[0].bodies[0].p.copy() }
        assertTrue(b.replay())
        val second = (0 until 600).map { b.step(); b.stations[0].bodies[0].p.copy() }
        assertEquals(first, second)
    }

    @Test fun doubleReplayDivergesEveryTime() {
        val b = bench(Hanger.DOUBLE, si = 2)
        b.grab(2, 1)
        b.moveHeld(2, 1, Vec3(0.26, 0.20, 0.0))
        b.release(2, 1, null)
        b.run(10.2)
        val ats = ArrayList<Vec3>()
        ats += b.stations[2].runAt10!!
        repeat(3) {
            assertTrue(b.replay())
            b.run(10.2)
            val d = b.stations[2].lastDivergeM
            assertNotNull(d)
            assertTrue("replay ${it + 1} differs only ${d!! * 100} cm", d > BenchConfig.TIP_DIVERGE_M)
            ats += b.stations[2].runAt10!!
        }
    }

    @Test fun sideVelocityDrawsAnEllipse() {
        val b = bench(Hanger.ROD); b.drag = 0.0
        pull(b, 0, 20.0, Vec3(0.0, 0.0, 0.3))
        var minZ = 0.0; var maxZ = 0.0
        repeat((3.0 / BenchConfig.H).toInt()) { b.step(); val z = b.stations[0].bodies[0].p.z; minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z) }
        assertTrue("minor half-axis ${(maxZ - minZ) / 2 * 100} cm", (maxZ - minZ) / 2 > 0.01)
    }

    @Test fun rodMaxReleaseStaysBelowHorizontal() {
        val b = bench(Hanger.ROD, size = 2); b.drag = 0.0
        pull(b, 0, 80.0, Vec3(1.5, 1.5, 1.5))
        var top = -1.0
        repeat((4.0 / BenchConfig.H).toInt()) { b.step(); top = maxOf(top, b.stations[0].bodies[0].p.y) }
        assertTrue("highest ${top} above pivot ${b.stations[0].pivot.y}", top <= b.stations[0].pivot.y + 1e-3)
    }

    @Test fun springCoilStopHolds() {
        val b = bench(Hanger.SPRING, size = 2, mat = BobMat.BRASS, si = 1); b.drag = 0.0
        val s = b.stations[1]
        b.grab(1, 0); b.moveHeld(1, 0, s.pivot + Vec3(0.0, -0.5, 0.0)); b.release(1, 0, Vec3(0.0, 1.5, 0.0))
        var min = 9.0
        repeat((5.0 / BenchConfig.H).toInt()) { b.step(); min = minOf(min, s.bodies[0].p.dist(s.pivot)) }
        assertTrue("closest ${min}", min >= BenchConfig.COIL_STOP - 1e-6)
    }

    @Test fun doubleLowerBobIgnoresItsOwnArm() {
        val b = bench(Hanger.DOUBLE, size = 2, si = 2)
        b.grab(2, 1); b.moveHeld(2, 1, Vec3(0.12, 0.05 + 0.02, 0.0)); b.release(2, 1, null)
        b.run(3.0)
        assertTrue(b.stations[2].bodies.all { it.p.z == 0.0 })
    }

    @Test fun stopAllSettlesWithoutTeleport() {
        val b = Bench()
        pull(b, 0, 60.0, Vec3(0.0, 0.0, 0.4))
        b.grab(1, 0); b.moveHeld(1, 0, b.stations[1].bodies[0].p + Vec3(0.05, -0.05, 0.05)); b.release(1, 0, null)
        b.grab(2, 1); b.moveHeld(2, 1, Vec3(0.27, 0.17, 0.0)); b.release(2, 1, null)
        b.run(1.0)
        assertTrue(b.stopAll())
        var maxJump = 0.0
        val prev = b.allBodies().map { it.p.copy() }.toMutableList()
        repeat((1.2 / BenchConfig.H).toInt() + 1) {
            b.step()
            b.allBodies().forEachIndexed { i, x -> maxJump = maxOf(maxJump, x.p.dist(prev[i])); prev[i] = x.p.copy() }
        }
        assertTrue("teleport ${maxJump * 1000} mm in one step", maxJump < 0.02)
        for (s in b.stations) s.bodies.forEachIndexed { k, x ->
            assertTrue("v ${x.v.len()}", x.v.len() < 0.002)
            assertTrue("station ${s.index} bob $k off rest ${x.p.dist(b.eqPos(s, k)) * 1000} mm", x.p.dist(b.eqPos(s, k)) < 0.005)
        }
    }

    @Test fun slowMotionKeepsPhysicsTime() {
        val b = bench(Hanger.ROD)
        val t0 = b.time
        repeat(240) { b.advance(1.0 / 60, slow = true) } // 4 s wall clock
        assertEquals(1.0, b.time - t0, 0.05)
    }

    @Test fun holdClampsRodAndSpring() {
        val b = Bench()
        b.grab(0, 0)
        val s = b.stations[0]
        b.moveHeld(0, 0, s.pivot + Vec3(0.3, 0.3, 0.0)) // above the pivot: clamped to 80°
        val d = (s.bodies[0].p - s.pivot).normalized()
        val ang = Math.toDegrees(kotlin.math.acos(-d.y))
        assertTrue("angle $ang", ang <= 80.0 + 1e-6)
        assertEquals(s.L, s.bodies[0].p.dist(s.pivot), 1e-9)
        b.grab(1, 0)
        val sp = b.stations[1]
        b.moveHeld(1, 0, sp.pivot + Vec3(0.0, -0.6, 0.0))
        val maxLen = minOf(0.265, 2 * b.springEq(sp) - 0.04)
        assertTrue(sp.bodies[0].p.dist(sp.pivot) <= maxLen + 1e-9)
        b.moveHeld(1, 0, sp.pivot + Vec3(0.0, -0.001, 0.0))
        assertTrue(sp.bodies[0].p.dist(sp.pivot) >= 0.05 - 1e-9)
    }

    @Test fun heldTargetIsPushedOutOfThePosts() {
        val b = Bench(listOf(Triple(Hanger.SPRING, 2, listOf(BobMat.STEEL)), Triple(Hanger.SPRING, 1, listOf(BobMat.STEEL)), Triple(Hanger.SPRING, 1, listOf(BobMat.STEEL))))
        b.grab(2, 0)
        b.moveHeld(2, 0, Vec3(0.36, -0.1, -0.05)) // straight into the right post
        val p = b.stations[2].bodies[0].p
        val q = closestOnSegment(p, Vec3(0.36, -0.25, -0.05), Vec3(0.36, 0.06, -0.05))
        assertTrue(p.dist(q) >= BenchConfig.POST_R + b.stations[2].bodies[0].r - 1e-3)
    }

    @Test fun periodMeterDashAfterAHit() {
        val b = bench(Hanger.ROD); b.drag = 0.0
        pull(b, 0, 10.0)
        b.run(3.0)
        assertNotNull(b.stations[0].meter.period)
        // a hard knock clears the crossings (needs a full new period)
        b.stations[0].meter.crosses.clear()
        assertFalse(b.stations[0].meter.period != null)
        b.run(1.2)
        assertNotNull(b.stations[0].meter.period)
    }

    @Test fun doubleCountsFlips() {
        val b = bench(Hanger.DOUBLE, si = 2); b.drag = 0.0
        b.grab(2, 1); b.moveHeld(2, 1, Vec3(0.12, 0.25, 0.0)); b.release(2, 1, Vec3(1.2, 0.0, 0.0))
        b.run(8.0)
        assertTrue("flips ${b.stations[2].meter.flips}", b.stations[2].meter.flips >= 1)
    }

    @Test fun idealValues() {
        val b = Bench()
        assertEquals(2 * PI * sqrt(0.18 / 9.81), b.idealPeriod(b.stations[0])!!, 1e-9)
        assertEquals(2 * PI * sqrt(0.1 / 50), b.idealPeriod(b.stations[1])!!, 1e-9)
    }
}
