package io.embodit.kinetica.ui.bench

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.kinetica.R
import io.embodit.kinetica.domain.BenchFlow
import io.embodit.kinetica.domain.BobMat
import io.embodit.kinetica.domain.Planet
import io.embodit.kinetica.platform.Graph
import io.embodit.kinetica.platform.TAG
import io.embodit.kinetica.ui.Lex
import io.embodit.kinetica.ui.bench.input.BoardInputConfig
import io.embodit.kinetica.ui.bench.input.BoardInputListener
import io.embodit.kinetica.ui.bench.input.BoardInputLog
import io.embodit.kinetica.ui.bench.input.InputSource
import io.embodit.kinetica.ui.bench.input.Offset3M
import io.embodit.kinetica.ui.bench.input.TargetResolver
import io.embodit.kinetica.ui.bench.input.boardInput
import io.embodit.kinetica.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the physics and the per-frame placement inside the ECS (never in Compose) and feeds the window's level gate. */
class BenchFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        val g = SceneHolder.graph ?: return
        val t0 = java.lang.System.nanoTime()
        g.flow.frame(context.deltaTime)
        SceneHolder.scene?.onFrame(context.deltaTime)
        FramePerf.record(context.deltaTime, (java.lang.System.nanoTime() - t0) / 1e6)
    }
}

/** Frame timing for ui-audit M16: frame dt and the bench's own CPU time per frame, logged every 10 s. */
object FramePerf {
    private var n = 0; private var sumDt = 0.0; private var worstDt = 0.0; private var sumCpu = 0.0; private var worstCpu = 0.0; private var slow = 0
    private var lastLog = 0L
    @Synchronized fun record(dt: Float, cpuMs: Double) {
        n++; sumDt += dt; if (dt > worstDt) worstDt = dt.toDouble(); sumCpu += cpuMs; if (cpuMs > worstCpu) worstCpu = cpuMs
        if (dt > 1.5 / 72) slow++
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLog > 10_000 && n > 0) {
            Log.i(TAG, "PERF frames=$n fps=%.1f worstDt=%.1f ms slowFrames=$slow benchCpu avg=%.2f worst=%.2f ms".format(n / sumDt, worstDt * 1000, sumCpu / n, worstCpu))
            n = 0; sumDt = 0.0; worstDt = 0.0; sumCpu = 0.0; worstCpu = 0.0; slow = 0; lastLog = now
        }
    }
}

internal object SceneHolder {
    @Volatile var scene: BenchScene? = null
    @Volatile var graph: Graph? = null
    @Volatile var listener: BenchListener? = null
}

/**
 * The Mixed Stage (Full Space): one full-size SpatialView holding the bench. Places the bench ahead of the head (S8),
 * then opens the control window through the level gate; routes every pinch / trigger / drag on a holder (bob, tag, ▶ key,
 * planet keys / globe, nameplate, base) to [BenchFlow] through the factory board-input template (D50 / D53: controller ray,
 * hand ray, eye-hand alike; near grabs also through the hand-joint fallback in [BenchScene]).
 */
@Composable
fun BenchScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val snap by graph.flow.snap.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { BenchScene(graph, scope) }
    val listener = remember(graph, scene) { BenchListener(graph.flow, scene).also { scene.listener = it } }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.closeStage = { scope.launch { runCatching { navigator.closeStage() }.onFailure { Log.w(TAG, "closeStage: $it") } } }
        graph.recall = { scene.place("recall") }
        onDispose { graph.window.detach(); graph.recall = {} }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> Log.i(TAG, "LIFECYCLE foreground")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        SceneHolder.listener = listener
        registerSystem<BenchFrameSystem>()
        onDispose {
            unregisterSystem<BenchFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    // springs: re-swept on the main thread (mesh uploads wait on the renderer; never inside the ECS update)
    LaunchedEffect(scene) {
        while (isActive) {
            scene.springTick()
            delay(30)
        }
    }

    // start-up placement diagnostics (D1 / D9 "启动后 1 / 3 / 5 秒打日志")
    LaunchedEffect(Unit) {
        for ((i, s) in listOf(1000L, 2000L, 2000L).withIndex()) {
            delay(s)
            Log.i(TAG, "PLACE t=${listOf(1, 3, 5)[i]}s built=${scene.built} phase=${graph.flow.snap.value.phase} ${scene.placeDiag()}")
        }
    }

    val loc = remember(context, settings.language) { context.localized(settings.language) }
    val lex = remember(loc) { lexOf(loc) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(remember { BenchTargets() }, listener, BoardInputConfig(dragStartM = 0.004f, logTag = TAG)),
        initial = { content, _ ->
            scene.attach(content)
            scene.place("start")
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S0 loading stand-in (design lifecycle "首帧兜底"), removed when the GLBs are in
                    val slab = MeshResource.createBox(Vector3(0.68f, 0.05f, 0.32f), cornerRadius = 0.004f)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.30f, 0.20f, 0.13f, 1f)); setRoughness(0.8f) }
                    slab to mat
                }.getOrNull()
            }
            quick?.let { (s, m) -> scene.showFallback(s, m) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            scene.installCards(resources)
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                Models(
                    load("base"), load("frame"), load("rod"), load("hinge"), BobMat.entries.associateWith { load("bob_" + it.name.lowercase()) },
                    load("plaque"), load("key"), load("selkey"), load("selector"), Planet.entries.associateWith { load("planet_" + it.name.lowercase()) },
                )
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s0hold", false)) { Log.i(TAG, "S0 hold 10 s"); delay(10_000) }
            if (models != null) {
                scene.install(resources, models)
                graph.flow.ready()
            }
        },
        update = { _, _ -> scene.apply(snap, lex) },
    )
}

