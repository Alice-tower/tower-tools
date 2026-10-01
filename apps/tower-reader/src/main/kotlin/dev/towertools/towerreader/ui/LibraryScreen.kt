package dev.towertools.towerreader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.towertools.towerreader.ReaderController
import dev.towertools.towerreader.library.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.imageio.ImageIO

@Composable
fun LibraryScreen(controller:ReaderController,onChooseFiles:()->Unit) {
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("最近导入") }
    var sortMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookRecord?>(null) }
    var deleting by remember { mutableStateOf<BookRecord?>(null) }
    var editingCategory by remember { mutableStateOf<String?>(null) }
    var categorySaving by remember { mutableStateOf(false) }
    val categories=controller.categories
    val books=controller.books.filter { book ->
        val matchesFilter=when(filter) { "all"->true; "recent"->book.lastOpenedAt>0; "uncategorized"->book.category.isBlank(); else->book.category==filter.removePrefix("category:") }
        matchesFilter && (query.isBlank() || listOf(book.title,book.author,book.sourceName).any { it.contains(query.trim(),ignoreCase=true) })
    }.let { list -> when { sort=="书名"->list.sortedBy { it.title.lowercase() }; sort=="最近阅读" || filter=="recent"->list.sortedByDescending { it.lastOpenedAt }; else->list.sortedByDescending { it.importedAt } } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        if(controller.importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal=30.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(controller.importStatus,modifier=Modifier.weight(1f),style=MaterialTheme.typography.caption)
                TextButton(onClick=controller::cancelImport) { Text("取消导入") }
            }
        }
        Divider(color=MaterialTheme.colors.onSurface.copy(alpha=.08f))
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(190.dp).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LibraryCategories(controller.books,categories,filter,{ filter=it },{ editingCategory=it },controller::reorderCategories)
                }
                Button(onClick=onChooseFiles,enabled=controller.ready && !controller.importing,
                    modifier=Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,top=8.dp,bottom=18.dp)) {
                    Text("＋ 导入书籍")
                }
            }
            Divider(Modifier.fillMaxHeight().width(1.dp),color=MaterialTheme.colors.onSurface.copy(alpha=.08f))
            Column(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(query,{ query=it },Modifier.weight(1f),singleLine=true,placeholder={ Text("搜索书名、作者或原文件名") })
                    Spacer(Modifier.width(18.dp))
                    Box {
                        OutlinedButton(onClick={ sortMenu=true }) { Text("$sort ▾") }
                        DropdownMenu(sortMenu,{ sortMenu=false }) { listOf("最近导入","最近阅读","书名").forEach { option -> DropdownMenuItem(onClick={ sort=option; sortMenu=false }) { Text(option) } } }
                    }
                }
                Text("${books.size} 本书",color=MaterialTheme.colors.onSurface.copy(alpha=.55f),style=MaterialTheme.typography.caption,modifier=Modifier.padding(vertical=18.dp))
                if(!controller.ready) {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("正在打开书库…",color=MaterialTheme.colors.onSurface.copy(alpha=.6f)) }
                } else if(books.isEmpty()) {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(if(controller.books.isEmpty()) "把第一本书放进来" else "这里暂时没有书籍",fontSize=23.sp,fontWeight=FontWeight.Medium)
                            Text(if(controller.books.isEmpty()) "拖入 TXT、EPUB 或 PDF，也可以点击导入书籍" else "试试其他分类或搜索词",color=MaterialTheme.colors.onSurface.copy(alpha=.6f),modifier=Modifier.padding(top=12.dp))
                            if(controller.books.isEmpty()) OutlinedButton(onClick=onChooseFiles,enabled=!controller.importing,modifier=Modifier.padding(top=22.dp)) { Text("选择书籍") }
                        }
                    }
                } else {
                    LazyVerticalGrid(columns=GridCells.Adaptive(168.dp),horizontalArrangement=Arrangement.spacedBy(24.dp),verticalArrangement=Arrangement.spacedBy(28.dp),modifier=Modifier.fillMaxSize()) {
                        items(books,key={ it.id }) { book -> BookCard(controller,book,{ controller.open(book) },{ editing=book },{ deleting=book }) }
                    }
                }
            }
        }
    }
    editing?.let { book -> EditBookDialog(book,categories,{ editing=null }) { title,category -> controller.editBook(book,title,category); editing=null } }
    editingCategory?.let { previous ->
        EditCategoryDialog(previous,categories,categorySaving,{ editingCategory=null }) { name ->
            categorySaving=true
            controller.renameCategory(previous,name) { saved ->
                categorySaving=false
                if(saved) {
                    if(filter=="category:$previous") filter="category:$name"
                    editingCategory=null
                }
            }
        }
    }
    deleting?.let { book ->
        AlertDialog(onDismissRequest={ deleting=null },title={ Text("移出书库？") },text={ Text("将“${book.title}”移出书库，并删除静读保存的托管副本和封面。你导入时选择的原文件会保留。") },
            confirmButton={ TextButton(onClick={ controller.removeBook(book); deleting=null }) { Text("移出书库",color=MaterialTheme.colors.error) } },
            dismissButton={ TextButton(onClick={ deleting=null }) { Text("取消") } })
    }
}

