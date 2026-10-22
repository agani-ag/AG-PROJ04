package com.agani.syncup.decide

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.ui.theme.isLight
import com.agani.syncup.wellbeing.RoundButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The Wheel spinner (docs/mockup/syncup-everyday-tools.html, phones 6–11): a wheel of anything.
// First choice — the first option it lands on wins. Last choice — each spin knocks one out, the
// last one left wins (spin each round, or Automatic). "Spin for everyone" plays it on every phone
// of a SyncUp Group; a wheel spun by someone else shows here as a temporary wheel.

private enum class WheelSheet { NONE, OPTIONS, WHEELS, RESULTS }
private enum class WheelDialog { NONE, NEW, RENAME, DELETE }

@Composable
internal fun WheelScreen(onBack: () -> Unit, onOpenGroup: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    WheelStore.init(context)
    val scope = rememberCoroutineScope()
    val game = remember { WheelGame(context, scope) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DecideFx.warmUp(context) } }

    // Every phone's spins arrive here, this one's too.
    val latest = Together.latest
    LaunchedEffect(latest) { latest?.let(game::onPlay) }

    var sheet by remember { mutableStateOf(WheelSheet.NONE) }
    var dialog by remember { mutableStateOf(WheelDialog.NONE) }

    val w = game.wheel
    val shared = game.shared
    val last = w.mode == WheelMode.LAST
    val busy = game.spinning || game.autoRunning || game.following
    val tooFew = w.left.size < 2 && (!last || w.options.size < 2)
    val over = last && !tooFew && w.left.size <= 1

    val everyone: () -> Unit = { if (Together.available) game.spinForEveryone() else onOpenGroup() }
    // The SPIN hub and shaking do what the main button does (never Pause).
    val main: () -> Unit = {
        when {
            game.cardOpen || game.spinning -> Unit
            shared != null -> if (!busy && !over && !tooFew) everyone()
            tooFew -> sheet = WheelSheet.OPTIONS
            over -> Unit
            last && w.auto -> if (!game.autoRunning) game.startAuto()
            else -> game.spin()
        }
    }

    ShakeToDo(enabled = shared == null && !busy && !game.cardOpen && !tooFew && !over && sheet == WheelSheet.NONE && dialog == WheelDialog.NONE) { main() }
    BackHandler(enabled = game.cardOpen) { game.closeCards() }

    Box(Modifier.fillMaxSize().background(cs.surface)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ToolTopBar("Wheel spinner", onBack) {
                if (Together.available && Together.phones > 0) GroupChip(Together.phones)
                WheelMenu()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                if (shared != null) SharedBanner(shared, onClose = game::leaveShared)
                NameRow(w, enabled = shared == null && !busy) { sheet = WheelSheet.WHEELS }

                // Locked while spinning (taps ignored); greyed only for a shared wheel, whose mode is the starter's.
                val locked = shared != null || busy
                val fade = if (shared != null) .6f else 1f
                PillTabs(
                    listOf("First choice", "Last choice"), selected = if (last) 1 else 0,
                    onSelect = { i ->
                        if (!locked) {
                            game.closeCards()
                            game.setWheel(w.copy(mode = if (i == 1) WheelMode.LAST else WheelMode.FIRST))
                        }
                    },
                    modifier = Modifier.padding(top = 10.dp).alpha(fade),
                )
                if (last) {
                    SmallPills(
                        listOf("Manual", "Automatic"), selected = if (w.auto) 1 else 0,
                        onSelect = { i -> if (!locked) game.setWheel(w.copy(auto = i == 1)) },
                        modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp).alpha(fade),
                    )
                }
                Text(
                    if (last) "Each spin knocks one out. The last one left wins." else "The first option the wheel lands on wins.",
                    fontSize = 12.5.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                WheelStage(game, last, onHubTap = main)

                if (shared == null) {
                    ToolsRow(
                        enabled = !busy,
                        onShuffle = { game.setWheel(w.shuffled()); DecideFx.buzz(context, 12, 80) },
                        onSort = { game.setWheel(w.sortedAz()) },
                        onResults = { sheet = WheelSheet.RESULTS },
                        onEdit = { sheet = WheelSheet.OPTIONS },
                    )
                }
                if (last && w.auto && !over) {
                    val running = game.autoRunning || game.following || (shared != null && game.spinning)
                    if (running) AutoChip("Round ${w.round} · spinning…")
                    else if (w.out.isNotEmpty()) AutoChip("Round ${w.round} · paused")
                }
                if (last && w.out.isNotEmpty()) OutChips(w.outOptions, "Out so far", Modifier.padding(top = 8.dp))
                if (tooFew && shared == null) FewNote { sheet = WheelSheet.OPTIONS }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
                    if (shared == null) MyButtons(game, w, tooFew, over, onEdit = { sheet = WheelSheet.OPTIONS }, onEveryone = everyone)
                    else SharedButtons(game, over, tooFew, onEveryone = everyone)
                }
            }
        }

        val win = game.winner
        val lastOne = game.lastOne
        if (win != null || lastOne != null) Scrim(onTap = game::closeCards)
        Confetti(if (game.confetti == 0) null else game.confetti)
        if (win != null) {
            WinnerCard(
                win.option, earlier = w.recent.drop(1).take(3),
                onRemove = game::removeWinner,
                onAgain = {
                    game.closeCards()
                    if (shared != null) everyone() else game.spin()
                },
            )
        }
        if (lastOne != null) {
            LastCard(
                lastOne, w.outOptions,
                onShare = { shareLastOne(context, w, lastOne) },
                onAgain = game::playAgain,
            )
        }
    }

    when (sheet) {
        WheelSheet.NONE -> Unit
        WheelSheet.OPTIONS -> WheelOptionsSheet(WheelStore.current) { options, moved ->
            val saved = WheelStore.current
            WheelStore.update(saved.copy(options = options, out = saved.out.mapNotNull { moved[it] }))
            sheet = WheelSheet.NONE
        }
        WheelSheet.WHEELS -> WheelsSheet(
            onDismiss = { sheet = WheelSheet.NONE },
            onNew = { sheet = WheelSheet.NONE; dialog = WheelDialog.NEW },
            onRename = { sheet = WheelSheet.NONE; dialog = WheelDialog.RENAME },
            onDelete = { sheet = WheelSheet.NONE; dialog = WheelDialog.DELETE },
        )
        WheelSheet.RESULTS -> WheelResultsSheet(
            w,
            onClear = { WheelStore.update(WheelStore.current.copy(recent = emptyList())) },
            onDismiss = { sheet = WheelSheet.NONE },
        )
    }

    when (dialog) {
        WheelDialog.NONE -> Unit
        WheelDialog.NEW -> WheelNameDialog("New wheel", "", "Next", onDismiss = { dialog = WheelDialog.NONE }, onDone = { name ->
            game.closeCards()
            WheelStore.add(name)
            dialog = WheelDialog.NONE
            // Straight on to what goes on it.
            sheet = WheelSheet.OPTIONS
        })
        WheelDialog.RENAME -> WheelNameDialog("Rename wheel", WheelStore.current.name, "Save", onDismiss = { dialog = WheelDialog.NONE }, onDone = { name ->
            WheelStore.update(WheelStore.current.copy(name = name.take(WHEEL_NAME_MAX)))
            dialog = WheelDialog.NONE
        })
        WheelDialog.DELETE -> {
            val target = WheelStore.current
            AlertDialog(
                onDismissRequest = { dialog = WheelDialog.NONE },
                title = { Text("Delete “${target.name}”?") },
                text = { Text("Its options and picks go too.") },
                confirmButton = {
                    TextButton(onClick = {
                        game.closeCards()
                        WheelStore.delete(target.id)
                        dialog = WheelDialog.NONE
                    }) { Text("Delete", color = cs.error) }
                },
                dismissButton = { TextButton(onClick = { dialog = WheelDialog.NONE }) { Text("Cancel") } },
            )
        }
    }
}

