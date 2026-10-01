package dev.towertools.towerreader.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.towertools.towerreader.library.BookRecord
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
internal fun LibraryCategories(books:List<BookRecord>,categories:List<String>,filter:String,onSelect:(String)->Unit,onEdit:(String)->Unit,onOrder:(List<String>)->Unit) {
    val listState=rememberLazyListState()
    val counts=remember(books) { books.groupingBy { it.category }.eachCount() }
    val recentCount=remember(books) { books.count { it.lastOpenedAt>0 } }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var dragPointer by remember { mutableFloatStateOf(0f) }
    val order=preview ?: categories
    val latestCategories by rememberUpdatedState(categories)
    val commit by rememberUpdatedState(onOrder)
    val move by rememberUpdatedState({
        val name=dragging
        if(name!=null) {
            val current=preview ?: latestCategories
            val keys=current.map { "category:$it" }.toSet()
            val visible=listState.layoutInfo.visibleItemsInfo.filter { it.key in keys }
            val names=visible.map { it.key.toString().removePrefix("category:") }
            // Wait for the preceding move to reach layout before interpreting another crossing.
            val target=if(current.filter { it in names }==names) visible.minByOrNull { abs(it.offset+it.size/2f-dragCenter) }?.key?.toString()?.removePrefix("category:") else null
            if(target!=null && target!=name) {
                val reordered=current.toMutableList()
                val from=reordered.indexOf(name); val to=reordered.indexOf(target)
                if(from>=0 && to>=0) {
                    reordered.add(to,reordered.removeAt(from))
                    // Keep the viewport at the same index while its keys change. Otherwise LazyColumn
                    // follows the old first visible key and can undo upward edge scrolling.
                    val first=listState.firstVisibleItemIndex
                    val offset=listState.firstVisibleItemScrollOffset
                    preview=reordered
                    listState.requestScrollToItem(first,offset)
                }
            }
        }
    })
    val edge=with(LocalDensity.current) { 36.dp.toPx() }
    val halfHandle=with(LocalDensity.current) { 10.dp.toPx() }
    LaunchedEffect(dragging,preview) {
        if(dragging==null) return@LaunchedEffect
        while(isActive) {
            withFrameNanos { }
            val layout=listState.layoutInfo
            val amount=when {
                dragPointer<layout.viewportStartOffset+edge -> -10f
                dragPointer>layout.viewportEndOffset-edge -> 10f
                else -> 0f
            }
            if(amount!=0f && listState.scrollBy(amount)!=0f) move()
        }
    }
    LazyColumn(state=listState,modifier=Modifier.width(190.dp).fillMaxHeight().padding(horizontal=14.dp,vertical=24.dp).testTag("library-categories"),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        item(key="fixed:heading") { Text("我的书库",style=MaterialTheme.typography.overline,color=MaterialTheme.colors.onSurface.copy(alpha=.5f),modifier=Modifier.padding(12.dp)) }
        item(key="fixed:all") { CategoryRow("全部书籍",books.size,filter=="all",{ onSelect("all") }) }
        item(key="fixed:recent") { CategoryRow("最近阅读",recentCount,filter=="recent",{ onSelect("recent") }) }
        item(key="fixed:uncategorized") { CategoryRow("未分类",counts[""] ?: 0,filter=="uncategorized",{ onSelect("uncategorized") }) }
        if(order.isNotEmpty()) item(key="fixed:categories") { Text("分类",style=MaterialTheme.typography.overline,color=MaterialTheme.colors.onSurface.copy(alpha=.5f),modifier=Modifier.padding(start=12.dp,top=24.dp,bottom=8.dp)) }
        items(order,key={ "category:$it" }) { category ->
            Box(Modifier.zIndex(if(dragging==category) 1f else 0f).graphicsLayer {
                translationY=if(dragging==category) listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key=="category:$category" }?.let { dragCenter-it.offset-it.size/2f } ?: 0f else 0f
            }) {
            ContextMenuArea(items={ listOf(ContextMenuItem("编辑") { onEdit(category) }) }) {
                CategoryRow(category,counts[category] ?: 0,filter=="category:$category",{ onSelect("category:$category") },dragging==category,
                    Modifier.testTag("category-handle-$category").semantics { contentDescription="拖动分类：$category" }
                        .pointerHoverIcon(PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)))
                        .pointerInput(category) {
                            detectDragGestures(
                                onDragStart={ position ->
                                    preview=latestCategories
                                    val item=listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key=="category:$category" }
                                    dragCenter=(item?.offset ?: 0)+(item?.size ?: 0)/2f
                                    dragPointer=dragCenter+position.y-halfHandle
                                    dragging=category
                                },
                                onDrag={ change,amount -> change.consume(); dragCenter+=amount.y; dragPointer+=amount.y; move() },
                                onDragEnd={ val result=preview ?: latestCategories; dragging=null; preview=null; if(result!=latestCategories) commit(result) },
                                onDragCancel={ dragging=null; preview=null },
                            )
                        })
            }
            }
        }
    }
}

