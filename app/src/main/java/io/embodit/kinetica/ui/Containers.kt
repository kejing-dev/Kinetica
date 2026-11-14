package io.embodit.kinetica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The Stage (default container, manifest pico.spatial.stage.id) and the control window. */
object Containers {
    const val STAGE = "KineticaStage"
    const val CONTROL = "KineticaControl"
    const val CONTROL_TAG = "control"
}

/** The two pages of the control window (design panels.control: 玩 / 设置). */
enum class Page(val stateId: String) { PLAY("P1"), SETTINGS("P2") }

/** Window-page state shared process-wide so debug commands can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var exitConfirm by mutableStateOf(false)
    /** Settings shows the 怎么操作 / How to screen in place of its controls (B19). */
    var howTo by mutableStateOf(false)
}
