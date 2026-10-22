package com.agani.syncup.shopping

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agani.syncup.browser.ui.BarIcon
import com.agani.syncup.music.CardDivider
import com.agani.syncup.music.ToolTile
import com.agani.syncup.music.ToolTint
import com.agani.syncup.music.ToolTopBar
import com.agani.syncup.music.cardSlice
import com.agani.syncup.ui.theme.PillTab
import com.agani.syncup.ui.theme.PillTabs
import com.agani.syncup.ui.theme.appCard
import com.agani.syncup.wellbeing.Choices
import com.agani.syncup.wellbeing.RoundButton
import com.agani.syncup.wellbeing.SheetLab
import com.agani.syncup.wellbeing.WTint

// Tools → Everyday → Shopping (docs/mockup/v7/everyday_page.py, phones 14–17): lists in folders,
// items sorted by aisle with a Bought pile, quick chips from things bought before, and a Split bill tab.

/** The Shopping tile's line: the list with the most to buy ("Groceries · 6 to buy"), or null. */
internal fun shoppingStatus(): String? {
    if (!ShoppingStore.ready) return null
    var best: ShopList? = null
    var most = 0
    for (l in ShoppingStore.lists) {
        val n = ShoppingStore.counts(l.id).first
        if (n > most) {
            most = n
            best = l
        }
    }
    return best?.let { "${it.name} · $most to buy" }
}

/**
 * Tools → Shopping: shopping lists (each a folder with its own colour) and Split bill. Lists sync to
 * the user's SyncUp account (Settings → Sync → Shopping lists); signed out they stay on this phone.
 */
@Composable
internal fun ShoppingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { ShoppingStore.init(context) }
    LaunchedEffect(Unit) { ShoppingSync.syncNow() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openId?.let { ShoppingStore.list(it) }
    // The open list was deleted (here or on another phone): back to the lists.
    LaunchedEffect(openId, open == null) { if (openId != null && open == null) openId = null }

    if (open != null) {
        BackHandler { openId = null }
        ListPage(open, onBack = { openId = null })
    } else {
        ShoppingHome(onBack = onBack, onOpen = { openId = it })
    }
}

