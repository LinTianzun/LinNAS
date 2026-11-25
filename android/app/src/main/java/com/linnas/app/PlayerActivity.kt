package com.linnas.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs

/**
 * 内置播放页(v1.5,主流播放器布局):
 *  - 纯黑舞台 + 顶部/底部渐变控制层,3.5 秒自动隐藏,点击画面切换
 *  - 中部 上一集 / -10s / 播放暂停 / +10s / 下一集
 *  - 底部进度条 + 时间 + 倍速/画面/旋转
 *  - 手势:双击左右快退快进、上下滑音量亮度、水平拖进度、长按 2 倍速
 *  - 续播记忆、同名字幕、画中画、锁横屏
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"
        const val EXTRA_VPATH = "vpath"
        const val EXTRA_SUB = "subtitle"
        const val EXTRA_PLAYLIST = "playlist"    // JSON: [{"name":..,"vpath":..}]
        const val EXTRA_PLAY_INDEX = "playIndex" // 当前项下标

        /** 需服务端转码才能播的旧格式。 */
        private val TRANS_CODE = setOf("avi", "rmvb", "rm", "flv", "wmv", "mpg", "mpeg", "vob", "asf", "ogv")

        private const val HIDE_DELAY = 3500L
    }

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var hintView: TextView
    private lateinit var overlay: View
    private lateinit var btnPlay: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
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
    private var currentMediaUrl = ""
    private var currentSubtitleUrl: String? = null
    private var errorDialog: AlertDialog? = null
    private var locked = false
    private var lastResumeSave = 0L
    private var shouldRestorePlay = true
    private var userSeeking = false

    // ---------- 播放列表 ----------
    private val client = LinNasClient()
    private var playlist = emptyList<Pair<String, String>>() // (vpath, name)
    private var playIdx = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_LinNas_Player)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val vpath = intent.getStringExtra(EXTRA_VPATH) ?: url
        playbackKey = vpath
        subtitleUrl = intent.getStringExtra(EXTRA_SUB)
        currentMediaUrl = url
        currentSubtitleUrl = subtitleUrl

        findViewById<TextView>(R.id.player_title).text = title
        hintView = findViewById(R.id.gestureHint)
        overlay = findViewById(R.id.playerOverlay)
        btnPlay = findViewById(R.id.btn_play)
        seekBar = findViewById(R.id.player_seek)
        tvPos = findViewById(R.id.tv_pos)
        tvDur = findViewById(R.id.tv_dur)
        view = findViewById(R.id.player_view)
        view.keepScreenOn = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 播放列表:多集时启用 上一集/下一集/列表 按钮
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
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setMediaItem(buildMediaItem(url, subtitleUrl))
        p.prepare()
        if (startPosition > 0) p.seekTo(startPosition)
        p.playWhenReady = true

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showPlaybackError(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayIcon()
                if (isPlaying) scheduleHide() else showControls()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    PlaybackStore.clear(this@PlayerActivity, playbackKey)
                    if (playIdx in 0 until playlist.size - 1) {
                        loadListIndex(playIdx + 1)
                    } else {
                        showControls()
                    }
                }
                updatePlayIcon()
                updateProgress()
            }
        })

        setupButtons(p)
        setupGestures()
        setupSeekBar(p)

        showControls()
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

    private val uiRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val hideControlsRunnable = Runnable { if (player?.isPlaying == true) hideControls() }

    override fun onStart() {
        super.onStart()
        if (player != null) {
            handler.post(uiRunnable)
            handler.postDelayed(saveProgressRunnable, 5000)
            if (!isInPictureInPictureMode) showControls()
        }
    }

    private fun showPlaybackError(error: PlaybackException) {
        if (isFinishing || isDestroyed) return
        showControls()
        player?.let {
            PlaybackStore.save(this, playbackKey, it.currentPosition, it.duration)
        }
        errorDialog?.dismiss()
        errorDialog = AlertDialog.Builder(this)
            .setTitle("播放失败")
            .setMessage("${error.errorCodeName}。可重试，或改用其他播放器打开。")
            .setPositiveButton("重试") { _, _ -> retryPlayback() }
            .setNeutralButton("其他播放器") { _, _ -> openExternalPlayer() }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { errorDialog = null }
            .show()
    }

    private fun retryPlayback() {
        val p = player ?: return
        val url = currentMediaUrl
        if (url.isBlank()) return
        val position = p.currentPosition.coerceAtLeast(0)
        p.setMediaItem(buildMediaItem(url, currentSubtitleUrl), position)
        p.prepare()
        p.playWhenReady = true
        showControls()
    }

    private fun openExternalPlayer() {
        if (currentMediaUrl.isBlank()) return
        val uri = android.net.Uri.parse(currentMediaUrl)
        val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        val mime = if (extension in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg")) {
            "audio/*"
        } else {
            "video/*"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime))
        } catch (_: ActivityNotFoundException) {
            toast("没有可用的外部播放器")
        }
    }

    // ---------- 控制层显隐 ----------

    private fun showControls() {
        overlay.visibility = View.VISIBLE
        setSystemBarsVisible(false)
        scheduleHide()
    }

    private fun hideControls() {
        overlay.visibility = View.GONE
        setSystemBarsVisible(true)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, HIDE_DELAY)
    }

    /** 控制层隐藏时全屏沉浸(隐藏状态栏/导航栏),显示时恢复。 */
    private fun setSystemBarsVisible(visible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = window.insetsController ?: return
            if (visible) {
                c.show(WindowInsets.Type.systemBars())
            } else {
                c.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (visible) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
    }

    private fun toggleControls() {
        if (overlay.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun updatePlayIcon() {
        val playing = player?.isPlaying == true
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (playing) "暂停" else "播放"
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0L)
        seekBar.max = dur.toInt().coerceAtLeast(1)
        if (!userSeeking) {
            seekBar.progress = p.currentPosition.toInt().coerceIn(0, seekBar.max)
        }
        tvPos.text = fmtTime(p.currentPosition)
        tvDur.text = fmtTime(dur)
    }

    private fun setupSeekBar(p: ExoPlayer) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) tvPos.text = fmtTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                p.seekTo(sb.progress.toLong())
                scheduleHide()
            }
        })
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

    // ---------- 播放列表 ----------

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
        findViewById<View>(R.id.btn_list).setOnClickListener {
            handler.removeCallbacks(hideControlsRunnable)
            showListDialog()
        }
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
            currentMediaUrl = url
            currentSubtitleUrl = sub
            p.setMediaItem(buildMediaItem(url, sub))
            p.prepare()
            val resume = PlaybackStore.position(this@PlayerActivity, vp)
            if (resume > 0) p.seekTo(resume)
            p.playWhenReady = true
            showControls()
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
            .setOnDismissListener { if (player?.isPlaying == true) scheduleHide() }
            .show()
    }

    private fun setupButtons(p: ExoPlayer) {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnPlay.setOnClickListener {
            if (p.isPlaying) p.pause() else p.play()
            updatePlayIcon()
            showControls()
        }
        findViewById<MaterialButton>(R.id.btn_seek_back).setOnClickListener {
            seekBy(p, -10_000)
            showHint("快退 10 秒")
        }
        findViewById<MaterialButton>(R.id.btn_seek_fwd).setOnClickListener {
            seekBy(p, 10_000)
            showHint("快进 10 秒")
        }
        findViewById<MaterialButton>(R.id.btn_aspect).setOnClickListener {
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            view.resizeMode = resizeModes[resizeIdx]
            val label = when (resizeIdx) { 0 -> "适应"; 1 -> "裁剪"; else -> "拉伸" }
            (it as MaterialButton).text = label
            showHint("画面:$label")
        }
        val speedBtn = findViewById<MaterialButton>(R.id.btn_speed)
        speedBtn.text = "1.0x"
        speedBtn.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val s = speeds[speedIdx]
            p.setPlaybackSpeed(s)
            val label = String.format(Locale.US, "%.2fx", s).replace(".00", ".0")
            speedBtn.text = label
            showHint("倍速 $label")
        }
        findViewById<MaterialButton>(R.id.btn_rotate).setOnClickListener {
            locked = !locked
            requestedOrientation = if (locked) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            showHint(if (locked) "已锁定横屏" else "已恢复自动旋转")
        }
    }

    private fun seekBy(p: ExoPlayer, delta: Long) {
        val dur = p.duration
        val target = (p.currentPosition + delta).coerceAtLeast(0L)
        p.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
        updateProgress()
        scheduleHide()
    }

    // ---------- 手势 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val root = findViewById<View>(R.id.playerRoot)
        var downX = 0f
        var downY = 0f
        var startVol = 0
        var startBright = 0f
        var mode = 0 // 0 未判定 1 音量 2 亮度 3 长按倍速 4 水平拖动
        var longPress = false

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { downX = e.x; downY = e.y; mode = 0; longPress = false; return true }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return false
                val half = view.width / 2
                if (e.x < half) {
                    seekBy(p, -10_000)
                    showHint("快退 10 秒")
                } else {
                    seekBy(p, 10_000)
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

        root.setOnTouchListener { _, ev ->
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
                        4 -> { // 水平拖动进度
                            val p = player ?: return@setOnTouchListener true
                            val total = p.duration.coerceAtLeast(1)
                            val deltaMs = (dx / view.width * total * 0.8).toLong()
                            val target = (p.currentPosition + deltaMs).coerceIn(0, total)
                            p.seekTo(target)
                            updateProgress()
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
        hintView.postDelayed(hideHint, 900)
    }

    private val hideHint = Runnable { hintView.visibility = View.GONE }

    private fun fmtTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- 画中画 ----------

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
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
            hideControls()
            overlay.visibility = View.GONE
        } else {
            showControls()
            if (shouldRestorePlay) player?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(uiRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        if (!isInPictureInPictureMode) {
            handler.removeCallbacks(saveProgressRunnable)
            player?.pause()
        } else {
            handler.removeCallbacks(saveProgressRunnable)
            handler.postDelayed(saveProgressRunnable, 5000)
        }
        player?.let { PlaybackStore.save(this, playbackKey, it.currentPosition, it.duration) }
    }

    override fun onDestroy() {
        errorDialog?.dismiss()
        player?.let { PlaybackStore.save(this, playbackKey, it.currentPosition, it.duration) }
        handler.removeCallbacks(saveProgressRunnable)
        handler.removeCallbacks(uiRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }
}
