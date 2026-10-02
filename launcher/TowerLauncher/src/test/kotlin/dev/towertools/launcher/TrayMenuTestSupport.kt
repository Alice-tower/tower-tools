@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.towertools.launcher

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.awt.Window

internal fun activeTrayMenuWindow(): ComposeWindow? =
    Window.getWindows().filterIsInstance<ComposeWindow>()
        .firstOrNull { it.name == TRAY_MENU_WINDOW_NAME && it.isDisplayable }

internal fun trayMenuAction(window: ComposeWindow, label: String): (() -> Boolean)? =
    trayMenuAction(window.semanticsOwners, label)

internal fun trayMenuAction(owners: Collection<SemanticsOwner>, label: String): (() -> Boolean)? =
    trayMenuNode(owners, label)?.config?.getOrNull(SemanticsActions.OnClick)?.action

internal fun trayMenuNode(owners: Collection<SemanticsOwner>, label: String): SemanticsNode? =
    owners.asSequence().flatMap { nodes(it.rootSemanticsNode) }
        .firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                node.config.getOrNull(SemanticsActions.OnClick)?.action != null
        }

private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> =
    sequenceOf(node) + node.children.asSequence().flatMap(::nodes)