// ============================================================================ Lists | Split bill
@Composable
private fun ShoppingHome(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var newList by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            ToolTopBar("Shopping", onBack) {
                Box {
                    BarIcon(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(18.dp), containerColor = cs.surfaceContainerLowest) {
                        DropdownMenuItem(text = { Text("New list") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = {
                            menu = false
                            tab = 0
                            newList = true
                        })
                        if (ShoppingSync.active) {
                            DropdownMenuItem(text = { Text("Sync now") }, leadingIcon = { Icon(Icons.Rounded.Sync, null) }, onClick = {
                                menu = false
                                ShoppingSync.syncNow()
                            })
                        }
                        if (ShoppingStore.history.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Forget past items") }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = {
                                menu = false
                                confirmForget = true
                            })
                        }
                    }
                }
            }
            PillTabs(
                listOf(PillTab("Lists", Icons.Rounded.ShoppingCart), PillTab("Split bill", Icons.Rounded.Receipt)),
                selected = tab, onSelect = { tab = it }, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (tab == 0) {
                ListsTab(onOpen = onOpen, onNew = { newList = true }, modifier = Modifier.weight(1f))
            } else {
                SplitBillTab(Modifier.weight(1f))
            }
        }
    }

    if (newList) {
        ListSheet(null, focusName = true, onDone = { created ->
            newList = false
            if (created != null) onOpen(created)
        })
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget past items?") },
            text = { Text("The quick chips start fresh. Your lists stay as they are.") },
            confirmButton = { TextButton(onClick = { ShoppingStore.forgetHistory(); confirmForget = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ListsTab(onOpen: (String) -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val lists = ShoppingStore.lists
    var editing by remember { mutableStateOf<Pair<ShopList, Boolean>?>(null) }
    var deleting by remember { mutableStateOf<ShopList?>(null) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp)) {
        if (lists.isEmpty()) {
            Text(
                "No lists yet. Make one for groceries, a festival or a trip.",
                fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
            )
        } else {
            Column(Modifier.fillMaxWidth().appCard()) {
                lists.forEachIndexed { i, l ->
                    if (i > 0) CardDivider()
                    FolderRow(
                        l,
                        onOpen = { onOpen(l.id) },
                        onRename = { editing = l to true },
                        onColour = { editing = l to false },
                        onDelete = { deleting = l },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        ShopSoftButton("New list", Icons.Rounded.Add, ToolTint.GREEN, onClick = onNew)
    }

    editing?.let { (l, focus) -> ListSheet(l, focusName = focus, onDone = { editing = null }) }
    deleting?.let { l -> DeleteListDialog(l, onDone = { deleting = null }) }
}

/** One list as a folder: its colour tile, name, how much is left, a thin bar of what's bought. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(l: ShopList, onOpen: () -> Unit, onRename: () -> Unit, onColour: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val (toBuy, bought) = ShoppingStore.counts(l.id)
    val total = toBuy + bought
    val sub = when {
        total == 0 -> "Empty"
        toBuy == 0 -> "All bought"
        bought == 0 -> "$toBuy to buy"
        else -> "$toBuy to buy · $bought bought"
    }
    val tint = l.tint
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "More for this list")
                .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        ) {
            ToolTile(Icons.Rounded.Folder, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(l.name, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, fontSize = 12.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
                Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceContainer)) {
                    if (total > 0 && bought > 0) {
                        Box(Modifier.fillMaxWidth(bought.toFloat() / total).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(tint.icon))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 8.dp)) {
            ListMenu(menu, onDismiss = { menu = false }, onRename = onRename, onColour = onColour, onDelete = onDelete)
        }
    }
}

/** Rename · Colour · (Uncheck all) · Delete — for a list. */
@Composable
private fun ListMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onColour: () -> Unit,
    onDelete: () -> Unit,
    onUncheckAll: (() -> Unit)? = null,
    canUncheck: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp), containerColor = cs.surfaceContainerLowest) {
        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { onDismiss(); onRename() })
        DropdownMenuItem(text = { Text("Colour") }, leadingIcon = { Icon(Icons.Rounded.Palette, null) }, onClick = { onDismiss(); onColour() })
        if (onUncheckAll != null) {
            DropdownMenuItem(
                text = { Text("Uncheck all") }, leadingIcon = { Icon(Icons.Rounded.RemoveDone, null) }, enabled = canUncheck,
                onClick = { onDismiss(); onUncheckAll() },
            )
        }
        DropdownMenuItem(
            text = { Text(if (onUncheckAll != null) "Delete list" else "Delete", color = cs.error) },
            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = cs.error) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

/** New list, or a list's name and colour. [onDone] gets the new list's id when one was made. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ListSheet(list: ShopList?, focusName: Boolean, onDone: (String?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(list?.name ?: "") }
    var color by remember {
        mutableStateOf(list?.color ?: (ShopColors.firstOrNull { c -> ShoppingStore.lists.none { it.color == c.name } } ?: ToolTint.GREEN).name)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusName) runCatching { focus.requestFocus() } }

    fun save() {
        if (name.isBlank()) return
        if (list == null) {
            onDone(ShoppingStore.newList(name, color).id)
        } else {
            ShoppingStore.saveList(list.copy(name = name.trim(), color = color))
            onDone(null)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onDone(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 20.dp)) {
            SheetHead(if (list == null) "New list" else "Edit list")
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                placeholder = { Text("Name, like Groceries") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
            SheetLab("Colour")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                ShopColors.forEach { t ->
                    val on = t.name == color
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(t.icon)
                            .then(if (on) Modifier.border(3.dp, t.tile, CircleShape) else Modifier)
                            .clickable(onClickLabel = t.name.lowercase()) { color = t.name },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Icon(Icons.Rounded.Check, "Picked", tint = t.onSolid, modifier = Modifier.size(20.dp))
                    }
                }
            }
            SheetButtons(
                confirm = if (list == null) "Create" else "Save", enabled = name.isNotBlank(),
                onCancel = { onDone(null) }, onConfirm = ::save,
            )
        }
    }
}

@Composable
private fun DeleteListDialog(l: ShopList, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val n = ShoppingStore.counts(l.id).let { it.first + it.second }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete “${l.name}”?") },
        text = { Text(if (n == 0) "The list is empty." else "Its $n item${if (n == 1) "" else "s"} will go too.") },
        confirmButton = { TextButton(onClick = { ShoppingStore.deleteList(l.id); onDone() }) { Text("Delete", color = cs.error) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ============================================================================ inside a list
@Composable
private fun ListPage(list: ShopList, onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editList by remember { mutableStateOf<Boolean?>(null) }
    var deleting by remember { mutableStateOf(false) }

    val all = ShoppingStore.itemsOf(list.id)
    val toBuy = all.filter { !it.bought }
    val bought = all.filter { it.bought }.sortedByDescending { it.boughtMs }
    val groups = Aisle.entries.mapNotNull { a ->
        toBuy.filter { it.aisleOf == a }.sortedWith(compareBy({ it.order }, { it.name.lowercase() })).takeIf { it.isNotEmpty() }?.let { a to it }
    }
    val chips = ShoppingStore.suggestions(list.id)

    Box(Modifier.fillMaxSize().background(cs.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            ToolTopBar(list.name, onBack) {
                BarIcon(Icons.Rounded.Share, "Share the list", enabled = all.isNotEmpty(), onClick = { shareText(context, shareListText(list, all)) })
                Box {
                    BarIcon(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
                    ListMenu(
                        menu, onDismiss = { menu = false },
                        onRename = { editList = true }, onColour = { editList = false }, onDelete = { deleting = true },
                        onUncheckAll = { ShoppingStore.uncheckAll(list.id) }, canUncheck = bought.isNotEmpty(),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            ) {
                item(key = "add") { AddBar(onClick = { adding = true }) }
                if (chips.isNotEmpty()) {
                    item(key = "chips") {
                        Suggestions(chips, onPick = { p -> ShoppingStore.addItem(list.id, p.name, p.qty, p.unit, p.aisle) })
                    }
                }
                if (all.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing here yet. Add what you need — it sorts itself by aisle.",
                            fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                        )
                    }
                }
                groups.forEach { (a, rows) ->
                    item(key = "h_${a.id}") { AisleHead(a.icon, "${a.title} · ${rows.size}", Modifier.animateItem()) }
                    itemsIndexed(rows, key = { _, r -> r.id }) { i, r ->
                        ItemRow(
                            r, first = i == 0, last = i == rows.lastIndex, modifier = Modifier.animateItem(),
                            onTick = { ShoppingStore.setBought(r.id, true) }, onOpen = { editingId = r.id },
                        )
                    }
                }
                if (bought.isNotEmpty()) {
                    item(key = "h_bought") {
                        AisleHead(Icons.Rounded.Check, "Bought · ${bought.size}", Modifier.animateItem(), action = "Clear", onAction = { ShoppingStore.clearBought(list.id) })
                    }
                    itemsIndexed(bought, key = { _, r -> r.id }) { i, r ->
                        ItemRow(
                            r, first = i == 0, last = i == bought.lastIndex, modifier = Modifier.animateItem(),
                            onTick = { ShoppingStore.setBought(r.id, false) }, onOpen = { editingId = r.id },
                        )
                    }
                }
            }
        }
    }

    if (adding) ItemSheet(list.id, null, onDone = { adding = false })
    editingId?.let { id ->
        val item = all.firstOrNull { it.id == id }
        if (item == null) LaunchedEffect(id) { editingId = null } else ItemSheet(list.id, item, onDone = { editingId = null })
    }
    editList?.let { focus -> ListSheet(list, focusName = focus, onDone = { editList = null }) }
    if (deleting) DeleteListDialog(list, onDone = { deleting = false })
}

/** "Add an item": a white pill with a + in a soft green circle. */
@Composable
private fun AddBar(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val g = ToolTint.GREEN
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(52.dp).appCard(RoundedCornerShape(26.dp))
            .clickable(onClickLabel = "Add an item", onClick = onClick).padding(start = 9.dp, end = 14.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(g.tile), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = g.icon, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("Add an item", fontSize = 15.sp, color = cs.onSurfaceVariant)
    }
}

/** Things bought before that aren't in the list now: a tap adds one with its last amount. */
@Composable
private fun Suggestions(chips: List<PastItem>, onPick: (PastItem) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val line = cs.outline
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
    ) {
        items(chips, key = { it.name.lowercase() }) { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(32.dp)
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawRoundRect(
                            color = line, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                            cornerRadius = CornerRadius(size.height / 2), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Add ${p.name}") { onPick(p) }
                    .padding(horizontal = 12.dp),
            ) {
                Icon(Icons.Rounded.History, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(p.name, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            }
        }
    }
}

/** A small heading over a group of items ("Fruits & vegetables · 3"), with an optional link. */
@Composable
private fun AisleHead(icon: ImageVector, title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 6.dp, end = 2.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** One thing to buy: a round tick, the name (and note), and how much in a soft green pill. */
@Composable
private fun ItemRow(item: ShopItem, first: Boolean, last: Boolean, modifier: Modifier, onTick: () -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val g = ToolTint.GREEN
    val done = item.bought
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().cardSlice(first, last).clickable(onClickLabel = "Edit", onClick = onOpen)
            .heightIn(min = 50.dp).padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .toggleable(value = done, role = Role.Checkbox, onValueChange = { onTick() }),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(g.icon), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = g.onSolid, modifier = Modifier.size(16.dp))
                }
            } else {
                Box(Modifier.size(24.dp).border(2.dp, cs.onSurfaceVariant, CircleShape))
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
            Text(
                item.name, fontSize = 15.sp, color = if (done) cs.onSurfaceVariant else cs.onSurface,
                textDecoration = if (done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (item.note.isNotBlank()) {
                Text(item.note, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (done) cs.surfaceContainer else g.tile).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.qtyText, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (done) cs.onSurfaceVariant else g.icon, maxLines = 1)
        }
    }
}

/** Add an item / edit one: name, how much (− n + and the unit), the aisle (picked from the name), a note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemSheet(listId: String, item: ShopItem?, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val g = ToolTint.GREEN
    val wt = WTint(g.icon, g.tile)
    var name by remember { mutableStateOf(item?.name ?: "") }
    var qty by remember { mutableDoubleStateOf(item?.qty ?: 1.0) }
    var unit by remember { mutableStateOf(item?.qtyUnit ?: QtyUnit.PCS) }
    var aisle by remember { mutableStateOf(item?.aisleOf ?: Aisle.OTHER) }
    var note by remember { mutableStateOf(item?.note ?: "") }
    // Picked by hand (or an item being edited): the name no longer changes them.
    var aislePicked by remember { mutableStateOf(item != null) }
    var amountPicked by remember { mutableStateOf(item != null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (item == null) runCatching { focus.requestFocus() } }

    fun onName(v: String) {
        name = v.take(80)
        val past = ShoppingStore.pastFor(name)
        if (!aislePicked) aisle = past?.let { Aisle.of(it.aisle) } ?: Aisles.guess(name)
        if (!amountPicked) {
            qty = past?.qty ?: 1.0
            unit = past?.let { QtyUnit.of(it.unit) } ?: QtyUnit.PCS
        }
    }

    fun save() {
        if (name.isBlank()) return
        if (item == null) {
            ShoppingStore.addItem(listId, name, qty, unit.label, aisle.id, note)
        } else {
            ShoppingStore.saveItem(item.copy(name = name.trim(), qty = qty, unit = unit.label, aisle = aisle.id, note = note.trim()))
        }
        onDone()
    }

    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLowest,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 20.dp)) {
            SheetHead(if (item == null) "Add an item" else "Edit item") {
                if (item != null) {
                    TextButton(onClick = { ShoppingStore.deleteItem(item.id); onDone() }) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = cs.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", color = cs.error)
                    }
                }
            }
            OutlinedTextField(
                value = name, onValueChange = ::onName, singleLine = true,
                placeholder = { Text("What to buy?") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
            SheetLab("How much")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                RoundButton("−", "Less", onClick = { qty = unit.down(qty); amountPicked = true }, size = 40.dp)
                Text(
                    fmtNum(qty), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp),
                )
                RoundButton("+", "More", onClick = { qty = unit.up(qty); amountPicked = true }, size = 40.dp)
            }
            Spacer(Modifier.height(12.dp))
            Choices(QtyUnit.entries, unit, { it.label }, wt, onPick = {
                qty = unit.convert(qty, it)
                unit = it
                amountPicked = true
            })
            Text(
                buildAnnotatedString {
                    append("Aisle")
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append(" · picked for you, change it if you like") }
                },
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
            )
            Choices(Aisle.entries, aisle, { it.short }, wt, onPick = {
                aisle = it
                aislePicked = true
            })
            SheetLab("Note")
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(120) }, singleLine = true,
                placeholder = { Text("Optional, like “ripe ones”") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            SheetButtons(confirm = if (item == null) "Add" else "Save", enabled = name.isNotBlank(), onCancel = onDone, onConfirm = ::save)
        }
    }
}

// ============================================================================ small parts
@Composable
private fun SheetHead(title: String, end: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 20.dp, end = 12.dp, bottom = 10.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        end()
    }
}

/** Cancel (soft blue) and the main action (solid green), side by side. */
@Composable
private fun SheetButtons(confirm: String, enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        PillButton("Cancel", cs.primaryContainer, cs.onPrimaryContainer, Modifier.weight(1f), onClick = onCancel)
        ShopSolidButton(confirm, ToolTint.GREEN, Modifier.weight(1f), enabled = enabled, onClick = onConfirm)
    }
}

@Composable
private fun PillButton(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(50.dp).clip(CircleShape).background(bg).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/** A full pill in the tool's solid colour (greyed when not [enabled]). */
@Composable
internal fun ShopSolidButton(text: String, tint: ToolTint, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    PillButton(
        text, if (enabled) tint.icon else cs.surfaceContainerHigh, if (enabled) tint.onSolid else cs.onSurfaceVariant,
        modifier, icon, enabled, onClick,
    )
}

/** A full pill in the tool's soft colour. */
@Composable
internal fun ShopSoftButton(text: String, icon: ImageVector?, tint: ToolTint, modifier: Modifier = Modifier, onClick: () -> Unit) =
    PillButton(text, tint.tile, tint.icon, modifier, icon, true, onClick)

/** The list as text for WhatsApp & co.: "Groceries\n• Tomatoes – 1 kg\n• Milk – 2 L". What's left to buy, or all when everything's bought. */
internal fun shareListText(list: ShopList, items: List<ShopItem>): String {
    val shown = items.filter { !it.bought }.ifEmpty { items }
        .sortedWith(compareBy({ it.aisleOf.ordinal }, { it.order }, { it.name.lowercase() }))
    return buildString {
        append(list.name)
        for (i in shown) {
            append("\n• ").append(i.name)
            if (!(i.qtyUnit == QtyUnit.PCS && i.qty == 1.0)) append(" – ").append(i.qtyText)
            if (i.note.isNotBlank()) append(" (").append(i.note.trim()).append(")")
        }
    }
}

/** Android's share sheet with [text]. */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}
