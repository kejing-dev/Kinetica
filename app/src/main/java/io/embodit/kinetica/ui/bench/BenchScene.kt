package io.embodit.kinetica.ui.bench

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.kinetica.domain.BenchConfig
import io.embodit.kinetica.domain.BenchPose
import io.embodit.kinetica.domain.BenchSnap
import io.embodit.kinetica.domain.BobMat
import io.embodit.kinetica.domain.Hanger
import io.embodit.kinetica.domain.Layout
import io.embodit.kinetica.domain.PlaceWait
import io.embodit.kinetica.domain.Planet
import io.embodit.kinetica.domain.StagePose
import io.embodit.kinetica.domain.Vec3
import io.embodit.kinetica.platform.Controllers
import io.embodit.kinetica.platform.DirectGrab
import io.embodit.kinetica.platform.Graph
import io.embodit.kinetica.platform.Hands
import io.embodit.kinetica.platform.Head
import io.embodit.kinetica.platform.HeadTracker
import io.embodit.kinetica.platform.TAG
import io.embodit.kinetica.ui.Lex
import io.embodit.kinetica.ui.bench.input.GazeTargets
import io.embodit.kinetica.ui.bench.input.InputSource
import io.embodit.kinetica.ui.bench.input.Offset3M
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.acos

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun v3(v: Vec3) = Vector3(v.x.toFloat(), v.y.toFloat(), v.z.toFloat())

/** The GLB templates (tools/build_bench.py, tools/build_planets.py). */
internal class Models(
    val base: Entity, val frame: Entity, val rod: Entity, val hinge: Entity, val bobs: Map<BobMat, Entity>,
    val plaque: Entity, val key: Entity, val selKey: Entity, val selector: Entity, val planets: Map<Planet, Entity>,
)

/** SDK resources made at start (on Dispatchers.IO: creating a resource waits on the renderer). */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()
    lateinit var bobShape: ShapeResource; private set
    lateinit var tagShape: ShapeResource; private set
    lateinit var keyShape: ShapeResource; private set
    lateinit var selKeyShape: ShapeResource; private set
    lateinit var globeShape: ShapeResource; private set
    lateinit var plaqueShape: ShapeResource; private set
    lateinit var baseShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set
    lateinit var tagPlane: MeshResource; private set
    lateinit var platePlane: MeshResource; private set
    lateinit var nameplatePlane: MeshResource; private set
    lateinit var keyPlane: MeshResource; private set
    lateinit var arrowPlane: MeshResource; private set
    lateinit var labelPlane: MeshResource; private set
    lateinit var lookPlane: MeshResource; private set
    lateinit var hintPlane: MeshResource; private set
    lateinit var ringPlane: MeshResource; private set
    lateinit var dash: MeshResource; private set
    lateinit var dot: MeshResource; private set
    lateinit var dotMat: Material; private set
    lateinit var dashMat: UnlitMaterial; private set
    lateinit var ringMat: UnlitMaterial; private set
    lateinit var rimMat: UnlitMaterial; private set
    lateinit var hoverMat: UnlitMaterial; private set
    lateinit var steel: PhysicallyBasedMaterial; private set

    fun <T : Resource> keep(r: T): T { synchronized(all) { all += r }; return r }
    fun close() { synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() } }

    companion object {
        fun unlit(tex: TextureResource, transparent: Boolean, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (transparent) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                setBaseColorTexture(tex)
                if (transparent) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.BACK)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            val io = Dispatchers.IO
            fun <T : Resource> T.k(): T = r.keep(this)
            // gate:allow box-helper collision shapes only (ShapeResource, invisible click areas), never a visible mesh
            fun box(x: Double, y: Double, z: Double) = ShapeResource.createBox(Vector3(x.toFloat(), y.toFloat(), z.toFloat()))
            val ringTex = async(io) { TextureResource.create(BenchTextures.ring()).k() }
            val rimTex = async(io) { TextureResource.create(BenchTextures.ring(android.graphics.Color.WHITE, inner = 0.34f, outer = 0.40f)).k() }
            listOf(
                async(io) { r.bobShape = ShapeResource.createSphere(BenchConfig.BOB_HIT_D.toFloat() / 2).k() },
                async(io) { r.tagShape = box(Layout.TAG_HIT_W, Layout.TAG_HIT_H, Layout.TAG_HIT_D).k() },
                // key collider sits behind the face (the cap is in the key's -Z)
                async(io) { r.keyShape = box(Layout.KEY_HIT_W, Layout.KEY_HIT_H, Layout.KEY_HIT_D).offsetByTranslation(Vector3(0f, 0f, -0.010f)).k() },
                async(io) { r.selKeyShape = box(0.05, 0.05, 0.03).offsetByTranslation(Vector3(0f, 0f, -0.010f)).k() },
                async(io) { r.globeShape = ShapeResource.createSphere((Layout.SEL_GLOBE_D / 2 + 0.004).toFloat()).k() },
                async(io) { r.plaqueShape = box(Layout.NAMEPLATE_W + 0.004, 0.046, 0.02).offsetByTranslation(Vector3(0f, 0f, -0.006f)).k() },
                // the base itself (S7): only the walnut body, not the bobs / tags / plates / keys / plaque / selector
                async(io) { r.baseShape = box(0.80, 0.06, 0.32).offsetByTranslation(Vector3(0f, -0.28f, 0f)).k() },
                async(io) { r.physics = PhysicsMaterialResource().k() },
                async(io) { r.tagPlane = MeshResource.createPlane(Layout.TAG_W.toFloat(), Layout.TAG_H.toFloat(), 0f).k() },
                async(io) { r.platePlane = MeshResource.createPlane(Layout.PLATE_W.toFloat(), Layout.PLATE_H.toFloat(), 0f).k() },
                async(io) { r.nameplatePlane = MeshResource.createPlane(Layout.NAMEPLATE_W.toFloat(), Layout.NAMEPLATE_H.toFloat(), 0f).k() },
                async(io) { r.keyPlane = MeshResource.createPlane(Layout.KEY_W.toFloat(), Layout.KEY_H.toFloat(), 0f).k() },
                async(io) { r.arrowPlane = MeshResource.createPlane(0.032f, 0.032f, 0f).k() },
                async(io) { r.labelPlane = MeshResource.createPlane(Layout.SEL_LABEL_W.toFloat(), Layout.SEL_LABEL_H.toFloat(), 0f).k() },
                async(io) { r.lookPlane = MeshResource.createPlane(LOOK_W, LOOK_H, 0f).k() },
                async(io) { r.hintPlane = MeshResource.createPlane(HINT_W, HINT_H, 0f).k() },
                async(io) { r.ringPlane = MeshResource.createPlane(0.09f, 0.09f, 0f).k() },
                // gate:allow geometry snap-frame dash: abstract drop-target marker (design spatialLayout.snapFrame), not an object
                async(io) { r.dash = MeshResource.createBox(Vector3(0.03f, 0.01f, 0.003f)).k() },
                // gate:allow geometry trail dot: abstract motion trace (design spatialLayout.trail), not an object
                async(io) { r.dot = MeshResource.createSphere(0.003f).k() },
                async(io) { r.dotMat = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 0.914f, 0.77f, 1f)); setApplyToneMapping(false) }.k() },
                async(io) { r.dashMat = UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 0.93f, 0.80f, 1f)); setApplyToneMapping(false) }.k() },
                async(io) {
                    r.steel = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                        setBaseColor(Color4(0.48f, 0.52f, 0.57f, 1f)); setMetallic(1f); setRoughness(0.3f); setCullingMode(MaterialCullingMode.NONE)
                    }.k()
                },
            ).awaitAll()
            r.ringMat = unlit(ringTex.await(), true, 0.8f).k()
            r.hoverMat = unlit(ringTex.await(), true, 0.35f).k()
            r.rimMat = unlit(rimTex.await(), true, 0.95f).k()
            r
        }

        const val LOOK_W = 0.44f
        const val LOOK_H = 0.08f
        const val HINT_W = 0.20f
        const val HINT_H = 0.03f
    }
}

