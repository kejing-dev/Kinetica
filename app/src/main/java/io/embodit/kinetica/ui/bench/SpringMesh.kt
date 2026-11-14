package io.embodit.kinetica.ui.bench

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The spring as a real helix rebuilt on the CPU every frame (design hangers: wire Ø 2.5 mm, coil Ø 1.6 cm, 12 turns,
 * wire section 10 segments, DV6 + emulator probe: 12 x 12 cost ~25 ms per upload). The coil is re-swept between the two ends, so stretching never squashes the wire
 * section (R1-16). Pure Kotlin: indices are fixed, only positions / normals change. 12 turns x 10 samples x 10 sides
 * x 2 = 2 400 triangles (budget <= 3.5k per spring).
 */
class SpringMesh(val turns: Int = 12, val perTurn: Int = 10, val sides: Int = 10, val coilR: Float = 0.008f, val wireR: Float = 0.00125f) {
    val samples = turns * perTurn + 1
    val vertexCount = samples * (sides + 1)
    val positions = FloatArray(vertexCount * 3)
    val normals = FloatArray(vertexCount * 3)
    val indices: IntArray = IntArray((samples - 1) * sides * 6).also { idx ->
        var k = 0
        val w = sides + 1
        for (i in 0 until samples - 1) for (j in 0 until sides) {
            val a = i * w + j; val b = a + 1; val c = a + w; val d = c + 1
            idx[k++] = a; idx[k++] = c; idx[k++] = b
            idx[k++] = b; idx[k++] = c; idx[k++] = d
        }
    }
    val triangles: Int get() = indices.size / 3

    /** Sweeps the coil from [a] (the pivot) to [b] (the top of the bob). */
    fun build(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
        var dx = bx - ax; var dy = by - ay; var dz = bz - az
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        dx /= len; dy /= len; dz /= len
        // u, w: perpendicular to the axis
        var ux: Float; var uy: Float; var uz: Float
        if (abs(dy) < 0.9f) { ux = dz; uy = 0f; uz = -dx } else { ux = 0f; uy = -dz; uz = dy }
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
        val wx = dy * uz - dz * uy; val wy = dz * ux - dx * uz; val wz = dx * uy - dy * ux
        val pitch = len / turns
        for (i in 0 until samples) {
            val t = i.toFloat() / (samples - 1)
            val ang = (t * turns * 2 * PI).toFloat()
            val ca = cos(ang); val sa = sin(ang)
            // centre on the helix
            val rx = ux * ca + wx * sa; val ry = uy * ca + wy * sa; val rz = uz * ca + wz * sa
            val cx = ax + dx * len * t + rx * coilR; val cy = ay + dy * len * t + ry * coilR; val cz = az + dz * len * t + rz * coilR
            // tangent = d/dang: circumferential + axial
            val circ = 2 * PI.toFloat() * coilR
            var tx = (-ux * sa + wx * ca) * circ + dx * pitch
            var ty = (-uy * sa + wy * ca) * circ + dy * pitch
            var tz = (-uz * sa + wz * ca) * circ + dz * pitch
            val tl = sqrt(tx * tx + ty * ty + tz * tz); tx /= tl; ty /= tl; tz /= tl
            // n1 = radial made perpendicular to the tangent; n2 = t x n1
            val d0 = rx * tx + ry * ty + rz * tz
            var n1x = rx - tx * d0; var n1y = ry - ty * d0; var n1z = rz - tz * d0
            val nl = sqrt(n1x * n1x + n1y * n1y + n1z * n1z); n1x /= nl; n1y /= nl; n1z /= nl
            val n2x = ty * n1z - tz * n1y; val n2y = tz * n1x - tx * n1z; val n2z = tx * n1y - ty * n1x
            for (j in 0..sides) {
                val b = (2 * PI * j / sides).toFloat()
                val cb = cos(b); val sb = sin(b)
                val nx = n1x * cb + n2x * sb; val ny = n1y * cb + n2y * sb; val nz = n1z * cb + n2z * sb
                val v = (i * (sides + 1) + j) * 3
                positions[v] = cx + nx * wireR; positions[v + 1] = cy + ny * wireR; positions[v + 2] = cz + nz * wireR
                normals[v] = nx; normals[v + 1] = ny; normals[v + 2] = nz
            }
        }
    }
}
