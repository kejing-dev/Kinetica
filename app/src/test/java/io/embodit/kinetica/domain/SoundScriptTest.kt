package io.embodit.kinetica.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 30 s of play through the real BenchFlow (left rod pulled into the middle bob, the double thrown, the spring bounced,
 * every 6 s) -> build/sound-events.csv (time ms, sound, gain). tools/loudness.py renders it with the app's SoundPlayer
 * rules into the loudness evidence (rendered from the app's own collision events, not a recording: no audio loopback here).
 */
class SoundScriptTest {
    @Test fun thirtySecondsOfCollisions() {
        var now = 0L
        val events = ArrayList<String>()
        val f = BenchFlow(clock = { now }, onSound = { s, g -> events += "$now,${s.name},%.3f".format(g) }).also { it.ready() }
        fun pull(si: Int, k: Int, dx: Float, dy: Float, dz: Float) {
            f.pressBob(si, k)
            repeat(6) { i -> now += 13; f.dragBob(dx * (i + 1) / 6, dy * (i + 1) / 6, dz * (i + 1) / 6) }
            f.releaseBob()
        }
        for (round in 0 until 5) {
            pull(0, 0, -0.16f, 0.11f, 0.02f)       // left rod up to the left: swings into the middle bob
            pull(1, 0, 0.03f, -0.06f, 0f)          // spring stretched down and aside
            pull(2, 1, 0.14f, 0.35f, 0f)           // the double thrown over
            if (round % 2 == 1) f.tapBob(0, 0)     // change a ball now and then
            repeat(6 * 72) { now += 14; f.frame(1f / 72) }
        }
        val out = File("build/sound-events.csv")
        out.writeText("ms,sound,gain\n" + events.joinToString("\n") + "\n")
        println("SOUND events=${events.size} -> ${out.absolutePath}")
        assertTrue("collisions should make sounds", events.count { it.contains("METAL") || it.contains("WOOD") || it.contains("PLATE") } >= 5)
    }
}
