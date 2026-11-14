package io.embodit.kinetica.ui

import io.embodit.kinetica.domain.BenchSnap
import io.embodit.kinetica.domain.Big
import io.embodit.kinetica.domain.Hanger
import io.embodit.kinetica.domain.Phase
import io.embodit.kinetica.domain.Readout
import io.embodit.kinetica.domain.Small
import io.embodit.kinetica.domain.StationView
import java.util.Locale

/**
 * Every string the 3D plates, tags and dial need, in one language (built from string resources at run time, by hand in
 * unit tests). Pure Kotlin so the D38 test can check that the plates and the panel read the same source.
 */
data class Lex(
    val hangers: List<String>,
    /** Index = size (0 short / 1 mid / 2 long). */
    val sizesRod: List<String>,
    /** Index = size (0 stiff / 1 mid / 2 soft): dragging the tag down makes the spring softer. */
    val sizesSpring: List<String>,
    val ideal: String,
    val flips: String,
    val since: String,
    val swinging: String,
    val answers: List<String>,
    /** 3D planet label, line 1 (MERCURY … PLUTO · DWARF; 水星 … 冥王星 · 矮行星), index = Planet.ordinal. */
    val planetLabels: List<String>,
    val planetNames: List<String>,
    val periodsRow: String,
    val stationNames: List<String>,
    val letGo: String = "Let go",
    val timing: String = "Timing",
    /** The ▶ key's word when the station is still / moving. */
    val swingGo: String = "Swing",
    val swingStop: String = "Stop",
    val lookAhead: String = "Look ahead. Bench coming.",
    val turnHint: String = "Stick left/right to turn",
) {
    /** Second line of the planet label: g with two decimals. */
    fun gLine(p: io.embodit.kinetica.domain.Planet): String = String.format(Locale.US, "%.2f m/s²", p.g)

    fun sizeName(type: Hanger, size: Int) = if (type == Hanger.SPRING) sizesSpring[size] else sizesRod[size]

    fun big(r: Readout): String = when (r.big) {
        Big.LET_GO -> letGo
        Big.TIMING -> timing
        Big.PERIOD -> String.format(Locale.US, "%.2f s", r.bigValue)
        Big.FLIPS -> flips.replace("{n}", r.bigValue.toInt().toString())
        Big.SWINGING -> swinging
    }

    fun small(r: Readout): String = when (r.small) {
        Small.IDEAL -> ideal.replace("{t}", String.format(Locale.US, "%.2f", r.smallValue))
        Small.SINCE -> since.replace("{t}", r.smallValue.toInt().toString())
        Small.ANSWER -> answers.getOrElse(r.tip) { "" }
    }

    fun tagLines(s: StationView): Pair<String, String> = hangers[s.type.ordinal] to "${sizeName(s.type, s.size)} ↕"

    /** The panel's one read-only periods line (design panels.control.pages.play): same source as the plates (D38). */
    fun periodsRow(snap: BenchSnap): String =
        if (snap.stations.size < 3) "" else periodsRow.replace("{a}", big(snap.stations[0].readout)).replace("{b}", big(snap.stations[1].readout)).replace("{c}", big(snap.stations[2].readout))

    companion object {
        /** English set, also used by the unit tests. */
        val EN = Lex(
            hangers = listOf("Rod", "Spring", "Double"),
            sizesRod = listOf("Short", "Mid", "Long"),
            sizesSpring = listOf("Stiff", "Mid", "Soft"),
            ideal = "Ideal {t} s", flips = "{n} flips", since = "{t} s since release", swinging = "Swinging",
            answers = listOf(
                "Same beat. Weight doesn't matter.", "Slower, right? Longer swings slower.", "Lazy, huh. The Moon doesn't pull as hard.",
                "Springs are different. Heavier, slower.", "Nope. It just hangs lower.", "One millimeter off. Totally different ride.",
            ),
            planetLabels = listOf("MERCURY", "VENUS", "EARTH", "MOON", "MARS", "JUPITER", "SATURN", "URANUS", "NEPTUNE", "PLUTO · DWARF"),
            planetNames = listOf("Mercury", "Venus", "Earth", "the Moon", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune", "Pluto"),
            periodsRow = "L {a} · M {b} · R {c}",
            stationNames = listOf("L", "M", "R"),
        )
    }
}

/** The status sentence key (design content.tone.lines), from the one state source (D38). */
enum class Line { S0, S1, COACH2, S2, S3, S4, S7, S8 }

fun statusLine(snap: BenchSnap): Line = when {
    snap.phase == Phase.LOADING -> Line.S0
    snap.phase == Phase.PLACING -> Line.S8
    snap.phase == Phase.BENCH -> Line.S7
    snap.phase == Phase.HOLDING -> Line.S2
    snap.coach == 2 -> Line.COACH2
    snap.phase == Phase.MOVING -> Line.S3
    snap.phase == Phase.SETTLING -> Line.S4
    else -> Line.S1
}
