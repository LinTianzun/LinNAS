package com.linnas.app

import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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
        addText("加载中…")
        lifecycleScope.launch {
            val libs = try {
                withContext(Dispatchers.IO) { client.libs() }
            } catch (e: Exception) {
                clearContent(); addText("读取失败: ${e.message}"); return@launch
            }
            clearContent()
            if (libs.isEmpty()) { addText("还没有存储库,请先在「添加存储库」里添加。"); return@launch }
            addText("共 ${libs.size} 个 · 点按编辑,长按删除")
            for (lib in libs) {
                addRow(
                    title = lib.name + (if (lib.readOnly) " · 只读" else "") + (if (!lib.enabled) " · 停用" else ""),
                    subtitle = lib.path,
                    onClick = { showEdit(lib) },
                    onLongClick = { confirmDelete(lib) },
                )
            }
        }
    }

    private fun showEdit(lib: Library) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etName = EditText(this).apply { hint = "显示名称"; setText(lib.name); setSingleLine() }
        val etPath = EditText(this).apply { hint = "服务端目录路径"; setText(lib.path); setSingleLine() }
        val cbReadOnly = CheckBox(this).apply { text = "只读"; isChecked = lib.readOnly }
        val cbEnabled = CheckBox(this).apply { text = "启用"; isChecked = lib.enabled }
        box.addView(etName)
        box.addView(etPath)
        box.addView(cbReadOnly)
        box.addView(cbEnabled)
        AlertDialog.Builder(this).setTitle("编辑「${lib.name}」").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val path = etPath.text.toString().trim()
                if (name.isBlank() || path.isBlank()) { toast("名称和路径不能为空"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            client.libUpdate(lib.id, name, path, cbReadOnly.isChecked, cbEnabled.isChecked)
                        }
                        toast("已保存")
                        load()
                    } catch (e: Exception) { toast("保存失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmDelete(lib: Library) {
        AlertDialog.Builder(this).setTitle("删除存储库")
            .setMessage("删除存储库「${lib.name}」?\n注意:只是从 App 里移除该存储库,不会删除电脑上的文件。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.deleteLib(lib.id) }
                        toast("已删除")
                        load()
                    } catch (e: Exception) { toast("删除失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }
}
