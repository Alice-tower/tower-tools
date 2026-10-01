package dev.towertools.towerreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.lightColors
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.towertools.towerreader.library.LibraryRepository
import dev.towertools.towerreader.ui.ReaderScreen
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

class ReaderUiTest {
    @get:Rule val rule=createComposeRule()
    @get:Rule val folder=TemporaryFolder()
    private fun <T> edt(action:()->T):T {
        val task=FutureTask<T> { action() }; EventQueue.invokeAndWait(task)
        return task.get(10,TimeUnit.SECONDS)
    }
    private fun sample(path:Path) {
        PDDocument().use { document ->
            repeat(3) { index ->
                val page=PDPage(); document.addPage(page)
                PDPageContentStream(document,page).use { content ->
                    content.setNonStrokingColor(java.awt.Color(244,238,223))
                    content.addRect(0f,0f,page.mediaBox.width,page.mediaBox.height); content.fill()
                    content.setNonStrokingColor(java.awt.Color(52,56,47))
                    content.beginText(); content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),26f)
                    content.newLineAtOffset(65f,690f); content.showText("Quiet Reading / Chapter ${index+1}")
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA),13f)
                    repeat(16) { line -> content.newLineAtOffset(0f,-27f); content.showText("A moment of quiet, a page of thought. Passage ${line+1}.") }
                    content.endText()
                }
            }
            document.save(path.toFile())
        }
    }
    @Test fun pdfJumpValidationCleanFullscreenAndTwoReadingThemes() {
        val data=folder.root.toPath().resolve("data")
        val source=folder.root.toPath().resolve("quiet-reading.pdf"); sample(source)
        val book=LibraryRepository(data).use { repo ->
            assertEquals(1,repo.importBooks(listOf(source)).added)
            repo.setSetting("spread","single"); repo.listBooks().single()
        }
        val controller=edt { ReaderController(data) }
        val fullscreen=mutableStateOf(false)
        try {
            rule.setContent {
                val palette=lightColors(primary=Color(0xff4b664c),background=Color(0xfff4eedf),surface=Color(0xfffaf5e9),onSurface=Color(0xff34382f),onBackground=Color(0xff34382f),onPrimary=Color.White)
                MaterialTheme(colors=palette) { Surface { Box(Modifier.size(1180.dp,820.dp).testTag("reader-test-host")) {
                    ReaderScreen(controller,fullscreen.value,{ fullscreen.value=!fullscreen.value })
                } } }
            }
            rule.waitUntil(15000) { controller.ready || controller.error!=null }
            assertNull(edt { controller.error })
            edt { controller.open(book) }
            rule.waitUntil(15000) { controller.pdf!=null && !controller.loading || controller.error!=null }
            rule.waitUntil(15000) { rule.onAllNodesWithContentDescription("PDF 页面").fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            rule.onNodeWithTag("reader-top-bar").assertExists()
            rule.onNodeWithTag("reader-bottom-bar").assertExists()
            rule.onNodeWithTag("pdf-page-count").assertTextEquals("第 1 / 3 页")
            rule.onNodeWithText("正在显示…").assertDoesNotExist()
            rule.onNodeWithText("跳转页码").performClick()
            val input=rule.onNode(hasSetTextAction() and hasText("页码"))
            input.performTextReplacement("0")
            rule.onNodeWithText("跳转",substring=false).assertIsNotEnabled()
            input.performTextReplacement("4")
            rule.onNodeWithText("跳转",substring=false).assertIsNotEnabled()
            input.performTextReplacement("2")
            rule.onNodeWithText("跳转",substring=false).assertIsEnabled().performClick()
            rule.waitUntil(10000) { controller.pdfPage==1 }
            rule.waitForIdle()
            rule.onNodeWithTag("pdf-page-count").assertTextEquals("第 2 / 3 页")
            fun screenshot(name:String) {
                rule.waitForIdle()
                controller.readerExecutor.submit {}.get(10,TimeUnit.SECONDS)
                rule.waitForIdle()
                val target=java.io.File("build/test-previews/$name").apply { parentFile.mkdirs() }
                ImageIO.write(rule.onNodeWithTag("reader-test-host").captureToImage().toAwtImage(),"png",target)
            }
            screenshot("reader-pdf.png")
            rule.onNodeWithText("阅读设置",substring=false).performClick()
            rule.onNodeWithText("✓ 暖纸",substring=false).assertExists()
            rule.onNodeWithText("深色",substring=false).assertExists()
            rule.onNodeWithText("浅色",substring=true).assertDoesNotExist()
            rule.onNodeWithText("完成",substring=false).performClick()
            rule.onNodeWithText("全屏",substring=false).performClick()
            rule.onNodeWithTag("reader-top-bar").assertDoesNotExist()
            rule.onNodeWithTag("reader-bottom-bar").assertDoesNotExist()
            rule.onNodeWithTag("pdf-page-count").assertDoesNotExist()
            rule.onNodeWithText("正在显示…").assertDoesNotExist()
            rule.onNodeWithContentDescription("PDF 页面").assertExists()
            screenshot("reader-fullscreen.png")
            assertEquals(book.id,edt { controller.active?.id })
            rule.runOnIdle { fullscreen.value=false }
            rule.onNodeWithTag("reader-top-bar").assertExists()
            rule.onNodeWithTag("reader-bottom-bar").assertExists()
            assertNull(edt { controller.error })
        } finally {
            val done=CountDownLatch(1); edt { controller.close { done.countDown() } }
            assertTrue(done.await(20,TimeUnit.SECONDS))
        }
    }
}
