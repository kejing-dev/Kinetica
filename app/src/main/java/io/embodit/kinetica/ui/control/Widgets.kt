package io.embodit.kinetica.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Slider
import com.pico.spatial.ui.design.Text
import io.embodit.kinetica.platform.TAG
import io.embodit.kinetica.platform.VOLUME_PERCENT
import io.embodit.kinetica.ui.theme.KineticaColors

/** Button height (B31: >= 64 dp, words on every button). */
val BUTTON_H = 64.dp

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
private fun ButtonIcon(icon: Int?, tint: Color) {
    if (icon == null) return
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
    Box(Modifier.width(8.dp))
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    Button(
        onClick = { Log.i(TAG, "BUTTON $tag"); onClick() }, modifier = modifier.height(BUTTON_H), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = BUTTON_H, minWidth = 160.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        ButtonIcon(icon, PicoTheme.colorScheme.labelPrimaryLight)
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, icon: Int? = null) {
    val fg = if (danger) KineticaColors.OnError else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = { Log.i(TAG, "BUTTON $tag"); onClick() }, modifier = modifier.height(BUTTON_H), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = BUTTON_H, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) KineticaColors.Error else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        ButtonIcon(icon, fg)
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Int? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = { Log.i(TAG, "BUTTON $tag on=${!on}"); onClick() }, modifier = modifier.height(BUTTON_H), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = BUTTON_H, minWidth = 96.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) KineticaColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        Box(Modifier.width(8.dp))
        ButtonIcon(icon, fg)
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/**
 * A draggable slider with three stops (low / mid / high) and the percentage (B20). The Slider gets a free Float (see
 * [VolumeDrag]): taps at 0 / 50 / 100 % and short drags change the stop; on release the thumb snaps to it.
 */
@Composable
internal fun VolumeSlider(level: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val drag = remember { VolumeDrag(level) }
    var pos by remember { mutableFloatStateOf(level.toFloat()) }
    LaunchedEffect(level) { drag.sync(level); pos = drag.pos }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = pos,
            onValueChange = { v -> val changed = drag.move(v); pos = drag.pos; if (changed != null) { Log.i(TAG, "VOLUME live $changed"); onChange(changed) } },
            onValueChangeFinished = { val at = drag.pos; val changed = drag.finish(); pos = drag.pos; Log.i(TAG, "VOLUME snap ${drag.level} from pos %.3f".format(at)); if (changed != null) onChange(changed) },
            modifier = Modifier.weight(1f).height(48.dp),
            valueRange = 0f..2f,
        )
        Box(Modifier.width(12.dp))
        Txt("${VOLUME_PERCENT[level.coerceIn(0, 2)]}%", "settings/volume-pct", style = PicoTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
    }
}
