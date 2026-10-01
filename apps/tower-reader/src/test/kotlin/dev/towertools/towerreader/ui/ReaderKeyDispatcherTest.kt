package dev.towertools.towerreader.ui

import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.*
import org.junit.jupiter.api.Test

class ReaderKeyDispatcherTest {
    private fun edt(action:()->Unit) {
        val task=FutureTask<Unit> { action() }
        EventQueue.invokeAndWait(task)
        task.get()
    }
    private fun event(source:java.awt.Component,id:Int,key:Int,char:Char=KeyEvent.CHAR_UNDEFINED,modifiers:Int=0)=
        KeyEvent(source,id,System.currentTimeMillis(),modifiers,key,char)
    private fun dispatch(event:KeyEvent):KeyEvent {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(event)
        return event
    }
    @Test fun awtDispatchPreservesTurnAutoRepeatButFullscreenAndEscapeOnlyRunOnce() = edt {
        val window=JFrame()
        val panel=JPanel().also { window.add(it) }
        val turns=mutableListOf<Int>(); var fullscreen=0; var escape=0
        val handler=ReaderKeyDispatcher(window,{true},{turns+=it},{fullscreen++},{escape++})
        try {
            fun cycle(key:Int,char:Char=KeyEvent.CHAR_UNDEFINED,mods:Int=0) {
                assertTrue(dispatch(event(panel,KeyEvent.KEY_PRESSED,key,char,mods)).isConsumed)
                assertTrue(dispatch(event(panel,KeyEvent.KEY_PRESSED,key,char,mods)).isConsumed)
                if(char!=KeyEvent.CHAR_UNDEFINED) assertTrue(dispatch(event(panel,KeyEvent.KEY_TYPED,KeyEvent.VK_UNDEFINED,char,mods)).isConsumed)
                assertTrue(dispatch(event(panel,KeyEvent.KEY_RELEASED,key,char,mods)).isConsumed)
            }
            cycle(KeyEvent.VK_LEFT); cycle(KeyEvent.VK_RIGHT)
            cycle(KeyEvent.VK_PAGE_UP); cycle(KeyEvent.VK_PAGE_DOWN)
            cycle(KeyEvent.VK_SPACE,' '); cycle(KeyEvent.VK_SPACE,' ',KeyEvent.SHIFT_DOWN_MASK)
            cycle(KeyEvent.VK_F11); cycle(KeyEvent.VK_ESCAPE,'\u001b')
            assertEquals(listOf(-1,-1,1,1,-1,-1,1,1,1,1,-1,-1),turns)
            assertEquals(1,fullscreen); assertEquals(1,escape)
            assertFalse(dispatch(event(panel,KeyEvent.KEY_TYPED,KeyEvent.VK_UNDEFINED,' ')).isConsumed)
        } finally { handler.close(); window.dispose() }
    }
    @Test fun dialogsOtherWindowsInputsModifiersAndDisabledStateAreUnaffected() = edt {
        val window=JFrame(); val panel=JPanel().also { window.add(it) }
        val input=JTextField().also { panel.add(it) }
        val dialog=JDialog(window); val dialogPanel=JPanel().also { dialog.add(it) }
        val other=JFrame(); val otherPanel=JPanel().also { other.add(it) }
        var enabled=true; var actions=0
        val handler=ReaderKeyDispatcher(window,{enabled},{actions++},{actions++},{actions++})
        try {
            for(source in listOf(input,dialogPanel,otherPanel)) {
                assertFalse(dispatch(event(source,KeyEvent.KEY_PRESSED,KeyEvent.VK_RIGHT)).isConsumed)
            }
            for(mods in listOf(KeyEvent.CTRL_DOWN_MASK,KeyEvent.ALT_DOWN_MASK,KeyEvent.META_DOWN_MASK,KeyEvent.ALT_GRAPH_DOWN_MASK)) {
                assertFalse(dispatch(event(panel,KeyEvent.KEY_PRESSED,KeyEvent.VK_RIGHT,modifiers=mods)).isConsumed)
            }
            enabled=false
            assertFalse(dispatch(event(panel,KeyEvent.KEY_PRESSED,KeyEvent.VK_F11)).isConsumed)
            assertEquals(0,actions)
            enabled=true
            assertTrue(dispatch(event(panel,KeyEvent.KEY_PRESSED,KeyEvent.VK_RIGHT)).isConsumed)
            assertTrue(dispatch(event(panel,KeyEvent.KEY_RELEASED,KeyEvent.VK_RIGHT)).isConsumed)
            assertEquals(1,actions)
        } finally { handler.close(); dialog.dispose(); other.dispose(); window.dispose() }
    }
    @Test fun closingUnregistersDispatcherAndClearsPendingKeyState() = edt {
        val window=JFrame(); val panel=JPanel().also { window.add(it) }
        var actions=0
        val handler=ReaderKeyDispatcher(window,{true},{actions++},{actions++},{actions++})
        try {
            assertTrue(dispatch(event(panel,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE,' ')).isConsumed)
            handler.close(); handler.close()
            assertFalse(dispatch(event(panel,KeyEvent.KEY_TYPED,KeyEvent.VK_UNDEFINED,' ')).isConsumed)
            assertFalse(dispatch(event(panel,KeyEvent.KEY_RELEASED,KeyEvent.VK_SPACE,' ')).isConsumed)
            assertFalse(dispatch(event(panel,KeyEvent.KEY_PRESSED,KeyEvent.VK_RIGHT)).isConsumed)
            assertEquals(1,actions)
            ReaderKeyDispatcher(window,{true},{actions++},{actions++},{actions++}).use {
                assertTrue(dispatch(event(panel,KeyEvent.KEY_PRESSED,KeyEvent.VK_SPACE,' ')).isConsumed)
                assertEquals(2,actions)
            }
        } finally { handler.close(); window.dispose() }
    }
}