/** The plate / tag / key / planet strings in the app language. */
internal fun lexOf(c: android.content.Context): Lex {
    val r = c.resources
    return Lex(
        hangers = r.getStringArray(R.array.hangers).toList(),
        sizesRod = r.getStringArray(R.array.sizes_rod).toList(),
        sizesSpring = r.getStringArray(R.array.sizes_spring).toList(),
        ideal = r.getString(R.string.readout_ideal),
        flips = r.getString(R.string.readout_flips),
        since = r.getString(R.string.readout_since),
        swinging = r.getString(R.string.readout_swinging),
        answers = r.getStringArray(R.array.tip_answers).toList(),
        planetLabels = r.getStringArray(R.array.planet_labels).toList(),
        planetNames = r.getStringArray(R.array.planet_names).toList(),
        periodsRow = r.getString(R.string.periods_row),
        stationNames = listOf("", "", ""),
        letGo = r.getString(R.string.readout_letgo),
        timing = r.getString(R.string.readout_timing),
        swingGo = r.getString(R.string.swing_go),
        swingStop = r.getString(R.string.swing_stop),
        lookAhead = r.getString(R.string.look_ahead),
        turnHint = r.getString(R.string.turn_hint),
    )
}

/** What a holder name stands for (one place; the hit-replay unit test uses it too). */
object BenchNames {
    sealed interface Hit
    data class BobHit(val station: Int, val k: Int) : Hit
    data class TagHit(val station: Int) : Hit
    /** The station's ▶ swing key. */
    data class GoHit(val station: Int) : Hit
    data object PlanetPrev : Hit
    data object PlanetNext : Hit
    data object PlanetGlobe : Hit
    data object NameplateHit : Hit
    data object BaseHit : Hit
    data object NoHit : Hit

    fun bob(si: Int, k: Int) = "bob:$si:$k"
    fun tag(si: Int) = "tag:$si"
    fun go(si: Int) = "go:$si"
    const val PLANET_PREV = "planet:prev"
    const val PLANET_NEXT = "planet:next"
    const val PLANET_GLOBE = "planet:globe"
    const val NAMEPLATE = "nameplate"
    const val BASE = "base"

    fun hitOf(name: String?): Hit {
        if (name == null) return NoHit
        val f = name.split(":")
        return when (f[0]) {
            "bob" -> if (f.size == 3) { val s = f[1].toIntOrNull(); val k = f[2].toIntOrNull(); if (s in 0..2 && k in 0..1) BobHit(s!!, k!!) else NoHit } else NoHit
            "tag" -> if (f.size == 2) f[1].toIntOrNull()?.takeIf { it in 0..2 }?.let { TagHit(it) } ?: NoHit else NoHit
            "go" -> if (f.size == 2) f[1].toIntOrNull()?.takeIf { it in 0..2 }?.let { GoHit(it) } ?: NoHit else NoHit
            "planet" -> when (name) { PLANET_PREV -> PlanetPrev; PLANET_NEXT -> PlanetNext; PLANET_GLOBE -> PlanetGlobe; else -> NoHit }
            NAMEPLATE -> if (f.size == 1) NameplateHit else NoHit
            BASE -> if (f.size == 1) BaseHit else NoHit
            else -> NoHit
        }
    }
}

