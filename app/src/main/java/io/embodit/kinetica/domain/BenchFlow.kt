package io.embodit.kinetica.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Language { SYSTEM, ZH, EN; fun next() = entries[(ordinal + 1) % 3] }

data class StationPref(val type: Hanger, val size: Int, val mats: List<BobMat>)

/** Everything kept between runs (design dataModel.Prefs). Slow motion is never kept. */
data class Settings(
    val language: Language = Language.SYSTEM,
    val muted: Boolean = false,
    /** 0 / 1 / 2 = low / mid / high (35 / 65 / 100 %). */
    val volume: Int = 2,
    val planet: Planet = Planet.EARTH,
    val stations: List<StationPref> = DEFAULT_STATIONS,
    val tipIndex: Int = 0,
    val coachDone: Boolean = false,
) {
    companion object {
        val DEFAULT_STATIONS = Bench.DEFAULT.map { (t, s, m) -> StationPref(t, s, m) }
    }
}

enum class Phase(val stateId: String) { LOADING("S0"), IDLE("S1"), HOLDING("S2"), MOVING("S3"), SETTLING("S4"), BENCH("S7"), PLACING("S8") }

/** Plate big line (q1-2 / D52: no symbol as an empty value): not released yet / timing / a period / flips / swinging. */
enum class Big { LET_GO, TIMING, PERIOD, FLIPS, SWINGING }
enum class Small { IDEAL, SINCE, ANSWER }

/** One plate's two lines, as data (the text is made in the app language by the UI). D38: one source for plate + panel. */
data class Readout(val big: Big, val bigValue: Double = 0.0, val small: Small, val smallValue: Double = 0.0, val tip: Int = -1)

/** [moving] drives the station's ▶ key: ■ Stop while it moves, ▶ Swing when it is still (or settling). */
data class StationView(val type: Hanger, val size: Int, val mats: List<BobMat>, val readout: Readout, val version: Int, val moving: Boolean = false)

enum class Sound { WOOD, METAL, PLATE, POST, SOFT, CLICK }

/** What the panel and the plates show; published only when it changes. */
data class BenchSnap(
    val phase: Phase = Phase.LOADING,
    val stations: List<StationView> = emptyList(),
    val planet: Planet = Planet.EARTH,
    val slow: Boolean = false,
    val canReplay: Boolean = false,
    val canStop: Boolean = false,
    /** 0..5 = the current "try this", 6 = all done. */
    val tipIndex: Int = 0,
    /** The tip whose answer is showing (4 s), or -1. */
    val answerTip: Int = -1,
    val answerStation: Int = -1,
    /** 1 = first coach step (left rod glows), 2 = tags glow, 0 = off. */
    val coach: Int = 0,
    val frozen: Boolean = false,
)

/**
 * The single state source of the bench (D38): the physics, the input entry points (the volume's board-input listener, the
 * panel buttons and the debug broadcast all call these same functions), tips, coach, sounds and prefs. Thread-safe: every
 * entry point takes [lock] (the ECS frame and Compose input may run on different threads).
 */
