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
fun Line.text(): String {
    val count = quantity ?: return stringResource(id, *args.toTypedArray())
    return LocalContext.current.resources.getQuantityString(id, count, *args.toTypedArray())
}

/** Under the app's name: how many of the three are working. With none, what the app is for. */
@Composable
fun headline(status: Status): String = when {
    status.paused -> stringResource(R.string.headline_paused)
    status.working == 0 -> stringResource(R.string.tagline)
    else -> LocalContext.current.resources.getQuantityString(R.plurals.fixes_working, status.working, status.working)
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
            listOf(Tile.ALLOC, Tile.RESERVE).forEach { tile ->
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
            if (status.on(Tile.KEEP)) KeptBar(status)
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
}

/** One tile: what the fix is called and, in a word, how it stands. Tapping opens it. */
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
        Tile.ALLOC, Tile.RESERVE -> FixDetail(tile, status, busy, actions)
        Tile.KEEP -> KeepDetail(status, busy, ask, actions)
    }
}

/** A switch for each fix of the tile and one sentence. */
@Composable
private fun ColumnScope.FixDetail(tile: Tile, status: Status, busy: Boolean, actions: Actions) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        tile.fixes.forEachIndexed { i, fix ->
            val usable = status.state(fix) != FixState.NA
            SwitchRow(
                title = stringResource(fix.switch),
                summary = status.note(fix)?.text(),
                summaryColor = status.tone(fix).color(),
                checked = usable && status.wanted(fix),
                enabled = usable && !busy,
                onToggle = { actions.set(fix.key, it) },
                shape = segment(i, tile.fixes.size),
            )
        }
    }
    Hint(stringResource(tile.about), Modifier.padding(horizontal = 8.dp))
}

/** How much is kept, one sentence, and a switch per process. */
@Composable
private fun ColumnScope.KeepDetail(status: Status, busy: Boolean, ask: (() -> Unit) -> Unit, actions: Actions) {
    if (status.keepWhy.isNotEmpty()) {
        Hint(status.keepReason().text(), Modifier.padding(horizontal = 8.dp))
    } else {
        val total = status.memTotalKb
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = segment(0, 1),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Text(
                        size(status.keptKb),
                        Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (total != null && total > 0) {
                        Spacer(Modifier.width(8.dp))
                        Hint(stringResource(R.string.keep_of, size(total)), Modifier.alignByBaseline())
                    }
                }
                KeptBar(status)
            }
        }
        Hint(stringResource(R.string.keep_about), Modifier.padding(horizontal = 8.dp))
    }
    KeepGroup(stringResource(R.string.keep_apps), Keep.entries.filter { it.restarts }, status, busy, ask, actions)
    KeepGroup(stringResource(R.string.keep_system), Keep.entries.filter { !it.restarts }, status, busy, ask, actions)
}

@Composable
private fun KeepGroup(
    title: String,
    keeps: List<Keep>,
    status: Status,
    busy: Boolean,
    ask: (() -> Unit) -> Unit,
    actions: Actions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            Modifier.padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            keeps.forEachIndexed { i, keep ->
                val kept = status.kept(keep)
                val usable = kept.state != KeepState.NA && kept.state != KeepState.NONE
                SwitchRow(
                    title = stringResource(keep.title),
                    summary = stringResource(keep.role),
                    value = status.value(keep)?.text(),
                    valueColor = status.tone(keep).color(),
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

/** One switch: its name, one line under it, and at most a number or a word beside it. */
@Composable
internal fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    shape: Shape,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier
                .heightIn(min = 72.dp)
                .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary != null) Hint(summary, color = summaryColor)
            }
            // tight on purpose: on a narrow cover screen with large text the name needs the room
            if (value != null) {
                Spacer(Modifier.width(8.dp))
                Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

// ------------------------------------------------------------ building blocks

/** The corners of row [index] in a stack of [count]: round outside, nearly square where rows meet. */
internal fun segment(index: Int, count: Int): Shape {
    val top = if (index == 0) 24.dp else 4.dp
    val bottom = if (index == count - 1) 24.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** Waiting and failed stand out; everything else is said quietly. */
@Composable
private fun Tone.color(): Color = when (this) {
    Tone.WAITING -> MaterialTheme.colorScheme.tertiary
    Tone.BAD -> MaterialTheme.colorScheme.error
    Tone.GOOD, Tone.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Something to do before the fixes work: apply after an install, resume after a failed start. */
@Composable
internal fun Banner(
    title: String,
    text: String? = null,
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
                if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
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
