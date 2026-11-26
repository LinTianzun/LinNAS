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
            val libs = try {
                withContext(Dispatchers.IO) { client.libs() }
            } catch (e: Exception) {
                clearContent()
                addEmptyState("读取失败", e.message ?: "无法获取存储库列表")
                return@launch
            }
            clearContent()
            if (libs.isEmpty()) {
                addEmptyState("还没有存储库", "请先在「添加存储库」里添加一个目录")
                return@launch
            }

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
                    subtitle = lib.path,
                    value = flags.ifBlank { "可读写" },
                    tintAttr = if (lib.enabled) R.attr.lPrimary else R.attr.lTextDim,
                    onClick = { showEdit(lib) },
                    onLongClick = { confirmDelete(lib) },
                )
            }
            addText("共 ${libs.size} 个 · 点按编辑,长按删除")
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