class BenchFlow(
    private val clock: () -> Long,
    initial: Settings = Settings(),
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound, Float) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {
    val lock = Any()
    val bench = Bench(initial.stations.map { Triple(it.type, it.size, it.mats) })
    private var settings = initial
    private val _snap = MutableStateFlow(BenchSnap())
    val snap: StateFlow<BenchSnap> = _snap.asStateFlow()

    var loading = true; private set
    var slow = false; private set
    /** S8: the bench is waiting for a level head / moving to the default pose (set by the Stage scene). */
    var placing = false; private set
    /** S7: the bench itself is held (physics paused, stations at rest). */
    var benchHeld = false; private set
    private var coach = if (initial.coachDone) 0 else 1
    private var coach2At = 0L
    private var answerTip = -1
    private var answerStation = -1
    private var answerUntil = 0L
    private val periodSeen = BooleanArray(3)
    private var stillSince = -1L
    private var phase = Phase.LOADING
    private val lastSound = HashMap<String, Long>()
    /** Station -> wall time of its last hanger swap (S5 animation), and the last resize (S6). */
    val swapAt = LongArray(3) { -1L }
    val resizeAt = LongArray(3) { -1L }
    /** Debug hold of an S5 / S6 animation frame (ms into it), or null. */
    @Volatile var animFreezeMs: Long? = null

    // the hold in progress (one hand)
    private var holdSi = -1
    private var holdK = -1
    private val holdStart = Vec3()
    private var holdTravel = 0.0
    private val hist = ArrayDeque<Pair<Long, Vec3>>()
    // the tag drag in progress
    private var tagSi = -1
    private var tagBase = 0
    private var tagTravel = 0.0

    var onReopenPanel: () -> Unit = {}

    init {
        bench.planet = initial.planet
        bench.stations.forEach { bench.rest(it) }
        publish()
    }

    val currentSettings: Settings get() = synchronized(lock) { settings }

    fun applySettings(s: Settings) = synchronized(lock) { settings = s }

    fun ready() = synchronized(lock) { loading = false; log("STATE S0 -> ready"); publish() }

    // ------------------------------------------------------------------ frame

    /** Called every frame by the ECS system with the wall-clock frame time in seconds. */
    fun frame(dt: Float) = synchronized(lock) {
        val now = clock()
        if (!benchHeld) bench.advance(dt.toDouble().coerceIn(0.0, 0.1), slow)
        sounds(now)
        tips(now)
        if (coach == 2 && now - coach2At > 12_000) endCoach()
        if (answerTip >= 0 && now > answerUntil && animFreezeMs == null && !bench.frozen) { answerTip = -1; answerStation = -1 }
        publish()
    }

    private fun sounds(now: Long) {
        for (c in bench.contacts) {
            if (c.speed < BenchConfig.SOUND_MIN_V || !c.fresh) continue
            val last = lastSound[c.key]
            if (last != null && now - last < 60) continue
            lastSound[c.key] = now
            val gain = sqrt((c.speed / BenchConfig.SOUND_FULL_V).coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(0.25f)
            val s = when (c.kind) {
                ContactKind.BOB_BOB -> when {
                    c.a == BobMat.WOOD || c.b == BobMat.WOOD -> Sound.WOOD
                    c.a == BobMat.BRASS && c.b == BobMat.BRASS -> Sound.PLATE
                    else -> Sound.METAL
                }
                ContactKind.METAL -> Sound.POST
                ContactKind.FELT -> Sound.SOFT
                ContactKind.COIL -> Sound.CLICK
            }
            onSound(s, if (c.kind == ContactKind.COIL) gain * 0.5f else gain)
        }
        bench.contacts.clear()
    }

    private fun tips(now: Long) {
        for (s in bench.stations) {
            val has = s.meter.period != null
            val first = has && !periodSeen[s.index]
            periodSeen[s.index] = has
            if (first) onPeriod(s, now)
            if (s.type == Hanger.DOUBLE && settings.tipIndex == 5 && (s.lastDivergeM ?: 0.0) > BenchConfig.TIP_DIVERGE_M) answer(5, s.index, now)
        }
    }

    private fun onPeriod(s: Station, now: Long) {
        log("PERIOD station=${s.index} ${s.type} %.3f s ideal %.3f".format(s.meter.period, bench.idealPeriod(s) ?: 0.0))
        val hit = when (settings.tipIndex) {
            0 -> s.type == Hanger.ROD && s.matChanged
            1 -> s.type == Hanger.ROD && s.longer
            2 -> s.type == Hanger.ROD && bench.planet == Planet.MOON
            3 -> s.type == Hanger.SPRING && s.bodies[0].mat == BobMat.BRASS
            4 -> s.type == Hanger.SPRING && bench.planet == Planet.JUPITER
            else -> false
        }
        if (hit) answer(settings.tipIndex, s.index, now)
    }

    private fun answer(tip: Int, station: Int, now: Long) {
        answerTip = tip; answerStation = station; answerUntil = now + 4000
        log("TIP $tip answered at station $station")
        save(settings.copy(tipIndex = tip + 1))
    }

    /** Debug: jump to tip [n] (0..6). */
    fun setTip(n: Int) = synchronized(lock) { save(settings.copy(tipIndex = n.coerceIn(0, 6))); publish() }

    /** Debug: show tip [n]'s answer now (the answered variant of the panel and the plate). */
    fun showAnswer(n: Int, station: Int) = synchronized(lock) { answer(n.coerceIn(0, 5), station, clock()); publish() }

    private fun save(s: Settings) { settings = s; onSettings(s) }

    private fun saveStations() {
        save(settings.copy(stations = bench.stations.map { StationPref(it.type, it.size, it.mats) }, planet = bench.planet))
    }

    private fun endCoach() {
        if (coach == 0) return
        coach = 0
        save(settings.copy(coachDone = true))
        log("COACH done")
    }

    fun setCoach(on: Boolean) = synchronized(lock) {
        coach = if (on) 1 else 0
        save(settings.copy(coachDone = !on))
        publish()
    }


    // ------------------------------------------------------------------ phase + snapshot

    private fun computePhase(now: Long): Phase {
        if (loading) return Phase.LOADING
        if (placing) return Phase.PLACING
        if (benchHeld) return Phase.BENCH
        if (bench.anyHeld()) return Phase.HOLDING
        if (bench.settling) return Phase.SETTLING
        val moving = bench.anyMoving() || bench.anyDisplaced()
        if (moving) { stillSince = -1L; return Phase.MOVING }
        if (phase == Phase.MOVING) {
            if (stillSince < 0) stillSince = now
            if (now - stillSince < 1000) return Phase.MOVING
        }
        return Phase.IDLE
    }

    fun readout(s: Station): Readout {
        val m = s.meter
        if (answerTip >= 0 && answerStation == s.index) {
            val big = baseBig(s)
            return Readout(big.first, big.second, Small.ANSWER, tip = answerTip)
        }
        val big = baseBig(s)
        return when (s.type) {
            Hanger.DOUBLE -> Readout(big.first, big.second, Small.SINCE, m.since(bench.time)?.toInt()?.toDouble() ?: 0.0)
            else -> Readout(big.first, big.second, Small.IDEAL, round2(bench.idealPeriod(s) ?: 0.0))
        }
    }

    private fun baseBig(s: Station): Pair<Big, Double> {
        val m = s.meter
        return when (s.type) {
            // a count is really 0 before the first release: "0 flips" (q1-2 / D52)
            Hanger.DOUBLE -> Big.FLIPS to (if (m.releasedAt == null) 0.0 else m.flips.toDouble())
            Hanger.SPRING -> if (m.releasedAt != null && m.swing && !m.frozen) Big.SWINGING to 0.0 else m.period?.let { Big.PERIOD to round2(it) } ?: empty(m)
            Hanger.ROD -> m.period?.let { Big.PERIOD to round2(it) } ?: empty(m)
        }
    }

    /** A period not measured yet: "Let go" before any release, "Timing" while the first periods run (periodMeterEmpty). */
    private fun empty(m: Meter): Pair<Big, Double> = (if (m.releasedAt == null || m.frozen) Big.LET_GO else Big.TIMING) to 0.0

    private fun round2(x: Double) = (x * 100).roundToInt() / 100.0

    private fun publish() {
        val now = clock()
        phase = computePhase(now)
        _snap.value = BenchSnap(
            phase = phase,
            stations = bench.stations.map { StationView(it.type, it.size, it.mats, readout(it), it.version, bench.stationMoving(it.index) && !bench.stationSettling(it.index)) },
            planet = bench.planet,
            slow = slow,
            canReplay = bench.canReplay(),
            canStop = bench.anyMoving() || bench.anyDisplaced() || bench.settling,
            tipIndex = settings.tipIndex,
            answerTip = answerTip,
            answerStation = answerStation,
            coach = coach,
            frozen = bench.frozen,
        )
    }

    // ------------------------------------------------------------------ bob input (T1 / T2)

    /** A pinch / trigger lands on bob [k] of station [si]: it is held (S2), even if it was moving. */
    fun pressBob(si: Int, k: Int) = synchronized(lock) {
        if (loading) return@synchronized
        val s = bench.stations.getOrNull(si) ?: return@synchronized
        if (k !in s.bodies.indices) return@synchronized
        if (holdSi >= 0) endHoldLocked(null)
        bench.grab(si, k)
        holdSi = si; holdK = k
        holdStart.set(s.bodies[k].p)
        holdTravel = 0.0
        hist.clear(); hist.addLast(clock() to holdStart.copy())
        log("HOLD station=$si bob=$k at $holdStart")
        publish()
    }

    /** Total hand movement since the press (metres, volume axes). */
    fun dragBob(dx: Float, dy: Float, dz: Float) = synchronized(lock) {
        if (holdSi < 0) return@synchronized
        val target = Vec3(holdStart.x + dx, holdStart.y + dy, holdStart.z + dz)
        holdTravel = maxOf(holdTravel, sqrt((dx * dx + dy * dy + dz * dz).toDouble()))
        val now = clock()
        hist.addLast(now to target)
        while (hist.size > 2 && now - hist.first().first > (BenchConfig.RELEASE_WINDOW_S * 1000).toLong()) hist.removeFirst()
        bench.moveHeld(holdSi, holdK, target, handVelocity())
    }

    private fun handVelocity(): Vec3? {
        if (hist.size < 2) return null
        val (t0, p0) = hist.first(); val (t1, p1) = hist.last()
        val dt = (t1 - t0) / 1000.0
        if (dt < 0.005) return null
        return (p1 - p0) * (1 / dt)
    }

    /** Let go: a press that moved < 8 mm is a tap (material change, released from rest where it was pressed). */
    fun releaseBob(vel: Vec3? = null) = synchronized(lock) { endHoldLocked(vel) }

    fun cancelBob() = synchronized(lock) {
        if (holdSi < 0) return@synchronized
        bench.releaseAtRest(holdSi, holdK)
        holdSi = -1; holdK = -1
        publish()
    }

    private fun endHoldLocked(explicitVel: Vec3?) {
        if (holdSi < 0) return
        val si = holdSi; val k = holdK
        holdSi = -1; holdK = -1
        if (holdTravel < BenchConfig.TAP_MAX_M && explicitVel == null) { tapLocked(si, k, holdStart); return }
        val v = explicitVel ?: handVelocity()
        bench.release(si, k, v)
        if (coach == 1) { coach = 2; coach2At = clock(); log("COACH step 2") }
        log("RELEASE station=$si bob=$k v=$v")
        saveStations()
        publish()
    }

    /** A tap on a bob (system tap without a press, or a press that did not move). */
    fun tapBob(si: Int, k: Int) = synchronized(lock) {
        if (loading) return@synchronized
        val s = bench.stations.getOrNull(si) ?: return@synchronized
        if (k !in s.bodies.indices) return@synchronized
        if (holdSi == si && holdK == k) { holdSi = -1; holdK = -1; tapLocked(si, k, holdStart); return@synchronized }
        bench.grab(si, k)
        tapLocked(si, k, s.bodies[k].p.copy())
    }

    private fun tapLocked(si: Int, k: Int, at: Vec3) {
        val s = bench.stations[si]
        val b = s.bodies[k]
        b.p.set(at)
        if (s.type == Hanger.DOUBLE) bench.doubleFromCartesian(s)
        bench.setMaterial(si, k, b.mat.next())
        bench.releaseAtRest(si, k)
        log("TAP station=$si bob=$k -> ${b.mat}")
        saveStations()
        publish()
    }

    // ------------------------------------------------------------------ tags (T3 / T4)

    fun tapTag(si: Int) = synchronized(lock) {
        if (loading || si !in 0..2) return@synchronized
        if (holdSi == si) endHoldLocked(null)
        bench.swapHanger(si)
        swapAt[si] = clock()
        periodSeen[si] = false
        onSound(Sound.CLICK, 1f)
        if (coach == 2) endCoach()
        log("SWAP station=$si -> ${bench.stations[si].type} (S5)")
        saveStations()
        publish()
    }

    fun tagDragStart(si: Int) = synchronized(lock) {
        if (loading || si !in 0..2) return@synchronized
        tagSi = si; tagBase = bench.stations[si].size; tagTravel = 0.0
        if (coach == 2) endCoach()
    }

    /** Vertical drag on a tag: down 2.5 cm = one size longer (softer spring); three stops, the ends only stop (S6). */
    fun tagDragMove(dy: Float, travel: Float = kotlin.math.abs(dy)) = synchronized(lock) {
        if (tagSi < 0) return@synchronized
        tagTravel = maxOf(tagTravel, travel.toDouble())
        val steps = (-dy / BenchConfig.TAG_STEP_M).roundToInt()
        val want = (tagBase + steps).coerceIn(0, 2)
        if (bench.resize(tagSi, want)) {
            resizeAt[tagSi] = clock()
            periodSeen[tagSi] = false
            onSound(Sound.CLICK, 1f)
            log("SIZE station=$tagSi -> $want (S6)")
            saveStations()
            publish()
        }
    }

    fun tagDragEnd() = synchronized(lock) {
        val si = tagSi
        tagSi = -1
        if (si >= 0 && tagTravel < BenchConfig.TAP_MAX_M && bench.stations[si].size == tagBase) {
            tagSi = -1
            // a press that did not move is a tap
            bench.swapHanger(si); swapAt[si] = clock(); onSound(Sound.CLICK, 1f); saveStations()
        }
        publish()
    }

    // ------------------------------------------------------------------ dial, nameplate, buttons

    /** Planet selector ▶ (and a tap on the globe): Moon → Mars → Earth → Jupiter → Moon. The window's ▶ calls this too. */
    fun planetNext() = stepPlanet(+1)

    /** Planet selector ◀. */
    fun planetPrev() = stepPlanet(-1)

    private fun stepPlanet(dir: Int) = synchronized(lock) {
        if (loading) return@synchronized
        bench.changePlanet(if (dir > 0) bench.planet.next() else bench.planet.prev())
        onSound(Sound.CLICK, 1f)
        log("GRAVITY -> ${bench.planet} (${if (dir > 0) "next" else "prev"})")
        saveStations()
        publish()
    }

    // ------------------------------------------------------------------ ▶ swing keys (D43)

    /** The station's 3D key: still -> swing with the default amplitude; moving -> settle that station (■ Stop). */
    fun tapSwing(si: Int) = synchronized(lock) {
        if (loading || si !in 0..2) return@synchronized
        if (holdSi == si) { bench.releaseAtRest(si, holdK); holdSi = -1; holdK = -1 }
        if (tagSi == si) tagSi = -1
        val moving = bench.stationMoving(si) && !bench.stationSettling(si)
        if (moving) {
            bench.stopStation(si)
            log("SWING station=$si stop")
        } else {
            bench.autoSwing(si)
            periodSeen[si] = false
            if (coach == 1) { coach = 2; coach2At = clock(); log("COACH step 2") }
            log("SWING station=$si ${bench.stations[si].type} start")
        }
        onSound(Sound.CLICK, 1f)
        publish()
    }

    // ------------------------------------------------------------------ the bench itself (S7 / S8)

    /** S7 starts: every station goes back to rest (the scene tweens 150 ms) and the physics pauses until [benchRelease]. */
    fun benchGrab() = synchronized(lock) {
        if (loading) return@synchronized
        if (holdSi >= 0) { bench.releaseAtRest(holdSi, holdK); holdSi = -1; holdK = -1 }
        tagSi = -1
        bench.stations.forEach { bench.rest(it) }
        bench.stationSettle.fill(0.0)
        benchHeld = true
        log("BENCH grab (S7)")
        publish()
    }

    fun benchRelease() = synchronized(lock) {
        if (!benchHeld) return@synchronized
        benchHeld = false
        log("BENCH release -> S1")
        publish()
    }

    fun setPlacing(on: Boolean) = synchronized(lock) { if (placing != on) { placing = on; log("PLACE ${if (on) "S8 start" else "done"}"); publish() } }

    fun tapNameplate() { log("NAMEPLATE -> reopen panel"); onReopenPanel() }

    /** [再放一次] (T8). Returns false (button disabled) when no station has been released yet. */
    fun replay(): Boolean = synchronized(lock) {
        if (holdSi >= 0) endHoldLocked(null)
        val ok = bench.replay()
        if (ok) bench.stations.forEach { periodSeen[it.index] = false }
        log("REPLAY ok=$ok")
        publish()
        ok
    }

    /** [全停下] (T5). Returns false when already still. */
    fun stopAll(): Boolean = synchronized(lock) {
        if (holdSi >= 0) endHoldLocked(null)
        val ok = bench.stopAll()
        log("STOP ok=$ok")
        publish()
        ok
    }

    fun setSlow(on: Boolean) = synchronized(lock) { slow = on; log("SLOW $on"); publish() }

    /** [恢复台子] (T9): default stations, Earth; tips and coach are kept. */
    fun restoreDefault() = synchronized(lock) {
        holdSi = -1; tagSi = -1
        Bench.DEFAULT.forEachIndexed { i, (t, s, m) -> bench.setStation(i, t, s, m); swapAt[i] = clock() }
        bench.changePlanet(Planet.EARTH)
        bench.stations.forEach { bench.rest(it) }
        log("RESTORE default bench")
        saveStations()
        publish()
    }

    /** Leaving for the background: physics pauses, the bobs go back to rest (not kept). */
    fun onBackground() = synchronized(lock) {
        holdSi = -1; tagSi = -1
        bench.stations.forEach { bench.rest(it) }
        saveStations()
        publish()
    }

    fun setFrozen(on: Boolean) = synchronized(lock) { bench.frozen = on; publish() }

    /** Debug: runs physics time instantly (QA holds). */
    fun fastForward(seconds: Double) = synchronized(lock) {
        val f = bench.frozen
        bench.frozen = false
        repeat((seconds / BenchConfig.H).toInt()) { bench.step() }
        bench.contacts.clear()
        tips(clock())
        bench.frozen = f
        publish()
    }

    fun setPlanet(p: Planet) = synchronized(lock) { bench.changePlanet(p); saveStations(); publish() }

    fun setSize(si: Int, size: Int) = synchronized(lock) {
        if (bench.resize(si, size)) { resizeAt[si] = clock(); saveStations() }
        publish()
    }

    fun setStation(si: Int, t: Hanger, size: Int, mats: List<BobMat>) = synchronized(lock) {
        bench.setStation(si, t, size, mats); saveStations(); publish()
    }

    /** The station a ▶ key belongs to is moving (the key reads ■ Stop). */
    fun stationMoving(si: Int): Boolean = synchronized(lock) { bench.stationMoving(si) && !bench.stationSettling(si) }

    val holding: Pair<Int, Int>? get() = synchronized(lock) { if (holdSi >= 0) holdSi to holdK else null }

    fun dump(): String = synchronized(lock) {
        val sb = StringBuilder("phase=${phase.stateId} planet=${bench.planet} t=%.2f slow=$slow tip=${settings.tipIndex} coach=$coach placing=$placing benchHeld=$benchHeld".format(bench.time))
        bench.stations.forEach { s ->
            sb.append(" | ${s.index}:${s.type}/${s.size}/${s.mats} ")
            s.bodies.forEach { b -> sb.append("p=${b.p} v=%.3f ".format(b.v.len())) }
            sb.append("T=${s.meter.period}")
        }
        sb.toString()
    }
}
