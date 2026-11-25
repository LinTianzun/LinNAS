package com.linnas.app

import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** v1.7.0 用户管理(仅管理员):新建 / 改角色与密码 / 删除。 */
class UserAdminActivity : ScreenScaffold() {

    override fun title() = "用户管理"

    override fun onCreateScreen() {
        addHeaderAction("新建") { showCreate() }
        addHeaderAction("刷新") { load() }
        load()
    }

    private fun load() {
        clearContent()
        addText("加载中…")
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { client.users() }
            } catch (e: Exception) {
                clearContent(); addText("读取失败: ${e.message}"); return@launch
            }
            clearContent()
            addText("共 ${list.size} 个用户 · 点按编辑,长按删除")
            for (u in list) {
                addRow(
                    title = "${u.username} · ${roleLabel(u.role)}" + if (u.disabled) " · 已停用" else "",
                    subtitle = "ID ${u.id}",
                    onClick = { showEdit(u) },
                    onLongClick = { confirmDelete(u) },
                )
            }
        }
    }

    private fun form(vararg pairs: Pair<String, String>): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        for ((hint, value) in pairs) {
            box.addView(EditText(this).apply {
                this.hint = hint
                setText(value)
                setSingleLine()
            })
        }
        return box
    }

    private fun showCreate() {
        val box = form("用户名" to "", "密码" to "", "角色 admin/user/viewer" to "user")
        val etUser = box.getChildAt(0) as EditText
        val etPw = box.getChildAt(1) as EditText
        val etRole = box.getChildAt(2) as EditText
        AlertDialog.Builder(this).setTitle("新建用户").setView(box)
            .setPositiveButton("创建") { _, _ ->
                val u = etUser.text.toString().trim()
                val p = etPw.text.toString()
                val r = etRole.text.toString().trim().ifBlank { "user" }
                if (u.isBlank() || p.isBlank()) { toast("用户名和密码不能为空"); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.createUser(u, p, r) }
                        toast("已创建 $u")
                        load()
                    } catch (e: Exception) { toast("创建失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showEdit(u: ServerUser) {
        val box = form("新密码(留空则不改)" to "", "角色 admin/user/viewer" to u.role)
        val etPw = box.getChildAt(0) as EditText
        val etRole = box.getChildAt(1) as EditText
        AlertDialog.Builder(this).setTitle("编辑「${u.username}」").setView(box)
            .setPositiveButton("保存") { _, _ ->
                val p = etPw.text.toString()
                val r = etRole.text.toString().trim()
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            client.updateUser(u.id, p.takeIf { it.isNotBlank() }, r.takeIf { it.isNotBlank() })
                        }
                        toast("已保存")
                        load()
                    } catch (e: Exception) { toast("保存失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun confirmDelete(u: ServerUser) {
        AlertDialog.Builder(this).setTitle("删除用户").setMessage("确定删除用户「${u.username}」?此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.deleteUser(u.id) }
                        toast("已删除")
                        load()
                    } catch (e: Exception) { toast("删除失败: ${e.message}") }
                }
            }
            .setNegativeButton("取消", null).show()
    }
}
