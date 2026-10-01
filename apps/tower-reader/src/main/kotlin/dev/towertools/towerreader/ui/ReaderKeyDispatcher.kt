package dev.towertools.towerreader.ui

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.text.JTextComponent

/** Install and close on the AWT event thread. Callbacks also execute on that thread.
 * [enabled] must exclude Compose/JavaFX text editing; Swing text components are detected here.
 */
class ReaderKeyDispatcher(
    private val window: Window,
    private val enabled: () -> Boolean,
    private val onTurn: (Int) -> Unit,
    private val onFullScreen: () -> Unit,
    private val onEscape: () -> Unit,
) : KeyEventDispatcher, AutoCloseable {
    private val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val pressed = mutableSetOf<Int>()
    @Volatile private var closed = false

    init { manager.addKeyEventDispatcher(this) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (closed) return false
        val key = event.keyCode
        if (!belongsToWindow(event.component) || manager.focusedWindow?.let { it !== window } == true ||
            isTextInput(event.component) || isTextInput(manager.focusOwner) || !enabled()) {
            // A focus change must not leave an old held key suppressing the next press.
            pressed.clear()
            return false
        }
        if (event.isControlDown || event.isAltDown || event.isMetaDown || event.isAltGraphDown) {
            pressed.clear()
            return false
        }
        val handled = when (event.id) {
            KeyEvent.KEY_PRESSED -> {
                if (key !in supportedKeys) false else {
                    val firstPress = pressed.add(key)
                    if (firstPress || key != KeyEvent.VK_F11 && key != KeyEvent.VK_ESCAPE) when (key) {
                        KeyEvent.VK_LEFT, KeyEvent.VK_PAGE_UP -> onTurn(-1)
                        KeyEvent.VK_RIGHT, KeyEvent.VK_PAGE_DOWN -> onTurn(1)
                        KeyEvent.VK_SPACE -> onTurn(if (event.isShiftDown) -1 else 1)
                        KeyEvent.VK_F11 -> onFullScreen()
                        KeyEvent.VK_ESCAPE -> onEscape()
                    }
                    true
                }
            }
            KeyEvent.KEY_TYPED -> (event.keyChar == ' ' && KeyEvent.VK_SPACE in pressed) ||
                (event.keyChar == '\u001b' && KeyEvent.VK_ESCAPE in pressed)
            KeyEvent.KEY_RELEASED -> pressed.remove(key)
            else -> false
        }
        if (handled) event.consume()
        return handled
    }

    private fun belongsToWindow(component: Component?): Boolean {
        var ancestor = component
        while (ancestor != null && ancestor !is Window) ancestor = ancestor.parent
        return ancestor === window
    }
    private fun isTextInput(component: Component?): Boolean {
        var ancestor = component
        while (ancestor != null && ancestor !is Window) {
            if (ancestor is JTextComponent) return true
            ancestor = ancestor.parent
        }
        return false
    }
    override fun close() {
        if (closed) return
        closed = true
        manager.removeKeyEventDispatcher(this)
        pressed.clear()
    }
    private companion object {
        val supportedKeys = setOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_PAGE_UP,
            KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_SPACE, KeyEvent.VK_F11, KeyEvent.VK_ESCAPE)
    }
}