@Composable
private fun CategoryRow(label:String,count:Int,selected:Boolean,onClick:()->Unit,dragging:Boolean=false,handle:Modifier?=null) {
    Row(Modifier.fillMaxWidth().testTag("library-filter-$label").clip(RoundedCornerShape(8.dp))
        .background(when { dragging -> MaterialTheme.colors.primary.copy(alpha=.2f); selected -> MaterialTheme.colors.primary.copy(alpha=.1f); else -> MaterialTheme.colors.background })
        .clickable(onClick=onClick).padding(start=if(handle==null) 12.dp else 4.dp,end=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
        if(handle!=null) {
            val color=MaterialTheme.colors.onSurface.copy(alpha=.5f)
            Box(handle.size(28.dp,20.dp),contentAlignment=Alignment.Center) {
                Canvas(Modifier.size(14.dp,12.dp)) { repeat(3) { row -> val y=1.dp.toPx()+row*(size.height-2.dp.toPx())/2; drawLine(color,Offset(0f,y),Offset(size.width,y),1.5.dp.toPx(),StrokeCap.Round) } }
            }
        }
        Text(label,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=if(selected) FontWeight.Medium else FontWeight.Normal,color=if(selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface)
        Text(count.toString(),style=MaterialTheme.typography.caption,color=MaterialTheme.colors.onSurface.copy(alpha=.5f))
    }
}

@Composable
internal fun EditCategoryDialog(previous:String,categories:List<String>,saving:Boolean,onClose:()->Unit,onSave:(String)->Unit) {
    var input by remember(previous) { mutableStateOf(TextFieldValue(previous,TextRange(0,previous.length))) }
    val name=input.text.trim()
    val error=when { name.isEmpty() -> "分类名称不能为空"; name!=previous && name in categories -> "已有同名分类，请换一个名称"; else -> null }
    val focus=remember { FocusRequester() }
    val save={ if(error==null && !saving) onSave(name) }
    AlertDialog(onDismissRequest={ if(!saving) onClose() },title={ Text("编辑分类") },text={
        Column(Modifier.width(330.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(input,{ input=it },label={ Text("分类名称") },singleLine=true,enabled=!saving,isError=error!=null,
                modifier=Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if(it.type==KeyEventType.KeyDown && (it.key==Key.Enter || it.key==Key.NumPadEnter)) { save(); true } else false
                })
            if(error!=null) Text(error,color=MaterialTheme.colors.error,style=MaterialTheme.typography.caption)
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    },confirmButton={ TextButton(onClick=save,enabled=error==null && !saving) { Text(if(saving) "保存中…" else "保存") } },dismissButton={ TextButton(onClick=onClose,enabled=!saving) { Text("取消") } })
}
