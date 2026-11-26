package com.linnas.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.linnas.app.backup.BackupConfig
import com.linnas.app.backup.PhotoBackupScheduler
import com.linnas.app.transfer.TransferService
import com.linnas.app.upload.UploadService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val client = LinNasClient()

    /** 扫电脑上的配对二维码,免手输地址。*/
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        val s = Discovery.parsePair(contents)
        if (s == null) {
            toast("二维码内容无法识别")
            return@registerForActivityResult
        }
        ServerConfig.baseUrl = s.baseUrl
        toast("已连接 ${s.ip}:${s.port}")
        connectAndLoad()
    }

    private fun startQrScan() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("对准电脑 linNas 配对页上的二维码")
            setBeepEnabled(false)
            // 扫码页锁定竖屏(与 manifest 覆盖 zxing 的 sensorLandscape 呼应)
            setOrientationLocked(true)
        }
        try {
            scanLauncher.launch(opts)
        } catch (e: Exception) {
            toast("无法打开相机:${e.message}")
        }
    }

    private lateinit var fileAdapter: FileListAdapter
    private lateinit var mediaAdapter: MediaGridAdapter
    private lateinit var mediaFolderAdapter: MediaFolderAdapter
    private var mediaMode = "flat"          // flat=全部平铺 / folder=文件夹浏览
    private var mediaFolderPath = ""        // 文件夹模式当前目录
    private val mediaFolderStack = mutableListOf<String>()
    private var mediaFolderEntries: List<Entry> = emptyList()  // 文件夹模式的数据源(当前类型的全部媒体)

    private var libs: List<Library> = emptyList()
    private var curPath: String = ""                    // "" = 存储库根
    private val crumbs = mutableListOf<Pair<String, String>>() // label -> vpath
    private var allRows: List<ListRow> = emptyList()
    private var currentTab = R.id.tab_files
    private var serverVersion = ""
    private var mediaType = "video"
    private var mediaAll: List<Entry> = emptyList()   // P2:媒体页当前列表(播放列表来源)

    // 视图
    private lateinit var connDot: View
    private lateinit var connText: TextView
    private lateinit var crumbsBar: LinearLayout
    private lateinit var fileList: RecyclerView
    private lateinit var fileGrid: RecyclerView
    private lateinit var filesEmpty: View
    private lateinit var diskInfo: TextView
    private lateinit var gridAdapter: FileGridAdapter
    private var disksList: List<DiskInfo> = emptyList()
    private var viewMode = "list"
    private var sortKey = "name"
    private var sortAsc = true
    // v1.8.0 文件列表筛选(类型/大小/时间)
    private var typeFilter = "all"   // all/dir/video/image/audio/archive/other
    private var sizeFilter = "all"   // all/lt10m/b10_100m/b100m_1g/gt1g
    private var timeFilter = "all"   // all/today/7d/30d
    private var globalSearch = false
    private var searchSeq = 0
    private lateinit var downloadList: LinearLayout
    private lateinit var downloadEmpty: View
    private lateinit var searchInput: TextInputEditText
    private lateinit var mediaGrid: RecyclerView
    private lateinit var mediaEmpty: View
    private lateinit var mediaPath: TextView
    private lateinit var swBackup: MaterialSwitch
    private lateinit var swWifi: MaterialSwitch
    private lateinit var etBackupDir: TextInputEditText
    private lateinit var backupStatus: TextView
    private lateinit var transferList: LinearLayout
    private lateinit var transferEmpty: View
    private lateinit var setServer: TextView
    private lateinit var setServerAddr: TextView
    private lateinit var setToken: TextView
    private lateinit var setServerVersion: TextView
    private lateinit var setAppVersion: TextView
    private lateinit var setDownloadDir: TextView

    private val handler = Handler(Looper.getMainLooper())
    // v1.8.1 传输页:复用行视图(此前每秒重建整页 → 进度条反复从 0 动画,看起来在闪烁)
    private val uploadViews = mutableMapOf<String, View>()
    private val offlineViews = mutableMapOf<String, View>()
    private val downloadViews = mutableMapOf<String, View>()
    private val uploadSpeeds = mutableMapOf<String, SpeedMeter>()

    private val poller = object : Runnable {
        override fun run() {
            if (currentTab == R.id.tab_transfers) refreshTransfers()
            handler.postDelayed(this, 1000)
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        if (curPath.isEmpty()) { toast("先进入一个存储库"); return@registerForActivityResult }
        for (u in uris) enqueueUpload(u)
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    /** v1.8.0:用户用系统文件夹选择器挑一个可见的下载目录(SAF),长期保存权限。 */
    private val downloadTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                UiPrefs.setDownloadTree(this, uri.toString())
                val name = queryTreeName(uri)
                UiPrefs.setDownloadTreeName(this, name)
                setDownloadDir.text = name
                toast("下载将保存到「$name」")
            } catch (e: Exception) {
                toast("无法使用该文件夹:${e.message ?: "权限被拒绝"}")
            }
        }

    private fun queryTreeName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: "已选文件夹"
    } catch (_: Exception) {
        uri.lastPathSegment ?: "已选文件夹"
    }

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            // 登录成功,继续加载主流程
            connectAndLoad(silent = true)
        } else {
            // 跳过登录:用访问口令继续,口令无效时由各请求报错提示
            loadAfterAuth()
        }
    }

    private fun canWrite(): Boolean = ServerConfig.sessionRole != "viewer"

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
        lifecycleScope.launch {
            try {
                OfflineDownloader.recoverInterrupted(this@MainActivity)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("检查离线下载状态失败:${e.message ?: "未知错误"}")
            }
        }
        setContentView(R.layout.activity_main)
        viewMode = UiPrefs.viewMode(this)
        sortKey = UiPrefs.sortKey(this)
        sortAsc = UiPrefs.sortAsc(this)

        connDot = findViewById(R.id.connDot)
        connText = findViewById(R.id.connText)
        crumbsBar = findViewById(R.id.crumbs)
        fileList = findViewById(R.id.fileList)
        fileGrid = findViewById(R.id.fileGrid)
        diskInfo = findViewById(R.id.diskInfo)
        downloadList = findViewById(R.id.downloadList)
        downloadEmpty = findViewById(R.id.downloadEmpty)
        filesEmpty = findViewById(R.id.filesEmpty)
        searchInput = findViewById(R.id.searchInput)
        mediaGrid = findViewById(R.id.mediaGrid)
        mediaEmpty = findViewById(R.id.mediaEmpty)
        swBackup = findViewById(R.id.swBackup)
        swWifi = findViewById(R.id.swWifi)
        etBackupDir = findViewById(R.id.etBackupDir)
        backupStatus = findViewById(R.id.backupStatus)
        transferList = findViewById(R.id.transferList)
        transferEmpty = findViewById(R.id.transferEmpty)
        setServer = findViewById(R.id.setServer)
        setServerAddr = findViewById(R.id.setServerAddr)
        setToken = findViewById(R.id.setToken)
        setServerVersion = findViewById(R.id.setServerVersion)
        setAppVersion = findViewById(R.id.setAppVersion)
        setDownloadDir = findViewById(R.id.setDownloadDir)

        fileAdapter = FileListAdapter(
            { vp -> client.thumbUrl(vp) },
            ::onRowOpen,
            ::onRowMore,
        ) { vpath ->
            val min = PlaybackStore.remainingMin(this, vpath)
            if (min > 0) "继续观看 · 还剩约 $min 分钟" else null
        }
        fileList.layoutManager = LinearLayoutManager(this)
        fileList.adapter = fileAdapter

        gridAdapter = FileGridAdapter({ vp -> client.thumbUrl(vp) }, ::onRowOpen, ::onRowMore)
        fileGrid.layoutManager = GridLayoutManager(this, 2)
        fileGrid.adapter = gridAdapter

        mediaAdapter = MediaGridAdapter({ vp -> client.thumbUrl(vp) }, ::onMediaOpen)
        mediaFolderAdapter = MediaFolderAdapter(
            { vp -> client.thumbUrl(vp) },
            ::onMediaOpenFolder,
            ::onMediaOpen,
        )
        val mediaGlm = GridLayoutManager(this, 2)
        mediaGlm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (mediaAdapter.isHeader(position)) 2 else 1
        }
        mediaGrid.layoutManager = mediaGlm
        mediaGrid.adapter = mediaAdapter
        mediaPath = findViewById(R.id.mediaPath)
        findViewById<MaterialButtonToggleGroup>(R.id.mediaModeToggle)
            .addOnButtonCheckedListener { _, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                mediaMode = if (id == R.id.btnModeFolder) "folder" else "flat"
                if (mediaMode == "flat") {
                    mediaFolderStack.clear()
                    mediaFolderPath = ""
                    mediaPath.visibility = View.GONE
                    mediaGrid.layoutManager = mediaGlm
                    mediaGrid.adapter = mediaAdapter
                    loadMedia(mediaType)
                } else {
                    mediaPath.visibility = View.VISIBLE
                    mediaGrid.layoutManager = LinearLayoutManager(this)
                    mediaGrid.adapter = mediaFolderAdapter
                    if (mediaFolderEntries.isEmpty()) loadMedia(mediaType) else renderMediaFolder()
                }
            }

        findViewById<View>(R.id.btnRefresh).setOnClickListener { connectAndLoad(silent = true) }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showTopMenu(it) }
        findViewById<View>(R.id.fabUpload).setOnClickListener {
            if (curPath.isEmpty()) toast("先进入一个存储库") else pickFile.launch("*/*")
        }

        (findViewById<MaterialButtonToggleGroup>(R.id.mediaToggle)).addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            mediaType = when (id) {
                R.id.btnTypeImage -> "image"
                R.id.btnTypeAudio -> "audio"
                else -> "video"
            }
            loadMedia(mediaType)
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onSearchChanged()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        setupBottomNav()
        setupBackup()
        setupSettings()
        setupSortView()
        setupTheme()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })

        findViewById<View>(R.id.btnClearFinished).setOnClickListener {
            UploadService.clearFinished()
            refreshTransfers()
        }

        if (ServerConfig.baseUrl.isBlank()) {
            showDiscoveryDialog()
        } else {
            connectAndLoad(silent = true)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
        // M7:多用户会话闲置超时失效后,回到页面时校验并重新登录
        if (ServerConfig.multiUser && ServerConfig.sessionToken.isNotBlank()) {
            lifecycleScope.launch {
                val me = try { withContext(Dispatchers.IO) { client.authMe() } } catch (_: Exception) { null }
                if (me == null || me.username.isBlank()) {
                    ServerConfig.clearSession()
                    bindSettings()
                    launchLogin()
                } else if (me.username != ServerConfig.sessionUser || me.role != ServerConfig.sessionRole) {
                    ServerConfig.sessionUser = me.username
                    ServerConfig.sessionRole = me.role
                    bindSettings()
                }
            }
        }
    }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poller) }

    // ---------- 导航 ----------

    private fun setupBottomNav() {
        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val csl = ColorStateList(states, intArrayOf(ThemeColors.primary(this), ThemeColors.textDim(this)))
        nav.itemIconTintList = csl
        nav.itemTextColor = csl
        nav.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            UiPrefs.setLastTab(this, item.itemId)
            findViewById<View>(R.id.pageFiles).visibility = if (item.itemId == R.id.tab_files) View.VISIBLE else View.GONE
            findViewById<View>(R.id.pageMedia).visibility = if (item.itemId == R.id.tab_media) View.VISIBLE else View.GONE
            findViewById<View>(R.id.pageTransfers).visibility = if (item.itemId == R.id.tab_transfers) View.VISIBLE else View.GONE
            findViewById<View>(R.id.pageSettings).visibility = if (item.itemId == R.id.tab_settings) View.VISIBLE else View.GONE
            findViewById<View>(R.id.fabUpload).visibility =
                if (item.itemId == R.id.tab_files && canWrite()) View.VISIBLE else View.GONE
            when (item.itemId) {
                R.id.tab_media -> loadMedia(mediaType)
                R.id.tab_transfers -> { refreshTransfers(); updateBackupStatus() }
                R.id.tab_settings -> bindSettings()
            }
            true
        }
        restoreTab()
    }

    /** 主题切换会 recreate 重建 Activity:恢复上次所在的 tab,而不是回到文件页。 */
    private fun restoreTab() {
        val id = UiPrefs.lastTab(this)
        if (id == R.id.tab_files) return
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            .selectedItemId = id
    }

    /** 切到底部导航 tab:0 文件 / 1 媒体 / 2 传输 / 3 设置。 */
    private fun selectTab(index: Int) {
        val id = intArrayOf(R.id.tab_files, R.id.tab_media, R.id.tab_transfers, R.id.tab_settings)[index]
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            .selectedItemId = id
    }

    private fun handleBack() {
        // 媒体页文件夹模式:先在目录树里返回上一级
        if (currentTab == R.id.tab_media && mediaFolderBack()) return
        if (currentTab != R.id.tab_files) {
            (findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav))
                .selectedItemId = R.id.tab_files
            return
        }
        when {
            curPath.isEmpty() -> { moveTaskToBack(true) }
            crumbs.size > 1 -> navigateTo(crumbs[crumbs.size - 2].second)
            else -> showRoot()
        }
    }

    // ---------- 连接 ----------

    private fun setConnState(state: String) {
        val (color, label) = when (state) {
            "online" -> R.color.mint to "已连接"
            "connecting" -> R.color.amber to "连接中"
            else -> R.color.danger to "未连接"
        }
        connDot.backgroundTintList = ContextCompat.getColorStateList(this, color)
        connText.text = label
    }

    private fun connectAndLoad(silent: Boolean = false) {
        if (ServerConfig.baseUrl.isBlank()) { showDiscoveryDialog(); return }
        setConnState("connecting")
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { client.health() }
            if (!ok) {
                setConnState("offline")
                if (libs.isEmpty()) showDiscoveryDialog() else if (!silent) showDiscoveryDialog()
                return@launch
            }
            // M7 多用户:探测 /api/auth/status,未登录则跳登录页
            val auth = try { withContext(Dispatchers.IO) { client.authStatus() } } catch (_: Exception) { null }
            val multiUser = auth?.optBoolean("multiUser") == true
            ServerConfig.multiUser = multiUser
            if (multiUser && ServerConfig.sessionToken.isBlank()) {
                launchLogin()
                return@launch
            }
            loadAfterAuth(silent)
        }
    }

    private fun launchLogin() {
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    private fun loadAfterAuth(silent: Boolean = true) {
        lifecycleScope.launch {
            val loadedLibs = try { withContext(Dispatchers.IO) { client.libs() } }
            catch (e: Exception) {
                if (ServerConfig.multiUser) {
                    setConnState("offline")
                    toast(e.message ?: "未登录或登录已过期")
                    launchLogin()
                } else {
                    setConnState("offline")
                    toast("加载失败: ${e.message}")
                }
                return@launch
            }
            val loadedDisks = withContext(Dispatchers.IO) { client.disks() }
            serverVersion = withContext(Dispatchers.IO) { client.version() }
            // 服务端自定义显示名(如「客厅NAS」):缓存后用于替代 IP 展示。
            val remoteName = withContext(Dispatchers.IO) { client.serverName() }
            if (remoteName.isNotBlank()) Prefs.setServerName(ServerConfig.baseUrl, remoteName)
            withContext(Dispatchers.Main) {
                libs = loadedLibs
                disksList = loadedDisks
                setConnState("online")
                bindSettings()
                if (curPath.isEmpty()) showRoot() else loadDir()
                updateDiskInfo()
            }
        }
    }

    private fun showRoot() {
        curPath = ""
        crumbs.clear()
        allRows = libs.map { ListRow.Lib(it) }
        renderCrumbs()
        applyAndSubmit(allRows)
    }

    private fun enterLib(lib: Library) {
        curPath = "/${lib.id}"
        crumbs.clear()
        crumbs.add("存储库" to "")
        crumbs.add(lib.name to curPath)
        loadDir()
    }

    private fun navigateTo(vpath: String) {
        if (vpath.isEmpty()) { showRoot(); return }
        val idx = crumbs.indexOfFirst { it.second == vpath }
        if (idx >= 0) while (crumbs.size > idx + 1) crumbs.removeAt(crumbs.size - 1)
        curPath = vpath
        loadDir()
    }

    private fun loadDir() {
        val path = curPath
        if (path.isEmpty()) { showRoot(); return }
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { client.ls(path) }
                withContext(Dispatchers.Main) {
                    allRows = resp.entries.map { ListRow.FileRow(it) }
                    filterFiles()
                    filesEmpty.visibility = if (resp.entries.isEmpty()) View.VISIBLE else View.GONE
                    renderCrumbs()
                }
            } catch (e: Exception) {
                toast("加载失败: ${e.message}")
            }
        }
    }

    private fun filterFiles() {
        val raw = searchInput.text?.toString()?.trim().orEmpty()
        val q = raw.lowercase()
        if (q.length >= 2) FavoritesStore.addSearch(this, raw)
        val byQuery = if (q.isEmpty()) allRows else allRows.filter {
            when (it) {
                is ListRow.Lib -> it.lib.name.lowercase().contains(q)
                is ListRow.FileRow -> it.e.name.lowercase().contains(q)
            }
        }
        applyAndSubmit(byQuery.filter { rowMatchesFilters(it) })
        runCatching {
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSort)
                .text = sortLabelText()
        }
    }

        /** v1.8.0 类型 / 大小 / 时间筛选(委托 FileFilters 纯函数,便于单测)。 */
    private fun rowMatchesFilters(row: ListRow): Boolean {
        if (row !is ListRow.FileRow) return true
        return FileFilters.matches(row.e, typeFilter, sizeFilter, timeFilter, System.currentTimeMillis())
    }

    private fun hasActiveFilter(): Boolean =
        typeFilter != "all" || sizeFilter != "all" || timeFilter != "all"

    private fun sortLabelText(): String {
        val k = when (sortKey) { "size" -> "大小"; "mtime" -> "时间"; else -> "名称" }
        val base = k + (if (sortAsc) " ↑" else " ↓")
        return if (hasActiveFilter()) "$base ·筛" else base
    }

    /** v1.8.0:媒体库按父文件夹分组(库根用库名,子目录用目录名)。 */
    private fun groupByFolder(entries: List<Entry>): List<Pair<String, List<Entry>>> {
        val map = LinkedHashMap<String, MutableList<Entry>>()
        for (e in entries) {
            val vp = e.vpath ?: ""
            val parent = if (vp.isBlank()) "" else vp.substringBeforeLast('/', "")
            val label = libs.firstOrNull { parent == "/" + it.id }?.name
                ?: parent.substringAfterLast('/').ifBlank { "根目录" }
            map.getOrPut(label) { mutableListOf() }.add(e)
        }
        return map.map { it.key to (it.value as List<Entry>) }
    }

    private fun renderCrumbs() {
        crumbsBar.removeAllViews()
        if (crumbs.isEmpty()) {
            crumbsBar.addView(chip("存储库", true) { showRoot() })
            return
        }
        crumbs.forEachIndexed { i, (label, vp) ->
            if (i > 0) {
                val sep = TextView(this).apply {
                    text = "/"
                    setTextColor(ThemeColors.outline(this@MainActivity))
                    textSize = 12f
                    setPadding(dp(2), 0, dp(2), 0)
                }
                crumbsBar.addView(sep)
            }
            crumbsBar.addView(chip(label, i == crumbs.size - 1) { navigateTo(vp) })
        }
    }

    private fun chip(label: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(
                if (active) ThemeColors.primary(this@MainActivity)
                else ThemeColors.textDim(this@MainActivity)
            )
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(150)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill)
            setOnClickListener { onClick() }
        }

    // ---------- 行操作 ----------

    private fun onRowOpen(row: ListRow) {
        when (row) {
            is ListRow.Lib -> enterLib(row.lib)
            is ListRow.FileRow -> {
                val e = row.e
                val vpNow = e.vpath ?: (curPath.trimEnd('/') + "/" + e.name)
                FavoritesStore.touchRecent(this, FavoritesStore.Ref(vpNow, e.name, e.isDir))
                if (e.isDir) {
                    val vp = e.vpath ?: (curPath.trimEnd('/') + "/" + e.name)
                    crumbs.add(e.name to vp)
                    curPath = vp
                    loadDir()
                } else {
                    openFile(e)
                }
            }
        }
    }

    private fun onRowMore(row: ListRow, anchor: View) {
        if (row !is ListRow.FileRow) return
        val e = row.e
        val vp = e.vpath ?: (curPath.trimEnd('/') + "/" + e.name)
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val playable = !e.isDir && (ext in FileListAdapter.VIDEO || ext in FileListAdapter.AUDIO)
        val offline = OfflineStore.get(this, vp)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (canWrite()) {
            actions += "重命名" to { renameDialog(vp, e.name) }
            actions += "删除" to { deleteConfirm(vp, e) }
            actions += "移动到…" to { showDirPicker { dest -> transferEntry(vp, dest, move = true) } }
            actions += "复制到…" to { showDirPicker { dest -> transferEntry(vp, dest, move = false) } }
            actions += "分享链接…" to {
                startActivity(Intent(this, SharesActivity::class.java).apply {
                    putExtra(SharesActivity.EXTRA_INITIAL_VPATH, vp)
                })
            }
        }
        actions += "详情" to { showInfoDialog(vp, e) }
        actions += "打包下载(ZIP)" to { downloadZip(vp, e) }
        if (playable) {
            when (offline?.status) {
                OfflineStore.ST_DONE -> actions += "移除离线缓存" to { toggleOffline(vp, e) }
                OfflineStore.ST_DOWNLOADING, OfflineStore.ST_PAUSED ->
                    actions += "离线下载中…" to { toggleOffline(vp, e) }
                else -> actions += "缓存到手机" to { toggleOffline(vp, e) }
            }
        }
        val fav = FavoritesStore.isFavorite(this, vp)
        actions += (if (fav) "取消收藏" else "收藏") to {
            val nowFav = FavoritesStore.toggleFavorite(this, FavoritesStore.Ref(vp, e.name, e.isDir))
            toast(if (nowFav) "已收藏" else "已取消收藏")
        }
        showActionSheet(e.name, actions)
    }

    private fun downloadZip(vp: String, e: Entry) {
        val base = (if (e.isDir) e.name else e.name.substringBeforeLast('.')).ifBlank { "下载" }
        val zipName = "$base.zip"
        startDownload(client.zipUrl(listOf(vp), zipName), zipName, TransferStore.KIND_ZIP)
    }

    /** v1.8.0 统一入队下载:走 TransferService(可暂停/续传/重试,保存到用户选的目录)。 */
    private fun startDownload(url: String, name: String, kind: String) {
        val item = TransferStore.Item(
            id = java.util.UUID.randomUUID().toString(),
            title = name,
            url = url,
            fileName = name,
            kind = kind,
            treeUri = UiPrefs.downloadTreeUri(this),
        )
        TransferService.enqueue(this, item)
        val dest = UiPrefs.downloadTreeName(this).ifBlank { "应用私有目录" }
        toast("开始下载 $name → $dest")
        selectTab(2)
        refreshTransfers()
    }

    /** v1.8.0 打开已完成的下载文件(content:// 走系统查看器,file:// 走 FileProvider)。 */
    private fun openTransferFile(item: TransferStore.Item) {
        val uri = item.localUri.ifBlank { toast("暂无可用文件"); return }
        val mime = item.mime.ifBlank { "application/octet-stream" }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast("没有可用的打开方式:${e.message}")
        }
    }

    /** 离线缓存完成:直接播放本机文件(图片走系统查看器)。 */
    private fun playOffline(o: OfflineStore.Item) {
        val f = OfflineStore.localFile(this, o.vpath)
        if (f == null) { toast("本地缓存文件不存在,请重新缓存"); return }
        val ext = o.name.substringAfterLast('.', "").lowercase()
        if (ext in FileListAdapter.IMAGE) { openOfflineGallery(o); return }
        val (pl, idx) = playlistFor(o.vpath)
        startPlayer("file://" + f.absolutePath, o.name, o.vpath, null, pl, idx)
    }

    /** 把应用内离线缓存另存到用户选的下载目录(SAF),方便在「文件」里找到。 */
    private fun exportOffline(o: OfflineStore.Item) {
        val src = OfflineStore.localFile(this, o.vpath)
        if (src == null) { toast("本地缓存文件不存在"); return }
        val tree = UiPrefs.downloadTreeUri(this)
        if (tree.isBlank()) {
            toast("请先选择下载文件夹")
            showDownloadDirDialog()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { copyToDownloadTree(tree, src, o.name) }
            val where = UiPrefs.downloadTreeName(this@MainActivity).ifBlank { "下载目录" }
            toast(if (ok) "已另存到「$where」" else "另存失败,请重试")
        }
    }

    private fun copyToDownloadTree(treeUri: String, src: File, name: String): Boolean {
        return try {
            val tree = Uri.parse(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val doc = DocumentsContract.createDocument(contentResolver, parent, mimeOf(name), name)
                ?: return false
            contentResolver.openOutputStream(doc)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in FileListAdapter.VIDEO -> "video/*"
            in FileListAdapter.AUDIO -> "audio/*"
            in FileListAdapter.IMAGE -> "image/*"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /** P1:/api/info 详情弹窗(文件夹给递归统计,媒体给 ffprobe 技术信息),改为分组 Sheet。 */
    private fun showInfoDialog(vp: String, e: Entry) {
        lifecycleScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { client.info(vp) }
            } catch (ex: Exception) {
                toast("读取详情失败:${ex.message}")
                return@launch
            }
            val sheet = Sheet(this@MainActivity, info.name.ifBlank { e.name }, if (info.isDir) "文件夹详情" else "文件详情")
            sheet.section("文件")
            sheet.keyValue("路径", info.path, mono = true)
            sheet.keyValue("类型", if (info.isDir) "文件夹" else info.kind.ifBlank { info.ext.ifBlank { "文件" } })
            if (!info.isDir) sheet.keyValue("大小", FileListAdapter.fmtSize(info.size))
            sheet.keyValue("修改时间", FileListAdapter.fmtDate(info.modTime))
            if (info.isDir) {
                sheet.keyValue("包含", "${info.files} 个文件 / ${info.dirs} 个文件夹")
                sheet.keyValue("总大小", FileListAdapter.fmtSize(info.totalBytes))
            }
            // 仅当存在媒体技术信息时才显示「媒体」分组,避免给普通文件塞空行
            val hasMedia = (info.width > 0 && info.height > 0) || info.duration > 0 || info.fps > 0 ||
                info.bitRate > 0 || info.videoCodec.isNotBlank() || info.audioCodec.isNotBlank()
            if (hasMedia) {
                sheet.section("媒体")
                if (info.width > 0 && info.height > 0) sheet.keyValue("分辨率", "${info.width}×${info.height}")
                if (info.duration > 0) sheet.keyValue("时长", fmtDuration(info.duration.toLong()))
                if (info.fps > 0) sheet.keyValue("帧率", String.format("%.2f", info.fps) + " fps")
                if (info.bitRate > 0) sheet.keyValue("码率", "${info.bitRate / 1000} kbps")
                if (info.videoCodec.isNotBlank()) sheet.keyValue("视频编码", info.videoCodec)
                if (info.audioCodec.isNotBlank()) sheet.keyValue("音频编码", info.audioCodec)
            }
            sheet.tonal("关闭") { sheet.dismiss() }
            sheet.show()
        }
    }

    /** P1:离线缓存开关(缓存到手机 / 移除缓存)。 */
    private fun toggleOffline(vp: String, e: Entry) {
        when (OfflineStore.get(this, vp)?.status) {
            OfflineStore.ST_DONE -> {
                val sheet = Sheet(this, "移除离线缓存", "删除本机缓存的「${e.name}」?服务器上的文件不受影响。")
                sheet.danger("删除") {
                    sheet.dismiss()
                    // 若之前另存到了下载目录,SAF 副本也一并删除
                    OfflineStore.get(this, vp)?.savedUri?.takeIf { it.isNotBlank() }?.let { deleteLocalUri(it) }
                    OfflineStore.remove(this, vp)
                    toast("已移除离线缓存")
                }
                sheet.tonal("取消") { sheet.dismiss() }
                sheet.show()
            }
            OfflineStore.ST_DOWNLOADING, OfflineStore.ST_PAUSED -> {
                toast("已在离线下载中,可到「传输」页管理")
                selectTab(2)
            }
            else -> {
                lifecycleScope.launch {
                    if (OfflineDownloader.start(this@MainActivity, vp, e.name, e.size)) {
                        toast("开始离线缓存:${e.name}")
                        selectTab(2)
                    } else {
                        toast("无法启动离线缓存，请重试")
                    }
                }
            }
        }
    }

    private fun fmtDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun openFile(e: Entry) {
        val vp = e.vpath ?: (curPath.trimEnd('/') + "/" + e.name)   // P2:跨库搜索结果带绝对 vpath
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val transcode = setOf("avi", "rmvb", "rm", "flv", "wmv", "mpg", "mpeg", "vob", "asf", "ogv")
        try {
            when {
                ext in FileListAdapter.VIDEO || ext in FileListAdapter.AUDIO -> lifecycleScope.launch {
                    // 已离线缓存则直接播本地文件;字幕走服务端 /api/subtitle。
                    val local = OfflineStore.localFile(this@MainActivity, vp)
                    val sub = withContext(Dispatchers.IO) { findSubtitle(vp) }
                    val url = local?.let { "file://" + it.absolutePath } ?: client.rawUrl(vp)
                    val (pl, idx) = playlistFor(vp)
                    startPlayer(url, e.name, vp, sub, pl, idx)
                }
                ext in transcode -> {
                    val (pl, idx) = playlistFor(vp)
                    startPlayer(client.streamUrl(vp), e.name, vp, null, pl, idx)
                }
                ext in FileListAdapter.IMAGE ->
                    openImageGallery(vp, allRows.mapNotNull { (it as? ListRow.FileRow)?.e })
                else -> {
                    startDownload(client.rawUrl(vp, true), e.name, TransferStore.KIND_DOWNLOAD)
                }
            }
        } catch (_: Exception) {
            toast("没有可用的打开方式")
        }
    }

    /** 把一组图片交给应用内浏览器(左右滑动切换),currentVp 用于定位起始页。 */
    private fun openImageGallery(currentVp: String, rows: List<Entry>) {
        val imgs = rows.filter {
            !it.isDir && it.name.substringAfterLast('.', "").lowercase() in FileListAdapter.IMAGE
        }
        if (imgs.isEmpty()) { toast("没有可浏览的图片"); return }
        val vpaths = ArrayList<String>()
        val names = ArrayList<String>()
        val urls = ArrayList<String>()
        for (e in imgs) {
            val vp = e.vpath ?: (curPath.trimEnd('/') + "/" + e.name)
            vpaths.add(vp)
            names.add(e.name)
            urls.add(client.rawUrl(vp))
        }
        ImageActivity.start(this, vpaths, names, urls, vpaths.indexOf(currentVp).coerceAtLeast(0))
    }

    /** 浏览已缓存的图片(本地 file://,无网也能看)。 */
    private fun openOfflineGallery(cur: OfflineStore.Item) {
        val items = OfflineStore.all(this).filter {
            it.name.substringAfterLast('.', "").lowercase() in FileListAdapter.IMAGE
        }
        val vpaths = ArrayList<String>()
        val names = ArrayList<String>()
        val urls = ArrayList<String>()
        for (item in items) {
            val f = OfflineStore.localFile(this, item.vpath) ?: continue
            vpaths.add(item.vpath)
            names.add(item.name)
            urls.add("file://" + f.absolutePath)
        }
        if (vpaths.isEmpty()) { toast("没有可浏览的缓存图片"); return }
        ImageActivity.start(this, vpaths, names, urls, vpaths.indexOf(cur.vpath).coerceAtLeast(0))
    }

    /** P2:从当前浏览上下文(目录列表/全局搜索/媒体页)构建可播放列表,返回 (JSON 数组, 当前下标)。 */
    private fun playlistFor(currentVp: String): Pair<String, Int> {
        val rows: List<Entry> = if (currentTab == R.id.tab_media) mediaAll
            else allRows.mapNotNull { (it as? ListRow.FileRow)?.e }
        val playable = rows.filter { it ->
            val x = it.name.substringAfterLast('.', "").lowercase()
            !it.isDir && (x in FileListAdapter.VIDEO || x in FileListAdapter.AUDIO)
        }
        val vps = playable.map { it.vpath ?: (curPath.trimEnd('/') + "/" + it.name) }
        val idx = vps.indexOf(currentVp)
        if (idx < 0 || playable.size < 2) return "[]" to -1
        val arr = StringBuilder("[")
        for (i in playable.indices) {
            if (i > 0) arr.append(',')
            arr.append("{\"name\":\"").append(jsonEscape(playable[i].name))
                .append("\",\"vpath\":\"").append(jsonEscape(vps[i])).append("\"}")
        }
        arr.append(']')
        return arr.toString() to idx
    }

    private fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** P1:用服务端 /api/subtitle 找同目录字幕(优先中文),返回可播放的 VTT 地址。 */
    private fun findSubtitle(vpath: String): String? {
        val tracks = try {
            client.subtitles(vpath)
        } catch (_: Exception) {
            return null
        }
        if (tracks.isEmpty()) return null
        val pick = tracks.firstOrNull { it.lang.startsWith("zh") } ?: tracks.first()
        return client.absoluteUrl(pick.url)
    }

    private fun startPlayer(url: String, title: String, vpath: String = "", subtitle: String? = null,
                            playlist: String = "[]", playIndex: Int = -1) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_VPATH, vpath)
            subtitle?.let { putExtra(PlayerActivity.EXTRA_SUB, it) }
            putExtra(PlayerActivity.EXTRA_PLAYLIST, playlist)
            putExtra(PlayerActivity.EXTRA_PLAY_INDEX, playIndex)
        })
    }

    private fun showTopMenu(anchor: View) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (canWrite()) actions += "新建文件夹" to { mkdirDialog() }
        actions += "批量选择…" to { showBatchSelect() }
        actions += "重新扫描服务器" to { connectAndLoad(silent = true) }
        actions += "连接其他服务器" to { showDiscoveryDialog() }
        actions += "收藏与最近…" to { showFavoritesAndRecents() }
        actions += "搜索历史…" to { showSearchHistory() }
        actions += "按类型筛选…" to { chooseTypeFilter() }
        actions += "按大小筛选…" to { chooseSizeFilter() }
        actions += "按时间筛选…" to { chooseTimeFilter() }
        if (hasActiveFilter()) actions += "清除筛选" to {
            typeFilter = "all"; sizeFilter = "all"; timeFilter = "all"
            filterFiles(); toast("已清除筛选")
        }
        showActionSheet("更多", actions)
    }

    // ---------- 收藏 / 最近 / 搜索历史 / 筛选 ----------

    private fun showFavoritesAndRecents() {
        val favs = FavoritesStore.favorites(this)
        val recents = FavoritesStore.recents(this)
        if (favs.isEmpty() && recents.isEmpty()) { toast("还没有收藏或最近访问"); return }
        val sheet = Sheet(this, "收藏与最近")
        fun open(r: FavoritesStore.Ref) {
            if (r.vpath.isNotBlank()) {
                if (r.isDir) { crumbs.clear(); navigateTo(r.vpath) }
                else openFile(Entry(r.name, false, 0L, 0L, r.vpath))
            }
        }
        if (favs.isNotEmpty()) {
            sheet.section("收藏")
            favs.forEach { r -> sheet.row(R.drawable.ic_file, r.name, onClick = { open(r) }) }
        }
        if (recents.isNotEmpty()) {
            sheet.section("最近访问")
            recents.forEach { r -> sheet.row(R.drawable.ic_history, r.name, onClick = { open(r) }) }
        }
        if (recents.isNotEmpty()) {
            sheet.danger("清空最近") {
                FavoritesStore.clearRecents(this); toast("已清空最近访问")
            }
        }
        sheet.tonal("关闭") { sheet.dismiss() }
        sheet.show()
    }

    private fun showSearchHistory() {
        val hist = FavoritesStore.searchHistory(this)
        if (hist.isEmpty()) { toast("暂无搜索历史"); return }
        val sheet = Sheet(this, "搜索历史")
        hist.forEach { q ->
            sheet.row(R.drawable.ic_history, q, onClick = {
                searchInput.setText(q)
                searchInput.setSelection(searchInput.text?.length ?: 0)
            })
        }
        sheet.danger("清空") {
            FavoritesStore.clearSearch(this); toast("已清空搜索历史")
        }
        sheet.tonal("关闭") { sheet.dismiss() }
        sheet.show()
    }

    private fun chooseTypeFilter() {
        val keys = arrayOf("all", "dir", "video", "image", "audio", "archive", "other")
        val names = arrayOf("全部", "仅文件夹", "视频", "图片", "音乐", "压缩包", "其他")
        val sheet = Sheet(this, "按类型筛选")
        keys.forEachIndexed { i, k ->
            sheet.row(0, names[i], checked = typeFilter == k, onClick = {
                typeFilter = k; filterFiles()
            })
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun chooseSizeFilter() {
        val keys = arrayOf("all", "lt10m", "b10_100m", "b100m_1g", "gt1g")
        val names = arrayOf("全部", "小于 10MB", "10–100MB", "100MB–1GB", "大于 1GB")
        val sheet = Sheet(this, "按大小筛选")
        keys.forEachIndexed { i, k ->
            sheet.row(0, names[i], checked = sizeFilter == k, onClick = {
                sizeFilter = k; filterFiles()
            })
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun chooseTimeFilter() {
        val keys = arrayOf("all", "today", "7d", "30d")
        val names = arrayOf("全部", "今天", "近 7 天", "近 30 天")
        val sheet = Sheet(this, "按时间筛选")
        keys.forEachIndexed { i, k ->
            sheet.row(0, names[i], checked = timeFilter == k, onClick = {
                timeFilter = k; filterFiles()
            })
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    // ---------- v1.8.0 多选 + 批量操作 ----------

    /** 当前目录可选条目(文件与文件夹)。 */
    private fun currentEntries(): List<Entry> =
        allRows.mapNotNull { (it as? ListRow.FileRow)?.e }

    private fun vpathOf(e: Entry): String = e.vpath ?: (curPath.trimEnd('/') + "/" + e.name)

    /** 多选对话框:勾选若干条目后进入批量操作。 */
    private fun showBatchSelect(preselect: String? = null) {
        if (curPath.isEmpty()) { toast("先进入一个存储库"); return }
        val rows = currentEntries()
        if (rows.isEmpty()) { toast("当前目录没有可选条目"); return }
        val labels = rows.map { e -> (if (e.isDir) "[文件夹] " else "") + e.name }.toTypedArray()
        val checked = BooleanArray(rows.size)
        if (preselect != null) {
            val i = rows.indexOfFirst { vpathOf(it) == preselect }
            if (i >= 0) checked[i] = true
        }
        AlertDialog.Builder(this)
            .setTitle("选择条目(${rows.size})")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("下一步") { _, _ ->
                val picked = rows.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) toast("未选择任何条目") else showBatchActions(picked)
            }
            .setNeutralButton("全选") { _, _ ->
                for (i in checked.indices) checked[i] = true
                showBatchActions(rows)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchActions(picked: List<Entry>) {
        val vps = picked.map { vpathOf(it) }
        val sheet = Sheet(this, "已选 ${picked.size} 项")
        sheet.row(R.drawable.ic_download, "打包下载(ZIP)") {
            val base = if (picked.size == 1) picked[0].name.substringBeforeLast('.')
            else (curPath.trimEnd('/').substringAfterLast('/').ifBlank { "linNas" }) + "${picked.size}项"
            val zipName = "$base.zip"
            startDownload(client.zipUrl(vps, zipName), zipName, TransferStore.KIND_ZIP)
        }
        if (canWrite()) {
            sheet.row(R.drawable.ic_trash_bin, "删除", destructive = true) { confirmBatchDelete(picked) }
            sheet.row(R.drawable.ic_folder, "移动到…") { showDirPicker { dest -> batchTransfer(vps, dest, move = true) } }
            sheet.row(R.drawable.ic_folder, "复制到…") { showDirPicker { dest -> batchTransfer(vps, dest, move = false) } }
        }
        sheet.row(R.drawable.ic_download, "加入离线缓存") { batchOffline(picked) }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun confirmBatchDelete(picked: List<Entry>) {
        if (!canWrite()) { toast("只读账号无法删除"); return }
        val sheet = Sheet(this, "批量删除 ${picked.size} 项?", "可到回收站找回(若服务端启用了回收站)")
        sheet.danger("删除") {
            sheet.dismiss()
            lifecycleScope.launch {
                var ok = 0
                var fail = 0
                for (e in picked) {
                    try {
                        withContext(Dispatchers.IO) { client.delete(vpathOf(e)) }
                        ok++
                    } catch (_: Exception) {
                        fail++
                    }
                }
                toast("已删除 $ok 项${if (fail > 0) ",失败 $fail 项" else ""}")
                loadDir()
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun batchTransfer(vps: List<String>, dest: String, move: Boolean) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (move) client.move(vps, dest) else client.copy(vps, dest)
                }
                toast("已${if (move) "移动" else "复制"} ${vps.size} 项")
                loadDir()
            } catch (e: Exception) {
                toast("批量操作失败:${e.message}")
            }
        }
    }

    private fun batchOffline(picked: List<Entry>) {
        val files = picked.filter { !it.isDir }
        if (files.isEmpty()) { toast("只有文件可以离线缓存"); return }
        lifecycleScope.launch {
            var started = 0
            for (e in files) {
                if (OfflineDownloader.start(this@MainActivity, vpathOf(e), e.name, e.size)) started++
            }
            toast("已加入离线缓存 $started 项")
            selectTab(2)
            refreshTransfers()
        }
    }

    private fun mkdirDialog() {
        if (curPath.isEmpty()) { toast("先进入一个存储库"); return }
        val sheet = Sheet(this, "新建文件夹", curPath)
        sheet.input("文件夹名称")
        sheet.primary("创建") { doMkdir(sheet) }
        sheet.onEnter { doMkdir(sheet) }
        sheet.show()
    }

    private fun doMkdir(sheet: Sheet) {
        val name = sheet.value()
        if (name.isBlank()) { sheet.inputView(0)?.error = "名称不能为空"; return }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.mkdir("$curPath/$name") }
                sheet.dismiss()
                loadDir()
            } catch (e: Exception) { toast("失败: ${e.message}") }
        }
    }

    private fun renameDialog(vp: String, oldName: String) {
        val sheet = Sheet(this, "重命名", "输入新的名称")
        sheet.input("新名称", oldName)
        sheet.primary("确定") { doRename(sheet, vp) }
        sheet.onEnter { doRename(sheet, vp) }
        sheet.show()
    }

    private fun doRename(sheet: Sheet, vp: String) {
        val name = sheet.value()
        if (name.isBlank()) { sheet.inputView(0)?.error = "名称不能为空"; return }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.rename(vp, name) }
                sheet.dismiss()
                loadDir()
            } catch (e: Exception) { toast("失败: ${e.message}") }
        }
    }

    private fun deleteConfirm(vp: String, e: Entry) {
        val sheet = Sheet(this, "删除 ${e.name}?", "将移入回收站,可恢复")
        sheet.danger("删除") {
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.delete(vp) }
                    loadDir(); toast("已删除")
                } catch (ex: Exception) { toast("失败: ${ex.message}") }
            }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    // ---------- 媒体 ----------

    private fun loadMedia(type: String) {
        mediaEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val all = mutableListOf<Entry>()
            var off = 0
            try {
                while (all.size < 1000) {
                    val page = withContext(Dispatchers.IO) { client.media(type, off, 100) }
                    if (page.isEmpty()) break
                    all += page; off += page.size
                    if (page.size < 100) break
                }
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) {
                mediaAll = all   // P2:播放列表来源
                mediaFolderEntries = all
                if (mediaMode == "flat") {
                    // 全部模式:平铺展示,所有媒体一屏可见
                    mediaAdapter.submit(all)
                } else {
                    renderMediaFolder()
                }
                mediaEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * v1.8.0 文件夹模式:像文件页一样按目录浏览媒体。
     * 服务端 /api/media 只返回文件,目录树由 vpath 推导:
     * 当前目录的下一层目录 = 父路径命中当前前缀且再无更深层级的那些目录。
     */
    private fun renderMediaFolder() {
        val cur = mediaFolderPath
        mediaPath.text = if (cur.isEmpty()) "全部媒体(根)" else cur
        val prefix = if (cur.isEmpty()) "/" else "$cur/"
        val childDirs = LinkedHashSet<String>()
        val childFiles = mutableListOf<Entry>()
        for (e in mediaFolderEntries) {
            val vp = e.vpath ?: continue
            if (!vp.startsWith(prefix)) continue
            val rest = vp.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                childFiles += e
            } else {
                childDirs += prefix + rest.substring(0, slash)
            }
        }
        val dirs = childDirs.map { p ->
            Entry(p.substringAfterLast('/'), true, 0L, 0L, p)
        }
        mediaFolderAdapter.submit(dirs + childFiles)
        mediaEmpty.visibility = if (dirs.isEmpty() && childFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 文件夹模式点目录:进入该目录。 */
    private fun onMediaOpenFolder(e: Entry) {
        val vp = e.vpath ?: return
        mediaFolderStack.add(mediaFolderPath)
        mediaFolderPath = vp
        renderMediaFolder()
    }

    /** 文件夹模式返回上一级。 */
    private fun mediaFolderBack(): Boolean {
        if (mediaMode != "folder") return false
        val parent = mediaFolderStack.removeLastOrNull() ?: ""
        mediaFolderPath = parent
        renderMediaFolder()
        return true
    }

    private fun onMediaOpen(e: Entry) {
        val vp = e.vpath ?: return
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val transcode = setOf("avi", "rmvb", "rm", "flv", "wmv", "mpg", "mpeg", "vob", "asf", "ogv")
        val (pl, idx) = playlistFor(vp)
        when {
            ext in transcode -> startPlayer(client.streamUrl(vp), e.name, vp, null, pl, idx)
            ext in FileListAdapter.IMAGE -> {
                // 用应用内图片浏览器打开:从媒体页列表筛出所有图片,支持左右滑动
                val imgs = mediaAll.filter { en ->
                    !en.isDir && en.name.substringAfterLast('.', "").lowercase() in FileListAdapter.IMAGE
                }.let { if (it.isEmpty()) listOf(e) else it }
                val vpaths = ArrayList<String>()
                val names = ArrayList<String>()
                val urls = ArrayList<String>()
                var start = 0
                imgs.forEachIndexed { i, en ->
                    val ivp = en.vpath ?: ""
                    vpaths.add(ivp)
                    names.add(en.name)
                    urls.add(client.rawUrl(ivp))
                    if (ivp == vp) start = i
                }
                ImageActivity.start(
                    ctx = this,
                    vpaths = vpaths,
                    names = names,
                    urls = urls,
                    startIndex = start,
                )
            }
            else -> lifecycleScope.launch {
                val local = OfflineStore.localFile(this@MainActivity, vp)
                val sub = withContext(Dispatchers.IO) { findSubtitle(vp) }
                val url = local?.let { "file://" + it.absolutePath } ?: client.rawUrl(vp)
                startPlayer(url, e.name, vp, sub, pl, idx)
            }
        }
    }

    // ---------- 备份 ----------

    private fun setupBackup() {
        swBackup.isChecked = BackupConfig.enabled(this)
        swWifi.isChecked = BackupConfig.wifiOnly(this)
        etBackupDir.setText(BackupConfig.targetDir(this))
        updateBackupStatus()
        // 备份目标目录:可直接在媒体库里选文件夹(也仍可手输)
        findViewById<View>(R.id.btnBackupDir).setOnClickListener {
            showDirPicker { dir -> etBackupDir.setText(dir) }
        }
        setDownloadDir.text = UiPrefs.downloadTreeName(this).ifBlank { "下载目录" }
        findViewById<View>(R.id.btnDownloadDir).setOnClickListener { showDownloadDirDialog() }
        findViewById<View>(R.id.btnSaveBackup).setOnClickListener { saveBackup(swBackup.isChecked) }
        findViewById<View>(R.id.btnBackupNow).setOnClickListener {
            swBackup.isChecked = true
            saveBackup(true)
            ensureMediaPermission()
            PhotoBackupScheduler.runNow(this)
            toast("已加入备份任务,后台执行")
        }
        if (BackupConfig.enabled(this)) PhotoBackupScheduler.schedule(this)
    }

    private fun saveBackup(enable: Boolean) {
        val dir = etBackupDir.text?.toString()?.trim().orEmpty().ifBlank { "/lib1/手机备份" }
        BackupConfig.setTargetDir(this, dir)
        BackupConfig.setWifiOnly(this, swWifi.isChecked)
        BackupConfig.setEnabled(this, enable)
        if (enable) { ensureMediaPermission(); PhotoBackupScheduler.schedule(this); toast("已开启自动备份 → $dir") }
        else { PhotoBackupScheduler.cancel(this); toast("已关闭自动备份") }
        updateBackupStatus()
    }

    private fun updateBackupStatus() {
        val recorded = BackupConfig.uploadedKeyCount(this)
        backupStatus.text = "上次:${BackupConfig.lastResult(this)}  ·  累计 ${BackupConfig.uploadedCount(this)} 张" +
            if (recorded > 0) "  ·  已记录 $recorded 个" else ""
        backupStatus.setOnClickListener { showBackupRecords() }
    }

    /** v1.8.0:展示已备份文件清单,回答「备份了哪些 / 会不会重复」。 */
    private fun showBackupRecords() {
        val names = BackupConfig.uploadedNames(this)
        if (names.isEmpty()) {
            toast("还没有已备份记录")
            return
        }
        val shown = names.takeLast(50).reversed()
        val sheet = Sheet(this, "已备份文件", "共 ${names.size} 个")
        sheet.text("已记录 ${names.size} 个已备份文件(按名称去重)。\n重复的同名同大小文件不会再次上传;更换备份目录会重置记录。")
        if (names.size > shown.size) sheet.text("仅显示最近 ${shown.size} 个:")
        shown.forEach { n -> sheet.text("· $n") }
        sheet.danger("清空记录") {
            sheet.dismiss()
            BackupConfig.clearUploaded(this)
            updateBackupStatus()
            toast("已清空备份记录")
        }
        sheet.tonal("关闭") { sheet.dismiss() }
        sheet.show()
    }

    /** v1.8.0 下载保存位置:用系统文件夹选择器(SAF)让用户直接选手机里的目录。 */
    private fun showDownloadDirDialog() {
        val current = UiPrefs.downloadTreeName(this).ifBlank { "未选择(保存到应用私有目录)" }
        val sheet = Sheet(this, "下载保存位置", "当前:$current")
        sheet.text("选择一个手机里可见的文件夹,下载与打包的文件会保存到那里,完成后可在「传输」页直接打开或分享。")
        sheet.primary("选择文件夹") {
            sheet.dismiss()
            downloadTreeLauncher.launch(null)
        }
        sheet.tonal("清除") {
            UiPrefs.setDownloadTree(this, "")
            UiPrefs.setDownloadTreeName(this, "")
            setDownloadDir.text = "下载目录"
            sheet.dismiss()
            toast("已改回应用私有目录(仅本应用可见)")
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun ensureMediaPermission() {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        ) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        permLauncher.launch(perms)
    }

    // ---------- 传输 ----------

    private fun refreshTransfers() {
        renderUploads()
        renderOffline()
        renderDownloads()
    }

    // ---------- 上传区 ----------

    private fun renderUploads() {
        transferList.removeAllViews()
        uploadViews.clear()
        val tasks = UploadService.progress.values.toList()
        transferEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        for (t in tasks) {
            val v = layoutInflater.inflate(R.layout.item_transfer, transferList, false)
            uploadViews[t.name] = v
            val pct = if (t.size > 0) ((t.sent * 100) / t.size).toInt() else 0
            val bps = uploadSpeeds.getOrPut(t.name) { SpeedMeter() }
                .update(System.currentTimeMillis(), t.sent)
            v.findViewById<TextView>(R.id.tname).text = t.name
            v.findViewById<TextView>(R.id.tstate).text = when {
                t.state == UploadService.ST_DONE -> "已完成"
                t.state.startsWith("失败") -> t.state
                else -> "${t.state} $pct%" + if (bps > 0) " · ${SpeedMeter.format(bps)}" else ""
            }
            val bar = v.findViewById<LinearProgressIndicator>(R.id.tprog)
            bar.max = 100
            // 动画只在进度真的变化时播放,避免每 tick 重播动画造成"闪烁"
            val target = if (t.state == UploadService.ST_DONE) 100 else pct
            if (bar.progress != target) bar.setProgressCompat(target, true)
            bar.visibility = if (t.state == UploadService.ST_DONE) View.GONE else View.VISIBLE
            val actionable = t.state != UploadService.ST_DONE
            val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tact)
            btn.visibility = if (actionable) View.VISIBLE else View.GONE
            btn.text = when {
                t.state == UploadService.ST_RUNNING -> "暂停"
                t.state == UploadService.ST_QUEUED -> "暂停"
                t.state == UploadService.ST_PAUSED -> "继续"
                t.state.startsWith("失败") -> "重试"
                else -> "取消"
            }
            btn.setOnClickListener {
                when {
                    t.state == UploadService.ST_RUNNING || t.state == UploadService.ST_QUEUED ->
                        UploadService.pause(this, t.name)
                    t.state == UploadService.ST_PAUSED || t.state.startsWith("失败") ->
                        UploadService.resume(this, t.name)
                    else -> UploadService.cancel(this, t.name)
                }
                refreshTransfers()
            }
            val clear = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tclear)
            clear.visibility = if (actionable) View.GONE else View.VISIBLE
            clear.setOnClickListener {
                UploadService.progress.remove(t.name)
                uploadSpeeds.remove(t.name)
                refreshTransfers()
            }
            v.findViewById<View>(R.id.tmore).visibility = View.GONE
            transferList.addView(v)
        }
    }

    // ---------- 离线缓存区 ----------

    private fun renderOffline() {
        val offlineList = findViewById<LinearLayout>(R.id.offlineList)
        val offlineEmpty = findViewById<View>(R.id.offlineEmpty)
        offlineList.removeAllViews()
        offlineViews.clear()
        val offs = OfflineStore.all(this)
        offlineEmpty.visibility = if (offs.isEmpty()) View.VISIBLE else View.GONE
        for (o in offs) {
            val v = layoutInflater.inflate(R.layout.item_transfer, offlineList, false)
            offlineViews[o.vpath] = v
            val pct = o.percent
            v.findViewById<TextView>(R.id.tname).text = o.name
            v.findViewById<TextView>(R.id.tstate).text = when (o.status) {
                OfflineStore.ST_DOWNLOADING ->
                    "缓存中 $pct%" + if (o.speedBps > 0) " · ${SpeedMeter.format(o.speedBps)}" else ""
                OfflineStore.ST_PAUSED -> "已暂停 $pct%"
                OfflineStore.ST_DONE -> if (o.savedUri.isNotBlank())
                    "已缓存 · 已保存到「${UiPrefs.downloadTreeName(this).ifBlank { "下载目录" }}」"
                else "已缓存 · 仅本应用可播"
                else -> "失败: ${o.msg}"
            }
            val bar = v.findViewById<LinearProgressIndicator>(R.id.tprog)
            bar.max = 100
            // 完成后满格进度条会让人以为还在下载 —— 直接隐藏
            bar.visibility = if (o.status == OfflineStore.ST_DONE) View.GONE else View.VISIBLE
            if (bar.visibility == View.VISIBLE) {
                if (bar.progress != pct) bar.setProgressCompat(pct, true)
            }
            val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tact)
            btn.visibility = View.VISIBLE
            btn.text = when (o.status) {
                OfflineStore.ST_DONE -> "打开"
                OfflineStore.ST_PAUSED -> "继续"
                OfflineStore.ST_FAILED -> "重试"
                else -> "暂停"
            }
            btn.setOnClickListener {
                when (o.status) {
                    OfflineStore.ST_DONE -> playOffline(o)
                    OfflineStore.ST_PAUSED, OfflineStore.ST_FAILED -> lifecycleScope.launch {
                        if (!OfflineDownloader.resume(this@MainActivity, o.vpath, o.name, o.size)) {
                            toast("无法恢复离线缓存，请重试")
                        }
                        refreshTransfers()
                    }
                    else -> {
                        OfflineDownloader.pause(this, o.vpath)
                        refreshTransfers()
                    }
                }
            }
            val more = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tmore)
            more.visibility = if (o.status == OfflineStore.ST_DONE && o.savedUri.isBlank()) View.VISIBLE else View.GONE
            more.text = "另存"
            more.setOnClickListener { exportOffline(o) }
            val clear = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tclear)
            clear.visibility = View.VISIBLE
            clear.text = if (o.status == OfflineStore.ST_DONE) "移除" else "取消"
            clear.setOnClickListener { confirmRemoveOffline(o) }
            // 长按卡片删除离线缓存(与「移除」一致)
            v.setOnLongClickListener { confirmRemoveOffline(o); true }
            offlineList.addView(v)
        }
    }

    // ---------- 下载区 ----------

    private fun renderDownloads() {
        downloadList.removeAllViews()
        downloadViews.clear()
        val downloads = TransferStore.all(this)
        downloadEmpty.visibility = if (downloads.isEmpty()) View.VISIBLE else View.GONE
        for (d in downloads) {
            val v = layoutInflater.inflate(R.layout.item_transfer, downloadList, false)
            downloadViews[d.id] = v
            v.findViewById<TextView>(R.id.tname).text = d.title
            val stateText = when (d.state) {
                TransferStore.ST_DONE -> "已保存 · " + (
                    if (d.localUri.startsWith("content:"))
                        UiPrefs.downloadTreeName(this).ifBlank { "所选文件夹" }
                    else "应用私有目录"
                ) + " · " + d.fileName
                TransferStore.ST_FAILED -> "失败:${d.error}"
                TransferStore.ST_CANCELLED -> "已取消"
                TransferStore.ST_PAUSED -> "已暂停 ${d.percent}%"
                TransferStore.ST_QUEUED -> "排队中"
                else -> "${d.percent}%" + if (d.speedBps > 0) " · ${SpeedMeter.format(d.speedBps)}" else ""
            }
            v.findViewById<TextView>(R.id.tstate).text = stateText
            val bar = v.findViewById<LinearProgressIndicator>(R.id.tprog)
            bar.max = 100
            val pct = if (d.total > 0 && d.state != TransferStore.ST_DONE) d.percent else 100
            bar.visibility = if (d.state == TransferStore.ST_DONE) View.GONE else View.VISIBLE
            if (bar.visibility == View.VISIBLE && bar.progress != pct) bar.setProgressCompat(pct, true)
            val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tact)
            when (d.state) {
                TransferStore.ST_DONE -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "打开"
                    btn.setOnClickListener { openTransferFile(d) }
                }
                TransferStore.ST_PAUSED, TransferStore.ST_FAILED -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "继续"
                    btn.setOnClickListener { TransferService.resume(this, d.id); refreshTransfers() }
                }
                TransferStore.ST_CANCELLED -> btn.visibility = View.GONE
                else -> {
                    btn.visibility = View.VISIBLE
                    btn.text = "暂停"
                    btn.setOnClickListener { TransferService.pause(this, d.id); refreshTransfers() }
                }
            }
            val clear = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tclear)
            clear.visibility = View.VISIBLE
            clear.text = if (d.state == TransferStore.ST_DONE) "删除" else "取消"
            clear.setOnClickListener {
                if (d.state == TransferStore.ST_DONE) {
                    confirmDeleteDownload(d)
                } else {
                    // 未完成的下载:只取消任务并移除记录(本地仅有 .part 临时文件,不涉及用户文件)
                    TransferService.cancel(this, d.id)
                    TransferStore.remove(this, d.id)
                    refreshTransfers()
                }
            }
            // 长按整张卡片也能删除(与「删除」按钮一致)
            v.setOnLongClickListener { confirmDeleteDownload(d); true }
            v.findViewById<View>(R.id.tmore).visibility = View.GONE
            downloadList.addView(v)
        }
    }

    /**
     * 删除传输记录对应的本地文件。
     *  - SAF 下载(content:// 由系统文档提供)直接用 DocumentsContract.deleteDocument;
     *  - 本应用 FileProvider 的 content:// 不支持 deleteDocument,映射回私有目录里的真实文件再删;
     *  - file:// 或纯路径用 File(path).delete()。
     * 返回是否真的删掉了文件(记录本身由调用方移除)。
     */
    private fun deleteLocalUri(localUri: String): Boolean {
        if (localUri.isBlank()) return false
        val uri = Uri.parse(localUri)
        if (uri.scheme == "content") {
            val byDoc = runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
            if (byDoc) return true
            return runCatching {
                val f = fileProviderToFile(uri)
                f != null && f.isFile && f.delete()
            }.getOrDefault(false)
        }
        val path = if (uri.scheme == "file") uri.path else localUri
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).let { it.isFile && it.delete() } }.getOrDefault(false)
    }

    /** 把本应用 FileProvider 的 content:// 映射回私有目录文件(根名见 res/xml/file_paths.xml)。 */
    private fun fileProviderToFile(uri: Uri): File? {
        if (uri.authority != "$packageName.fileprovider") return null
        val segs = uri.pathSegments
        if (segs.size < 2) return null
        val rel = segs.drop(1).joinToString("/")
        val base = when (segs[0]) {
            "downloads" -> File(getExternalFilesDir(null) ?: filesDir, "Download")
            "offline" -> File(getExternalFilesDir(null) ?: filesDir, "offline")
            "internal" -> filesDir
            "cache" -> cacheDir
            else -> return null
        }
        return File(base, rel)
    }

    /** 删除一条下载(记录 + 本地文件),二次确认。 */
    private fun confirmDeleteDownload(d: TransferStore.Item) {
        val sheet = Sheet(this, "删除「${d.title}」?", "下载记录与本地文件将一并删除,无法恢复")
        sheet.danger("删除") {
            sheet.dismiss()
            if (d.state != TransferStore.ST_DONE) TransferService.cancel(this, d.id)
            deleteLocalUri(d.localUri)
            // 私有目录下载中断留下的 .part 残留一起清掉
            if (d.treeUri.isBlank()) {
                val part = TransferService.partFile(this, d.fileName.ifBlank { d.title })
                if (part.exists()) part.delete()
            }
            TransferStore.remove(this, d.id)
            refreshTransfers()
            toast("已删除")
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    /** 删除一条离线缓存(记录 + offline 目录文件 + 可选 SAF 副本),二次确认。 */
    private fun confirmRemoveOffline(o: OfflineStore.Item) {
        val done = o.status == OfflineStore.ST_DONE
        val sheet = Sheet(
            this,
            if (done) "移除离线缓存" else "取消离线缓存",
            "「${o.name}」的本机缓存文件将被删除,服务器文件不受影响",
        )
        sheet.danger(if (done) "移除" else "取消下载") {
            sheet.dismiss()
            if (!done) OfflineDownloader.cancel(this, o.vpath)  // 内部已取消任务并移除记录
            if (o.savedUri.isNotBlank()) deleteLocalUri(o.savedUri)
            OfflineStore.remove(this, o.vpath)                   // 删 offline 目录文件 + .part + 记录
            refreshTransfers()
            toast("已移除缓存")
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    private fun enqueueUpload(uri: Uri) {
        val name = queryName(uri) ?: "file_${System.currentTimeMillis()}"
        val size = querySize(uri)
        UploadService.startUpload(this, uri, name, size, curPath)
        toast("已加入上传队列: $name")
        refreshTransfers()
    }

    private fun queryName(uri: Uri): String? = contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }

    private fun querySize(uri: Uri): Long = contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.SIZE)
        if (i >= 0 && c.moveToFirst()) c.getLong(i) else 0L
    } ?: 0L

    // ---------- 设置 ----------

    private fun setupSettings() {
        findViewById<View>(R.id.btnScan).setOnClickListener { showDiscoveryDialog() }
        findViewById<View>(R.id.btnManual).setOnClickListener { showManualDialog() }
        findViewById<View>(R.id.btnChangeToken).setOnClickListener { showTokenDialog() }
        findViewById<View>(R.id.btnSysInfo).setOnClickListener { showSysInfo() }
        findViewById<View>(R.id.btnDevices).setOnClickListener { showDevices() }
        findViewById<View>(R.id.btnVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
        findViewById<View>(R.id.btnTrash).setOnClickListener {
            startActivity(Intent(this, TrashActivity::class.java))
        }
        findViewById<View>(R.id.btnShares).setOnClickListener {
            startActivity(Intent(this, SharesActivity::class.java))
        }
        findViewById<View>(R.id.btnAddLib).setOnClickListener { showAddLibrary() }
        findViewById<View>(R.id.btnLogin).setOnClickListener { launchLogin() }
        findViewById<View>(R.id.btnLogout).setOnClickListener { doLogout() }
        // P2:操作日志页(仅管理员可见)
        findViewById<View>(R.id.btnAudit).setOnClickListener {
            startActivity(Intent(this, AuditActivity::class.java))
        }
        // v1.7.0:活跃会话 / 用户管理 / 网络唤醒 / 维护与索引 / 存储库管理
        findViewById<View>(R.id.btnSessions).setOnClickListener {
            startActivity(Intent(this, SessionsActivity::class.java))
        }
        findViewById<View>(R.id.btnUsers).setOnClickListener {
            startActivity(Intent(this, UserAdminActivity::class.java))
        }
        findViewById<View>(R.id.btnWol).setOnClickListener {
            startActivity(Intent(this, WolActivity::class.java))
        }
        findViewById<View>(R.id.btnMaintenance).setOnClickListener {
            startActivity(Intent(this, MaintenanceActivity::class.java))
        }
        findViewById<View>(R.id.btnLibAdmin).setOnClickListener {
            startActivity(Intent(this, LibraryAdminActivity::class.java))
        }
        // v1.8.0 版本检查 / 更新
        findViewById<View>(R.id.btnVersionCheck).setOnClickListener {
            startActivity(Intent(this, VersionCheckActivity::class.java))
        }
        // v1.8.5 生物识别总开关:只切开关,不校验硬件——没指纹的设备由 authenticate 的降级路径兜底。
        val swBio = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swSettingsBio)
        swBio.isChecked = BiometricGate.isEnabled(this)
        swBio.setOnCheckedChangeListener { _, checked ->
            BiometricGate.setEnabled(this, checked)
            toast(if (checked) "已开启指纹登录与保险箱解锁" else "已关闭指纹登录与保险箱解锁")
        }
    }

    /** 服务端 /api/audit 仅管理员可用:口令模式视为管理员;多用户模式需 sessionRole==admin。 */
    private fun canAdmin(): Boolean = !ServerConfig.multiUser || ServerConfig.sessionRole == "admin"

    private fun doLogout() {
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { client.logout() } } catch (_: Exception) { }
            ServerConfig.clearSession()
            bindSettings()
            toast("已退出登录")
        }
    }

    /** 手机端添加存储库:单个 Sheet 内逐级浏览电脑目录 + 明确的下一步。 */
    private fun showAddLibrary() {
        val sheet = Sheet(this, "添加存储库", "选一个电脑上的目录,添加后手机上即可像云盘一样浏览")
        var cur = ""             // 当前浏览的电脑路径("" = 磁盘列表)
        var seq = 0              // 请求序号:丢弃过期/乱序回调,防连点闪烁
        var nameEdited = false   // 用户是否手动改过名称
        var suppressWatch = false // 程序自动填名时抑制 TextWatcher 误标记

        sheet.section("选择目录")
        val area = sheet.customArea()
        val crumb = TextView(this).apply {
            setTextColor(ThemeColors.textDim(this@MainActivity)); textSize = 12f
            setPadding(dp(4), 0, dp(4), dp(8))
            text = "位置:电脑(选择磁盘)"
        }
        area.addView(crumb)
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        area.addView(rowsBox)

        sheet.section("存储库名称")
        sheet.input("显示名称(留空 = 用目录名)", "")
        val nameEt = sheet.inputView(0)
        nameEt?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!suppressWatch) nameEdited = true }
        })

        // 父级:盘符根(C:\)回到磁盘列表;其余回到上一级目录
        fun parentOf(p: String): String {
            if (p.isBlank()) return ""
            val t = p.trimEnd('\\', '/')
            if (t.length <= 2) return ""                    // "C:" -> 磁盘列表
            val i = t.lastIndexOf('\\')
            if (i < 0) return ""
            val par = t.substring(0, i)
            return if (par.length == 2 && par[1] == ':') par + "\\" else par  // "C:" -> "C:\"
        }

        // 未手动改名时,自动填成当前目录名(盘符根用「C盘」)
        fun nameAuto() {
            if (nameEdited) return
            var nm = cur.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
            if (nm.isBlank()) return
            if (nm.length == 2 && nm[1] == ':') nm = nm.substring(0, 1) + "盘"
            suppressWatch = true
            nameEt?.setText(nm)
            nameEt?.setSelection(nm.length)
            suppressWatch = false
        }

        fun addCurrent() {
            if (cur.isBlank()) { toast("先选择一个目录"); return }
            val nm = sheet.value(0).ifBlank {
                cur.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { cur }
            }.trim()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.addLibrary(nm, cur) }
                    sheet.dismiss()
                    toast("已添加 $nm")
                    connectAndLoad(silent = true)
                } catch (e: Exception) { toast("添加失败: ${e.message}") }
            }
        }

        // 只在这一层数据变化时整体重建 rowsBox,请求期间先放「加载中…」占位,避免逐行异步 addView 闪烁
        fun reload() {
            val mySeq = ++seq
            val path = cur
            crumb.text = if (path.isEmpty()) "位置:电脑(选择磁盘)" else "位置:$path"
            rowsBox.removeAllViews()
            if (path.isNotEmpty()) {
                rowsBox.addView(ScreenUi.row(this@MainActivity, R.drawable.ic_folder, ".. 返回上一级", onClick = {
                    cur = parentOf(path); nameAuto(); reload()
                }))
            }
            val loading = TextView(this).apply {
                text = "加载中…"; setTextColor(ThemeColors.textDim(this@MainActivity)); textSize = 12f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            rowsBox.addView(loading)
            lifecycleScope.launch {
                val o = try { withContext(Dispatchers.IO) { client.fsDirs(path) } } catch (e: Exception) {
                    if (mySeq == seq && sheet.isShowing()) {
                        rowsBox.removeView(loading)
                        rowsBox.addView(TextView(this@MainActivity).apply {
                            text = if (path.isEmpty()) "读取失败: ${e.message}" else "路径不存在或不可读: ${e.message}"
                            setTextColor(ThemeColors.textDim(this@MainActivity)); textSize = 12f
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                        })
                        if (path.isNotEmpty()) toast("路径不存在或不可读")
                    }
                    return@launch
                }
                if (mySeq != seq || !sheet.isShowing()) return@launch
                rowsBox.removeView(loading)
                if (path.isEmpty()) {
                    val drives = o.optJSONArray("drives")
                    if (drives != null) for (i in 0 until drives.length()) {
                        val el = drives.opt(i)
                        if (el is org.json.JSONObject) {
                            val letter = el.optString("letter").ifBlank { el.optString("name") }
                            val free = el.optLong("freeMB", 0L)
                            rowsBox.addView(ScreenUi.row(this@MainActivity, R.drawable.ic_server,
                                "磁盘 " + letter.trimEnd('\\') + if (free > 0) " · 剩余 " + fmtGB(free) else "",
                                onClick = { cur = letter; nameAuto(); reload() }))
                        } else {
                            val s = drives.optString(i)
                            rowsBox.addView(ScreenUi.row(this@MainActivity, R.drawable.ic_server, "磁盘 $s",
                                onClick = { cur = s; nameAuto(); reload() }))
                        }
                    }
                } else {
                    val dirs = o.optJSONArray("dirs")
                    if (dirs != null) for (i in 0 until dirs.length()) {
                        val d = dirs.optJSONObject(i) ?: continue
                        rowsBox.addView(ScreenUi.row(this@MainActivity, R.drawable.ic_folder,
                            d.optString("name") + "  /",
                            onClick = { cur = d.optString("path"); nameAuto(); reload() }))
                    }
                    rowsBox.addView(ScreenUi.row(this@MainActivity, R.drawable.ic_sheet_check,
                        "✓ 就选这个目录", onClick = { addCurrent() }))
                }
            }
        }

        // 兜底:手动粘贴完整路径,进入后照常逐级浏览/校验(统一走 Sheet,与其余弹层一致)
        sheet.tonal("手动输入路径") {
            val input = Sheet(this, "手动输入路径", "粘贴或输入电脑上的完整路径")
            input.input("完整路径,如 D:\\电影", cur, InputType.TYPE_CLASS_TEXT)
            fun submit() {
                val p = input.value(0).trim()
                if (p.isBlank()) {
                    input.inputView(0)?.error = "请输入完整路径"
                    return
                }
                cur = p
                nameAuto()
                input.dismiss()
                reload()
            }
            input.onEnter { submit() }
            input.primary("进入") { submit() }
            input.tonal("取消") { input.dismiss() }
            input.show()
        }
        sheet.primary("添加为存储库") { addCurrent() }
        sheet.show()
        reload()
    }

    /** 逐级浏览电脑目录,选中后回调完整路径。 */
    private fun showPcDirPicker(onPick: (String) -> Unit) {
        var current = ""
        val sheet = Sheet(this, "浏览电脑目录", "逐级进入目标目录")
        val list = sheet.customArea()
        fun render() {
            list.removeAllViews()
            list.addView(TextView(this).apply {
                text = "当前: " + current.ifEmpty { "电脑(选择磁盘)" }
                setTextColor(ThemeColors.textDim(this@MainActivity)); textSize = 12f
                setPadding(dp(4), 0, dp(4), dp(8))
            })
            val path = current
            fun addRow(label: String, target: String) {
                list.addView(ScreenUi.row(this, R.drawable.ic_folder, label, onClick = {
                    current = target
                    render()
                }))
            }
            if (path.isNotEmpty()) {
                val parent = if (path.count { it == '\\' } <= 1) "" else path.substringBeforeLast('\\')
                addRow(".. 返回上一级", parent)
            }
            lifecycleScope.launch {
                val o = try { withContext(Dispatchers.IO) { client.fsDirs(path) } } catch (e: Exception) {
                    toast("读取失败: ${e.message}"); return@launch
                }
                if (!sheet.isShowing() || current != path) return@launch
                if (path.isEmpty()) {
                    val drives = o.optJSONArray("drives")
                    if (drives != null) for (i in 0 until drives.length()) {
                        val el = drives.opt(i)
                        if (el is org.json.JSONObject) {
                            val letter = el.optString("letter").ifBlank { el.optString("name") }
                            val free = el.optLong("freeMB", 0L)
                            addRow("磁盘 " + letter.trimEnd('\\') + if (free > 0) " · 剩余 " + fmtGB(free) else "", letter)
                        } else {
                            val s = drives.optString(i)
                            addRow("磁盘 $s", s)
                        }
                    }
                } else {
                    val dirs = o.optJSONArray("dirs")
                    if (dirs != null) for (i in 0 until dirs.length()) {
                        val d = dirs.optJSONObject(i) ?: continue
                        addRow(d.optString("name") + "  /", d.optString("path"))
                    }
                }
            }
        }
        sheet.primary("选择此目录") {
            if (current.isEmpty()) toast("先进入一个目录") else { sheet.dismiss(); onPick(current) }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
        render()
    }

    private fun showTokenDialog() {
        val sheet = Sheet(this, "修改访问口令", "至少 4 位;两项都留空 = 取消口令")
        sheet.input("新口令", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.input("确认口令", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        sheet.primary("保存") { doSaveToken(sheet) }
        sheet.onEnter { doSaveToken(sheet) }
        sheet.show()
    }

    private fun doSaveToken(sheet: Sheet) {
        val a = sheet.value(0)
        val b = sheet.value(1)
        // 允许「两项都留空」清空口令(保留旧的“留空=不设口令”能力);否则长度≥4 且两次一致
        if (a.isNotEmpty() || b.isNotEmpty()) {
            if (a.length < 4) { sheet.inputView(0)?.error = "口令至少 4 位"; return }
            if (a != b) { sheet.inputView(1)?.error = "两次输入不一致"; return }
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.updateToken(a) }
                ServerConfig.token = a
                sheet.dismiss()
                bindSettings()
                toast(if (a.isEmpty()) "已取消口令" else "口令已更新")
            } catch (e: Exception) { toast("失败: ${e.message}") }
        }
    }

    private fun showSysInfo() {
        lifecycleScope.launch {
            try {
                val o = withContext(Dispatchers.IO) { client.settingsInfo() }
                val idx = o.optJSONObject("index")
                val sheet = Sheet(this@MainActivity, "系统信息", "服务端运行状态")
                sheet.section("应用")
                sheet.keyValue("版本", o.optString("version"))
                sheet.keyValue("口令", if (o.optBoolean("tokenSet")) "已设置" else "未设置")
                sheet.keyValue("保险箱", if (o.optBoolean("vaultExists")) "已创建" else "未创建")
                sheet.section("设备")
                sheet.keyValue("多用户", if (o.optBoolean("multiUser")) "已开启(${o.optInt("userCount")} 个用户)" else "未开启")
                sheet.keyValue("空闲锁屏", "${o.optInt("idleLockMinutes")} 分钟")
                sheet.section("网络")
                sheet.keyValue("端口", o.optInt("port").toString())
                sheet.keyValue("ffmpeg", if (o.optBoolean("ffmpeg")) "可用" else "未安装")
                sheet.section("存储")
                sheet.keyValue("存储库", "${o.optInt("libCount")} 个")
                sheet.keyValue("回收站保留", "${o.optInt("trashRetentionDays")} 天")
                sheet.keyValue("转码缓存上限", "${o.optInt("transcodeCacheMB")} MB")
                if (idx != null) {
                    sheet.keyValue("索引扫描中", if (idx.optBoolean("scanning")) "是" else "否")
                    val stats = idx.optJSONObject("stats")
                    if (stats != null) {
                        sheet.keyValue(
                            "媒体统计",
                            "视频 ${stats.optInt("video")} · 图片 ${stats.optInt("image")} · 音乐 ${stats.optInt("audio")}",
                        )
                    }
                }
                sheet.tonal("关闭") { sheet.dismiss() }
                sheet.show()
            } catch (e: Exception) { toast("读取失败: ${e.message}") }
        }
    }

    /** 最近活跃时间(秒 → MM-dd HH:mm)。 */
    private fun fmtLastSeen(sec: Long): String =
        if (sec <= 0) "-" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(sec * 1000))

    /** 本机局域网 IPv4(用于在设备列表里标「本机」);取不到返回 null。 */
    private fun localIpv4(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) { null }

    /**
     * 按设备名/UA 猜平台:Android 系关键词与桌面系统关键词都不含时视为未知。
     * 返回(图标, 平台中文名),只用于展示,不影响任何功能逻辑。
     * 说明:okhttp 仍归 Android —— 本 App 自己请求的 UA 就是 "okhttp/x"(截图里 192.168.10.3
     * 那台正是本机),移到脚本类会把本机错标成「命令行/脚本」。python/curl/wget/go-http 这类
     * 无 android/mobile 的脚本客户端单独归到「命令行/脚本」,避免显示成手机或彻底未知。
     */
    private fun devicePlatform(ua: String): Pair<Int, String> {
        val s = ua.trim().lowercase()
        if (s.isEmpty()) return Pair(R.drawable.ic_device_unknown, "未知设备")
        val androidKw = listOf("android", "mobile", "okhttp", "dalvik")
        // "pc" 是 "crop"/"pci" 的子串会误判,去掉;windows/macintosh/linux/x11/desktop 已足够识别桌面。
        val desktopKw = listOf("windows", "macintosh", "linux", "x11", "desktop")
        val scriptKw = listOf("python", "curl", "wget", "go-http", "libwww", "java/")
        return when {
            androidKw.any { it in s } -> Pair(R.drawable.ic_device_phone, "Android 手机")
            scriptKw.any { it in s } -> Pair(R.drawable.ic_device_desktop, "命令行/脚本")
            desktopKw.any { it in s } -> Pair(R.drawable.ic_device_desktop, "桌面电脑")
            else -> Pair(R.drawable.ic_device_unknown, "未知设备")
        }
    }

    private fun showDevices() {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.devices() }
                val myIp = withContext(Dispatchers.IO) { localIpv4() }
                val sheet = Sheet(this@MainActivity, "已连接设备", "共 ${list.size} 台")
                if (list.isEmpty()) sheet.text("暂无设备记录")
                for (d in list) {
                    val isMe = !myIp.isNullOrBlank() && d.ip == myIp
                    val value = (if (isMe) "本机 · " else "") + "最近活跃 " + fmtLastSeen(d.last) + " · ${d.count} 次"
                    // v1.8.5:按平台换图标,副标题前缀标明平台,方便一眼区分手机与电脑。
                    // UA 为空时只显示平台,避免出现孤零零的「平台 · 」尾巴。
                    val (icon, platform) = devicePlatform(d.ua)
                    val subtitle = if (d.ua.isBlank()) platform else platform + " · " + d.ua.take(60)
                    sheet.row(icon, d.ip, subtitle = subtitle, value = value)
                }
                if (list.isNotEmpty()) sheet.danger("清空记录") { confirmClearDevices(sheet) }
                sheet.tonal("关闭") { sheet.dismiss() }
                sheet.show()
            } catch (e: Exception) { toast("读取失败: ${e.message}") }
        }
    }

    private fun confirmClearDevices(sheet: Sheet) {
        val confirm = Sheet(this, "清空设备记录?", "仅清除访问记录,不影响正在使用的设备")
        confirm.danger("清空") {
            confirm.dismiss()
            sheet.dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.clearDevices() }
                    toast("已清空")
                } catch (e: Exception) { toast("失败: ${e.message}") }
            }
        }
        confirm.tonal("取消") { confirm.dismiss() }
        confirm.show()
    }

    private fun bindSettings() {
        // 优先展示服务端自定义名(替代裸 IP);没连上时显示地址或「未连接」。
        val srvName = Prefs.serverNameFor(ServerConfig.baseUrl).trim()
        val srvHost = ServerConfig.host().trim()
        setServer.text = srvName.ifBlank { srvHost }.ifBlank { "未连接" }
        setServerAddr.text = if (srvName.isBlank()) "" else srvHost
        setServerAddr.visibility = if (setServerAddr.text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        setToken.text = if (ServerConfig.token.isBlank()) "无口令" else "口令已设置"
        setServerVersion.text = if (serverVersion.isBlank()) "" else "服务端 v$serverVersion"
        val appVer = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { null }
        setAppVersion.text = "linNas App v" + (appVer ?: "")
        val loggedIn = ServerConfig.sessionUser.isNotBlank()
        val lb = findViewById<View>(R.id.btnLogin)
        val lo = findViewById<View>(R.id.btnLogout)
        lb.visibility = if (loggedIn) View.GONE else View.VISIBLE
        lo.visibility = if (loggedIn) View.VISIBLE else View.GONE
        val who = findViewById<TextView>(R.id.setLoginUser)
        who.text = if (loggedIn) "已登录:${ServerConfig.sessionUser}(${roleLabel(ServerConfig.sessionRole)})" else "未登录(口令访问)"
        // P2:操作日志仅管理员可见
        findViewById<View>(R.id.btnAudit).visibility = if (canAdmin()) View.VISIBLE else View.GONE
        // v1.7.0:用户管理 / 维护与索引 / 存储库管理 仅管理员可见;会话与网络唤醒所有人可用
        val adminVisible = if (canAdmin()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnUsers).visibility = adminVisible
        findViewById<View>(R.id.btnMaintenance).visibility = adminVisible
        findViewById<View>(R.id.btnLibAdmin).visibility = adminVisible
    }

    // ---------- 发现 / 手动连接 ----------

    private fun showDiscoveryDialog() {
        val sheet = Sheet(this, "发现服务器", "自动搜索同一 WiFi 下的电脑")
        val status = ScreenUi.note(this, "正在自动搜索(广播 + mDNS + 网段扫描)…")
        val list = sheet.customArea()
        list.addView(status)
        sheet.tonal("手动输入") { sheet.dismiss(); showManualDialog() }
        sheet.tonal("扫码配对") { sheet.dismiss(); startQrScan() }
        sheet.show()

        val shown = HashSet<String>()
        lifecycleScope.launch {
            val servers = Discovery.discover { s ->
                runOnUiThread {
                    if (!shown.add("${s.ip}:${s.port}")) return@runOnUiThread
                    status.text = "找到 ${shown.size} 台服务器,点一下连接"
                    val row = layoutInflater.inflate(R.layout.item_server, list, false)
                    row.findViewById<TextView>(R.id.srvName).text = s.name + " · " + s.ip
                    row.findViewById<TextView>(R.id.srvMeta).text =
                        (if (s.version.isNotBlank()) "v${s.version}  ·  " else "") + "点击连接"
                    row.setOnClickListener {
                        if (s.name.isNotBlank()) Prefs.setServerName(s.baseUrl, s.name)
                        ServerConfig.baseUrl = s.baseUrl
                        sheet.dismiss()
                        connectAndLoad()
                    }
                    list.addView(row)
                }
            }
            if (servers.isEmpty()) {
                runOnUiThread {
                    status.text = "没找到服务器。确认电脑端已启动、手机连的是同一个 WiFi;也可点「扫码配对」扫电脑上的二维码。"
                }
            }
        }
    }

    private fun showManualDialog() {
        val sheet = Sheet(this, "手动连接", "输入电脑上 linNas 服务的地址")
        sheet.input("http://192.168.x.x:52700", ServerConfig.baseUrl.ifBlank { "http://" })
        sheet.input(
            "口令(没有可留空)", ServerConfig.token,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        sheet.primary("连接") {
            val addr = sheet.value(0)
            if (!addr.startsWith("http://") && !addr.startsWith("https://")) {
                sheet.inputView(0)?.error = "地址需以 http:// 或 https:// 开头"
                return@primary
            }
            ServerConfig.baseUrl = addr
            ServerConfig.token = sheet.value(1)
            sheet.dismiss()
            connectAndLoad()
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
    }

    // ---------- 搜索(当前目录 / 全库) ----------

    private fun onSearchChanged() {
        val q = searchInput.text?.toString()?.trim().orEmpty()
        if (!globalSearch || q.isEmpty()) {
            filterFiles()
            return
        }
        val seq = ++searchSeq
        handler.postDelayed({
            if (seq != searchSeq) return@postDelayed
            lifecycleScope.launch {
                val hits = try { withContext(Dispatchers.IO) { client.search(q, 200) } } catch (_: Exception) { emptyList() }
                if (seq != searchSeq) return@launch
                allRows = hits.map { ListRow.FileRow(it) }
                applyAndSubmit(allRows)
            }
        }, 320)
    }

    // ---------- 移动 / 复制 + 目录选择 ----------

    private fun transferEntry(vp: String, dest: String, move: Boolean) {
        if (dest == curPath.trimEnd('/')) { toast("已经在这个目录了"); return }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (move) client.move(listOf(vp), dest) else client.copy(listOf(vp), dest)
                }
                toast(if (move) "已移动" else "已复制")
                loadDir()
            } catch (e: Exception) { toast("失败: ${e.message}") }
        }
    }

    private fun showDirPicker(onPick: (String) -> Unit) {
        var current = if (curPath.isEmpty()) "" else curPath.trimEnd('/')
        val sheet = Sheet(this, "选择目标文件夹", "移动 / 复制到指定目录")
        val list = sheet.customArea()
        fun render() {
            list.removeAllViews()
            list.addView(TextView(this).apply {
                text = "当前: " + current.ifEmpty { "存储库" }
                setTextColor(ThemeColors.textDim(this@MainActivity)); textSize = 12f
                setPadding(dp(4), 0, dp(4), dp(8))
            })
            val path = current
            fun addRow(label: String, target: String, icon: Int) {
                list.addView(ScreenUi.row(this, icon, label, onClick = {
                    current = target
                    render()
                }))
            }
            if (path.isEmpty()) {
                libs.forEach { lib -> addRow(lib.name, "/" + lib.id, R.drawable.ic_library) }
                return
            }
            addRow(".. 上一级", if (path.count { it == '/' } <= 1) "" else path.substringBeforeLast('/'), R.drawable.ic_folder)
            lifecycleScope.launch {
                val entries = try { withContext(Dispatchers.IO) { client.ls(path).entries } } catch (_: Exception) { emptyList() }
                if (!sheet.isShowing() || current != path) return@launch
                entries.filter { it.isDir }.forEach { e ->
                    addRow(e.name + " /", path.trimEnd('/') + "/" + e.name, R.drawable.ic_folder)
                }
            }
        }
        sheet.primary("选择此目录") {
            if (current.isEmpty()) toast("先进入一个库") else { sheet.dismiss(); onPick(current) }
        }
        sheet.tonal("取消") { sheet.dismiss() }
        sheet.show()
        render()
    }

    // ---------- 排序 / 视图 / 主题 / 磁盘 ----------

    /** 排序后同时喂给列表与网格适配器,并按 viewMode 切换显示。 */
    private fun applyAndSubmit(rows: List<ListRow>) {
        val sorted = sortRows(rows)
        fileAdapter.submit(sorted, curPath)
        gridAdapter.submit(sorted, curPath)
        fileList.visibility = if (viewMode == "grid") View.GONE else View.VISIBLE
        fileGrid.visibility = if (viewMode == "grid") View.VISIBLE else View.GONE
        filesEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        updateDiskInfo()
    }

    private fun sortRows(rows: List<ListRow>): List<ListRow> {
        val cmp = Comparator<ListRow> { a, b ->
            val aDir = a is ListRow.Lib || (a as? ListRow.FileRow)?.e?.isDir == true
            val bDir = b is ListRow.Lib || (b as? ListRow.FileRow)?.e?.isDir == true
            if (aDir != bDir) return@Comparator if (aDir) -1 else 1
            val an = when (a) { is ListRow.Lib -> a.lib.name; is ListRow.FileRow -> a.e.name }
            val bn = when (b) { is ListRow.Lib -> b.lib.name; is ListRow.FileRow -> b.e.name }
            val base = when (sortKey) {
                "size" -> ((a as? ListRow.FileRow)?.e?.size ?: 0L)
                    .compareTo((b as? ListRow.FileRow)?.e?.size ?: 0L)
                "mtime" -> ((a as? ListRow.FileRow)?.e?.modTime ?: 0L)
                    .compareTo((b as? ListRow.FileRow)?.e?.modTime ?: 0L)
                else -> an.lowercase().compareTo(bn.lowercase())
            }
            if (sortAsc) base else -base
        }
        return rows.sortedWith(cmp)
    }

    private fun setupSortView() {
        val btnSort = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSort)
        btnSort.text = sortLabelText()
        btnSort.setOnClickListener {
            val popup = PopupMenu(this, btnSort)
            popup.menu.add(0, 1, 0, "按名称").isCheckable = true
            popup.menu.add(0, 2, 1, "按大小").isCheckable = true
            popup.menu.add(0, 3, 2, "按时间").isCheckable = true
            popup.menu.add(0, 4, 3, if (sortAsc) "改为降序" else "改为升序")
            popup.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    1 -> sortKey = "name"
                    2 -> sortKey = "size"
                    3 -> sortKey = "mtime"
                    4 -> sortAsc = !sortAsc
                }
                UiPrefs.setSortKey(this, sortKey)
                UiPrefs.setSortAsc(this, sortAsc)
                btnSort.text = sortLabelText()
                filterFiles()
                true
            }
            popup.show()
        }
        val btnView = findViewById<android.widget.ImageButton>(R.id.btnView)
        btnView.setImageResource(if (viewMode == "grid") R.drawable.ic_list else R.drawable.ic_grid)
        btnView.setOnClickListener {
            viewMode = if (viewMode == "grid") "list" else "grid"
            UiPrefs.setViewMode(this, viewMode)
            btnView.setImageResource(if (viewMode == "grid") R.drawable.ic_list else R.drawable.ic_grid)
            applyAndSubmit(allRows)
        }
        val btnGlobal = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGlobal)
        btnGlobal.setOnClickListener {
            globalSearch = !globalSearch
            btnGlobal.setTextColor(if (globalSearch) ThemeColors.primary(this) else ThemeColors.textDim(this))
            toast(if (globalSearch) "全库搜索" else "当前目录搜索")
            onSearchChanged()
        }
    }

    /** 主题色块:6 套配色(4 深 + 2 浅),选中项描边加粗。 */
    private fun setupTheme() {
        val grid = findViewById<LinearLayout>(R.id.themeGrid)
        grid.removeAllViews()
        val cur = ThemeStore.index(this)
        val all = ThemeStore.all
        var start = 0
        while (start < all.size) {
            val rowL = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (start > 0) setPadding(0, dp(10), 0, 0)
            }
            for (k in start until minOf(start + 3, all.size)) {
                rowL.addView(
                    themeCell(k, all[k], k == cur),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            grid.addView(
                rowL,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            start += 3
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun themeCell(index: Int, t: ThemeStore.T, selected: Boolean): View {
        val ripple = theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            val d = it.getDrawable(0); it.recycle(); d
        }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = if (selected) "主题：${t.label}，已选择" else "主题：${t.label}"
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = ripple
        }
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, t.swatch))
            setStroke(
                dp(if (selected) 2 else 1),
                if (selected) ThemeColors.primary(this@MainActivity)
                else ThemeColors.outline(this@MainActivity)
            )
        }
        val accent = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, t.primary))
        }
        val swatch = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = LayerDrawable(arrayOf(base, accent)).apply {
                // 底部主题色条:6 套主题一眼可辨(原来只有近黑的底色)
                setLayerInset(1, dp(10), dp(28), dp(10), dp(8))
            }
        }
        cell.addView(swatch, LinearLayout.LayoutParams(dp(72), dp(46)))
        cell.addView(TextView(this).apply {
            text = t.label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            setTextColor(
                if (selected) ThemeColors.primary(this@MainActivity)
                else ThemeColors.textDim(this@MainActivity)
            )
        })
        cell.setOnClickListener {
            if (index != ThemeStore.index(this)) {
                ThemeStore.setIndex(this, index)
                recreate()
            }
        }
        return cell
    }

    private fun updateDiskInfo() {
        val text = if (curPath.isNotEmpty()) {
            val libId = curPath.trimStart('/').substringBefore('/')
            val d = disksList.firstOrNull { it.id == libId }
            if (d != null) "${d.name} · 剩余 ${fmtGB(d.freeMB)}" else ""
        } else {
            val total = disksList.sumOf { it.freeMB }
            if (disksList.isEmpty()) "" else "共剩余 ${fmtGB(total)}"
        }
        diskInfo.text = text
    }

    private fun fmtGB(mb: Long): String {
        if (mb <= 0) return "0 GB"
        val tb = mb / 1024.0 / 1024.0
        return if (tb >= 1) String.format("%.1f TB", tb) else String.format("%.0f GB", mb / 1024.0)
    }

    // ---------- 小工具 ----------

    /** 统一的「更多」动作面板:改用 Sheet 视觉(拖动条 + 圆角卡片行),替代默认 PopupMenu 的突兀样式。 */
    private fun showActionSheet(title: String?, actions: List<Pair<String, () -> Unit>>) {
        val sheet = Sheet(this, title ?: "操作")
        for ((label, action) in actions) {
            val destructive = label.startsWith("删除") || label.startsWith("清空") || label.contains("移除")
            sheet.row(0, label, destructive = destructive, onClick = action)
        }
        sheet.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun roleLabel(role: String) = when (role) {
        "admin" -> "管理员"
        "viewer" -> "只读"
        "user" -> "用户"
        else -> "未登录"
    }
}
