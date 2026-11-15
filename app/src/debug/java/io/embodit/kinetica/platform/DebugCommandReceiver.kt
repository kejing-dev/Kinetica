package io.embodit.kinetica.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.kinetica.domain.BobMat
import io.embodit.kinetica.domain.Hanger
import io.embodit.kinetica.domain.Language
import io.embodit.kinetica.domain.Planet
import io.embodit.kinetica.domain.Vec3
import io.embodit.kinetica.ui.Page
import io.embodit.kinetica.ui.Ui
import io.embodit.kinetica.ui.bench.BenchListener
import io.embodit.kinetica.ui.bench.BenchNames
import io.embodit.kinetica.ui.bench.BenchTextures
import io.embodit.kinetica.ui.bench.SceneHolder
import io.embodit.kinetica.ui.bench.input.BoardInputConfig
import io.embodit.kinetica.ui.bench.input.DragTotal
import io.embodit.kinetica.ui.bench.input.InputSource
import io.embodit.kinetica.ui.bench.input.Offset3M
import io.embodit.kinetica.ui.bench.route
import io.embodit.kinetica.ui.control.exitApp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug builds only. Every input command goes through the same listener / BenchFlow calls the volume and the window use
 * (D29: no shortcuts that set state directly).
 *
 *   adb shell am broadcast -a io.embodit.kinetica.DEBUG -p io.embodit.kinetica --es cmd <cmd> [--es a "<arg>"]
 *
 *   tap "bob <i> <k>" | "tag <i>" | "go <i>" | prev | next | globe | nameplate | base   a pinch on that holder
 *   drag "bob <i> <k> <dx> <dy> <dz> [hold]" | "tag <i> <dy>"  press, move (metres, bench axes), release unless hold
 *   drag "bench <dx> <dy> <dz> [hold]"   grab the base and move it (metres, Stage axes) · rotate bench <deg> · stick <x|off>
 *   recall (settings [叫回面前]) · place (dump the placement diagnostics)
 *   release "[vx vy vz]"   let go of the held bob with that hand velocity (m/s)
 *   replay · stop · slow on|off · restore · preset default|collide|chaos|moving · planet mercury|venus|earth|moon|mars|jupiter|saturn|uranus|neptune|pluto
 *   size "<i> <0-2>" · sizes rod|spring|double · mat "<i> <k> wood|steel|brass" · type "<i> rod|spring|double"
 *   hold S2|S3|S4|S5|S6|off   QA holds (physics frozen / animation frame frozen)  · ff <seconds> · freeze on|off
 *   tip <0-6> · answer "<tip> <station>" · coach on|off · ftest on|off (F↑ on every brass face)
 *   page play|settings · howto on|off · lang zh|en|system · mute on|off · volume 0-2 · closepanel · openpanel · exitask · confirm · cancel
 *   s0hold on|off · dump · instances
 *   dragdeltas "bob <i> <k>|tag <i>|bench  <dx> <dy> <dz> <n> [hold]"   headset-shaped stream (D53): n events of one
 *     per-event delta each 16 ms through DragTotal + the dragStartM gate, exactly as BoardInput (bob / tag: bench axes)
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        val scene = SceneHolder.scene
        val listener = SceneHolder.listener ?: BenchListener(flow, scene)
        /** Debug drags speak bench axes; the listener takes Stage axes (like the system), so turn them first. */
        fun moveLocal(d: Offset3M) = listener.onDragMove(scene?.toStage(d) ?: d)
        Log.i(TAG, "CMD $cmd $a")
        fun target(parts: List<String>): String? = when (parts.getOrNull(0)) {
            "bob" -> BenchNames.bob(parts[1].toInt(), parts.getOrNull(2)?.toIntOrNull() ?: 0)
            "tag" -> BenchNames.tag(parts[1].toInt())
            "go" -> BenchNames.go(parts[1].toInt())
            "prev" -> BenchNames.PLANET_PREV
            "next", "dial" -> BenchNames.PLANET_NEXT
            "globe" -> BenchNames.PLANET_GLOBE
            "nameplate" -> BenchNames.NAMEPLATE
            "base" -> BenchNames.BASE
            else -> null
        }
        /** A real press -> drag -> (release) on a bob, via the listener. */
        fun dragBob(si: Int, k: Int, dx: Float, dy: Float, dz: Float, hold: Boolean) {
            val t = BenchNames.bob(si, k)
            listener.onPress(t, InputSource.UNKNOWN)
            listener.onDragStart(t, InputSource.UNKNOWN)
            moveLocal(Offset3M(dx, dy, dz))
            if (!hold) listener.onDragEnd()
        }
        /** Pull a bob to an angle (degrees from straight down, in the x-y plane) from its rest, then let go with [v]. */
        fun pullTo(si: Int, k: Int, deg: Double, v: Vec3?, hold: Boolean = false) {
            val st = flow.bench.stations[si]
            val b = st.bodies[k]
            val r = when (st.type) { Hanger.ROD -> st.L; Hanger.DOUBLE -> st.L * (k + 1); Hanger.SPRING -> flow.bench.springEq(st) }
            val tx = st.pivot.x + r * sin(Math.toRadians(deg)); val ty = st.pivot.y - r * cos(Math.toRadians(deg))
            val t = BenchNames.bob(si, k)
            listener.onPress(t, InputSource.UNKNOWN)
            listener.onDragStart(t, InputSource.UNKNOWN)
            moveLocal(Offset3M((tx - b.p.x).toFloat(), (ty - b.p.y).toFloat(), 0f))
            if (!hold) { if (v != null) flow.releaseBob(v) else listener.onDragEnd() }
        }
        fun preset(name: String) {
            flow.setFrozen(false); flow.animFreezeMs = null
            flow.restoreDefault()
            when (name) {
                "collide" -> pullTo(0, 0, -75.0, null)
                "chaos" -> dragBob(2, 1, 0.14f, 0.35f, 0f, false)
                "moving" -> {
                    pullTo(0, 0, -30.0, Vec3(0.0, 0.0, 0.3))
                    dragBob(1, 0, 0f, -0.04f, 0f, false)
                    dragBob(2, 1, 0.14f, 0.35f, 0f, false)
                }
            }
        }
        when (cmd) {
            "tap" -> { val t = target(f); if (t != null) { if (t.startsWith("bob")) listener.onPress(t, InputSource.UNKNOWN); listener.onTap(t, InputSource.UNKNOWN, null) } }
            "drag" -> when (f.getOrNull(0)) {
                "bob" -> dragBob(f[1].toInt(), f[2].toInt(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat(), f.getOrNull(6) == "hold")
                "tag" -> { val t = BenchNames.tag(f[1].toInt()); listener.onDragStart(t, InputSource.UNKNOWN); moveLocal(Offset3M(0f, f[2].toFloat(), 0f)); listener.onDragEnd() }
                "bench" -> {
                    if (listener.onDragStart(BenchNames.BASE, InputSource.UNKNOWN)) {
                        listener.onDragMove(Offset3M(f[1].toFloat(), f[2].toFloat(), f[3].toFloat()))
                        if (f.getOrNull(4) != "hold") listener.onDragEnd()
                    }
                }
            }
            "dragdeltas" -> {
                // Same path as BoardInput's drag detector: reset at press, add each event's delta, start past dragStartM,
                // onDragMove(total). The deltas are what SDK 6.1.9 sends (position3d - previousPosition3d).
                val (t, rest) = when (f.getOrNull(0)) {
                    "bob" -> BenchNames.bob(f[1].toInt(), f[2].toInt()) to f.drop(3)
                    "tag" -> BenchNames.tag(f[1].toInt()) to f.drop(2)
                    else -> BenchNames.BASE to f.drop(1)
                }
                val step = Offset3M(rest[0].toFloat(), rest[1].toFloat(), rest[2].toFloat())
                val n = rest[3].toInt(); val hold = rest.getOrNull(4) == "hold"
                val stageAxes = t == BenchNames.BASE
                val total = DragTotal(); val gate = BoardInputConfig().dragStartM
                val h = android.os.Handler(android.os.Looper.getMainLooper())
                var dragging = false
                total.reset()
                if (t.startsWith("bob")) listener.onPress(t, InputSource.UNKNOWN)
                Log.i(TAG, "DRAGREPLAY press $t step=$step n=$n")
                for (i in 0 until n) h.postDelayed({
                    val d = total.add(step)
                    if (!dragging && d.length > gate) { dragging = listener.onDragStart(t, InputSource.UNKNOWN); Log.i(TAG, "DRAGREPLAY start ev=${total.events} taken=$dragging") }
                    if (dragging) { if (stageAxes) listener.onDragMove(d) else moveLocal(d) }
                    if (total.events % 10 == 0 || i == n - 1) Log.i(TAG, "DRAGREPLAY ev=${total.events} total=(%.3f, %.3f, %.3f) len=%.3f m".format(d.x, d.y, d.z, d.length))
                    if (i == n - 1 && !hold) { if (dragging) listener.onDragEnd() else listener.onTap(t, InputSource.UNKNOWN, null); Log.i(TAG, "DRAGREPLAY end dragging=$dragging") }
                }, 16L * (i + 1))
            }
            "release" -> flow.releaseBob(if (f.size >= 3) Vec3(f[0].toDouble(), f[1].toDouble(), f[2].toDouble()) else null)
            "replay" -> flow.replay()
            "stop" -> flow.stopAll()
            "slow" -> flow.setSlow(on)
            "restore" -> flow.restoreDefault()
            "preset" -> preset(a)
            "planet" -> flow.setPlanet(Planet.entries.firstOrNull { it.name.equals(a.trim(), ignoreCase = true) } ?: Planet.EARTH)
            "rotate" -> scene?.turn(f[1].toDouble())
            "stick" -> scene?.controllers?.debugStick = a.toFloatOrNull()
            "release-bench" -> listener.onDragEnd()
            "recall" -> graph.recallFront()
            "place" -> Log.i(TAG, "PLACE diag ${scene?.placeDiag()}")
            "size" -> flow.setSize(f[0].toInt(), f[1].toInt())
            "sizes" -> {
                val t = Hanger.valueOf(a.uppercase())
                for (i in 0..2) flow.setStation(i, t, i, if (t == Hanger.DOUBLE) listOf(BobMat.WOOD, BobMat.STEEL) else listOf(BobMat.STEEL))
            }
            "mat" -> { val s = flow.bench.stations[f[0].toInt()]; flow.setStation(s.index, s.type, s.size, s.mats.toMutableList().also { it[f[1].toInt()] = BobMat.valueOf(f[2].uppercase()) }) }
            "type" -> { val s = flow.bench.stations[f[0].toInt()]; val t = Hanger.valueOf(f[1].uppercase()); flow.setStation(s.index, t, s.size, if (t == Hanger.DOUBLE) listOf(s.mats[0], BobMat.STEEL) else listOf(s.mats[0])) }
            "ff" -> flow.fastForward(a.toDouble())
            "freeze" -> flow.setFrozen(on)
            "hold" -> when (a.uppercase()) {
                "S2" -> { preset("default"); pullTo(0, 0, -60.0, null, hold = true) }
                "S3" -> { preset("moving"); flow.fastForward(3.0); flow.setFrozen(true) }
                "S4" -> { preset("moving"); flow.fastForward(2.0); flow.stopAll(); flow.fastForward(0.3); flow.setFrozen(true) }
                "S5" -> { flow.setFrozen(false); flow.tapTag(1); flow.animFreezeMs = 150L }
                "S6" -> { flow.setFrozen(false); flow.animFreezeMs = null; for (i in 0..2) flow.setStation(i, Hanger.ROD, i, listOf(BobMat.STEEL)) }
                else -> { flow.setFrozen(false); flow.animFreezeMs = null }
            }
            "tip" -> flow.setTip(a.toInt())
            "answer" -> flow.showAnswer(f[0].toInt(), f.getOrNull(1)?.toInt() ?: 0)
            "coach" -> flow.setCoach(on)
            "ftest" -> { BenchTextures.testF = on; SceneHolder.scene?.refreshAll() }
            "page" -> Ui.page = if (a == "settings") Page.SETTINGS else Page.PLAY
            "lang" -> graph.update { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "mute" -> graph.update { it.copy(muted = on) }
            "volume" -> graph.update { it.copy(volume = a.toInt().coerceIn(0, 2)) }
            "closepanel" -> graph.window.close()
            "openpanel" -> graph.window.request("debug")
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.howTo = false; Ui.exitConfirm = true }
            "howto" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = false; Ui.howTo = a != "off" }
            "cancel" -> Ui.exitConfirm = false
            "confirm" -> exitApp(context, graph)
            "s0hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s0hold", on).apply()
            "dump" -> Log.i(TAG, "DUMP ${flow.dump()} panel=${graph.window.panelState} page=${Ui.page} snap=${flow.snap.value}")
            "instances" -> Log.i(TAG, "INSTANCES\n${SceneHolder.scene?.instances()}")
            "route" -> route(flow, a)
            else -> Log.w(TAG, "CMD unknown $cmd")
        }
    }
}
