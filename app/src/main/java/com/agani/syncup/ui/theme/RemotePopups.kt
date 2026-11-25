package com.agani.syncup.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/*
 * The app's dialogs, bottom sheets and menus: Material's own, with the remote's selected ring inside them too
 * (see RemoteFocus.kt — a pop-up is its own window, which the page's ring can't reach). Same names and the
 * parameters the app uses, so a screen only imports these instead of androidx.compose.material3's.
 */

/** Material's AlertDialog, with the ring on its buttons, text boxes and lists. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    containerColor: Color = AlertDialogDefaults.containerColor,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { RingArea(keepInside = false) { confirmButton() } },
        modifier = modifier,
        dismissButton = dismissButton?.let { b -> { RingArea(keepInside = false) { b() } } },
        icon = icon,
        title = title,
        text = text?.let { t -> { RingArea(keepInside = false) { t() } } },
        containerColor = containerColor,
    )
}

/** Material's ModalBottomSheet, with the ring on whatever's selected in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = containerColor,
    ) {
        RingArea(keepInside = true, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** Material's DropdownMenu, with the ring on the selected item. */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor,
    ) {
        RingArea(keepInside = true, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}
