package io.embodit.kinetica.ui.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** The spring helix: triangle budget, true round wire section at any stretch (R1-16, DV6). */
class SpringMeshTest {
    @Test fun budgetAndSection() {
        val m = SpringMesh()
        assertTrue("tris ${m.triangles}", m.triangles <= 3500); assertTrue(m.sides >= 8)
        for (len in listOf(0.05f, 0.08f, 0.2f)) {
            m.build(0f, 0.05f, 0f, 0f, 0.05f - len, 0f)
            // every ring of the tube is a circle of the wire radius around its centre
            for (i in 0 until m.samples step 17) {
                var cx = 0f; var cy = 0f; var cz = 0f
                for (j in 0 until m.sides) { val v = (i * (m.sides + 1) + j) * 3; cx += m.positions[v]; cy += m.positions[v + 1]; cz += m.positions[v + 2] }
                cx /= m.sides; cy /= m.sides; cz /= m.sides
                for (j in 0 until m.sides) {
                    val v = (i * (m.sides + 1) + j) * 3
                    val dx = m.positions[v] - cx; val dy = m.positions[v + 1] - cy; val dz = m.positions[v + 2] - cz
                    assertEquals(m.wireR, sqrt(dx * dx + dy * dy + dz * dz), m.wireR * 0.05f)
                }
            }
        }
    }
}
