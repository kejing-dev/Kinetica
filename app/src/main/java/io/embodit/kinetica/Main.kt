package io.embodit.kinetica

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.kinetica.ui.Containers
import io.embodit.kinetica.ui.bench.BenchScreen
import io.embodit.kinetica.ui.control.ControlWindow
import io.embodit.kinetica.ui.theme.KineticaTheme

/**
 * Full Space (design windowModel stage_plus_control_window): one Mixed Stage (manifest pico.spatial.stage.style = 1,
 * passthrough) holds the bench, placed 0.50 m ahead of the eye at desk height; all 2D UI lives in one Planar control
 * window (520 x 600 dp, native TabBar) that the system opens ahead of the head (no Placement: the Stage is not a window).
 * The Stage opens it through the level gate once the bench is placed; dragging / closing are the system controls (D21).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage { KineticaTheme { BenchScreen() } }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 520.dp, height = 600.dp),
        ) {
            KineticaTheme {
                Box(Modifier.windowConstraints(minWidth = 480.dp, minHeight = 560.dp, maxWidth = 720.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