/** The wheel, as big as fits: a little smaller in Last choice, where the out list needs room. */
@Composable
private fun WheelStage(game: WheelGame, last: Boolean, onHubTap: () -> Unit) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        val size = min(min(maxWidth, if (last) 264.dp else 300.dp), screenH * .37f)
        WheelCanvas(
            slices = game.wheel.left,
            rotation = { game.rotation },
            highlight = game.highlight,
            size = size,
            onHubTap = onHubTap,
        )
    }
}

/** The wheel's name (opens the saved wheels) and its count. */
@Composable
private fun NameRow(w: Wheel, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = "Your wheels", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        // The name and its ▾ take the room on the left; the count stays at the right edge.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(w.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (w.id != SHARED_WHEEL_ID) Icon(Icons.Rounded.KeyboardArrowDown, null, tint = cs.onSurface, modifier = Modifier.padding(start = 2.dp).size(20.dp))
        }
        Text(w.countText, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Shuffle · sort A–Z · recent picks · edit: round soft buttons under the wheel. */
@Composable
private fun ToolsRow(enabled: Boolean, onShuffle: () -> Unit, onSort: () -> Unit, onResults: () -> Unit, onEdit: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        SoftIcon(Icons.Rounded.Shuffle, "Shuffle", enabled, onShuffle)
        SoftIcon(Icons.Rounded.SortByAlpha, "Sort A to Z", enabled, onSort)
        SoftIcon(Icons.Rounded.History, "Recent picks", true, onResults)
        SoftIcon(Icons.Rounded.Edit, "Edit options", enabled, onEdit)
    }
}

