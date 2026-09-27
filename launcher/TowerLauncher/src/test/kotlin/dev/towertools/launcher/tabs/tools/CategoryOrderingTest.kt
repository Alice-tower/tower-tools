package dev.towertools.launcher.tabs.tools

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryOrderingTest {
    @Test
    fun savedOrderKeepsExistingCategoriesAndAppendsNewOnes() {
        val tools = listOf("图片&影音", "本地管理", "系统环境", "未分类")
            .map { category -> LauncherTool(category, category, category, "", "1.0.0", Path.of("tool.exe"), category, 0) }

        assertEquals(
            listOf("系统环境", "图片&影音", "本地管理", "未分类"),
            orderedCategories(tools, listOf("已删除分类", "系统环境", "图片&影音", "系统环境")),
        )
    }

    @Test
    fun draggingReordersOnlyTheChosenCategory() {
        val before = listOf("图片&影音", "本地管理", "系统环境")
        assertEquals(listOf("本地管理", "系统环境", "图片&影音"), moveCategory(before, "图片&影音", "系统环境"))
        assertEquals(before, moveCategory(before, "收藏", "系统环境"))
    }
}
