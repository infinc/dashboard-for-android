package app.walldash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import kotlin.math.roundToInt

@Composable
fun PaneTitle(title: String, lead: String) {
    Text(title, fontSize = 20.tu, fontWeight = FontWeight.SemiBold)
    Text(lead, color = Wd.Text2, fontSize = 13.tu, lineHeight = 1.6.em, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
}

/** 見出し・中身・補足の 1 まとまり。 */
@Composable
fun Field(label: String? = null, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        if (label != null) Text(label, color = Wd.Text2, fontSize = 13.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        content()
        if (hint != null) Hint(hint)
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = Wd.Text3, fontSize = 12.tu, lineHeight = 1.6.em, modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun Notice(text: String, color: Color = Wd.Text2) {
    Text(
        text,
        color = color,
        fontSize = 12.5f.tu,
        lineHeight = 1.7.em,
        modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)
            .clip(RoundedCornerShape(12.dp)).background(Wd.Surface2)
            .border(1.dp, Wd.Border, RoundedCornerShape(12.dp)).padding(14.dp),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.tu, color = if (enabled) Wd.Text else Wd.Text3, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current, checkedThumbColor = Wd.OnAccent),
        )
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(!checked) }.padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = LocalAccent.current, checkmarkColor = Wd.OnAccent))
        Text(label, fontSize = 14.tu)
    }
}

/** 選択肢から 1 つ選ぶ欄。[options] は（保存する値, 表示名）。 */
@Composable
fun <T> Select(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Wd.Bg)
                .border(1.dp, Wd.Border, RoundedCornerShape(10.dp)).clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == value }?.second ?: value.toString(), fontSize = 14.tu, modifier = Modifier.weight(1f))
            Text("▾", color = Wd.Text3, fontSize = 14.tu)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (v == value) LocalAccent.current else Wd.Text) },
                    onClick = { open = false; onChange(v) },
                )
            }
        }
    }
}

/** 0..100 の割合を選ぶつまみ。値は右上に % で出す。 */
@Composable
fun PercentSlider(label: String, percent: Int, min: Int, max: Int, step: Int, onChange: (Int) -> Unit, hint: String? = null) {
    Field(hint = hint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Wd.Text2, fontSize = 13.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$percent%", color = LocalAccent.current, fontSize = 13.tu, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) / step - 1,
        )
    }
}

@Composable
fun Input(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    number: Boolean = false,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        placeholder = { Text(placeholder, color = Wd.Text3, fontSize = 14.tu) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Wd.Text, fontSize = 14.tu),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Wd.Bg, unfocusedContainerColor = Wd.Bg,
            focusedBorderColor = LocalAccent.current, unfocusedBorderColor = Wd.Border,
        ),
    )
}

@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, color: Color? = null) {
    val accent = color ?: LocalAccent.current
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (primary && enabled) accent else Color.Transparent)
            .border(1.dp, if (primary && enabled) accent else Wd.Border, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> Wd.Text3
                primary -> Wd.OnAccent
                else -> color ?: Wd.Text
            },
            fontSize = 14.tu,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
fun StatusText(text: String, color: Color = Wd.Text2) {
    if (text.isNotEmpty()) Text(text, color = color, fontSize = 12.5f.tu, lineHeight = 1.5.em, modifier = Modifier.padding(top = 6.dp))
}

/** アクセント色の候補を丸で並べる。 */
@Composable
fun ColorSwatches(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (hex, label) ->
                    val selected = hex.equals(value, ignoreCase = true)
                    val color = app.walldash.ui.theme.colorOf(hex)
                    Column(
                        Modifier.width(76.dp).clip(RoundedCornerShape(10.dp))
                            .border(1.dp, if (selected) color else Wd.Border, RoundedCornerShape(10.dp))
                            .clickable { onChange(hex) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(color))
                        Spacer(Modifier.height(6.dp))
                        Text(label, fontSize = 11.5f.tu, color = if (selected) Wd.Text else Wd.Text2)
                    }
                }
            }
        }
    }
}
