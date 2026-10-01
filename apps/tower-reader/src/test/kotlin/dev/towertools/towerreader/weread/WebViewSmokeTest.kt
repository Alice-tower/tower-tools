package dev.towertools.towerreader.weread

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.Robot
import java.awt.Rectangle
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebViewSmokeTest {
    @TempDir lateinit var directory: Path
    @Test fun nativeWebViewOpensAndCloses() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("TOWER_READER_VERIFY_WEREAD") == "1")
        probe(WebViewSmokeProbe::class.java.name)
    }
    @Test fun cookiesAndSiteStorageSurviveRestartAndCanBeCleared() = probe(WebViewPersistenceProbe::class.java.name)
    @Test fun composePageStaysResidentAndSupportsFullscreen() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("TOWER_READER_VERIFY_WEREAD") == "1")
        probe(WeReadComposeProbe::class.java.name)
    }
    private fun probe(mainClass: String) {
        val classpath=(generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.filter { it.protocol=="file" }.map { Path.of(it.toURI()).toString() }.toList()+
            System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct().joinToString(java.io.File.pathSeparator)
        val log=directory.resolve("webview.log")
        val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),
            "-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-cp",classpath,
            mainClass,directory.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(45,TimeUnit.SECONDS),"WebView2 timed out: ${Files.readString(log)}")
            assertEquals(0,process.exitValue(),Files.readString(log))
            assertTrue(!Files.readString(log).contains("threw the following exception"), Files.readString(log))
            val report=Path.of("build/verification/"+mainClass.substringAfterLast(".")+".log"); Files.createDirectories(report.parent); Files.copy(log,report,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { if(process.isAlive) { process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS) } }
    }
}
object WebViewSmokeProbe {
    @JvmStatic fun main(args:Array<String>) {
        var frame:JFrame?=null
        var browser:WebViewBrowser?=null
        var failure:Throwable?=null
        try {
            val parent=CompletableFuture<HWND>()
            EventQueue.invokeAndWait {
                val canvas=Canvas()
                frame=JFrame("静读 · 微信读书嵌入验证").apply {
                    setSize(1000,700); setLocationRelativeTo(null); add(canvas); isVisible=true
                }
                parent.complete(HWND(Native.getComponentPointer(canvas)))
            }
            val ready=CompletableFuture<Unit>()
            browser=WebViewBrowser(parent.get(),Path.of(args[0]),"https://weread.qq.com/web/shelf",onState={
                println(it)
                if(it.error!=null) ready.completeExceptionally(IllegalStateException(it.error))
                else if(it.ready&&!it.loading) ready.complete(Unit)
            },onShortcut={})
            ready.get(30,TimeUnit.SECONDS)
            println(browser.evaluate("JSON.stringify({title:document.title,text:document.body.innerText.slice(0,300)})").get(5,TimeUnit.SECONDS))
            Thread.sleep(500)
            val bounds=CompletableFuture<Rectangle>()
            EventQueue.invokeAndWait { bounds.complete(Rectangle(frame!!.locationOnScreen,frame!!.size)) }
            val image=Robot().createScreenCapture(bounds.get())
            val preview=Path.of("build/test-previews/weread-native.png");Files.createDirectories(preview.parent)
            javax.imageio.ImageIO.write(image,"png",preview.toFile())
            println("WEBVIEW_PASS")
        } catch(error:Throwable) { failure=error; error.printStackTrace() }
        finally { browser?.close()?.get(10,TimeUnit.SECONDS); EventQueue.invokeAndWait { frame?.dispose() } }
        kotlin.system.exitProcess(if(failure==null) 0 else 1)
    }
}
