package io.embodit.kinetica.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.kinetica.BuildConfig
import io.embodit.kinetica.R
import io.embodit.kinetica.domain.BenchSnap
import io.embodit.kinetica.domain.Phase
import io.embodit.kinetica.domain.Settings
import io.embodit.kinetica.platform.Graph
import io.embodit.kinetica.platform.TAG
import io.embodit.kinetica.ui.Line
import io.embodit.kinetica.ui.Page
import io.embodit.kinetica.ui.Ui
import io.embodit.kinetica.ui.bench.lexOf
import io.embodit.kinetica.ui.statusLine
import io.embodit.kinetica.ui.theme.KineticaColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer the system opens ahead of the head, 800 x 420 dp, v0.1.2): all 2D UI, two pages switched by the native
 * SpatialUI TabBar (D8): Play / Settings. Landscape and two columns so the window ends ~15° under the eye and the bench below it plus
 * the wings beside it stay in the level default view (QA #2 q2-1). No page scrolls (D44); rows are 16 dp apart (q1-1) and the block
 * is centred. Buttons are 64 dp with words (B31).
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val snap by graph.flow.snap.collectAsStateWithLifecycle()
    val settings by graph.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) { Ui.exitConfirm = false; Ui.howTo = false }
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(snap, settings, graph)
            Page.SETTINGS -> if (Ui.howTo) HowToPage(settings, graph) else SettingsPage(settings, graph)
        }
    }

    // The native TabBar is its own system window: resolve the labels here, in the app-language context, and pass plain
    // strings in (ui_gates tabbar-lang; an English cold start shows English tabs).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    NativeTabs(tabs)
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    // rows 16 dp apart (q1-1 / B31: no SpaceBetween-made gaps), the block centred (D44 blank <= 25 %)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), content = content)
}

/** Page header (64 dp): the title and [静音] on both pages (D15 / B31: mute always shows). */
@Composable
private fun Header(title: String, tag: String, settings: Settings, graph: Graph) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        ToggleButton(stringResource(R.string.btn_mute), "$tag/mute", on = settings.muted, icon = if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
            onClick = { graph.update { it.copy(muted = !it.muted) }; Log.i(TAG, "MUTE ${!settings.muted}") })
    }
}

@Composable
internal fun lineText(line: Line): String = stringResource(when (line) {
    Line.S0 -> R.string.line_s0
    Line.S7 -> R.string.line_s7
    Line.S8 -> R.string.line_s8
    Line.S1 -> R.string.line_s1
    Line.COACH2 -> R.string.line_coach2
    Line.S2 -> R.string.line_s2
    Line.S3 -> R.string.line_s3
    Line.S4 -> R.string.line_s4
})

// ------------------------------------------------------------------ Play (P1)

