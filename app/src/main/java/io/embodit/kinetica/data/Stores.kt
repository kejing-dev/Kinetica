package io.embodit.kinetica.data

import android.content.Context
import io.embodit.kinetica.domain.BobMat
import io.embodit.kinetica.domain.Hanger
import io.embodit.kinetica.domain.Language
import io.embodit.kinetica.domain.Planet
import io.embodit.kinetica.domain.Settings
import io.embodit.kinetica.domain.StationPref
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Prefs codec (one line per field), kept pure for the unit test. */
object PrefsCodec {
    fun stations(list: List<StationPref>): String = list.joinToString(";") { s -> "${s.type.name},${s.size},${s.mats.joinToString("+") { it.name }}" }

    fun parseStations(text: String?): List<StationPref>? = runCatching {
        val out = text!!.split(";").map { part ->
            val f = part.split(",")
            val t = Hanger.valueOf(f[0])
            val mats = f[2].split("+").map { BobMat.valueOf(it) }
            require(mats.size == if (t == Hanger.DOUBLE) 2 else 1)
            StationPref(t, f[1].toInt().coerceIn(0, 2), mats)
        }
        require(out.size == 3)
        out
    }.getOrNull()
}

/** App settings in SharedPreferences, exposed as a StateFlow so both windows recompose on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("muted", s.muted)
            .putInt("volume", s.volume)
            .putString("planet", s.planet.name)
            .putString("stations", PrefsCodec.stations(s.stations))
            .putInt("tipIndex", s.tipIndex)
            .putBoolean("coachDone", s.coachDone)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            muted = prefs.getBoolean("muted", false),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            planet = Planet.valueOf(prefs.getString("planet", Planet.EARTH.name)!!),
            stations = PrefsCodec.parseStations(prefs.getString("stations", null)) ?: Settings.DEFAULT_STATIONS,
            tipIndex = prefs.getInt("tipIndex", 0).coerceIn(0, 6),
            coachDone = prefs.getBoolean("coachDone", false),
        )
    }.getOrElse { Settings() }
}
