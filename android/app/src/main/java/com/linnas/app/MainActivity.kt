package com.linnas.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
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
import com.linnas.app.upload.UploadService
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
    private var globalSearch = false
    private var searchSeq = 0
    private lateinit var downloadList: LinearLayout
    private lateinit var downloadEmpty: View
    private lateinit var searchInput: TextInputEditText
    private lateinit var mediaGrid: RecyclerView
    private lateinit var mediaEmpty: View
    private lateinit var swBackup: MaterialSwitch
    private lateinit var swWifi: MaterialSwitch
    private lateinit var etBackupDir: TextInputEditText
    private lateinit var backupStatus: TextView
    private lateinit var transferList: LinearLayout
    private lateinit var transferEmpty: View
    private lateinit var setServer: TextView
    private lateinit var setToken: TextView
    private lateinit var setServerVersion: TextView
    private lateinit var setAppVersion: TextView
    private lateinit var setDownloadDir: TextView

    private val handler = Handler(Looper.getMainLooper())
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
        mediaGrid.layoutManager = GridLayoutManager(this, 2)
        mediaGrid.adapter = mediaAdapter

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
        val q = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val rows = if (q.isEmpty()) allRows else allRows.filter {
            when (it) {
                is ListRow.Lib -> it.lib.name.lowercase().contains(q)
                is ListRow.FileRow -> it.e.name.lowercase().contains(q)
            }
        }
        applyAndSubmit(rows)
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
                    text = "/"; setTextColor(getColor(R.color.outline)); textSize = 13f
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
            setTextColor(getColor(if (active) R.color.text else R.color.text_dim))
            textSize = 13f
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
        val vp = e.vpath ?: (curPath.trimEnd('/') + "/" + e.name)   // P2:跨库搜索结果带绝对 vpath
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val playable = !e.isDir && (ext in FileListAdapter.VIDEO || ext in FileListAdapter.AUDIO)
        val offline = OfflineStore.get(this, vp)
        val popup = PopupMenu(this, anchor)
        if (canWrite()) {
            popup.menu.add(0, 1, 0, "重命名")
            popup.menu.add(0, 2, 1, "删除")
            popup.menu.add(0, 3, 2, "移动到…")
            popup.menu.add(0, 4, 3, "复制到…")
            popup.menu.add(0, 5, 4, "分享链接…")
        }
        popup.menu.add(0, 6, 5, "详情")
        if (playable) {
            when (offline?.status) {
                OfflineStore.ST_DONE -> popup.menu.add(0, 7, 6, "移除离线缓存")
                OfflineStore.ST_DOWNLOADING, OfflineStore.ST_PAUSED ->
                    popup.menu.add(0, 7, 6, "离线下载中…")
                else -> popup.menu.add(0, 7, 6, "缓存到手机")
            }
        }
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> renameDialog(vp, e.name)
                2 -> deleteConfirm(vp, e)
                3 -> showDirPicker { dest -> transferEntry(vp, dest, move = true) }
                4 -> showDirPicker { dest -> transferEntry(vp, dest, move = false) }
                5 -> startActivity(Intent(this, SharesActivity::class.java).apply {
                    putExtra(SharesActivity.EXTRA_INITIAL_VPATH, vp)
                })
                6 -> showInfoDialog(vp, e)
                7 -> toggleOffline(vp, e)
            }
            true
        }
        popup.show()
    }

    /** P1:/api/info 详情弹窗(文件夹给递归统计,媒体给 ffprobe 技术信息)。 */
    private fun showInfoDialog(vp: String, e: Entry) {
        lifecycleScope.launch {
            val info = try {
                withContext(Dispatchers.IO) { client.info(vp) }
            } catch (ex: Exception) {
                toast("读取详情失败:${ex.message}")
                return@launch
            }
            val sb = StringBuilder()
            sb.append("路径:").append(info.path).append('\n')
            sb.append("类型:").append(
                if (info.isDir) "文件夹" else info.kind.ifBlank { info.ext.ifBlank { "文件" } }
            )
            if (!info.isDir) sb.append('\n').append("大小:").append(FileListAdapter.fmtSize(info.size))
            sb.append('\n').append("修改时间:").append(FileListAdapter.fmtDate(info.modTime))
            if (info.isDir) {
                sb.append('\n').append("包含:").append(info.files).append(" 个文件 / ")
                    .append(info.dirs).append(" 个文件夹")
                sb.append('\n').append("总大小:").append(FileListAdapter.fmtSize(info.totalBytes))
            }
            if (info.width > 0 && info.height > 0) {
                sb.append('\n').append("分辨率:").append(info.width).append('×').append(info.height)
            }
            if (info.duration > 0) {
                sb.append('\n').append("时长:").append(fmtDuration(info.duration.toLong()))
            }
            if (info.fps > 0) sb.append('\n').append("帧率:")
                .append(String.format("%.2f", info.fps)).append(" fps")
            if (info.bitRate > 0) sb.append('\n').append("码率:")
                .append(info.bitRate / 1000).append(" kbps")
            if (info.videoCodec.isNotBlank()) sb.append('\n').append("视频编码:").append(info.videoCodec)
            if (info.audioCodec.isNotBlank()) sb.append('\n').append("音频编码:").append(info.audioCodec)
            AlertDialog.Builder(this@MainActivity)
                .setTitle(info.name.ifBlank { e.name })
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /** P1:离线缓存开关(缓存到手机 / 移除缓存)。 */
    private fun toggleOffline(vp: String, e: Entry) {
        when (OfflineStore.get(this, vp)?.status) {
            OfflineStore.ST_DONE -> AlertDialog.Builder(this)
                .setTitle("移除离线缓存")
                .setMessage("删除本机缓存的「${e.name}」?服务器上的文件不受影响。")
                .setPositiveButton("删除") { _, _ ->
                    OfflineStore.remove(this, vp)
                    toast("已移除离线缓存")
                }
                .setNegativeButton("取消", null)
                .show()
            OfflineStore.ST_DOWNLOADING, OfflineStore.ST_PAUSED -> {
                toast("已在离线下载中,可到「传输」页管理")
                selectTab(2)
            }
            else -> {
                OfflineDownloader.start(this, vp, e.name, e.size)
                toast("开始离线缓存:${e.name}")
                selectTab(2)
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
                ext in FileListAdapter.IMAGE -> startActivity(
                    Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(client.rawUrl(vp)), "image/*") }
                )
                else -> {
                    Downloads.enqueue(this, client.rawUrl(vp, true), e.name, UiPrefs.downloadDir(this))
                    toast("已开始下载 ${e.name}")
                }
            }
        } catch (_: Exception) {
            toast("没有可用的打开方式")
        }
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
        val popup = PopupMenu(this, anchor)
        if (canWrite()) popup.menu.add(0, 1, 0, "新建文件夹")
        popup.menu.add(0, 2, 1, "重新扫描服务器")
        popup.menu.add(0, 3, 2, "连接其他服务器")
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> mkdirDialog()
                2 -> connectAndLoad(silent = true)
                3 -> showDiscoveryDialog()
            }
            true
        }
        popup.show()
    }

    private fun mkdirDialog() {
        if (curPath.isEmpty()) { toast("先进入一个存储库"); return }
        val input = EditText(this).apply { hint = "文件夹名称"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("新建文件夹").setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.mkdir("$curPath/$name") }
                        loadDir()
                    } catch (e: Exception) { toast("失败: ${e.message}") }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun renameDialog(vp: String, oldName: String) {
        val input = EditText(this).apply { setText(oldName); setSingleLine() }
        AlertDialog.Builder(this).setTitle("重命名").setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.rename(vp, name) }
                        loadDir()
                    } catch (e: Exception) { toast("失败: ${e.message}") }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun deleteConfirm(vp: String, e: Entry) {
        val msg = if (e.isDir) "确定删除文件夹「${e.name}」及其内容?" else "确定删除「${e.name}」?"
        AlertDialog.Builder(this).setTitle("删除").setMessage(msg)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.delete(vp) }
                        loadDir(); toast("已删除")
                    } catch (ex: Exception) { toast("失败: ${ex.message}") }
                }
            }.setNegativeButton("取消", null).show()
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
                mediaAdapter.submit(all)
                mediaEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onMediaOpen(e: Entry) {
        val vp = e.vpath ?: return
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val transcode = setOf("avi", "rmvb", "rm", "flv", "wmv", "mpg", "mpeg", "vob", "asf", "ogv")
        val (pl, idx) = playlistFor(vp)
        when {
            ext in transcode -> startPlayer(client.streamUrl(vp), e.name, vp, null, pl, idx)
            ext in FileListAdapter.IMAGE -> toast("图片请在文件页打开")
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
        setDownloadDir.text = UiPrefs.downloadDir(this).ifBlank { "下载目录" }
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
        backupStatus.text = "上次:${BackupConfig.lastResult(this)}  ·  累计 ${BackupConfig.uploadedCount(this)} 张"
    }

    /** 下载保存位置:系统「下载」目录下的子文件夹(UiPrefs.downloadDir)。 */
    private fun showDownloadDirDialog() {
        val input = EditText(this).apply {
            hint = "子文件夹名(如 linNas;留空存到下载根目录)"
            setText(UiPrefs.downloadDir(this@MainActivity))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("下载保存位置")
            .setMessage("文件会保存到「手机 / 下载 / 子文件夹」;留空则直接存到「下载」。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                UiPrefs.setDownloadDir(this, v)
                setDownloadDir.text = v.ifBlank { "下载目录" }
                toast(if (v.isBlank()) "已改为「下载」根目录" else "已改为「下载/$v」")
            }
            .setNegativeButton("取消", null)
            .show()
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
        transferList.removeAllViews()
        val tasks = UploadService.progress.values.toList()
        transferEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        for (t in tasks) {
            val v = layoutInflater.inflate(R.layout.item_transfer, transferList, false)
            v.findViewById<TextView>(R.id.tname).text = t.name
            val pct = if (t.size > 0) ((t.sent * 100) / t.size).toInt() else 0
            v.findViewById<TextView>(R.id.tstate).text = when {
                t.state == UploadService.ST_DONE -> "已完成"
                t.state.startsWith("失败") -> t.state
                else -> "${t.state} $pct%"
            }
            val bar = v.findViewById<LinearProgressIndicator>(R.id.tprog)
            bar.max = 100
            bar.setProgressCompat(if (t.state == UploadService.ST_DONE) 100 else pct, true)
            // P1:单任务操作(暂停/继续/取消/重试/清除)
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
                refreshTransfers()
            }
            transferList.addView(v)
        }
        // 离线缓存区(OfflineStore 登记的任务)
        val offlineList = findViewById<LinearLayout>(R.id.offlineList)
        val offlineEmpty = findViewById<View>(R.id.offlineEmpty)
        offlineList.removeAllViews()
        val offs = OfflineStore.all(this)
        offlineEmpty.visibility = if (offs.isEmpty()) View.VISIBLE else View.GONE
        for (o in offs) {
            val v = layoutInflater.inflate(R.layout.item_transfer, offlineList, false)
            v.findViewById<TextView>(R.id.tname).text = o.name
            val pct = o.percent
            v.findViewById<TextView>(R.id.tstate).text = when (o.status) {
                OfflineStore.ST_DOWNLOADING -> "缓存中 $pct%"
                OfflineStore.ST_PAUSED -> "已暂停 $pct%"
                OfflineStore.ST_DONE -> "已缓存"
                else -> "失败: ${o.msg}"
            }
            val bar = v.findViewById<LinearProgressIndicator>(R.id.tprog)
            bar.max = 100
            bar.setProgressCompat(if (o.status == OfflineStore.ST_DONE) 100 else pct, true)
            val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tact)
            btn.visibility = if (o.status == OfflineStore.ST_DONE) View.GONE else View.VISIBLE
            btn.text = if (o.status == OfflineStore.ST_PAUSED) "继续" else "暂停"
            btn.setOnClickListener {
                if (o.status == OfflineStore.ST_PAUSED) {
                    OfflineDownloader.resume(this, o.vpath, o.name, o.size)
                } else {
                    OfflineDownloader.pause(this, o.vpath)
                }
                refreshTransfers()
            }
            val clear = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tclear)
            clear.visibility = View.VISIBLE
            clear.text = if (o.status == OfflineStore.ST_DONE) "移除" else "取消"
            clear.setOnClickListener {
                if (o.status != OfflineStore.ST_DONE) OfflineDownloader.cancel(this, o.vpath)
                OfflineStore.remove(this, o.vpath)
                refreshTransfers()
            }
            offlineList.addView(v)
        }
        // 下载队列(系统 DownloadManager 提供进度)
        downloadList.removeAllViews()
        val dls = Downloads.tasks(this)
        downloadEmpty.visibility = if (dls.isEmpty()) View.VISIBLE else View.GONE
        for (d in dls) {
            val v = layoutInflater.inflate(R.layout.item_transfer, downloadList, false)
            v.findViewById<TextView>(R.id.tname).text = d.name
            val pct = if (d.total > 0) ((d.done * 100) / d.total).toInt() else 0
            val stateText = when (d.status) {
                android.app.DownloadManager.STATUS_SUCCESSFUL -> "已完成"
                android.app.DownloadManager.STATUS_FAILED -> "失败"
                else -> "$pct%"
            }
            v.findViewById<TextView>(R.id.tstate).text = stateText
            val bar = v.findViewById<LinearProgressIndicator>(R.id.tprog)
            bar.max = 100
            bar.setProgressCompat(if (d.status == android.app.DownloadManager.STATUS_SUCCESSFUL) 100 else pct, true)
            val clear = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tclear)
            clear.visibility = View.GONE
            val btn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.tact)
            btn.visibility = View.GONE
            downloadList.addView(v)
        }
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

    /** 手机端添加存储库:浏览电脑盘符/目录,选一个目录注册为存储库。 */
    private fun showAddLibrary() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val crumb = TextView(this).apply {
            setTextColor(ThemeColors.textDim(this@MainActivity)); textSize = 12f; text = "电脑"
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(box) }
        wrap.addView(crumb)
        wrap.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))

        var current = ""
        val dlg = AlertDialog.Builder(this)
            .setTitle("添加存储库")
            .setView(wrap)
            .setPositiveButton("选这个目录", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (current.isEmpty()) { toast("先进入一个磁盘/目录"); return@setOnClickListener }
                val input = EditText(this@MainActivity).apply { hint = "显示名称(如 电影)"; setSingleLine() }
                AlertDialog.Builder(this@MainActivity).setTitle("命名存储库").setView(input)
                    .setPositiveButton("添加") { _, _ ->
                        val nm = input.text.toString().trim().ifBlank { current.substringAfterLast('\\') }
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { client.addLibrary(nm, current) }
                                toast("已添加 $nm")
                                dlg.dismiss()
                                connectAndLoad(silent = true)
                            } catch (e: Exception) { toast("添加失败: ${e.message}") }
                        }
                    }.setNegativeButton("取消", null).show()
            }
        }
        dlg.show()

        fun row(label: String, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(ThemeColors.text(this@MainActivity)); textSize = 14f
                setPadding(dp(10), dp(12), dp(10), dp(12)); setOnClickListener { onClick() }
            }
            box.addView(tv)
        }
        fun load(path: String) {
            current = path
            crumb.text = "目录: " + path.ifEmpty { "电脑(选择磁盘)" }
            box.removeAllViews()
            lifecycleScope.launch {
                val o = try { withContext(Dispatchers.IO) { client.fsDirs(path) } } catch (e: Exception) { toast("读取失败: ${e.message}"); return@launch }
                if (path.isNotEmpty()) {
                    val parent = o.optString("parent")
                    row(".. 返回上级") { load(parent) }
                }
                val dirs = o.optJSONArray("dirs")
                if (dirs != null) for (i in 0 until dirs.length()) {
                    val d = dirs.optJSONObject(i) ?: continue
                    val name = d.optString("name"); val full = d.optString("path")
                    row(name + "  /") { load(full) }
                }
                if (path.isEmpty()) {
                    val drives = o.optJSONArray("drives")
                    if (drives != null) for (i in 0 until drives.length()) {
                        val s = drives.optString(i)
                        row("磁盘 " + s) { load(s) }
                    }
                }
            }
        }
        load("")
    }

    private fun showTokenDialog() {
        val input = EditText(this).apply { hint = "新口令(留空=不设口令)"; setText(ServerConfig.token); setSingleLine() }
        AlertDialog.Builder(this).setTitle("修改访问口令").setView(input)
            .setPositiveButton("保存") { _, _ ->
                val t = input.text.toString().trim()
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.updateToken(t) }
                        ServerConfig.token = t
                        bindSettings()
                        toast("口令已更新")
                    } catch (e: Exception) { toast("失败: ${e.message}") }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showSysInfo() {
        lifecycleScope.launch {
            try {
                val o = withContext(Dispatchers.IO) { client.settingsInfo() }
                val idx = o.optJSONObject("index")
                val msg = buildString {
                    append("版本: ").append(o.optString("version")).append('\n')
                    append("端口: ").append(o.optInt("port")).append('\n')
                    append("存储库: ").append(o.optInt("libCount")).append(" 个\n")
                    append("口令: ").append(if (o.optBoolean("tokenSet")) "已设置" else "未设置").append('\n')
                    append("ffmpeg: ").append(if (o.optBoolean("ffmpeg")) "可用" else "未安装").append('\n')
                    if (idx != null) {
                        val stats = idx.optJSONObject("stats")
                        append("索引: 扫描中=").append(idx.optBoolean("scanning")).append('\n')
                        if (stats != null) {
                            append("视频 ").append(stats.optInt("video")).append(" · 图片 ")
                                .append(stats.optInt("image")).append(" · 音乐 ").append(stats.optInt("audio"))
                        }
                    }
                }
                AlertDialog.Builder(this@MainActivity).setTitle("系统信息").setMessage(msg)
                    .setPositiveButton("好", null).show()
            } catch (e: Exception) { toast("读取失败: ${e.message}") }
        }
    }

    private fun showDevices() {
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { client.devices() }
                val msg = if (list.isEmpty()) "暂无设备记录" else list.joinToString("\n\n") {
                    "${it.ip}\n${it.ua}\n访问 ${it.count} 次"
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("已连接设备 (${list.size})")
                    .setMessage(msg)
                    .setPositiveButton("清空记录") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { client.clearDevices() }
                                toast("已清空")
                            } catch (e: Exception) { toast("失败: ${e.message}") }
                        }
                    }
                    .setNegativeButton("关闭", null).show()
            } catch (e: Exception) { toast("读取失败: ${e.message}") }
        }
    }

    private fun bindSettings() {
        setServer.text = ServerConfig.baseUrl.ifBlank { "未连接" }
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
    }

    // ---------- 发现 / 手动连接 ----------

    private fun showDiscoveryDialog() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val status = TextView(this).apply {
            text = "正在自动搜索(广播 + mDNS + 网段扫描)…"
            setTextColor(getColor(R.color.text_dim)); textSize = 13f
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        wrap.addView(status); wrap.addView(list)

        val dlg = AlertDialog.Builder(this)
            .setTitle("发现服务器")
            .setView(wrap)
            .setPositiveButton("手动输入", null)
            .setNeutralButton("扫码配对", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dlg.dismiss(); showManualDialog()
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dlg.dismiss(); startQrScan()
            }
        }
        dlg.show()

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
                        ServerConfig.baseUrl = s.baseUrl
                        dlg.dismiss()
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
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val addr = EditText(this).apply {
            hint = "http://192.168.x.x:52700"
            setText(ServerConfig.baseUrl.ifBlank { "http://" })
            setSingleLine()
        }
        val token = EditText(this).apply {
            hint = "口令(没有可留空)"
            setText(ServerConfig.token)
            setSingleLine()
        }
        wrap.addView(addr); wrap.addView(token)
        AlertDialog.Builder(this)
            .setTitle("手动连接")
            .setView(wrap)
            .setPositiveButton("连接") { _, _ ->
                ServerConfig.baseUrl = addr.text.toString().trim()
                ServerConfig.token = token.text.toString().trim()
                connectAndLoad()
            }
            .setNegativeButton("取消", null)
            .show()
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
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val crumb = TextView(this).apply {
            setTextColor(ThemeColors.textDim(this@MainActivity)); textSize = 12f; text = "选择目标文件夹"
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(box) }
        wrap.addView(crumb)
        wrap.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)))

        var current = ""
        val dlg = AlertDialog.Builder(this)
            .setTitle("选择目标文件夹")
            .setView(wrap)
            .setPositiveButton("选这里") { _, _ -> if (current.isNotEmpty()) onPick(current) else toast("先进入一个库") }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()

        fun row(label: String, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                text = label
                setTextColor(ThemeColors.text(this@MainActivity))
                textSize = 14f
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener { onClick() }
            }
            box.addView(tv)
        }

        fun load(vp: String) {
            current = vp
            crumb.text = "目标: " + (vp.ifEmpty { "存储库" })
            box.removeAllViews()
            if (vp.isEmpty()) {
                libs.forEach { lib -> row(lib.name) { load("/" + lib.id) } }
                return
            }
            row(".. (上一级)") {
                val parent = if (vp.count { it == '/' } <= 1) "" else vp.substringBeforeLast('/')
                load(parent)
            }
            lifecycleScope.launch {
                val entries = try { withContext(Dispatchers.IO) { client.ls(vp).entries } } catch (_: Exception) { emptyList() }
                entries.filter { it.isDir }.forEach { e ->
                    row(e.name + " /") { load(vp.trimEnd('/') + "/" + e.name) }
                }
            }
        }
        load(if (curPath.isEmpty()) "" else curPath.trimEnd('/'))
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
        fun label(): String {
            val k = when (sortKey) { "size" -> "大小"; "mtime" -> "时间"; else -> "名称" }
            return k + (if (sortAsc) " ↑" else " ↓")
        }
        btnSort.text = label()
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
                btnSort.text = label()
                applyAndSubmit(allRows)
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
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = ripple
        }
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(ContextCompat.getColor(this@MainActivity, t.swatch))
                setStroke(
                    dp(if (selected) 2 else 1),
                    if (selected) ThemeColors.primary(this@MainActivity)
                    else ThemeColors.outline(this@MainActivity)
                )
            }
        }
        cell.addView(swatch, LinearLayout.LayoutParams(dp(72), dp(46)))
        cell.addView(TextView(this).apply {
            text = t.label
            textSize = 12f
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun roleLabel(role: String) = when (role) {
        "admin" -> "管理员"
        "viewer" -> "只读"
        "user" -> "用户"
        else -> "未登录"
    }
}