@Composable
private fun PlayPage(snap: BenchSnap, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    if (snap.phase == Phase.LOADING) {
        PageColumn {
            Header(stringResource(R.string.app_name), "play", settings, graph)
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Txt(stringResource(R.string.line_s0), "play/loading", style = PicoTheme.typography.titleMedium)
            }
        }
        return
    }
    val lex = remember(context.resources.configuration) { lexOf(context) }
    val planet = stringArrayResource(R.array.planet_names)[snap.planet.ordinal]
    val questions = stringArrayResource(R.array.tip_questions)
    val answers = stringArrayResource(R.array.tip_answers)
    PageColumn {
        Header(stringResource(R.string.app_name), "play", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // left: what to do next, the periods (same source as the brass plates, D38), try this
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(22.dp)).background(KineticaColors.Card).padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                    val line = lineText(statusLine(snap)) + if (snap.slow) " · " + stringResource(R.string.line_slow) else ""
                    Txt(line, "play/status", style = PicoTheme.typography.titleMedium, color = KineticaColors.Ink, maxLines = 2)
                }
                Txt(lex.periodsRow(snap), "play/periods", style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                // try this (content.tips): the question, or its answer for 4 s
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(KineticaColors.Card).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val answered = snap.answerTip >= 0
                    Icon(painter = painterResource(R.drawable.ic_lightbulb), contentDescription = null, modifier = Modifier.size(24.dp), tint = KineticaColors.Secondary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(if (answered) R.string.tip_answer_label else R.string.tip_label), "play/tip-label", style = PicoTheme.typography.labelMedium, color = KineticaColors.Secondary)
                        val text = when {
                            answered -> answers.getOrElse(snap.answerTip) { "" }
                            snap.tipIndex >= 6 -> stringResource(R.string.tips_done)
                            else -> questions.getOrElse(snap.tipIndex) { "" }
                        }
                        Txt(text, "play/tip", style = PicoTheme.typography.bodyLarge, color = KineticaColors.Ink, maxLines = 2)
                    }
                }
            }
            // right: the main button (D43) [再放一次]; then [全停下] and slow motion; why a button is grey
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PrimaryButton(stringResource(R.string.btn_replay), "play/replay", onClick = { flow.replay() }, enabled = snap.canReplay, icon = R.drawable.ic_replay, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton(stringResource(R.string.btn_stop), "play/stop", onClick = { flow.stopAll() }, enabled = snap.canStop, icon = R.drawable.ic_stop, modifier = Modifier.weight(1f))
                    ToggleButton(stringResource(R.string.btn_slow), "play/slow", on = snap.slow, onClick = { flow.setSlow(!snap.slow) }, icon = R.drawable.ic_slow_motion, modifier = Modifier.weight(1.1f))
                }
                val why = when {
                    !snap.canReplay -> stringResource(R.string.replay_disabled)
                    !snap.canStop -> stringResource(R.string.stop_disabled)
                    else -> ""
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                    Txt(why, "play/why", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
                }
            }
        }
        // gravity: same as the 3D selector (◀ ▶ beside the planet on the right wing), here as a full-width row (user 2026-11-13)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.btn_planet_prev), "play/planet-prev", onClick = { flow.planetPrev() }, icon = R.drawable.ic_chevron_left, modifier = Modifier.width(160.dp))
            Txt(stringResource(R.string.gravity_line, planet, lex.gLine(snap.planet)), "play/gravity", style = PicoTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = KineticaColors.Ink, maxLines = 2, modifier = Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
            SecondaryButton(stringResource(R.string.btn_planet_next), "play/planet-next", onClick = { flow.planetNext() }, icon = R.drawable.ic_chevron_right, modifier = Modifier.width(160.dp))
        }
    }
}