@Composable
private fun SoftIcon(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(cs.surfaceContainer).clickable(enabled = enabled, onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = cs.onSurface.copy(alpha = if (enabled) 1f else .38f), modifier = Modifier.size(20.dp)) }
}

/** "Round 6 · spinning…" in the wheel's soft colour. */
@Composable
private fun AutoChip(text: String) {
    val t = DecideTint.WHEEL
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(t.tile).padding(horizontal = 12.dp),
        ) {
            Icon(Icons.Rounded.Refresh, null, tint = t.icon, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = t.icon, maxLines = 1)
        }
    }
}

/** The gentle note when there's not enough on the wheel. */
@Composable
private fun FewNote(onAdd: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer)
            .clickable(onClick = onAdd).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("Add at least 2 options to spin.", fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Add", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = DecideTint.WHEEL.icon)
    }
}

/** The buttons for a saved wheel. */
@Composable
private fun MyButtons(game: WheelGame, w: Wheel, tooFew: Boolean, over: Boolean, onEdit: () -> Unit, onEveryone: () -> Unit) {
    val tint = DecideTint.WHEEL
    val last = w.mode == WheelMode.LAST
    when {
        tooFew -> DecideButton("Add options", tint, onClick = onEdit, icon = Icons.Rounded.Edit)
        over -> DecideButton("Play again", tint, onClick = game::playAgain, icon = Icons.Rounded.Refresh)
        last && w.auto && game.autoRunning -> DecideButton("Pause", tint, onClick = game::pause, soft = true, icon = Icons.Rounded.Pause)
        last && w.auto -> if (w.out.isEmpty()) DecideButton("Spin", tint, onClick = game::startAuto, enabled = !game.spinning)
        else DecideButton("Resume", tint, onClick = game::startAuto, icon = Icons.Rounded.PlayArrow)
        last -> DecideButton("Spin — round ${w.round}", tint, onClick = game::spin, enabled = !game.spinning)
        else -> DecideButton("Spin", tint, onClick = game::spin, enabled = !game.spinning)
    }
    if (!tooFew && !over && !game.autoRunning) {
        DecideButton("Spin for everyone", tint, onClick = onEveryone, soft = true, icon = Icons.Rounded.Groups, enabled = !game.spinning)
    }
}

/** The buttons while a shared wheel shows: anyone can spin the next one for everyone. */
@Composable
private fun SharedButtons(game: WheelGame, over: Boolean, tooFew: Boolean, onEveryone: () -> Unit) {
    val tint = DecideTint.WHEEL
    when {
        game.autoRunning -> DecideButton("Pause", tint, onClick = game::pause, soft = true, icon = Icons.Rounded.Pause)
        game.following -> Unit
        over -> DecideButton("Play again", tint, onClick = game::playAgain, icon = Icons.Rounded.Refresh)
        else -> DecideButton("Spin for everyone", tint, onClick = onEveryone, icon = Icons.Rounded.Groups, enabled = !game.spinning && !tooFew)
    }
    DecideButton("Back to my wheel", tint, onClick = game::leaveShared, soft = true, icon = Icons.AutoMirrored.Rounded.ArrowBack)
}

/** "Arun spun for everyone" — the wheel on screen came from the group. */
@Composable
private fun SharedBanner(s: SharedWheel, onClose: () -> Unit) {
    val t = ToolTint.INDIGO
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(18.dp)).background(t.tile).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(t.icon), contentAlignment = Alignment.Center) {
            if (s.mine) Icon(Icons.Rounded.Groups, null, tint = t.onSolid, modifier = Modifier.size(17.dp))
            else Text(s.by.trim().take(1).uppercase().ifEmpty { "?" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.onSolid)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                if (s.mine) append("You spun for everyone")
                else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(s.by.ifBlank { "Someone" }) }
                    append(" spun for everyone")
                }
            },
            fontSize = 14.sp, color = t.icon, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back to my wheel", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Back to my wheel", tint = t.icon, modifier = Modifier.size(18.dp))
        }
    }
}

/** "4 phones" next to ⋮ while in a group. */
@Composable
private fun GroupChip(phones: Int) {
    val t = ToolTint.INDIGO
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(t.tile).padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Rounded.Groups, null, tint = t.icon, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text("$phones phone${if (phones == 1) "" else "s"}", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = t.icon)
    }
}