@Composable private fun BookCard(controller:ReaderController,book:BookRecord,onOpen:()->Unit,onEdit:()->Unit,onRemove:()->Unit) {
    val cover by produceState<ImageBitmap?>(null,book.id) {
        value=withContext(Dispatchers.IO) { runCatching { controller.coverPath(book)?.let { ImageIO.read(it.toFile())?.toComposeImageBitmap() } }.getOrNull() }
    }
    ContextMenuArea(items={ listOf(ContextMenuItem("编辑",onEdit),ContextMenuItem("移出",onRemove)) }) {
    Column(Modifier.widthIn(max=240.dp).testTag("book-card-${book.id}").clickable(onClick=onOpen)) {
        Surface(shape=RoundedCornerShape(9.dp),elevation=3.dp,modifier=Modifier.fillMaxWidth().aspectRatio(.7f),color=MaterialTheme.colors.primary.copy(alpha=.12f)) {
            Box(contentAlignment=Alignment.Center) {
                if(cover!=null) Image(cover!!,book.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                else Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(book.format.name,style=MaterialTheme.typography.overline,color=MaterialTheme.colors.primary)
                    Spacer(Modifier.height(22.dp))
                    Text(book.title,fontSize=21.sp,fontWeight=FontWeight.Medium,maxLines=5,overflow=TextOverflow.Ellipsis)
                    if(book.author.isNotBlank()) Text(book.author,style=MaterialTheme.typography.caption,modifier=Modifier.padding(top=18.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Text(if(book.lastOpenedAt>0) "已读 ${(book.progress*100).toInt()}%" else "尚未阅读",
                    style=MaterialTheme.typography.caption,fontWeight=FontWeight.Medium,color=MaterialTheme.colors.onPrimary,
                    modifier=Modifier.align(Alignment.TopStart).padding(9.dp).background(MaterialTheme.colors.primary,RoundedCornerShape(5.dp)).padding(horizontal=9.dp,vertical=5.dp))
                Text(book.format.name,style=MaterialTheme.typography.overline,color=MaterialTheme.colors.onSurface,modifier=Modifier.align(Alignment.BottomEnd).padding(8.dp).background(MaterialTheme.colors.surface.copy(alpha=.92f),RoundedCornerShape(4.dp)).padding(horizontal=7.dp,vertical=2.dp))
            }
        }
        Text(book.title,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().padding(top=12.dp))
        Text(book.author.ifBlank { book.category.ifBlank { "未分类" } },style=MaterialTheme.typography.caption,color=MaterialTheme.colors.onSurface.copy(alpha=.58f),maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
    }
    }
}

@Composable private fun EditBookDialog(book:BookRecord,categories:List<String>,onDismiss:()->Unit,onSave:(String,String)->Unit) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var category by remember(book.id) { mutableStateOf(book.category) }
    var categoryMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={ Text("编辑书籍") },text={
        Column(Modifier.width(390.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(title,{ title=it },label={ Text("书名") },singleLine=true,modifier=Modifier.fillMaxWidth(),isError=title.isBlank())
            OutlinedTextField(category,{ category=it },label={ Text("分类") },placeholder={ Text("留空表示未分类；也可输入新分类") },singleLine=true,modifier=Modifier.fillMaxWidth())
            if(categories.isNotEmpty()) Box {
                TextButton(onClick={ categoryMenu=true }) { Text("选择已有分类 ▾") }
                DropdownMenu(categoryMenu,{ categoryMenu=false }) {
                    DropdownMenuItem(onClick={ category=""; categoryMenu=false }) { Text("未分类") }
                    categories.forEach { existing -> DropdownMenuItem(onClick={ category=existing; categoryMenu=false }) { Text(existing) } }
                }
            }
        }
    },confirmButton={ TextButton(onClick={ onSave(title.trim(),category.trim()) },enabled=title.isNotBlank()) { Text("保存") } },dismissButton={ TextButton(onClick=onDismiss) { Text("取消") } })
}