/** What the input listener needs from the Stage (null in unit tests: drags are then already in bench axes). */
internal interface BenchHost {
    /** A Stage-space drag (metres) in bench axes. */
    fun toLocal(d: Offset3M): Offset3M
    /** Eye-hand drag gain for [target] (1..3; 1 for the other sources). */
    fun gain(target: String, source: InputSource): Float
    fun benchDragStart(): Boolean
    fun benchDragMove(stage: Offset3M)
    fun benchDragEnd()
    fun hover(name: String?, source: InputSource)
    fun pressed(name: String)
    val directActive: Boolean
}

/**
 * The Stage side (Full Space, design container STAGE_MIXED): a root aligned with the Stage, the bench under it at
 * [pose] (placed from the head's heading + eye height, S8; moved by grabbing the base, S7), the「看正前方」card and the
 * snap frame. The physics lives in [io.embodit.kinetica.domain.BenchFlow]; [onFrame] (ECS frame system) copies body
 * positions onto the entities and runs placement, the stick turn, the near-head fade and the direct-grab fallback;
 * [springTick] (main thread) re-sweeps the spring meshes. Hit holders (board-input template, D50): "bob:<s>:<k>",
 * "tag:<s>", "go:<s>" (▶ keys), "planet:prev|next|globe", "nameplate", "base". No app-drawn controller ray (D41 更正).
 */
internal class BenchScene(private val graph: Graph, private val scope: CoroutineScope) : BenchHost {
    private val flow get() = graph.flow
    val head = HeadTracker()
    val controllers = Controllers()
    val hands = Hands()
    private var stageRoot: Entity? = null
    /** The bench (its local frame = the old volume frame). */
    val root: Entity = Entity().apply { setName("root") }
    private var res: SceneResources? = null
    private var models: Models? = null
    private var fallback: Entity? = null
    var built = false; private set
    @Volatile var pose: BenchPose? = null; private set

    private class StationE(
        val group: Entity,
        val rods: List<Entity>,
        val hinge: Entity,
        val bobs: List<Entity>,
        val bobModels: List<Map<BobMat, Entity>>,
        var spring: ModelEntity? = null,
        var springMesh: MeshResource? = null,
        val trail: List<ModelEntity>,
    ) {
        var trailNext = 0
        var trailT = 0L
        val trailAge = LongArray(trail.size) { -1L }
        var tweenFrom: List<Vec3>? = null
        var tweenAt = 0L
        var version = -1
    }

    private val stations = ArrayList<StationE>()
    private val tagMats = ArrayList<UnlitMaterial>()
    private val plateMats = ArrayList<UnlitMaterial>()
    private val keys = ArrayList<Entity>()
    private val keyMats = ArrayList<UnlitMaterial>()
    private val keyPressAt = LongArray(3) { -1L }
    private var nameplateMat: UnlitMaterial? = null
    private var labelMat: UnlitMaterial? = null
    private var lookMat: UnlitMaterial? = null
    private var hintMat: UnlitMaterial? = null
    private var lookCard: Entity? = null
    private var turnHint: Entity? = null
    private var snapFrame: Entity? = null
    private val dashes = ArrayList<Entity>()
    private var globe: Entity? = null
    private val globeModels = HashMap<Planet, Entity>()
    private val selKeys = HashMap<String, Entity>()
    private val selPressAt = HashMap<String, Long>()
    private var coachRing: ModelEntity? = null
    private var rim: ModelEntity? = null
    private var hoverRing: ModelEntity? = null
    private val springMeshes = Array(3) { SpringMesh() }
    private val springIdx: List<Int> = springMeshes[0].indices.toList()

    private var lex: Lex = Lex.EN
    private var snap = BenchSnap()
    private val shown = HashMap<String, String>()
    private val busy = HashSet<String>()
    private val owned = HashMap<String, TextureResource>()
    @Volatile private var hoverName: String? = null
    @Volatile private var hoverSrc = InputSource.UNKNOWN
    @Volatile private var hoverAt = 0L
    private val startMs = SystemClock.uptimeMillis()

    // placement (S8), bench drag (S7)
    private val placeWait = PlaceWait()
    private var animFrom: BenchPose? = null
    private var animTo: BenchPose? = null
    private var animAt = 0L
    private var placeWhy = ""
    private var dragPose0: BenchPose? = null
    private var dragFrame: BenchPose? = null
    private var dragYaw = 0.0
    private val snapper = io.embodit.kinetica.domain.BenchSnapper()
    private var faded = false
    private var lastHead: Head? = null
    private var lastPlaceLog = 0L

