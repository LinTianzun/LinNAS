package com.linnas.app

import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v1.7.0 存储库管理(仅管理员):编辑名称/路径/只读/启用,或删除存储库。 */
class LibraryAdminActivity : ScreenScaffold() {

    override fun title() = "存储库管理"

    override fun onCreateScreen() {
        addHeaderAction("刷新") { load() }
        load()
    }

    private fun load() {
        clearContent()
        addLoading()
        lifecycleScope.launch {
            // /api/libs 只有名称/路径/开关,没有容量;容量来自 /api/disks(按存储库 id 对应)。
            // 两次请求分开兜底:磁盘信息失败(比如旧版服务端没有该接口)只退化为「容量未知」,
            // 不能连累存储库列表本身。
            val libs = try {
                withContext(Dispatchers.IO) { client.libs() }
            } catch (e: Exception) {
                clearContent()
                addEmptyState("读取失败", e.message ?: "无法获取存储库列表")
                return@launch
            }
            val disks = try {
                withContext(Dispatchers.IO) { client.disks() }
            } catch (_: Exception) {
                emptyList<DiskInfo>()
            }
            clearContent()
            if (libs.isEmpty()) {
                addEmptyState("还没有存储库", "请先在「添加存储库」里添加一个目录")
                return@launch
            }

            // 磁盘信息按存储库 id 建索引;取不到时对应行退化为「容量未知」,不影响其它信息
            val diskById = disks.associateBy { it.id }
            addSectionHeader("存储库")
            for (lib in libs) {
                val flags = buildString {
                    if (lib.readOnly) append("只读")
                    if (!lib.enabled) {
                        if (isNotEmpty()) append(" · ")
                        append("已停用")
                    }
                }
                addIconRow(
                    iconRes = R.drawable.ic_server,
                    title = lib.name,
                    // 副标题两行:第一行路径,第二行容量(已用/总量),一眼看清这个库占了多少盘
                    subtitle = "${lib.path}\n${capacityLabel(diskById[lib.id])}",
                    value = flags.ifBlank { "可读写" },
                    tintAttr = if (lib.enabled) R.attr.lPrimary else R.attr.lTextDim,
                    onClick = { showEdit(lib) },
                    onLongClick = { confirmDelete(lib) },
                )
            }
            addText("共 ${libs.size} 个 · 点按编辑,长按删除\n容量为存储库所在磁盘的整体用量")
        }
    }

    /** 容量文案:用已用/总量;拿不到磁盘信息或总量为 0 时退化为「容量未知」。 */
    private fun capacityLabel(disk: DiskInfo?): String {
        val d = disk ?: return "容量未知"
        if (d.totalMB <= 0) return "容量未知"
        val used = (d.totalMB - d.freeMB).coerceAtLeast(0)
        return "已用 ${formatBytes(used)} · 共 ${formatBytes(d.totalMB)}"
    }

    /** MB → 「1.2 TB / 812 GB / 123 MB」,与主界面 fmtGB 同口径(1024 进制)。 */
    private fun formatBytes(mb: Long): String {
        if (mb <= 0) return "0 MB"
        val tb = mb / 1024.0 / 1024.0
        return when {
            tb >= 1 -> String.format(java.util.Locale.US, "%.1f TB", tb)
            mb >= 1024 -> String.format(java.util.Locale.US, "%.0f GB", mb / 1024.0)
            else -> "$mb MB"
        }
    }

    private fun showEdit(lib: Library) {
        val sheet = Sheet(this, "编辑「${lib.name}」")
        sheet.input("显示名称", lib.name)
        sheet.input("服务端目录路径", lib.path)
        // 两个布尔开关改用分段选择器:原地选中,不必再拼 CheckBox
        var readOnly = lib.readOnly
        var enabled = lib.enabled
        sheet.segmented(listOf("可读写", "只读"), if (lib.readOnly) 1 else 0) { i -> readOnly = i == 1 }
        sheet.segmented(listOf("已停用", "启用"), if (lib.enabled) 1 else 0) { i -> enabled = i == 1 }
        sheet.primary("保存") {
            val name = sheet.value(0)
            val path = sheet.value(1)
            if (name.isBlank() || path.isBlank()) {
                sheet.inputView(0)?.error = "名称和路径不能为空"
                toast("名称和路径不能为空")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        client.libUpdate(lib.id, name, path, readOnly, enabled)
                    }
                    sheet.dismiss()
                    toast("已保存")
                    load()
                } catch (e: Exception) { toast("保存失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun confirmDelete(lib: Library) {
        val sheet = Sheet(
            this,
            "删除存储库",
            "删除存储库「${lib.name}」?\n注意:只是从 App 里移除该存储库,不会删除电脑上的文件。",
        )
        sheet.danger("删除") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.deleteLib(lib.id) }
                    toast("已删除")
                    load()
                } catch (e: Exception) { toast("删除失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }
}
