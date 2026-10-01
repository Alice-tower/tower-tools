package dev.towertools.towerreader.weread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import dev.towertools.towerreader.ui.ReaderFullscreen
import dev.towertools.towerreader.ui.configureReaderRendering
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

object WeReadComposeProbe {
    @JvmStatic fun main(args: Array<String>) {
        configureReaderRendering()
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            val controller = remember { WeReadController(Path.of(args[0])) }
            val mode = remember { arrayOfNulls<ReaderFullscreen>(1) }
            val host = remember { arrayOfNulls<java.awt.Window>(1) }
            Window(onCloseRequest = {}, state = rememberWindowState(width = 1050.dp, height = 760.dp), title = "静读 · 微信读书界面验证") {
                val fullscreen = remember(window) { ReaderFullscreen(window).also { mode[0] = it } }
                SideEffect { host[0] = window }
                MaterialTheme {
                    Column(Modifier.fillMaxSize()) {
                        if (!fullscreen.active) Row { TextButton(onClick = { controller.select(false) }) { Text("本地书库") }; TextButton(onClick = { controller.select(true) }) { Text("微信读书") } }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (controller.activated) WeReadScreen(controller, controller.selected, fullscreen.active, fullscreen::toggle, window)
                            if (!controller.selected) Box(Modifier.fillMaxSize().background(Color(0xffe0ecd8)))
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                try { withTimeout(35000) {
                    while (!controller.ready) delay(20)
                    controller.select(true)
                    while (!controller.state.ready || controller.state.loading) { check(controller.state.error == null) { controller.state.error.orEmpty() }; delay(30) }
                    val pane = checkNotNull(controller.pane)
                    host[0]!!.toFront()
                    com.sun.jna.platform.win32.User32.INSTANCE.SetForegroundWindow(com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getComponentPointer(host[0])))
                    val original = pane.evaluate("window.towerMarker='same-instance';var marker=document.createElement('div');marker.style='position:fixed;top:130px;left:10px;width:60px;height:60px;background:rgb(200,30,180);z-index:2147483647';document.body.appendChild(marker);window.towerMarker").get(3,TimeUnit.SECONDS)
                    delay(700)
                    capture(host[0]!!, "weread-compose.png")
                    println("Visible page passed")
                    controller.select(false); delay(350)
                    capture(host[0]!!, "weread-hidden.png", hidden = true)
                    controller.select(true); delay(400)
                    check(controller.pane === pane)
                    check(pane.evaluate("window.towerMarker").get(3,TimeUnit.SECONDS) == original)
                    repeat(2) { cycle ->
                        println("Starting fullscreen cycle $cycle")
                        com.sun.jna.platform.win32.User32.INSTANCE.SetForegroundWindow(com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getComponentPointer(host[0])))
                        pane.focusPage(); delay(250)
                        println("Web focus="+pane.evaluate("document.hasFocus()").get(3,TimeUnit.SECONDS)+" foreground="+com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow())
                        requireForeground(host[0]!!)
                        val robot = Robot(); robot.keyPress(KeyEvent.VK_F11); delay(60); robot.keyRelease(KeyEvent.VK_F11)
                        while(mode[0]?.active!=true) delay(20)
                        delay(500)
                        capture(host[0]!!,"weread-fullscreen-$cycle.png")
                        check(pane.evaluate("window.towerMarker").get(3,TimeUnit.SECONDS) == original)
                        pane.focusPage(); delay(150); requireForeground(host[0]!!); robot.keyPress(KeyEvent.VK_ESCAPE); delay(60); robot.keyRelease(KeyEvent.VK_ESCAPE)
                        while(mode[0]?.active==true) delay(20)
                        delay(250)
                    }
                    // Ensure the website's login entry can produce its QR view inside the embedded browser.
                    pane.evaluate("Array.from(document.querySelectorAll('button,a,span,div')).find(e=>e.children.length===0 && e.textContent.trim()==='登录')?.click();true").get(3,TimeUnit.SECONDS)
                    delay(1800)
                    capture(host[0]!!,"weread-login.png")
                    pane.restart()
                    while (!controller.state.ready || controller.state.loading) { check(controller.state.error == null) { controller.state.error.orEmpty() }; delay(30) }
                    check(controller.pane === pane)
                    check(pane.evaluate("window.towerMarker").get(3,TimeUnit.SECONDS) == "null")
                    println("COMPOSE_WEREAD_PASS: resident page, native fullscreen, return from fullscreen, login page and clean restart")
                } } catch(error:Throwable) { failure=error; error.printStackTrace() }
                mode[0]?.exit()
                controller.close { exitApplication() }
            }
        }
        kotlin.system.exitProcess(if(failure==null) 0 else 1)
    }
    private fun requireForeground(window: java.awt.Window) {
        check(com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow()?.pointer == com.sun.jna.Native.getComponentPointer(window)) {
            "验证窗口未处于前台；停止截图和键盘操作，避免干扰其他窗口。"
        }
    }
    private fun capture(window: java.awt.Window, name: String, hidden: Boolean = false) {
        check(EventQueue.isDispatchThread())
        requireForeground(window)
        val image = Robot().createScreenCapture(Rectangle(window.locationOnScreen,window.size))
        var expected=0; var total=0; var marker=0
        for(y in 130 until image.height-40 step 15) for(x in 40 until image.width-40 step 15) {
            val color=java.awt.Color(image.getRGB(x,y)); total++
            if(if(hidden) kotlin.math.abs(color.red-224)<10 && kotlin.math.abs(color.green-236)<10 else color.red>220 && color.green>220 && color.blue>210) expected++
        }
        for(y in 50 until image.height-20 step 4) for(x in 5 until image.width-5 step 4) {
            val color=java.awt.Color(image.getRGB(x,y))
            if(kotlin.math.abs(color.red-200)<8 && kotlin.math.abs(color.green-30)<8 && kotlin.math.abs(color.blue-180)<8) marker++
        }
        val file=Path.of("build/test-previews",name);Files.createDirectories(file.parent);ImageIO.write(image,"png",file.toFile())
        check(expected>total*(if(hidden) .65 else .35) && (if(hidden) marker==0 else marker>60)) { "WebView not visible / hidden correctly: $name ($expected/$total marker=$marker)" }
    }
}