    // direct grab (hand joints)
    private val grabs = arrayOf(DirectGrab(), DirectGrab())
    private val closed = BooleanArray(2)
    var listener: BenchListener? = null

    // ---------------------------------------------------------------- lifecycle

    /** Called once from SpatialView.initial: aligns our root with the Stage (a full-size SpatialView is not at its origin). */
    fun attach(content: SpatialViewContent) {
        val sr = Entity().apply { setName("stageRoot") }
        content.addEntity(sr)
        val offset = sr.convertPositionFrom(Vector3.ZERO, null)
        val rot = sr.convertRotationFrom(Quat.identity(), null)
        sr.transform().apply { setPosition(offset); setQuaternion(rot) }
        stageRoot = sr
        root.enabled = false
        sr.addChild(root)
        Log.i(TAG, "STAGE root offset=(${offset.x}, ${offset.y}, ${offset.z})")
        head.start(); controllers.start(); hands.start()
    }

    fun release() { runCatching { head.stop() }; runCatching { controllers.stop() }; runCatching { hands.stop() } }

    /** S8: cold start / [叫回面前]: wait for a level, still head, then move the bench to the default pose. */
    fun place(why: String) {
        placeWhy = why
        placeWait.ask(why, SystemClock.uptimeMillis())
        flow.setPlacing(true)
        Log.i(TAG, "PLACE ask why=$why")
    }

    /** S0 stand-in (design lifecycle 首帧兜底): one plain slab where the base goes, until the GLBs are in. */
    fun showFallback(slab: MeshResource, material: Material) {
        if (built || fallback != null) return
        val e = ModelEntity(slab, material).apply { setName("fallbackBase") }
        e.transform().setPosition(Vector3(0f, -0.275f, 0f))
        root.addChild(e)
        fallback = e
        Log.i(TAG, "SCENE fallback shown S0")
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    private fun place(e: Entity, p: Vec3) = e.transform().setPosition(v3(p))

    private fun rotX(deg: Double) = Quat(Vector3(1f, 0f, 0f), Math.toRadians(deg).toFloat())
    private fun rotY(rad: Double) = Quat(Vector3(0f, 1f, 0f), rad.toFloat())

    private fun texMat(r: SceneResources): UnlitMaterial =
        UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(Color4(1f, 1f, 1f, 1f)); setApplyToneMapping(false) }.also { r.keep(it) }

    fun installCards(r: SceneResources) {
        val sr = stageRoot ?: return
        if (lookCard != null) return
        res = r
        lookMat = texMat(r)
        lookCard = ModelEntity(r.lookPlane, lookMat!!).apply { setName("lookCard"); enabled = false; sr.addChild(this) }
        val frame = Entity().apply { setName("snapFrame"); enabled = false }
        sr.addChild(frame)
        // dashes along the base footprint 0.80 x 0.32, 1 cm tall, at the base bottom (design spatialLayout.snapFrame)
        fun run(ax: Double, az: Double, bx: Double, bz: Double) {
            val len = Vec3(bx - ax, 0.0, bz - az).len(); val n = (len / 0.05).toInt()
            for (i in 0 until n) {
                val t = (i + 0.5) / n
                val d = ModelEntity(r.dash, r.dashMat).apply { setName("dash") }
                d.transform().setPosition(Vector3((ax + (bx - ax) * t).toFloat(), -0.295f, (az + (bz - az) * t).toFloat()))
                if (kotlin.math.abs(bx - ax) < 1e-6) d.transform().setQuaternion(rotY(PI / 2))
                frame.addChild(d); dashes += d
            }
        }
        run(-0.40, 0.16, 0.40, 0.16); run(-0.40, -0.16, 0.40, -0.16); run(-0.40, -0.16, -0.40, 0.16); run(0.40, -0.16, 0.40, 0.16)
        snapFrame = frame
        refreshTextures(force = true)
    }

