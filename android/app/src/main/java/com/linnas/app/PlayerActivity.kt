package com.linnas.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 内置播放页(v1.0):
 *  - 横屏/竖屏自由切换(旋转按钮 + 随设备旋转)
 *  - 手势:双击左右快退/快进、上下滑调音量/亮度、长按倍速
 *  - 续播记忆:自动记住进度,再次打开从断点继续
 *  - 同名字幕(.srt/.ass)自动挂载
 *  - 画面比例、倍速、解码错误提示、拔耳机暂停、保持亮屏
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        const val EXTRA_VPATH = "vpath"
        const val EXTRA_SUB = "subtitle"
        // P2 播放列表
        const val EXTRA_PLAYLIST = "playlist"    // JSON: [{"name":..,"vpath":..}]
        const val EXTRA_PLAY_INDEX = "playIndex" // 当前项下标

        /** 需服务端转码才能播的旧格式。 */
        private val TRANS_CODE = setOf("avi", "rmvb", "rm", "flv", "wmv", "mpg", "mpeg", "vob", "asf", "ogv")
    }

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var hintView: TextView
    private lateinit var topBar: View
    private lateinit var audioManager: AudioManager

    private val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)
    private var speedIdx = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
    )
    private var resizeIdx = 0

    private var playbackKey = ""
    private var brightness = -1f
    private var startPosition = 0L
    private var subtitleUrl: String? = null
    private var locked = false
    private var lastResumeSave = 0L
    private var shouldRestorePlay = true

    // ---------- P2 播放列表 ----------
    private val client = LinNasClient()
    private var playlist = emptyList<Pair<String, String>>() // (vpath, name)
    private var playIdx = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val vpath = intent.getStringExtra(EXTRA_VPATH) ?: url
        playbackKey = vpath
        subtitleUrl = intent.getStringExtra(EXTRA_SUB)

        findViewById<TextView>(R.id.player_title).text = title
        hintView = findViewById(R.id.gestureHint)
        topBar = findViewById(R.id.playerTopBar)
        view = findViewById(R.id.player_view)
        view.keepScreenOn = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // P2 播放列表:多集时启用 上一集/下一集/列表 按钮
        playlist = parsePlaylist(intent.getStringExtra(EXTRA_PLAYLIST))
        playIdx = intent.getIntExtra(EXTRA_PLAY_INDEX, -1)
        if (playlist.size > 1 && playIdx in playlist.indices) {
            setupPlaylistButtons()
            updateTitleWithIndex(title)
        }

        // 续播:优先用传入起点,否则用本地记录
        startPosition = intent.getLongExtra(EXTRA_START, 0L)
        if (startPosition <= 0) startPosition = PlaybackStore.position(this, playbackKey)

        val p = ExoPlayer.Builder(this).build()
        player = p
        view.player = p
        p.setHandleAudioBecomingNoisy(true)
        p.setMediaItem(buildMediaItem(url, subtitleUrl))
        p.prepare()
        if (startPosition > 0) p.seekTo(startPosition)
        p.playWhenReady = true

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "播放出错:${error.errorCodeName},可尝试用系统播放器打开",
                    Toast.LENGTH_LONG,
                ).show()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    PlaybackStore.clear(this@PlayerActivity, playbackKey)
                    // P2:播完自动连播下一集
                    if (playIdx in 0 until playlist.size - 1) loadListIndex(playIdx + 1)
                }
            }
        })

        setupButtons(p)
        setupGestures()

        // 每 5 秒保存一次进度
        handler.postDelayed(saveProgressRunnable, 5000)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                PlaybackStore.save(this@PlayerActivity, playbackKey, p.currentPosition, p.duration)
            }
            handler.postDelayed(this, 5000)
        }
    }

    private fun buildMediaItem(url: String, sub: String?): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        sub?.let {
            try {
                builder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it))
                            // 服务端 /api/subtitle/content 统一输出 WebVTT;file:// 本地字幕按扩展名判断
                            .setMimeType(
                                // 服务端 /api/subtitle/content 统一输出 WebVTT;file:// 本地字幕按扩展名判断
                                if (it.startsWith("file:")) when (it.substringAfterLast('.', "").lowercase()) {
                                    "vtt" -> "text/vtt"
                                    "ass", "ssa" -> "text/x-ssa"
                                    else -> "application/x-subrip"
                                } else "text/vtt"
                            )
                            .setLanguage("zh")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            } catch (_: Exception) { }
        }
        return builder.build()
    }

    // ---------- P2 播放列表 ----------

    private fun parsePlaylist(json: String?): List<Pair<String, String>> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val vp = o.optString("vpath")
                if (vp.isBlank()) null else vp to o.optString("name", vp)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun setupPlaylistButtons() {
        findViewById<View>(R.id.btn_prev).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_next).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_list).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_prev).setOnClickListener { loadListIndex(playIdx - 1) }
        findViewById<View>(R.id.btn_next).setOnClickListener { loadListIndex(playIdx + 1) }
        findViewById<View>(R.id.btn_list).setOnClickListener { showListDialog() }
    }

    /** 切到播放列表第 i 集:本地缓存/转码/原流 + 服务端字幕 + 每集独立续播。 */
    private fun loadListIndex(i: Int) {
        if (i < 0 || i >= playlist.size) return
        val p = player ?: return
        val (vp, name) = playlist[i]
        playIdx = i
        playbackKey = vp
        updateTitleWithIndex(name)
        lifecycleScope.launch {
            val (url, sub) = withContext(Dispatchers.IO) {
                val ext = vp.substringAfterLast('.', "").lowercase()
                val local = OfflineStore.localFile(this@PlayerActivity, vp)
                val u = if (local != null) "file://" + local.absolutePath
                    else if (ext in TRANS_CODE) client.streamUrl(vp)
                    else client.rawUrl(vp)
                u to findSubFor(vp)
            }
            p.setMediaItem(buildMediaItem(url, sub))
            p.prepare()
            val resume = PlaybackStore.position(this@PlayerActivity, vp)
            if (resume > 0) p.seekTo(resume)
            p.playWhenReady = true
        }
    }

    private fun findSubFor(vp: String): String? {
        val tracks = try { client.subtitles(vp) } catch (_: Exception) { return null }
        if (tracks.isEmpty()) return null
        val pick = tracks.firstOrNull { it.lang.startsWith("zh") } ?: tracks.first()
        return try { client.absoluteUrl(pick.url) } catch (_: Exception) { null }
    }

    private fun updateTitleWithIndex(name: String) {
        val t = if (playlist.size > 1) "${playIdx + 1}/${playlist.size} · $name" else name
        findViewById<TextView>(R.id.player_title).text = t
    }

    private fun showListDialog() {
        val names = playlist.mapIndexed { i, item ->
            if (i == playIdx) "▶ " + item.second else item.second
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("播放列表(${playlist.size})")
            .setItems(names) { _, which -> loadListIndex(which) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun setupButtons(p: ExoPlayer) {
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_seek_back).setOnClickListener {
            p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
        }
        findViewById<Button>(R.id.btn_seek_fwd).setOnClickListener {
            p.seekTo(p.currentPosition + 10_000)
        }
        findViewById<Button>(R.id.btn_aspect).setOnClickListener {
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            view.resizeMode = resizeModes[resizeIdx]
            (it as Button).text = when (resizeIdx) { 0 -> "适应"; 1 -> "裁剪"; else -> "拉伸" }
        }
        val speedBtn = findViewById<Button>(R.id.btn_speed)
        speedBtn.text = "1.0x"
        speedBtn.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val s = speeds[speedIdx]
            p.setPlaybackSpeed(s)
            speedBtn.text = String.format(java.util.Locale.US, "%.2fx", s).replace(".00", ".0")
        }
        findViewById<Button>(R.id.btn_rotate).setOnClickListener {
            locked = !locked
            requestedOrientation = if (locked) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            toast(if (locked) "已锁定横屏" else "已恢复自动旋转")
        }
    }

    // ---------- 手势 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val root = findViewById<View>(R.id.playerRoot)
        var downX = 0f
        var downY = 0f
        var startVol = 0
        var startBright = 0f
        var mode = 0 // 0 未判定 1 音量 2 亮度 3 长按倍速
        var longPress = false

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { downX = e.x; downY = e.y; mode = 0; longPress = false; return true }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (view.isControllerFullyVisible) view.hideController() else view.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return false
                val half = view.width / 2
                if (e.x < half) {
                    p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                    showHint("快退 10 秒")
                } else {
                    p.seekTo(p.currentPosition + 10_000)
                    showHint("快进 10 秒")
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPress = true
                mode = 3
                player?.setPlaybackSpeed(2.0f)
                showHint("2.0x 倍速播放")
            }
        })

        root.setOnTouchListener { v, ev ->
            gesture.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    startVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBright = currentBrightness()
                    mode = 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (mode == 0 && !longPress && abs(dx) > 12 && abs(dx) > abs(dy)) {
                        // 水平拖动 = 快进/快退
                        mode = 4
                    } else if (mode == 0 && !longPress && abs(dy) > 24 && abs(dy) > abs(dx)) {
                        mode = if (dy < 0) 2 else 1
                    }
                    when (mode) {
                        1 -> { // 音量
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (-dy / view.height * max).toInt()
                            val nv = (startVol + delta).coerceIn(0, max)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                            showHint("音量 $nv / $max")
                        }
                        2 -> { // 亮度
                            val delta = (-dy / view.height * 1.0f)
                            val nb = (startBright + delta).coerceIn(0.01f, 1f)
                            setBrightness(nb)
                            showHint("亮度 ${(nb * 100).toInt()}%")
                        }
                        4 -> { // 水平拖动快进
                            val p = player ?: return@setOnTouchListener true
                            val total = p.duration.coerceAtLeast(1)
                            val deltaMs = (dx / view.width * total * 0.8).toLong()
                            val target = (p.currentPosition + deltaMs).coerceIn(0, total)
                            p.seekTo(target)
                            showHint(fmtTime(target))
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPress) {
                        player?.setPlaybackSpeed(speeds[speedIdx])
                        longPress = false
                        showHint("恢复正常速度")
                    }
                    hintView.postDelayed({ hintView.visibility = View.GONE }, 700)
                    true
                }
            }
            true
        }
    }

    private fun currentBrightness(): Float {
        val lp = window.attributes.screenBrightness
        return if (lp >= 0f) lp else 0.5f
    }

    private fun setBrightness(v: Float) {
        val lp = window.attributes
        lp.screenBrightness = v
        window.attributes = lp
    }

    private fun showHint(text: String) {
        hintView.text = text
        hintView.visibility = View.VISIBLE
        hintView.removeCallbacks(hideHint)
        hintView.postDelayed(hideHint, 700)
    }

    private val hideHint = Runnable { hintView.visibility = View.GONE }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- 画中画(P1) ----------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户按 Home 离开时自动进入画中画(视频播放中、API 26+)
        val p = player ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            p.isPlaying && p.playbackState != Player.STATE_ENDED
        ) {
            shouldRestorePlay = false
            try { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            view.hideController()
        } else {
            // 从画中画返回:恢复用户离开前的播放状态
            if (shouldRestorePlay) player?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        // 画中画模式下 onStop 不保存为「离开」,避免覆盖进度逻辑误暂停
        if (!isInPictureInPictureMode) {
            player?.pause()
        }
        player?.let { PlaybackStore.save(this, playbackKey, it.currentPosition, it.duration) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveProgressRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }
}
