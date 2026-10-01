package dev.towertools.towerreader.flow

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.towertools.towerreader.ReaderController
import dev.towertools.towerreader.library.LibraryRepository
import dev.towertools.towerreader.ui.ReaderScreen
import dev.towertools.towerreader.ui.ReaderFullscreen
import dev.towertools.towerreader.ui.configureReaderRendering
import javafx.application.Platform
import javafx.scene.web.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.KeyEvent
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowFullscreenTest {
    @TempDir lateinit var directory:Path
    @Test fun actualWindowsFullscreenKeepsEpubPaintedAndRestoresItsText() {
        val classpath=(generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.filter { it.protocol=="file" }.map { Path.of(it.toURI()).toString() }.toList()+
            System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct().joinToString(java.io.File.pathSeparator)
        val log=directory.resolve("fullscreen.log")
        val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),
            "-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-cp",classpath,
            FlowFullscreenProbe::class.java.name,directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(55,TimeUnit.SECONDS),"Native fullscreen probe timed out: ${Files.readString(log)}")
            assertEquals(0,process.exitValue(),Files.readString(log))
            assertTrue(Files.readString(log).contains("FULLSCREEN_PASS"))
        } finally { if(process.isAlive) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS) } }
    }
}

object FlowFullscreenProbe {
    @JvmStatic fun main(args:Array<String>) {
        try { run(Path.of(args[0])); println("FULLSCREEN_PASS"); kotlin.system.exitProcess(0) }
        catch(failure:Throwable) { failure.printStackTrace(); kotlin.system.exitProcess(1) }
    }
    private fun <T> awt(action:()->T):T {
        if(EventQueue.isDispatchThread()) return action()
        val task=FutureTask<T> { action() }; EventQueue.invokeAndWait(task); return task.get(8,TimeUnit.SECONDS)
    }
    private fun <T> fx(action:()->T):T {
        val future=CompletableFuture<T>()
        Platform.runLater { try { future.complete(action()) } catch(error:Throwable) { future.completeExceptionally(error) } }
        return future.get(8,TimeUnit.SECONDS)
    }
    private fun pane(component:Component):FlowPane? {
        if(component is FlowPane) return component
        if(component is Container) component.components.forEach { pane(it)?.let { found -> return found } }
        return null
    }
    private fun key(source:Component,value:Int) = awt {
        val manager=KeyboardFocusManager.getCurrentKeyboardFocusManager()
        listOf(KeyEvent.KEY_PRESSED,KeyEvent.KEY_RELEASED).forEach { type ->
            val event=KeyEvent(source,type,System.currentTimeMillis(),0,value,KeyEvent.CHAR_UNDEFINED)
            manager.dispatchEvent(event); check(event.isConsumed) { "Fullscreen shortcut $value was not consumed" }
        }
    }
    private fun capture(panel:FlowPane,name:String,theme:String) {
        val bounds=awt { Rectangle(panel.locationOnScreen,panel.size) }
        check(bounds.width>600 && bounds.height>350) { "Reader bounds collapsed: $bounds" }
        val image=Robot().createScreenCapture(bounds)
        val output=Path.of("build","test-previews",name); Files.createDirectories(output.parent); ImageIO.write(image,"png",output.toFile())
        val expected=java.awt.Color.decode(if(theme=="dark") "#202329" else "#f4eedf")
        var paper=0; var ink=0; var samples=0
        for(y in 16 until image.height-16 step 12) for(x in 16 until image.width-16 step 12) {
            val color=java.awt.Color(image.getRGB(x,y)); samples++
            if(kotlin.math.abs(color.red-expected.red)<12 && kotlin.math.abs(color.green-expected.green)<12 && kotlin.math.abs(color.blue-expected.blue)<12) paper++
            if(if(theme=="dark") color.red>100 else color.red<120) ink++
        }
        val web=fx { panel.scene.root.childrenUnmodifiable.last() as WebView }
        println("$name bounds=$bounds paper=$paper/$samples showing=${panel.isShowing} web=${fx { web.engine.executeScript("[window.innerWidth,window.innerHeight,Reader.state()].join(';')") }}")
        check(paper>samples*.55 && ink>samples*.01) { "The native reading surface is blank/black; see $output (ink=$ink)" }
    }
    private fun run(root:Path) {
        System.setProperty("javafx.cachedir",root.resolve("javafx").toString())
        configureReaderRendering()
        val source=root.resolve("fullscreen.epub")
        ZipOutputStream(Files.newOutputStream(source)).use { zip ->
            mapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>",
                "book.opf" to "<package><manifest><item id='text' href='chapter.xhtml'/></manifest><spine><itemref idref='text'/></spine></package>",
                "chapter.xhtml" to "<html><body><h1>全屏阅读验证</h1>"+"<p>阅读应该保持连续，切换全屏后文字仍然清晰可见。</p>".repeat(800)+"</body></html>"
            ).forEach { (name,body) -> zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() }
        }
        val data=root.resolve("library")
        val book=LibraryRepository(data).use { repo -> check(repo.importBooks(listOf(source)).added==1); repo.setSetting("spread","single"); repo.listBooks().single() }
        var failure:Throwable?=null
        application(exitProcessOnExit=false) {
            val state=rememberWindowState(width=900.dp,height=650.dp)
            val controller=remember { ReaderController(data) }
            var host:java.awt.Window?=null
            val mode=remember { arrayOfNulls<ReaderFullscreen>(1) }
            Window(onCloseRequest={},state=state,title="静读 · 全屏验证") {
                DisposableEffect(window) { host=window; window.isAlwaysOnTop=true; onDispose { } }
                val fullscreen=remember(window) { ReaderFullscreen(window).also { mode[0]=it } }
                MaterialTheme { ReaderScreen(controller,fullscreen.active,fullscreen::toggle,window) }
            }
            LaunchedEffect(Unit) {
                try { withTimeout(45000) {
                    while(!controller.ready) { check(controller.error==null) { controller.error.orEmpty() }; delay(20) }
                    controller.open(book)
                    while(controller.pageState==null) { check(controller.error==null) { controller.error.orEmpty() }; delay(20) }
                    val window=checkNotNull(host)
                    val original=awt { checkNotNull(pane(window)) }
                    for(theme in listOf("paper","dark")) {
                        val placement=if(theme=="dark") WindowPlacement.Maximized else WindowPlacement.Floating
                        state.placement=placement
                        controller.updateSettings(controller.settings.copy(theme=theme,spread=if(theme=="dark") "double" else "single"))
                        controller.goChapter(0,800)
                        while(controller.loading || controller.pageState?.offset!=800) { check(controller.error==null) { controller.error.orEmpty() }; delay(20) }
                        delay(450)
                        val restoredBounds=awt { Rectangle(window.bounds) }
                        capture(original,"native-$theme-window.png",theme)
                        repeat(2) { cycle ->
                            key(window,KeyEvent.VK_F11)
                            while(mode[0]?.active!=true) delay(20)
                            delay(700)
                            check(awt { pane(window)===original }) { "Native fullscreen replaced the reader" }
                            awt {
                                val hwnd=HWND(Native.getComponentPointer(window))
                                val monitor=WinUser.MONITORINFO()
                                check(User32.INSTANCE.GetMonitorInfo(User32.INSTANCE.MonitorFromWindow(hwnd,WinUser.MONITOR_DEFAULTTONEAREST),monitor).booleanValue())
                                val rect=RECT(); check(User32.INSTANCE.GetWindowRect(hwnd,rect))
                                check(rect.toRectangle()==monitor.rcMonitor.toRectangle()) { "Fullscreen did not cover the monitor: $rect" }
                                check(window.insets==java.awt.Insets(0,0,0,0)) { "Fullscreen kept window borders: ${window.insets}" }
                            }
                            check(controller.pageState?.offset==800) { "Fullscreen lost the text anchor: ${controller.pageState}" }
                            capture(original,"native-$theme-fullscreen-$cycle.png",theme)
                            val before=controller.pageState!!.page
                            key(original,KeyEvent.VK_RIGHT)
                            while(controller.pageState?.page==before) { check(controller.error==null) { controller.error.orEmpty() }; delay(20) }
                            original.jump(800); delay(250)
                            key(original,KeyEvent.VK_ESCAPE)
                            while(mode[0]?.active==true) delay(20)
                            delay(500)
                            check(controller.active!=null && controller.pageState?.offset==800)
                            check(state.placement==placement) { "Window placement was not restored: ${state.placement}" }
                            check(awt { window.bounds==restoredBounds }) { "Window bounds were not restored: ${window.bounds}, expected $restoredBounds" }
                            capture(original,"native-$theme-restored-$cycle.png",theme)
                        }
                    }
                } } catch(error:Throwable) { failure=error }
                mode[0]?.exit()
                controller.close { Platform.exit(); exitApplication() }
            }
        }
        failure?.let { throw it }
    }
}