    fun install(r: SceneResources, m: Models) {
        if (built) return
        res = r; models = m
        val t0 = SystemClock.elapsedRealtime()
        // the base is the S7 handle: collider over the walnut body only, system hover lights the base
        val baseHolder = Entity().apply { setName(BenchNames.BASE) }
        baseHolder.addChild(clone(m.base).apply { setName("baseModel") })
        GazeTargets.install(baseHolder, r.baseShape, r.physics)
        root.addChild(baseHolder)
        root.addChild(clone(m.frame).apply { setName("frame") })
        for (si in 0..2) {
            val g = Entity().apply { setName("station:$si") }
            root.addChild(g)
            val rods = (0..1).map { k -> clone(m.rod).apply { setName("rod:$si:$k"); g.addChild(this) } }
            val hinge = clone(m.hinge).apply { setName("hinge:$si"); g.addChild(this) }
            val bobModels = ArrayList<Map<BobMat, Entity>>()
            val bobs = (0..1).map { k ->
                val holder = Entity().apply { setName("bob:$si:$k") }
                val ms = BobMat.entries.associateWith { mat -> clone(m.bobs[mat]!!).apply { setName("bobm:$si:$k:${mat.name}"); holder.addChild(this) } }
                bobModels += ms
                GazeTargets.install(holder, r.bobShape, r.physics)
                g.addChild(holder)
                holder
            }
            val trail = (0 until 12).map { ModelEntity(r.dot, r.dotMat).apply { setName("trail:$si"); enabled = false; root.addChild(this) } }
            stations += StationE(g, rods, hinge, bobs, bobModels, trail = trail)
            // tag
            val tm = texMat(r); tagMats += tm
            val tag = ModelEntity(r.tagPlane, tm).apply { setName("tag:$si") }
            place(tag, Layout.tagCentre(si)); tag.transform().setQuaternion(rotX(-Layout.TAG_TILT_DEG))
            GazeTargets.install(tag, r.tagShape, r.physics)
            root.addChild(tag)
            // readout plate (no hits)
            val pm = texMat(r); plateMats += pm
            val plate = ModelEntity(r.platePlane, pm).apply { setName("plate:$si") }
            place(plate, Layout.plateCentre(si)); plate.transform().setQuaternion(rotX(-Layout.BEVEL_DEG))
            root.addChild(plate)
            // ▶ swing key (D43): holder at the face centre, leaning back 50°; cap model + canvas face
            val key = Entity().apply { setName(BenchNames.go(si)) }
            place(key, Layout.keyCentre(si)); key.transform().setQuaternion(rotX(-Layout.KEY_TILT_DEG))
            key.addChild(clone(m.key).apply { setName("keycap:$si") })
            val km = texMat(r); keyMats += km
            key.addChild(ModelEntity(r.keyPlane, km).apply { setName("keyface:$si"); transform().setPosition(Vector3(0f, 0f, 0.0008f)) })
            GazeTargets.install(key, r.keyShape, r.physics)
            root.addChild(key); keys += key
        }
        // nameplate plaque (tap = reopen the control window)
        val np = Entity().apply { setName(BenchNames.NAMEPLATE) }
        place(np, Layout.NAMEPLATE)
        np.transform().setQuaternion(rotY(Math.toRadians(Layout.NAMEPLATE_YAW_DEG)).times(rotX(-Layout.NAMEPLATE_TILT_DEG)))
        np.addChild(clone(m.plaque).apply { setName("plaqueModel") })
        nameplateMat = texMat(r)
        np.addChild(ModelEntity(r.nameplatePlane, nameplateMat!!).apply { setName("nameplateFace"); transform().setPosition(Vector3(0f, 0f, 0.001f)) })
        GazeTargets.install(np, r.plaqueShape, r.physics)
        root.addChild(np)
        // planet selector (D13): shelf, globe (four planets, the current one shown), ◀ ▶ keys, label
        val sel = Entity().apply { setName("selector") }
        place(sel, Layout.SEL); sel.transform().setQuaternion(rotY(Math.toRadians(Layout.SEL_YAW_DEG)))
        root.addChild(sel)
        sel.addChild(clone(m.selector).apply { setName("selectorModel") })
        val gl = Entity().apply { setName(BenchNames.PLANET_GLOBE) }
        place(gl, Layout.SEL_GLOBE)
        for ((p, e) in m.planets) {
            val c = clone(e).apply { setName("planetm:${p.name}") }
            val d = Layout.SEL_GLOBE_D.toFloat(); c.transform().setScaleVector(Vector3(d, d, d))
            gl.addChild(c); globeModels[p] = c
        }
        GazeTargets.install(gl, r.globeShape, r.physics)
        sel.addChild(gl); globe = gl
        for (next in listOf(false, true)) {
            val name = if (next) BenchNames.PLANET_NEXT else BenchNames.PLANET_PREV
            val k = Entity().apply { setName(name) }
            place(k, if (next) Layout.SEL_NEXT else Layout.SEL_PREV); k.transform().setQuaternion(rotX(-Layout.SEL_KEY_TILT_DEG))
            k.addChild(clone(m.selKey).apply { setName("selkey:$next") })
            val am = texMat(r)
            k.addChild(ModelEntity(r.arrowPlane, am).apply { setName("arrow:$next"); transform().setPosition(Vector3(0f, 0f, 0.0008f)) })
            texture("arrow$next", "arrow|${BenchTextures.testF}", am) { BenchTextures.arrow(next) }
            GazeTargets.install(k, r.selKeyShape, r.physics)
            sel.addChild(k); selKeys[name] = k
        }
        labelMat = texMat(r)
        val label = ModelEntity(r.labelPlane, labelMat!!).apply { setName("planetLabel") }
        place(label, Layout.SEL_LABEL + Vec3(0.0, 0.0, 0.001)); label.transform().setQuaternion(rotX(-Layout.SEL_LABEL_TILT_DEG))
        sel.addChild(label)
        // turn hint (shown while a controller ray is on the base or the bench is held)
        hintMat = texMat(r)
        turnHint = ModelEntity(r.hintPlane, hintMat!!).apply { setName("turnHint"); enabled = false }
        place(turnHint!!, Vec3(-0.22, -0.195, 0.165)); turnHint!!.transform().setQuaternion(rotX(-40.0))
        root.addChild(turnHint!!)
        // marks
        coachRing = ModelEntity(r.ringPlane, r.ringMat).apply { setName("coachRing"); enabled = false; root.addChild(this) }
        rim = ModelEntity(r.ringPlane, r.rimMat).apply { setName("heldRim"); enabled = false; root.addChild(this) }
        hoverRing = ModelEntity(r.ringPlane, r.hoverMat).apply { setName("hoverRing"); enabled = false; root.addChild(this) }
        buildLight()
        fallback?.removeFromParent(); fallback = null
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms")
        refreshTextures(force = true)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (not over-exposed)
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.86f, 1f), 0.8f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (25 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-50 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    /** Compose hands over the snapshot and the strings of the app language. */
    fun apply(next: BenchSnap, l: Lex) {
        val langChanged = l != lex
        snap = next; lex = l
        refreshTextures(force = langChanged)
    }

    fun refreshAll() { shown.clear(); refreshTextures(force = true) }

    // ---------------------------------------------------------------- textures (only when the text changes, <= 4 Hz)

    private var lastTexMs = 0L

    private fun refreshTextures(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastTexMs < 250) return
        lastTexMs = now
        val f = BenchTextures.testF
        lookMat?.let { m -> texture("look", "${lex.lookAhead}|$f", m) { BenchTextures.card(lex.lookAhead, 1024, 186, SceneResources.LOOK_H.toDouble(), 2.6f, "look") } }
        if (!built) return
        val s = snap
        if (s.stations.size < 3) return
        val coach2 = s.coach == 2
        val glowIdx = if (coach2) (((now - startMs) / 600) % 3).toInt() else -1
        for (i in 0..2) {
            val st = s.stations[i]
            val (a, b) = lex.tagLines(st)
            val glow = i == glowIdx
            texture("tag$i", "$a|$b|$glow|$f", tagMats[i]) { BenchTextures.tag(a, b, glow, i) }
            val big = lex.big(st.readout); val small = lex.small(st.readout)
            texture("plate$i", "$big|$small|$f", plateMats[i]) { BenchTextures.plate(big, small, i) }
            val word = if (st.moving) lex.swingStop else lex.swingGo
            texture("key$i", "$word|${st.moving}|$f", keyMats[i]) { BenchTextures.key(word, st.moving, i) }
        }
        nameplateMat?.let { m -> texture("nameplate", "KINETICA|$f", m) { BenchTextures.nameplate("KINETICA") } }
        labelMat?.let { m ->
            val p = s.planet
            val name = lex.planetLabels[p.ordinal]; val g = lex.gLine(p)
            texture("label", "$name|$g|$f", m) { BenchTextures.planetLabel(name, g, p.ordinal) }
        }
        hintMat?.let { m -> texture("hint", "${lex.turnHint}|$f", m) { BenchTextures.card(lex.turnHint, 1024, 154, SceneResources.HINT_H.toDouble(), 1.2f, "hint") } }
    }

