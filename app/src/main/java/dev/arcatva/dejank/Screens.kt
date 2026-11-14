@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)

package dev.arcatva.dejank

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoadingScreen(padding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator()
    }
}

@Composable
fun MessageScreen(padding: PaddingValues, title: String, body: String, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** The fixes, and on a wide screen the rest beside them. */
@Composable
fun ReadyScreen(
    status: Status,
    busy: Boolean,
    theme: ThemeChoice,
    wide: Boolean,
    actions: Actions,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    // Switching pin on restarts two apps; that is asked first.
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val foreign = status.api != EXPECTED_API
    val gap = Arrangement.spacedBy(16.dp)

    val fixes: @Composable ColumnScope.() -> Unit = {
        if (foreign) {
            Section("Update the app") { Hint("The installed module is a different version from this app.") }
        } else {
            Fix.entries.forEach { fix ->
                FixCard(
                    fix = fix,
                    status = status,
                    busy = busy,
                    onToggle = { on ->
                        if (on && fix == Fix.PIN) {
                            confirm = { actions.set(fix, true) }
                        } else {
                            actions.set(fix, on)
                        }
                    },
                )
            }
        }
    }
    val rest: @Composable ColumnScope.() -> Unit = {
        ThemeCard(theme = theme, onPick = actions.setTheme)
        AboutCard(status = status, appVersion = appVersion, actions = actions)
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = if (wide) 1200.dp else 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = gap,
        ) {
            if (!foreign && status.paused) {
                Banner(
                    title = "Paused",
                    text = "The last start didn't finish, so nothing was applied.",
                    button = "Resume",
                    busy = busy,
                    onClick = actions.resume,
                )
            } else if (!foreign && status.anyPending) {
                Banner(
                    title = "Not applied yet",
                    text = "The fixes that are switched on are not in effect.",
                    button = "Apply",
                    busy = busy,
                    onClick = {
                        if (status.pending(Fix.PIN)) {
                            confirm = actions.apply
                        } else {
                            actions.apply()
                        }
                    },
                )
            }
            if (wide) {
                Row(horizontalArrangement = gap) {
                    Column(Modifier.weight(1f), verticalArrangement = gap, content = fixes)
                    Column(Modifier.weight(1f), verticalArrangement = gap, content = rest)
                }
            } else {
                fixes()
                rest()
            }
        }
    }

    confirm?.let { go ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restart System UI and Home?") },
            text = { Text("They restart once so that all their memory is protected. The screen flickers for a few seconds.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        go()
                    },
                ) { Text("Restart") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** One fix: its name and "?", the switch, what it does in a sentence, and its state with the numbers. */
@Composable
internal fun FixCard(fix: Fix, status: Status, busy: Boolean, onToggle: (Boolean) -> Unit) {
    val state = status.state(fix)
    val usable = state != FixState.NA
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = HelpSize), verticalAlignment = Alignment.CenterVertically) {
                Text(fix.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge)
                HelpButton(fix.title, fix.help)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = usable && status.wanted(fix),
                onCheckedChange = onToggle,
                enabled = usable && !busy,
            )
        }
        Hint(fix.summary)
        StateLine(status.tone(fix), status.line(fix))
    }
}

@Composable
internal fun StateLine(tone: Tone, text: String) {
    val scheme = MaterialTheme.colorScheme
    val color = when (tone) {
        Tone.GOOD -> scheme.primary
        Tone.WAITING -> scheme.tertiary
        Tone.BAD -> scheme.error
        Tone.IDLE -> scheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (tone == Tone.IDLE) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
}

/** Something to do before the fixes work: apply after an install, resume after a failed start. */
@Composable
internal fun Banner(title: String, text: String, button: String, busy: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            Modifier.padding(start = 20.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onClick, enabled = !busy) { Text(button) }
        }
    }
}

@Composable
internal fun ThemeCard(theme: ThemeChoice, onPick: (ThemeChoice) -> Unit) {
    Section("Theme") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ThemeChoice.entries.forEach { choice ->
                OutlinedToggleButton(
                    checked = theme == choice,
                    onCheckedChange = { if (it) onPick(choice) },
                ) { Text(choice.label) }
            }
        }
    }
}

@Composable
internal fun AboutCard(status: Status, appVersion: String, actions: Actions) {
    Section("About") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (appVersion.isNotEmpty()) Fact("App", appVersion)
            Fact("Module", status.version.ifEmpty { "Unknown" })
        }
        Hint("Everything here is undone by a reboot. Nothing is written to the system partitions.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.copyReport) { Text("Copy report") }
            TextButton(onClick = actions.openSource) { Text("Source") }
        }
    }
}

// ------------------------------------------------------------ building blocks

/** Every card. */
@Composable
internal fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
internal fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
internal fun Fact(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
    }
}

/** Touch size of a "?". */
internal val HelpSize = 40.dp

/** A "?" behind a name: what is wrong, what the fix changes, what it costs. */
@Composable
internal fun HelpButton(title: String, text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(HelpSize)) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("?", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("OK") } },
            title = { Text(title) },
            text = { Text(text) },
        )
    }
}