/** Hit entity (or an ancestor) -> the holder name; the base, frame, rods, springs, plates take no hits. */
internal class BenchTargets : TargetResolver<String> by TargetResolver.byName(::targetOf) {
    companion object {
        fun targetOf(name: String): String? = name.takeIf { BenchNames.hitOf(it) != BenchNames.NoHit }

        /** Hit replay (build.md 命中回放): [path] = the hit node's name first, then its parents'. */
        fun resolvePath(path: List<String>): String? = io.embodit.kinetica.ui.bench.input.resolvePath(path, ::targetOf)
    }
}

/**
 * Every resolved press / tap / drag goes to [BenchFlow] (the same calls the debug broadcast makes). Drags arrive in Stage
 * axes and are turned into bench axes by [host] (eye-hand drags scaled by the distance gain); the base drag moves the
 * bench itself (S7). While the hand-joint grab holds something, system events are ignored (one grab at a time).
 */
internal class BenchListener(private val flow: BenchFlow, private val host: BenchHost?) : BoardInputListener<String> {
    private var dragKind: BenchNames.Hit = BenchNames.NoHit
    private var gain = 1f
    /** A system press / drag is running (the hand-joint fallback then stays out). */
    @Volatile var systemBusy = false; private set

    private fun ignored(source: InputSource) = source != InputSource.HAND_JOINTS && host?.directActive == true

    override fun onPress(target: String, source: InputSource) {
        if (ignored(source)) return
        if (source != InputSource.HAND_JOINTS) systemBusy = true
        val h = BenchNames.hitOf(target)
        BoardInputLog.write(TAG, "INPUT press src=${source.label} target=$target")
        if (h is BenchNames.BobHit) flow.pressBob(h.station, h.k)
    }

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        systemBusy = false
        if (ignored(source)) return
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target")
        target?.let { host?.pressed(it) }
        route(flow, target)
    }

    override fun onDragStart(target: String, source: InputSource): Boolean {
        if (ignored(source)) return false
        val h = BenchNames.hitOf(target)
        BoardInputLog.write(TAG, "INPUT drag src=${source.label} target=$target")
        dragKind = h
        gain = host?.gain(target, source) ?: 1f
        val taken = when (h) {
            is BenchNames.BobHit -> { if (flow.holding != h.station to h.k) flow.pressBob(h.station, h.k); true }
            is BenchNames.TagHit -> { flow.tagDragStart(h.station); true }
            BenchNames.BaseHit -> host?.benchDragStart() ?: false
            else -> false
        }
        if (!taken) dragKind = BenchNames.NoHit
        return taken
    }

    override fun onDragMove(totalM: Offset3M) {
        val l = host?.toLocal(totalM) ?: totalM
        when (dragKind) {
            is BenchNames.BobHit -> flow.dragBob(l.x * gain, l.y * gain, l.z * gain)
            is BenchNames.TagHit -> flow.tagDragMove(l.y * gain, l.length * gain)
            BenchNames.BaseHit -> host?.benchDragMove(totalM)
            else -> Unit
        }
    }

    override fun onDragEnd() {
        systemBusy = false
        when (dragKind) {
            is BenchNames.BobHit -> flow.releaseBob()
            is BenchNames.TagHit -> flow.tagDragEnd()
            BenchNames.BaseHit -> host?.benchDragEnd()
            else -> Unit
        }
        dragKind = BenchNames.NoHit
    }

    override fun onDragCancel() {
        systemBusy = false
        when (dragKind) {
            is BenchNames.BobHit -> flow.cancelBob()
            is BenchNames.TagHit -> flow.tagDragEnd()
            BenchNames.BaseHit -> host?.benchDragEnd()
            else -> flow.cancelBob()
        }
        dragKind = BenchNames.NoHit
    }

    /** D50 hover: a hint only, never a state change (the turn hint / stick on the base reads it). */
    override fun onHover(target: String?, source: InputSource) { host?.hover(target, source) }
}

/** Holder name -> the one flow call (taps from the Stage and the debug broadcast). */
internal fun route(flow: BenchFlow, name: String?) {
    when (val h = BenchNames.hitOf(name)) {
        is BenchNames.BobHit -> flow.tapBob(h.station, h.k)
        is BenchNames.TagHit -> flow.tapTag(h.station)
        is BenchNames.GoHit -> flow.tapSwing(h.station)
        BenchNames.PlanetPrev -> flow.planetPrev()
        BenchNames.PlanetNext, BenchNames.PlanetGlobe -> flow.planetNext()
        BenchNames.NameplateHit -> flow.tapNameplate()
        BenchNames.BaseHit, BenchNames.NoHit -> Unit
    }
}