    private fun texture(slot: String, key: String, mat: UnlitMaterial, draw: () -> Bitmap) {
        if (shown[slot] == key || slot in busy) return
        busy += slot
        scope.launch {
            val tex = withContext(Dispatchers.IO) { runCatching { TextureResource.create(draw()) }.onFailure { Log.w(TAG, "TEX $slot failed: $it") }.getOrNull() }
            if (tex != null) {
                mat.setBaseColorTexture(tex)
                owned.put(slot, tex)?.let { old -> runCatching { old.close() } }
                shown[slot] = key
                Log.i(TAG, "TEX $slot \"$key\"")
            }
            busy -= slot
        }
    }

    // ---------------------------------------------------------------- BenchHost (input)

    override fun toLocal(d: Offset3M): Offset3M {
        val p = pose ?: return d
        val l = p.dirToLocal(Vec3(d.x.toDouble(), d.y.toDouble(), d.z.toDouble()))
        return Offset3M(l.x.toFloat(), l.y.toFloat(), l.z.toFloat())
    }

    /** Stage-space direction of a bench-axes drag (debug commands speak bench axes). */
    fun toStage(d: Offset3M): Offset3M {
        val p = pose ?: return d
        val s = p.dirToStage(Vec3(d.x.toDouble(), d.y.toDouble(), d.z.toDouble()))
        return Offset3M(s.x.toFloat(), s.y.toFloat(), s.z.toFloat())
    }

    /** Eye-hand (design input.eyeHand): drag = pinch-point travel x clamp(eye→target / eye→hand, 1, 3); gaze plays no part. */
    override fun gain(target: String, source: InputSource): Float {
        if (source != InputSource.EYE_HAND) return 1f
        val h = lastHead ?: return 1f
        val p = pose ?: return 1f
        val local = when (val hit = BenchNames.hitOf(target)) {
            is BenchNames.BobHit -> synchronized(flow.lock) { flow.bench.stations[hit.station].bodies.getOrNull(hit.k)?.p?.copy() }
            is BenchNames.TagHit -> Layout.tagCentre(hit.station)
            else -> null
        } ?: return 1f
        val (l, r) = hands.samples()
        val hand = listOfNotNull(l, r).minByOrNull { it.pinchGap }?.pinchPoint ?: return 1f
        val g = (h.eye.dist(p.toStage(local)) / maxOf(0.05, h.eye.dist(hand))).coerceIn(1.0, 3.0).toFloat()
        Log.i(TAG, "INPUT eye-hand gain=%.2f".format(g))
        return g
    }

    override fun benchDragStart(): Boolean {
        val p = pose ?: return false
        if (flow.snap.value.phase == io.embodit.kinetica.domain.Phase.LOADING || placeWait.waiting) return false
        dragPose0 = p; dragYaw = 0.0
        val h = lastHead
        dragFrame = if (h != null) StagePose.defaultPose(h.eye, h.forward) else p
        snapper.reset()
        tweenAllFromShown()
        flow.benchGrab()
        dragFrame?.let { f -> snapFrame?.let { sf -> sf.enabled = true; sf.transform().setPosition(v3(f.origin)); sf.transform().setQuaternion(rotY(f.yaw)) } }
        Log.i(TAG, "BENCH drag start pose=${p.origin} yaw=%.1f frame=${dragFrame?.origin}".format(Math.toDegrees(p.yaw)))
        return true
    }

    override fun benchDragMove(stage: Offset3M) {
        val p0 = dragPose0 ?: return
        val eye = lastHead?.eye ?: (p0.origin + Vec3(0.0, StagePose.DROP_M, 0.0))
        val o = StagePose.clampOrigin(p0.origin + Vec3(stage.x.toDouble(), stage.y.toDouble(), stage.z.toDouble()), eye)
        val free = BenchPose(o, p0.yaw + dragYaw)
        val f = dragFrame ?: free
        val was = snapper.snapped
        setPose(snapper.step(free, f))
        if (snapper.snapped != was) Log.i(TAG, "BENCH snap=${snapper.snapped}")
    }

    override fun benchDragEnd() {
        if (dragPose0 == null) return
        dragPose0 = null
        snapFrame?.enabled = false
        flow.benchRelease()
        Log.i(TAG, "BENCH placed at ${pose?.origin} yaw=%.1f snapped=${snapper.snapped}".format(Math.toDegrees(pose?.yaw ?: 0.0)))
    }

    override fun hover(name: String?, source: InputSource) { hoverAt = SystemClock.uptimeMillis(); hoverName = name; hoverSrc = source }

    override fun pressed(name: String) {
        val now = SystemClock.uptimeMillis()
        when (val h = BenchNames.hitOf(name)) {
            is BenchNames.GoHit -> keyPressAt[h.station] = now
            BenchNames.PlanetPrev -> selPressAt[BenchNames.PLANET_PREV] = now
            BenchNames.PlanetNext -> selPressAt[BenchNames.PLANET_NEXT] = now
            else -> Unit
        }
    }

    override val directActive: Boolean get() = grabs.any { it.active != null }

    /** Debug: turn the bench by [deg] about its own vertical axis (same path as the stick). */
    fun turn(deg: Double) { pose?.let { setPose(it.copy(yaw = it.yaw + Math.toRadians(deg))) } }

    private fun setPose(p: BenchPose) {
        pose = p
        root.transform().setPosition(v3(p.origin))
        root.transform().setQuaternion(rotY(p.yaw))
    }