// ------------------------------------------------------------------ Settings (P2 / P3)

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    val langs = stringArrayResource(R.array.language_values)
    PageColumn {
        Header(stringResource(R.string.tab_settings), "settings", settings, graph)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(stringResource(R.string.settings_language, langs[settings.language.ordinal]), "settings/lang",
                    onClick = { graph.update { it.copy(language = it.language.next()) } }, icon = R.drawable.ic_language, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.settings_volume), "settings/volume-label", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
                    VolumeSlider(settings.volume, onChange = { v -> graph.update { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
                }
                // D1: one line for moving / turning the bench
                Txt(stringResource(R.string.move_hint), "settings/move-hint", style = PicoTheme.typography.bodyMedium, color = KineticaColors.Ink, maxLines = 2)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // D44: [叫回面前] always on the first screen; [恢复台子] beside it (C4)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(stringResource(R.string.btn_recall), "settings/recall", onClick = { graph.recallFront() }, icon = R.drawable.ic_recenter, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.btn_restore), "settings/restore", onClick = { graph.flow.restoreDefault() }, icon = R.drawable.ic_restore, modifier = Modifier.weight(1f))
                }
                if (Ui.exitConfirm) {
                    // P3: the confirmation opens in place of the quit row (D26 / D52: words + action icon, no ✕)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(KineticaColors.Card).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(stringResource(R.string.quit_q), "confirm/q", style = PicoTheme.typography.titleMedium, color = KineticaColors.Ink)
                            Spacer(Modifier.width(12.dp))
                            Txt(stringResource(R.string.quit_line), "confirm/line", style = PicoTheme.typography.bodySmall, color = KineticaColors.InkSecondary, maxLines = 2, modifier = Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = { Ui.exitConfirm = false }, icon = R.drawable.ic_back, modifier = Modifier.weight(1f))
                            SecondaryButton(stringResource(R.string.btn_quit_go), "confirm/quit", danger = true, onClick = { exitApp(context, graph) }, icon = R.drawable.ic_exit, modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    SecondaryButton(stringResource(R.string.btn_quit), "settings/quit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT P3") }, icon = R.drawable.ic_exit, modifier = Modifier.fillMaxWidth(), danger = true)
                    SecondaryButton(stringResource(R.string.btn_how_to), "settings/howto", onClick = { Ui.howTo = true; Log.i(TAG, "HOWTO on") }, icon = R.drawable.ic_help, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
            Txt(stringResource(R.string.footnote), "settings/footnote", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
        }
    }
}

/** One row of the how-to table: icon + action, the hand way, the controller way. */
private class HowRow(val icon: Int, val title: Int, val hand: Int, val ctrl: Int, val key: String)

private val HOW_ROWS = listOf(
    HowRow(R.drawable.ic_touch, R.string.how_to_pull, R.string.how_to_pull_hand, R.string.how_to_pull_ctrl, "pull"),
    HowRow(R.drawable.ic_tab_play, R.string.how_to_swing, R.string.how_to_swing_hand, R.string.how_to_swing_ctrl, "swing"),
    HowRow(R.drawable.ic_swap, R.string.how_to_ball, R.string.how_to_ball_hand, R.string.how_to_ball_ctrl, "ball"),
    HowRow(R.drawable.ic_unfold, R.string.how_to_tag, R.string.how_to_tag_hand, R.string.how_to_tag_ctrl, "tag"),
    HowRow(R.drawable.ic_language, R.string.how_to_planet, R.string.how_to_planet_hand, R.string.how_to_planet_ctrl, "planet"),
    HowRow(R.drawable.ic_open_with, R.string.how_to_move, R.string.how_to_move_hand, R.string.how_to_move_ctrl, "move"),
)

/**
 * 怎么操作 / How to (B19, user 2026-11-14 光影渲染: "write how to operate it on one settings screen"): every main action in one
 * row, the hand gesture and the controller way side by side; [返回] goes back to the settings controls. Mute stays (D15).
 */
@Composable
private fun HowToPage(settings: Settings, graph: Graph) {
    PageColumn {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(stringResource(R.string.btn_back), "howto/back", onClick = { Ui.howTo = false; Log.i(TAG, "HOWTO off") }, icon = R.drawable.ic_back)
            Spacer(Modifier.width(16.dp))
            Txt(stringResource(R.string.how_to_title), "howto/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            ToggleButton(stringResource(R.string.btn_mute), "howto/mute", on = settings.muted, icon = if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                onClick = { graph.update { it.copy(muted = !it.muted) }; Log.i(TAG, "MUTE ${!settings.muted}") })
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(KineticaColors.Card).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(HOW_TITLE_W))
                Txt(stringResource(R.string.how_to_col_hand), "howto/col-hand", style = PicoTheme.typography.labelLarge, color = KineticaColors.InkSecondary, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Txt(stringResource(R.string.how_to_col_ctrl), "howto/col-ctrl", style = PicoTheme.typography.labelLarge, color = KineticaColors.InkSecondary, modifier = Modifier.weight(1f))
            }
            HOW_ROWS.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(HOW_TITLE_W), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(r.icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = KineticaColors.Ink)
                        Spacer(Modifier.width(8.dp))
                        Txt(stringResource(r.title), "howto/${r.key}", style = PicoTheme.typography.labelLarge, color = KineticaColors.Ink, maxLines = 2)
                    }
                    Txt(stringResource(r.hand), "howto/${r.key}-hand", style = PicoTheme.typography.bodyMedium, color = KineticaColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Txt(stringResource(r.ctrl), "howto/${r.key}-ctrl", style = PicoTheme.typography.bodyMedium, color = KineticaColors.Ink, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private val HOW_TITLE_W = 168.dp

/** D26 / D44 exit: save, release, close both window containers, remove every task, then end the process. */
internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}

/** The native SpatialUI TabBar (D8); labels arrive already resolved in the app language. */
@Composable
private fun NativeTabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}
