package io.embodit.kinetica.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** User 2026-11-14: ten bodies in the selector; ringed globes stay clear of the ◀ ▶ keys and the label; a change is smooth (D51). */
class PlanetSelectorTest {
    @Test fun ringedGlobesClearTheKeysAndTheLabel() {
        val keyInner = Layout.SEL_NEXT.x - Layout.SEL_KEY_D / 2
        val labelTop = Layout.SEL_LABEL.y + Layout.SEL_LABEL_H / 2
        for (p in Planet.entries) {
            val r = Layout.globeReachM(p)
            assertTrue("$p reach ${r * 100} cm vs key ${keyInner * 100} cm", Layout.SEL_GLOBE.x + r <= keyInner - 0.004)
            assertTrue("$p bottom vs label", Layout.SEL_GLOBE.y - r >= labelTop + 0.004)
        }
        // the plain globes keep the v0.1.2 size
        assertEquals(Layout.SEL_GLOBE_D / 2, Layout.globeReachM(Planet.EARTH), 0.0)
    }

    @Test fun switchAnimationIsSmoothAndEndsCentredAtFullSize() {
        var prevIn = Layout.globeSwitch(0)
        assertEquals(Layout.GLOBE_SLIDE_M, prevIn.inOffsetM, 1e-9)
        assertEquals(0.4, prevIn.inScale, 1e-9)
        var maxScale = 0.0
        for (ms in 10L..400L step 10) {
            val s = Layout.globeSwitch(ms)
            assertTrue("jump at $ms ms", kotlin.math.abs(s.inOffsetM - prevIn.inOffsetM) < 0.006 && kotlin.math.abs(s.inScale - prevIn.inScale) < 0.12)
            maxScale = maxOf(maxScale, s.inScale)
            prevIn = s
        }
        val end = Layout.globeSwitch(Layout.GLOBE_SWITCH_MS.toLong())
        assertTrue(end.done); assertEquals(0.0, end.inOffsetM, 0.0); assertEquals(1.0, end.inScale, 0.0); assertEquals(0.0, end.outScale, 0.0)
        assertTrue("small overshoot $maxScale", maxScale in 1.0..1.1)
        // the leaving globe shrinks and is gone before the end
        assertTrue(Layout.globeSwitch(150).outScale in 0.0..0.95)
        assertEquals(0.0, Layout.globeSwitch(260).outScale, 0.0)
    }
}
