package io.embodit.kinetica.ui.bench

import io.embodit.kinetica.domain.BenchFlow
import io.embodit.kinetica.domain.BobMat
import io.embodit.kinetica.domain.Hanger
import io.embodit.kinetica.domain.Planet
import io.embodit.kinetica.ui.bench.input.parseLoggedPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** build.md 命中回放: every real hit chain (GLB mesh node first, then its parents) resolves to a holder, and the holder to a flow call. */
class HitReplayTest {
    @Test fun meshNodesResolveToTheirHolders() {
        assertEquals("bob:0:0", BenchTargets.resolvePath(listOf("bob_steel", "bobm:0:0:STEEL", "bob:0:0", "station:0", "root")))
        assertEquals("bob:2:1", BenchTargets.resolvePath(parseLoggedPath("bob_wood < bobm:2:1:WOOD < bob:2:1 < station:2 < root")))
        assertEquals("tag:1", BenchTargets.resolvePath(listOf("tag:1", "root")))
        assertEquals("go:2", BenchTargets.resolvePath(listOf("key", "keycap:2", "go:2", "root")))
        assertEquals("planet:next", BenchTargets.resolvePath(listOf("selkey", "selkey:true", "planet:next", "selector", "root")))
        assertEquals("planet:globe", BenchTargets.resolvePath(listOf("planet_mars", "planetm:MARS", "planet:globe", "selector", "root")))
        assertEquals("nameplate", BenchTargets.resolvePath(listOf("plaque", "plaqueModel", "nameplate", "root")))
        assertEquals("base", BenchTargets.resolvePath(listOf("base", "baseModel", "base", "root")))
        // things that take no hits
        for (n in listOf("baseModel", "frame", "rod:0:0", "plate:1", "spring:1", "trail:0", "hinge:2", "planetLabel", "selector", "go:5", "planet:up", "bob:3:0", "tag:7")) assertNull(n, BenchTargets.resolvePath(listOf(n, "root")))
    }

    @Test fun everyHolderReachesTheFlow() {
        var now = 0L
        var reopened = 0
        val f = BenchFlow(clock = { now }).also { it.ready(); it.onReopenPanel = { reopened++ } }
        val l = BenchListener(f, null)
        val src = io.embodit.kinetica.ui.bench.input.InputSource.HAND_RAY
        // bob tap: material cycles
        l.onPress("bob:0:0", src); l.onTap("bob:0:0", src, null)
        assertEquals(BobMat.BRASS, f.bench.stations[0].bodies[0].mat)
        // double's lower bob tap
        l.onTap("bob:2:1", src, null)
        assertEquals(BobMat.BRASS, f.bench.stations[2].bodies[1].mat)
        // tag tap and tag drag
        l.onTap("tag:1", src, null); assertEquals(Hanger.DOUBLE, f.bench.stations[1].type)
        assertTrue(l.onDragStart("tag:0", src)); l.onDragMove(io.embodit.kinetica.ui.bench.input.Offset3M(0f, -0.03f, 0f)); l.onDragEnd()
        assertEquals(2, f.bench.stations[0].size)
        // planet keys / globe, ▶ key, nameplate
        l.onTap("planet:next", src, null); assertEquals(Planet.JUPITER, f.bench.planet)
        l.onTap("planet:prev", src, null); l.onTap("planet:prev", src, null); assertEquals(Planet.MARS, f.bench.planet)
        l.onTap("planet:globe", src, null); assertEquals(Planet.EARTH, f.bench.planet)
        l.onTap("go:0", src, null); assertTrue(f.stationMoving(0))
        l.onTap("go:0", src, null); assertTrue(!f.stationMoving(0))
        l.onTap("nameplate", src, null); assertEquals(1, reopened)
        // a bob drag: press, travel, release with speed
        l.onPress("bob:1:1", src)
        assertTrue(l.onDragStart("bob:1:1", src))
        repeat(6) { i -> now += 12; l.onDragMove(io.embodit.kinetica.ui.bench.input.Offset3M(0.012f * (i + 1), 0.01f * (i + 1), 0f)) }
        l.onDragEnd()
        assertTrue(f.bench.stations[1].rec != null)
    }

    /** D1 反思「眼手拖动跟手不跟头」: an eye-hand drag moves by the pinch-point travel x the gain; head turns are no input. */
    @Test fun eyeHandDragFollowsTheHandTimesGain() {
        val f = BenchFlow(clock = { 0L }).also { it.ready() }
        var turned = 0.0
        val host = object : BenchHost {
            override fun toLocal(d: io.embodit.kinetica.ui.bench.input.Offset3M) = d
            override fun gain(target: String, source: io.embodit.kinetica.ui.bench.input.InputSource) = if (source == io.embodit.kinetica.ui.bench.input.InputSource.EYE_HAND) 1.3f else 1f
            override fun benchDragStart() = false
            override fun benchDragMove(stage: io.embodit.kinetica.ui.bench.input.Offset3M) {}
            override fun benchDragEnd() {}
            override fun hover(name: String?, source: io.embodit.kinetica.ui.bench.input.InputSource) {}
            override fun pressed(name: String) {}
            override val directActive = false
        }
        val l = BenchListener(f, host)
        val src = io.embodit.kinetica.ui.bench.input.InputSource.EYE_HAND
        val b = f.bench.stations[1].bodies[0] // the spring: moves freely within its range
        val p0 = b.p.copy()
        l.onPress("bob:1:0", src); assertTrue(l.onDragStart("bob:1:0", src))
        // the head turns 40° while pinching: nothing reaches the listener, the bob stays
        turned = 40.0
        assertTrue(b.p.dist(p0) < 1e-9)
        // the hand moves 2 cm down: the bob moves 2.6 cm
        l.onDragMove(io.embodit.kinetica.ui.bench.input.Offset3M(0f, -0.02f, 0f))
        assertEquals(-0.026, b.p.y - p0.y, 1e-6)
        l.onDragEnd()
        assertTrue(turned > 0)
    }
}
