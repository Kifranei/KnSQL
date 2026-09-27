package dev.knsql.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.knsql.i18n.tr
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Two equal-width buttons filling the bottom of a dialog, like the other KnSQL dialogs. */
@Composable
fun DialogButtons(
    cancel: String,
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(text = cancel, onClick = onCancel, modifier = Modifier.weight(1f), minWidth = 0.dp)
        Button(
            onClick = onConfirm,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            minWidth = 0.dp,
            colors = if (destructive) {
                ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColorsPrimary()
            },
        ) { Text(confirm) }
    }
}

/** A tappable checkbox with a label. */
@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(state = ToggleableState(checked), onClick = { if (enabled) onChange(!checked) }, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 10.dp), style = MiuixTheme.textStyles.body2)
    }
}

/** Shows SQL that is about to run and asks for confirmation. */
@Composable
fun SqlConfirmDialog(
    show: Boolean,
    title: String,
    summary: String,
    statements: List<String>,
    confirm: String,
    working: Boolean,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                statements.forEach { sql ->
                    Text(
                        text = sql.trimEnd().let { if (it.endsWith(";")) it else "$it;" },
                        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            if (working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(tr("正在执行…"), modifier = Modifier.padding(start = 12.dp), style = MiuixTheme.textStyles.body2)
                }
            }
            DialogButtons(
                cancel = tr("取消"),
                confirm = confirm,
                onCancel = onDismiss,
                onConfirm = onConfirm,
                destructive = destructive,
                enabled = !working,
            )
        }
    }
}