    private fun tweenAllFromShown() {
        val now = SystemClock.uptimeMillis()
        for (e in stations) { e.tweenFrom = e.bobs.map { b -> val q = b.transform().position; Vec3(q.x.toDouble(), q.y.toDouble(), q.z.toDouble()) }; e.tweenAt = now }
    }

    // ---------------------------------------------------------------- per frame

    private fun ease(t: Float): Float { val u = t.coerceIn(0f, 1f); return u * u * (3 - 2 * u) }

    fun onFrame(dt: Float) {
        val now = SystemClock.uptimeMillis()
        val h = head.head()
        if (h != null) lastHead = h
        graph.window.stepFrame(h?.pitchDeg?.toFloat())
        placementFrame(now, h)
        lookCardFrame(h)
        if (pose != null) {
            stickFrame(dt)
            fadeFrame(h)
            directGrabFrame()
        }
        refreshTextures()
        if (built) benchFrame(now)
    }

    private fun placementFrame(now: Long, h: Head?) {
        if (placeWait.waiting) {
            if (placeWait.step(now, h?.pitchDeg, h?.yaw)) {
                val eye = h?.eye ?: lastHead?.eye ?: Vec3(0.0, 1.2, 0.0)
                val fwd = h?.forward ?: lastHead?.forward ?: Vec3(0.0, 0.0, -1.0)
                val target = StagePose.defaultPose(eye, fwd)
                animFrom = pose ?: target.copy(origin = target.origin + Vec3(0.0, -0.08, 0.0))
                animTo = target; animAt = now
                root.enabled = true
                Log.i(TAG, "PLACE go why=$placeWhy eye=$eye pitch=${h?.pitchDeg?.let { "%.1f".format(it) }} -> origin=${target.origin} yaw=%.1f".format(Math.toDegrees(target.yaw)))
            } else if (now - lastPlaceLog > 1000) { lastPlaceLog = now; Log.i(TAG, "PLACE wait head=${h != null} pitch=${h?.pitchDeg?.let { "%.1f".format(it) }}") }
        }
        val to = animTo ?: return
        val from = animFrom ?: to
        val u = ((now - animAt) / 300f).coerceIn(0f, 1f)
        val k = 1 - (1 - u) * (1 - u) // ease-out
        val dy = to.yaw - from.yaw
        val turn = Math.atan2(kotlin.math.sin(dy), kotlin.math.cos(dy))
        setPose(BenchPose(from.origin + (to.origin - from.origin) * k.toDouble(), from.yaw + turn * k))
        if (u >= 1f) {
            animTo = null; animFrom = null
            flow.setPlacing(false)
            graph.window.request(placeWhy)
            Log.i(TAG, "PLACE done why=$placeWhy origin=${to.origin}")
        }
    }

    private fun lookCardFrame(h: Head?) {
        val card = lookCard ?: return
        val want = placeWait.waiting || graph.window.levelHint
        card.enabled = want && h != null
        if (!card.enabled || h == null) return
        val f = StagePose.flat(h.forward) ?: return
        card.transform().setPosition(v3(Vec3(h.eye.x + f.x, h.eye.y - 0.05, h.eye.z + f.z)))
        card.transform().setQuaternion(rotY(StagePose.yawFacing(f)))
    }

    private fun stickFrame(dt: Float) {
        val now = SystemClock.uptimeMillis()
        val onBase = hoverName == BenchNames.BASE && hoverSrc == InputSource.CONTROLLER && now - hoverAt < 300
        val dragging = dragPose0 != null
        turnHint?.enabled = built && (onBase || dragging)
        if (!onBase && !dragging) return
        val turn = StagePose.stickTurn(controllers.stickX.toDouble(), dt.toDouble())
        if (turn == 0.0) return
        if (dragging) { dragYaw += turn; pose?.let { p -> val p0 = dragPose0!!; setPose(snapper.step(BenchPose(p.origin, p0.yaw + dragYaw), dragFrame ?: p)) } }
        else pose?.let { setPose(it.copy(yaw = it.yaw + turn)) }
    }

    private fun fadeFrame(h: Head?) {
        val p = pose ?: return
        val inside = h != null && StagePose.headInside(p, h.eye)
        if (inside == faded) return
        faded = inside
        // D14: 40 % while the head is in the bench; the component is removed again (never left at 1.0, lesson 2026-10-29)
        if (inside) root.components.set(OpacityControllerComponent(StagePose.NEAR_OPACITY)) else root.components.remove(OpacityControllerComponent::class.java)
        Log.i(TAG, "FADE head inside=$inside")
    }

    /** Design input.direct: hand joints grab a bob / tag / the base when the system pointer did not take the near pinch. */
    private fun directGrabFrame() {
        val l = listener ?: return
        val p = pose ?: return
        val (left, right) = hands.samples()
        listOf(left, right).forEachIndexed { i, s ->
            val c = if (s == null) false else if (closed[i]) s.pinchGap < 0.03 || s.fist else s.pinchGap < DirectGrab.PINCH_ON_M || s.fist
            closed[i] = c
            val point = s?.grabPoint
            val target = point?.let { targetAt(p.toLocal(it)) }
            when (val a = grabs[i].step(c, point, target, l.systemBusy || grabs[1 - i].active != null)) {
                is DirectGrab.Start -> {
                    Log.i(TAG, "DIRECT grab hand=$i target=${a.target}")
                    l.onPress(a.target, InputSource.HAND_JOINTS)
                    if (!l.onDragStart(a.target, InputSource.HAND_JOINTS)) grabs[i].step(false, null, null, false)
                }
                is DirectGrab.Move -> l.onDragMove(Offset3M(a.total.x.toFloat(), a.total.y.toFloat(), a.total.z.toFloat()))
                DirectGrab.End -> { Log.i(TAG, "DIRECT release hand=$i"); l.onDragEnd() }
                null -> Unit
            }
        }
    }