/** ⋮: Sound & buzz, and Spin time (− n s +). */
@Composable
private fun WheelMenu() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(DecideFx.enabled(context)) }
    var secs by remember { mutableIntStateOf(WheelStore.spinSeconds(context)) }
    Box {
        BarIcon(Icons.Rounded.MoreVert, "More", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(18.dp), containerColor = cs.surfaceContainerLowest) {
            DropdownMenuItem(
                text = { Text("Sound & buzz") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
                trailingIcon = { Switch(checked = sound, onCheckedChange = null) },
                onClick = {
                    sound = !sound
                    DecideFx.setEnabled(context, sound)
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)) {
                Icon(Icons.Rounded.Timer, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Spin time", style = MaterialTheme.typography.labelLarge, color = cs.onSurface, modifier = Modifier.padding(end = 16.dp))
                RoundButton("−", "Shorter", onClick = {
                    secs = (secs - 1).coerceIn(WheelStore.SPIN_RANGE)
                    WheelStore.setSpinSeconds(context, secs)
                })
                Text("$secs s", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
                RoundButton("+", "Longer", onClick = {
                    secs = (secs + 1).coerceIn(WheelStore.SPIN_RANGE)
                    WheelStore.setSpinSeconds(context, secs)
                })
            }
        }
    }
}

/** Like [PillTabs], one size smaller: the sub-choice under it (Manual / Automatic). */
@Composable
private fun SmallPills(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(cs.surfaceContainer).padding(3.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) cs.surfaceContainerLowest else Color.Transparent, tween(150), label = "pill")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).height(30.dp)
                    .then(if (on && cs.isLight) Modifier.shadow(1.dp, RoundedCornerShape(50), clip = false) else Modifier)
                    .clip(RoundedCornerShape(50)).background(bg).clickable(onClickLabel = label) { onSelect(i) },
            ) {
                Text(
                    label, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) cs.onSurface else cs.onSurfaceVariant,
                )
            }
        }
    }
}

/** The dim layer behind a result card; a tap on it closes the card. */
@Composable
private fun Scrim(onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize()
            .background(if (cs.isLight) Color(0x5C0F172A) else Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    )
}

/** The card for a result: white, big corners, over the scrim (taps on it stay on it). */
@Composable
private fun BoxScope.ResultCard(alignment: Alignment, content: @Composable () -> Unit) {
    Column(
        Modifier.align(alignment).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 28.dp)
            .fillMaxWidth().appCard(RoundedCornerShape(28.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/** First choice: the winner in its slice's colour. */
@Composable
private fun BoxScope.WinnerCard(win: WheelOption, earlier: List<String>, onRemove: () -> Unit, onAgain: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = DecideTint.WHEEL
    ResultCard(Alignment.BottomCenter) {
        Text("First choice · the wheel picked", fontSize = 13.sp, color = cs.onSurfaceVariant)
        Text(
            win.name, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, color = wheelColor(win.color),
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        if (earlier.isNotEmpty()) {
            Text("Earlier picks: ${earlier.joinToString(" · ")}", fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            DecideButton("Remove", tint, onClick = onRemove, modifier = Modifier.weight(1f), soft = true, icon = Icons.Rounded.DeleteOutline)
            DecideButton("Spin again", tint, onClick = onAgain, modifier = Modifier.weight(1f))
        }
    }
}

/** Last choice: the last one left, and who went out in what order. */
@Composable
private fun BoxScope.LastCard(winner: WheelOption, out: List<WheelOption>, onShare: () -> Unit, onAgain: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = DecideTint.WHEEL
    ResultCard(Alignment.Center) {
        Text("Last choice · the last one left", fontSize = 13.sp, color = cs.onSurfaceVariant)
        Text(
            winner.name, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, color = wheelColor(winner.color),
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
        )
        if (out.isNotEmpty()) {
            // A long out list scrolls inside the card, so the buttons stay on screen.
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                OutChips(out, "Out order", struck = false)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            DecideButton("Share", tint, onClick = onShare, modifier = Modifier.weight(1f), soft = true, icon = Icons.Rounded.Share)
            DecideButton("Play again", tint, onClick = onAgain, modifier = Modifier.weight(1f))
        }
    }
}

/** "Wheel Team: last one left — Priya. Out order: 1 Dev, 2 Sara, …" to any app. */
private fun shareLastOne(context: Context, w: Wheel, winner: WheelOption) {
    val order = w.outOptions.mapIndexed { i, o -> "${i + 1} ${o.name}" }.joinToString(", ")
    val text = "Wheel ${w.name}: last one left — ${winner.name}." + if (order.isNotEmpty()) " Out order: $order" else ""
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share result")) }
}
