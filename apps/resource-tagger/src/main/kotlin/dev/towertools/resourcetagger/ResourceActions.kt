package dev.towertools.resourcetagger

import androidx.compose.foundation.ContextMenuItem

/** Host-owned management actions. Plugins only contribute additional preview actions. */
class ResourceActions(
    val data: Snapshot,
    val busy: Boolean,
    private val controller: LibraryController,
    val editTags: (String) -> Unit,
    val relocate: (Resource) -> Unit,
    val remove: (Resource) -> Unit,
    val confirmType: (Resource, Review) -> Unit,
) {
    fun acknowledge(resource: Resource, pending: Review) = controller.submit(if (pending.reason == Reason.New) "确认收录" else "保留记录") { it.acknowledge(resource.id) }
    fun ignore(resource: Resource) = controller.submit(if (resource.status == Status.Ignored) "取消忽略" else "忽略资源") { if (resource.status == Status.Ignored) it.unignore(resource.id) else it.ignore(resource.id) }
    fun navigate(resource: Resource, open: Boolean) {
        val root = data.roots.single { it.id == resource.rootId }
        controller.submit(if (open) "打开目录" else "在所在目录中显示", refresh = false) { Navigator.navigate(root, resource, open) }
    }
    fun menu(resource: Resource): List<ContextMenuItem> = buildList {
        if (!busy) {
            if (resource.status == Status.Active) {
                if (resource.kind == Kind.Directory) add(ContextMenuItem("打开目录") { navigate(resource, true) })
                add(ContextMenuItem("在所在目录中显示") { navigate(resource, false) })
            }
            add(ContextMenuItem("编辑标签") { editTags(resource.id) })
            if (resource.status == Status.Missing) {
                add(ContextMenuItem("重新定位") { relocate(resource) })
                if (data.reviews.none { it.resourceId == resource.id && it.reason == Reason.TypeChanged }) add(ContextMenuItem("保留记录") { controller.submit("保留记录") { it.acknowledge(resource.id) } })
            }
            if (resource.status == Status.Ignored) add(ContextMenuItem("取消忽略") { controller.submit("取消忽略") { it.unignore(resource.id) } })
            else add(ContextMenuItem("忽略") { controller.submit("忽略资源") { it.ignore(resource.id) } })
            add(ContextMenuItem("从数据库移除") { this@ResourceActions.remove(resource) })
        }
    }
}