    /** What a grab point (bench space) is inside: a bob, a tag or the base. */
    private fun targetAt(q: Vec3): String? {
        synchronized(flow.lock) {
            for (s in flow.bench.stations) s.bodies.forEachIndexed { k, b -> if (q.dist(b.p) < BenchConfig.BOB_HIT_D / 2 + 0.01) return BenchNames.bob(s.index, k) }
        }
        for (i in 0..2) { val t = Layout.tagCentre(i); if (kotlin.math.abs(q.x - t.x) < 0.05 && kotlin.math.abs(q.y - t.y) < 0.04 && kotlin.math.abs(q.z - t.z) < 0.03) return BenchNames.tag(i) }
        if (kotlin.math.abs(q.x) < 0.41 && q.y > -0.32 && q.y < -0.24 && kotlin.math.abs(q.z) < 0.17) return BenchNames.BASE
        return null
    }

    private fun benchFrame(now: Long) {
        val freeze = flow.animFreezeMs
        synchronized(flow.lock) {
            val bench = flow.bench
            for (st in bench.stations) {
                val e = stations[st.index]
                // S6 tween: the displayed bobs ease from where they were over 150 ms
                if (st.version != e.version) {
                    if (e.version >= 0 && now - flow.resizeAt[st.index] < 100) { e.tweenFrom = e.bobs.map { b -> Vec3(b.transform().position.x.toDouble(), b.transform().position.y.toDouble(), b.transform().position.z.toDouble()) }; e.tweenAt = now }
                    e.version = st.version
                }
                // S5: the hanger lifts 3 cm in 150 ms, then drops back in 150 ms
                val swapT = (freeze ?: (now - flow.swapAt[st.index])).toFloat()
                val lift = if (flow.swapAt[st.index] >= 0 && swapT < 300f) 0.03f * (if (swapT < 150f) ease(swapT / 150f) else 1f - ease((swapT - 150f) / 150f)) else 0f
                e.group.transform().setPosition(Vector3(0f, lift, 0f))
                val tw = e.tweenFrom?.let { from -> val u = ((now - e.tweenAt) / 150f); if (u >= 1f) { e.tweenFrom = null; null } else from to ease(u) }
                val pos = st.bodies.mapIndexed { k, b -> tw?.let { (from, u) -> from.getOrNull(k)?.let { f -> f + (b.p - f) * u.toDouble() } } ?: b.p }
                for (k in 0..1) {
                    val holder = e.bobs[k]
                    val b = st.bodies.getOrNull(k)
                    holder.enabled = b != null
                    if (b == null) continue
                    place(holder, pos[k])
                    for ((mat, model) in e.bobModels[k]) {
                        model.enabled = mat == b.mat
                        if (model.enabled) { val d = mat.visualDiaM.toFloat(); model.transform().setScaleVector(Vector3(d, d, d)) }
                    }
                }
                when (st.type) {
                    Hanger.ROD -> { link(e.rods[0], st.pivot, pos[0]); e.rods[1].enabled = false; e.hinge.enabled = false }
                    Hanger.DOUBLE -> {
                        link(e.rods[0], st.pivot, pos[0]); link(e.rods[1], pos[0], pos[1])
                        e.hinge.enabled = true; place(e.hinge, pos[0])
                    }
                    Hanger.SPRING -> { e.rods.forEach { it.enabled = false }; e.hinge.enabled = false }
                }
                e.spring?.enabled = st.type == Hanger.SPRING
                if (st.type != Hanger.SPRING && !bench.frozen) {
                    val b = st.bodies.last()
                    if (now - e.trailT >= 50 && !b.held && b.v.len() > 0.03) {
                        e.trailT = now
                        val i = e.trailNext; e.trailNext = (i + 1) % e.trail.size
                        place(e.trail[i], b.p); e.trailAge[i] = now
                    }
                }
                for (i in e.trail.indices) {
                    val a = e.trailAge[i]
                    val k = if (a < 0) 0f else 1f - (now - a) / 600f
                    if (k <= 0f || st.type == Hanger.SPRING) { e.trail[i].enabled = false; continue }
                    e.trail[i].enabled = true
                    e.trail[i].transform().setScaleVector(Vector3(k, k, k))
                }
            }
            val s0 = bench.stations[0]
            coachRing?.let { ring ->
                val on = snap.coach == 1 && s0.type == Hanger.ROD
                ring.enabled = on
                if (on) {
                    place(ring, s0.bodies[0].p + Vec3(0.0, 0.0, -0.002))
                    val t = (now - startMs) / 1000f
                    val breath = if (t < 2.6f) 0.5f - 0.5f * kotlin.math.cos(t / 1.3f * 2 * PI.toFloat()) else 0.4f
                    res?.ringMat?.setOpacity(0.25f + 0.65f * breath)
                    val sc = 0.9f + 0.15f * breath
                    ring.transform().setScaleVector(Vector3(sc, sc, sc))
                }
            }
            val held = flow.holding
            rim?.let { r ->
                val b = held?.let { (si, k) -> bench.stations[si].bodies.getOrNull(k) }
                r.enabled = b != null
                if (b != null) place(r, b.p + Vec3(0.0, 0.0, -0.001))
            }
            hoverRing?.let { hr ->
                val name = hoverName?.takeIf { now - hoverAt < 1200 }
                val f = name?.split(":")
                val b = if (f != null && f[0] == "bob" && f.size == 3) bench.stations.getOrNull(f[1].toInt())?.bodies?.getOrNull(f[2].toInt()) else null
                hr.enabled = b != null && held == null
                if (b != null) place(hr, b.p + Vec3(0.0, 0.0, -0.0015))
            }
            // planet globe: the current one, turning slowly
            val planet = bench.planet
            for ((p, m) in globeModels) m.enabled = p == planet
            globe?.transform()?.setQuaternion(rotY(((now - startMs) / 1000.0) * Math.toRadians(12.0)))
        }
        // key press: the key dips 4 mm along its normal for 120 ms
        for (i in 0..2) {
            val t = now - keyPressAt[i]
            val dip = if (keyPressAt[i] >= 0 && t < 160) 0.004 * kotlin.math.sin(Math.PI * t / 160.0) else 0.0
            val n = Layout.keyFace(i).normal
            place(keys[i], Layout.keyCentre(i) - n * dip)
        }
        for ((name, k) in selKeys) {
            val at = selPressAt[name] ?: -1L
            val t = now - at
            val dip = if (at >= 0 && t < 160) 0.004 * kotlin.math.sin(Math.PI * t / 160.0) else 0.0
            val tl = Math.toRadians(Layout.SEL_KEY_TILT_DEG)
            val n = Vec3(0.0, kotlin.math.sin(tl), kotlin.math.cos(tl))
            place(k, (if (name == BenchNames.PLANET_NEXT) Layout.SEL_NEXT else Layout.SEL_PREV) - n * dip)
        }
    }

