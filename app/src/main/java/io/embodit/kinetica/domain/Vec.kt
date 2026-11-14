package io.embodit.kinetica.domain

import kotlin.math.sqrt

/** Small mutable double vector for the physics core (metres, volume axes: +X right, +Y up, +Z toward the user). */
class Vec3(@JvmField var x: Double = 0.0, @JvmField var y: Double = 0.0, @JvmField var z: Double = 0.0) {
    fun set(o: Vec3): Vec3 { x = o.x; y = o.y; z = o.z; return this }
    fun set(ax: Double, ay: Double, az: Double): Vec3 { x = ax; y = ay; z = az; return this }
    fun copy(): Vec3 = Vec3(x, y, z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun len2() = x * x + y * y + z * z
    fun addScaled(o: Vec3, k: Double): Vec3 { x += o.x * k; y += o.y * k; z += o.z * k; return this }
    fun scale(k: Double): Vec3 { x *= k; y *= k; z *= k; return this }
    fun normalized(): Vec3 { val l = len(); return if (l < 1e-12) Vec3(0.0, -1.0, 0.0) else Vec3(x / l, y / l, z / l) }
    fun dist(o: Vec3): Double { val dx = x - o.x; val dy = y - o.y; val dz = z - o.z; return sqrt(dx * dx + dy * dy + dz * dz) }
    override fun toString() = "(%.4f, %.4f, %.4f)".format(x, y, z)
    override fun equals(other: Any?) = other is Vec3 && other.x == x && other.y == y && other.z == z
    override fun hashCode() = (x.hashCode() * 31 + y.hashCode()) * 31 + z.hashCode()
}

/** Closest point on segment [a, b] to [p]. */
fun closestOnSegment(p: Vec3, a: Vec3, b: Vec3): Vec3 {
    val ab = b - a
    val l2 = ab.len2()
    if (l2 < 1e-14) return a.copy()
    val t = ((p - a).dot(ab) / l2).coerceIn(0.0, 1.0)
    return a.copy().addScaled(ab, t)
}
