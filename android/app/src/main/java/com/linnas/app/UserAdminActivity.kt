package com.linnas.app

import android.widget.EditText
import android.widget.LinearLayout
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
        addLoading()
        lifecycleScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { client.users() }
            } catch (e: Exception) {
                clearContent()
                addEmptyState("读取失败", e.message ?: "无法获取用户列表")
                return@launch
            }
            clearContent()
            if (list.isEmpty()) {
                addEmptyState("还没有用户", "点右上角「新建」创建第一个账号")
                return@launch
            }

            val admins = list.filter { it.role == "admin" }
            val normal = list.filter { it.role != "admin" }

            if (admins.isNotEmpty()) {
                addSectionHeader("管理员")
                for (u in admins) addUserRow(u)
            }
            if (normal.isNotEmpty()) {
                addSectionHeader("普通用户")
                for (u in normal) addUserRow(u)
            }
            addText("共 ${list.size} 个用户 · 点按编辑,长按删除")
        }
    }

    /** 一条用户卡片行。 */
    private fun addUserRow(u: ServerUser) {
        val status = when {
            u.disabled -> "已停用"
            else -> roleLabel(u.role)
        }
        addIconRow(
            iconRes = if (u.role == "admin") R.drawable.ic_lock else R.drawable.ic_user,
            title = u.username,
            subtitle = "ID ${u.id}  ·  ${roleLabel(u.role)}",
            value = status,
            tintAttr = if (u.disabled) R.attr.lTextDim else R.attr.lPrimary,
            onClick = { showEdit(u) },
            onLongClick = { confirmDelete(u) },
        )
    }

    private fun showCreate() {
        val sheet = Sheet(this, "新建用户")
        sheet.input("用户名")
        sheet.input("密码", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.input("角色 admin/user/viewer", "user")
        sheet.primary("创建") {
            val u = sheet.value(0)
            val p = sheet.inputView(1)?.text?.toString().orEmpty()
            val r = sheet.value(2).ifBlank { "user" }
            if (u.isBlank() || p.isBlank()) {
                sheet.inputView(0)?.error = "用户名和密码不能为空"
                toast("用户名和密码不能为空")
                return@primary
            }
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.createUser(u, p, r) }
                    sheet.dismiss()
                    toast("已创建 $u")
                    load()
                } catch (e: Exception) { toast("创建失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun showEdit(u: ServerUser) {
        val sheet = Sheet(this, "编辑「${u.username}」")
        sheet.input("新密码(留空则不改)", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.input("角色 admin/user/viewer", u.role)
        sheet.primary("保存") {
            val p = sheet.inputView(0)?.text?.toString().orEmpty()
            val r = sheet.value(1)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        client.updateUser(u.id, p.takeIf { it.isNotBlank() }, r.takeIf { it.isNotBlank() })
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

    private fun confirmDelete(u: ServerUser) {
        val sheet = Sheet(this, "删除用户", "确定删除用户「${u.username}」?此操作不可恢复。")
        sheet.danger("删除") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.deleteUser(u.id) }
                    toast("已删除")
                    load()
                } catch (e: Exception) { toast("删除失败: ${e.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }
}