    /** Places a 1 m rod model (origin at its top, pointing -Y) from [a] to [b]. */
    private fun link(e: Entity, a: Vec3, b: Vec3) {
        e.enabled = true
        val d = b - a
        val len = d.len()
        place(e, a)
        val n = d.normalized()
        val down = Vec3(0.0, -1.0, 0.0)
        val axis = down.cross(n)
        val al = axis.len()
        val ang = acos(down.dot(n).coerceIn(-1.0, 1.0))
        val q = if (al < 1e-9) (if (down.dot(n) > 0) Quat.identity() else Quat(Vector3(1f, 0f, 0f), PI.toFloat())) else Quat(v3(axis * (1 / al)), ang.toFloat())
        e.transform().setQuaternion(q)
        e.transform().setScaleVector(Vector3(1f, len.toFloat(), 1f))
    }

    // ---------------------------------------------------------------- springs (main thread)

    private var springLast = arrayOfNulls<Vec3>(3)
    var springWorstMs = 0.0; private set
    var springLastMs = 0.0; private set
    var springFailed = false; private set
    private var springSum = 0.0
    private var springN = 0

    /** Re-sweeps each spring that moved (> 0.3 mm) and uploads it with replaceWithMeshModel (needsCheck: per-frame mesh). */
    fun springTick() {
        val r = res ?: return
        if (!built || springFailed) return
        val t0 = SystemClock.elapsedRealtimeNanos()
        var any = false
        for (si in 0..2) {
            val e = stations[si]
            val (isSpring, top, end) = synchronized(flow.lock) {
                val st = flow.bench.stations[si]
                if (st.type != Hanger.SPRING) Triple(false, Vec3(), Vec3()) else {
                    val b = st.bodies[0]
                    val dir = (b.p - st.pivot).normalized()
                    val lift = e.group.transform().position.y.toDouble()
                    Triple(true, st.pivot + Vec3(0.0, lift, 0.0), b.p - dir * (b.r * 0.85) + Vec3(0.0, lift, 0.0))
                }
            }
            if (!isSpring) continue
            val last = springLast[si]
            if (last != null && last.dist(end) < 0.0003 && e.spring != null) continue
            springLast[si] = end
            val sm = springMeshes[si]
            sm.build(top.x.toFloat(), top.y.toFloat(), top.z.toFloat(), end.x.toFloat(), end.y.toFloat(), end.z.toFloat())
            val pos = ArrayList<Vector3>(sm.vertexCount); val nor = ArrayList<Vector3>(sm.vertexCount)
            var minX = 9f; var minY = 9f; var minZ = 9f; var maxX = -9f; var maxY = -9f; var maxZ = -9f
            for (v in 0 until sm.vertexCount) {
                val x = sm.positions[v * 3]; val y = sm.positions[v * 3 + 1]; val z = sm.positions[v * 3 + 2]
                pos += Vector3(x, y, z); nor += Vector3(sm.normals[v * 3], sm.normals[v * 3 + 1], sm.normals[v * 3 + 2])
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            val model = MeshModel(pos, springIdx, nor)
            val bounds = BoundingBox(Vector3(minX, minY, minZ), Vector3(maxX, maxY, maxZ))
            val ok = runCatching {
                val mesh = e.springMesh
                if (mesh == null) {
                    val m = r.keep(MeshResource.createWithMeshModel(model, bounds, "spring:$si"))
                    e.springMesh = m
                    e.spring = ModelEntity(m, r.steel).apply { setName("spring:$si"); root.addChild(this) }
                } else {
                    mesh.replaceWithMeshModel(model, bounds)
                    e.spring?.components?.get(ModelComponent::class.java)?.mesh = mesh
                }
            }.onFailure { Log.e(TAG, "SPRING mesh update failed", it); springFailed = true }.isSuccess
            any = any || ok
        }
        if (any) {
            springLastMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            springSum += springLastMs; springN++
            if (springN % 200 == 0) Log.i(TAG, "SPRING mesh updates=$springN avg %.2f ms worst %.2f ms".format(springSum / springN, springWorstMs))
            if (springLastMs > springWorstMs) { springWorstMs = springLastMs; Log.i(TAG, "SPRING mesh update worst %.2f ms (%d tris each)".format(springWorstMs, springMeshes[0].triangles)) }
        }
    }

    // ---------------------------------------------------------------- debug / audit

    /** Instance list for ui-audit (D24): every visible entity, its position and whether it blends. */
    fun instances(): String {
        val sb = StringBuilder()
        fun visit(e: Entity, depth: Int) {
            val n = runCatching { e.getName() }.getOrDefault("?")
            val mc = e.components[ModelComponent::class.java]
            if (mc != null && e.enabled) {
                val p = e.transform().position
                sb.append("${"  ".repeat(depth)}$n pos=(%.3f,%.3f,%.3f)\n".format(p.x, p.y, p.z))
            }
            e.getChildren().forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return sb.toString()
    }

    /** Placement diagnostics (D1 「启动后 1 / 3 / 5 秒打日志」). */
    fun placeDiag(): String {
        val h = lastHead; val p = pose
        if (h == null || p == null) return "head=${h != null} pose=${p != null}"
        val l = p.toLocal(h.eye)
        val f = StagePose.flat(h.forward)
        val toBench = p.origin - h.eye
        val ang = if (f == null) 0.0 else Math.toDegrees(Math.atan2(f.x * toBench.z - f.z * toBench.x, f.x * toBench.x + f.z * toBench.z))
        return "eye=${h.eye} pitch=%.1f bench=${p.origin} yaw=%.1f eyeInBench=$l horiz=%.3f m azimuth=%.1f° window=${graph.window.panelState}".format(
            h.pitchDeg, Math.toDegrees(p.yaw), Math.hypot(toBench.x, toBench.z), ang)
    }

    fun dispose() {
        release()
        owned.values.forEach { runCatching { it.close() } }
        owned.clear()
        res?.close()
        res = null
    }

}
