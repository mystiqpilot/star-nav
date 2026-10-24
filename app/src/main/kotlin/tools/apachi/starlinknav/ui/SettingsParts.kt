package tools.apachi.starlinknav.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/** Input rows shared by the pages that hold settings (Settings, Mock GPS, Starlink > Connection). */

/** Commits only values [valid] accepts; an invalid entry stays on screen, marked as an error. */
@Composable
fun TextSetting(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    valid: (String) -> Boolean = { true },
    /** Whether the text already says [value] (a number comes back reformatted: "-1" as "-1.0"). */
    same: (String, String) -> Boolean = { a, b -> a == b },
    enabled: Boolean = true,
    onCommit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    // Only a change from elsewhere ("Use the learned value", another screen) replaces the text: replacing it with each
    // reformatted commit moved the cursor, so typing -176.5 into "-177.0" ended as -170.
    LaunchedEffect(value) { if (!same(text, value)) text = value }
    val ok = valid(text)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (valid(it)) onCommit(it)
        },
        label = { Text(label) },
        isError = !ok,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun NumberSetting(label: String, value: String, valid: (String) -> Boolean, enabled: Boolean = true, onCommit: (String) -> Unit) =
    TextSetting(label, value, KeyboardType.Decimal, valid, { a, b -> (a.isBlank() && b.isBlank()) || a.toDoubleOrNull()?.let { it == b.toDoubleOrNull() } == true }, enabled, onCommit)

@Composable
fun SwitchSetting(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Space.touch), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A learned mount offset as a manual value: one decimal, as the field shows it. */
fun roundedDeg(d: Double): Float = "%.1f".format(java.util.Locale.ROOT, d).toFloat()
