@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)

package dev.arcatva.dejank

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun Line.text(): String = stringResource(id, *args.toTypedArray())

/** "2 fixes are working. 1 is switched off." */
@Composable
fun headline(status: Status): String {
    if (status.paused) return stringResource(R.string.headline_paused)
    if (status.nothingApplied) return stringResource(R.string.headline_pending)
    val resources = LocalContext.current.resources
    val working = resources.getQuantityString(R.plurals.fixes_working, status.working, status.working)
    val off = status.switchedOff
    return if (off == 0) working else working + " " + resources.getQuantityString(R.plurals.fixes_off, off, off)
}

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
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/**
 * The tiles, or what is behind the one that is [open]. On a wide screen both
 * are there side by side.
 */
@Composable
fun ReadyScreen(
    status: Status,
    busy: Boolean,
    theme: ThemeChoice,
    wide: Boolean,
    open: Tile?,
    onOpen: (Tile) -> Unit,
    actions: Actions,
    appVersion: String,
    modifier: Modifier = Modifier,
) {
    // Keeping System UI or the home app restarts it; that is asked first.
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask: (() -> Unit) -> Unit = { confirm = it }
    val gap = Arrangement.spacedBy(16.dp)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (status.api != EXPECTED_API) {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Banner(stringResource(R.string.update_title), stringResource(R.string.update_text))
            }
        } else if (wide) {
            val shown = open ?: Tile.KEEP
            Row(
                Modifier
                    .widthIn(max = 1200.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = gap,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = 32.dp),
                    verticalArrangement = gap,
                ) {
                    Home(status, busy, theme, shown, onOpen, ask, actions, appVersion)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = 32.dp),
                    verticalArrangement = gap,
                ) {
                    Text(
                        stringResource(shown.title),
                        Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Detail(shown, status, busy, ask, actions)
                }
            }
        } else {
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = gap,
            ) {
                if (open == null) {
                    Home(status, busy, theme, null, onOpen, ask, actions, appVersion)
                } else {
                    Detail(open, status, busy, ask, actions)
                }
            }
        }
    }

    confirm?.let { go ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.restart_title)) },
            text = { Text(stringResource(R.string.restart_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        go()
                    },
                ) { Text(stringResource(R.string.restart)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ------------------------------------------------------------------ first screen

@Composable
private fun ColumnScope.Home(
    status: Status,
    busy: Boolean,
    theme: ThemeChoice,
    selected: Tile?,
    onOpen: (Tile) -> Unit,
    ask: (() -> Unit) -> Unit,
    actions: Actions,
    appVersion: String,
) {
    if (status.paused) {
        Banner(
            title = stringResource(R.string.paused_title),
            text = stringResource(R.string.paused_text),
            button = stringResource(R.string.resume),
            busy = busy,
            onClick = actions.resume,
        )
    } else if (status.anyPending) {
        Banner(
            title = stringResource(R.string.pending_title),
            text = stringResource(R.string.pending_text),
            button = stringResource(R.string.apply),
            busy = busy,
            onClick = { if (status.applyRestarts) ask(actions.apply) else actions.apply() },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(Tile.GPU, Tile.RESERVE).forEach { tile ->
                TileCard(
                    tile = tile,
                    status = status,
                    selected = selected == tile,
                    onClick = { onOpen(tile) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
        TileCard(
            tile = Tile.KEEP,
            status = status,
            selected = selected == Tile.KEEP,
            onClick = { onOpen(Tile.KEEP) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (status.keepWhy.isEmpty()) {
                KeptBar(status)
                Text(
                    stringResource(R.string.keep_count, status.keptCount, Keep.entries.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeChoice.entries.forEach { choice ->
            OutlinedToggleButton(
                checked = theme == choice,
                onCheckedChange = { if (it) actions.setTheme(choice) },
            ) { Text(stringResource(choice.label)) }
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.copyReport) { Text(stringResource(R.string.copy_report)) }
            TextButton(onClick = actions.openSource) { Text(stringResource(R.string.source)) }
            Spacer(Modifier.weight(1f))
            Text(
                status.version.ifEmpty { appVersion },
                Modifier.padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Hint(stringResource(R.string.undo_hint), Modifier.padding(horizontal = 12.dp))
    }
}

/** One tile: what the fix is called and, in a few words, how it stands. Tapping opens it. */
@Composable
internal fun TileCard(
    tile: Tile,
    status: Status,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val on = status.on(tile)
    val tone = status.tone(tile)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = if (on) scheme.primaryContainer else scheme.surfaceContainer,
        contentColor = if (on) scheme.onPrimaryContainer else scheme.onSurface,
        border = when {
            selected -> BorderStroke(2.dp, scheme.primary)
            on -> null
            else -> BorderStroke(1.dp, scheme.outlineVariant)
        },
    ) {
        Column(
            Modifier
                .heightIn(min = 132.dp)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(tile.icon), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.weight(1f))
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(tile.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    status.short(tile).text(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        on -> scheme.onPrimaryContainer
                        tone == Tone.BAD -> scheme.error
                        tone == Tone.WAITING -> scheme.tertiary
                        else -> scheme.onSurfaceVariant
                    },
                )
            }
            extra?.invoke(this)
        }
    }
}

/** How much of the phone's RAM is kept, to scale. */
@Composable
internal fun KeptBar(status: Status, modifier: Modifier = Modifier) {
    val total = status.memTotalKb ?: return
    if (total <= 0) return
    val part = (status.keptKb.toFloat() / total).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(LocalContentColor.current.copy(alpha = 0.16f)),
    ) {
        if (part > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(part)
                    .fillMaxHeight()
                    .background(LocalContentColor.current, RoundedCornerShape(4.dp)),
            )
        }
    }
}

// ------------------------------------------------------------------ one fix

@Composable
private fun ColumnScope.Detail(
    tile: Tile,
    status: Status,
    busy: Boolean,
    ask: (() -> Unit) -> Unit,
    actions: Actions,
) {
    when (tile) {
        Tile.GPU -> FixDetail(Fix.GPU, status, busy, actions)
        Tile.RESERVE -> FixDetail(Fix.RESERVE, status, busy, actions)
        Tile.KEEP -> KeepDetail(status, busy, ask, actions)
    }
}

@Composable
private fun ColumnScope.FixDetail(fix: Fix, status: Status, busy: Boolean, actions: Actions) {
    val usable = status.state(fix) != FixState.NA
    Hint(stringResource(fix.about), Modifier.padding(horizontal = 8.dp))
    SwitchRow(
        title = stringResource(fix.switch),
        role = null,
        tone = status.tone(fix),
        state = status.line(fix).text(),
        checked = usable && status.wanted(fix),
        enabled = usable && !busy,
        onToggle = { actions.set(fix.key, it) },
        shape = segment(0, 1),
    )
    Cost(stringResource(fix.cost))
}

@Composable
private fun ColumnScope.KeepDetail(status: Status, busy: Boolean, ask: (() -> Unit) -> Unit, actions: Actions) {
    Hint(stringResource(R.string.keep_about), Modifier.padding(horizontal = 8.dp))
    if (status.keepWhy.isNotEmpty()) {
        StateLine(Tone.IDLE, status.keepReason().text(), Modifier.padding(horizontal = 8.dp))
    } else {
        val total = status.memTotalKb
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = segment(0, 1),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (total != null && total > 0) {
                        stringResource(R.string.keep_total, size(status.keptKb), size(total))
                    } else {
                        stringResource(R.string.keep_short_on, size(status.keptKb))
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                KeptBar(status)
            }
        }
    }
    KeepGroup(
        title = stringResource(R.string.keep_apps),
        hint = stringResource(R.string.keep_apps_hint),
        keeps = Keep.entries.filter { it.restarts },
        status = status,
        busy = busy,
        ask = ask,
        actions = actions,
    )
    KeepGroup(
        title = stringResource(R.string.keep_system),
        hint = stringResource(R.string.keep_system_hint),
        keeps = Keep.entries.filter { !it.restarts },
        status = status,
        busy = busy,
        ask = ask,
        actions = actions,
    )
    Cost(stringResource(R.string.keep_cost))
}

@Composable
private fun KeepGroup(
    title: String,
    hint: String,
    keeps: List<Keep>,
    status: Status,
    busy: Boolean,
    ask: (() -> Unit) -> Unit,
    actions: Actions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Hint(hint)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            keeps.forEachIndexed { i, keep ->
                val kept = status.kept(keep)
                val usable = kept.state != KeepState.NA && kept.state != KeepState.NONE
                SwitchRow(
                    title = stringResource(keep.title),
                    role = stringResource(keep.role),
                    tone = status.tone(keep),
                    state = status.line(keep).text(),
                    checked = usable && kept.wanted,
                    enabled = usable && !busy,
                    onToggle = { on ->
                        if (on && keep.restarts) ask { actions.set(keep.key, true) } else actions.set(keep.key, on)
                    },
                    shape = segment(i, keeps.size),
                )
            }
        }
    }
}

/** One switch: what it is, what it is for, and its state with the numbers. */
@Composable
internal fun SwitchRow(
    title: String,
    role: String?,
    tone: Tone,
    state: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    shape: Shape,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(start = 20.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (role != null) Hint(role)
                StateLine(tone, state)
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

@Composable
private fun Cost(text: String) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.what_it_costs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Hint(text)
    }
}

// ------------------------------------------------------------ building blocks

/** The corners of row [index] in a stack of [count]: round outside, nearly square where rows meet. */
internal fun segment(index: Int, count: Int): Shape {
    val top = if (index == 0) 24.dp else 4.dp
    val bottom = if (index == count - 1) 24.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

@Composable
internal fun StateLine(tone: Tone, text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val color = when (tone) {
        Tone.GOOD -> scheme.primary
        Tone.WAITING -> scheme.tertiary
        Tone.BAD -> scheme.error
        Tone.IDLE -> scheme.outline
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (tone == Tone.IDLE) scheme.onSurfaceVariant else scheme.onSurface,
        )
    }
}

/** Something to do before the fixes work: apply after an install, resume after a failed start. */
@Composable
internal fun Banner(
    title: String,
    text: String,
    button: String? = null,
    busy: Boolean = false,
    onClick: () -> Unit = {},
) {
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
            if (button != null) {
                Spacer(Modifier.width(12.dp))
                Button(onClick = onClick, enabled = !busy) { Text(button) }
            }
        }
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = color)
}
