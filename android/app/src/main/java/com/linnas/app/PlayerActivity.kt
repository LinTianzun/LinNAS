package com.linnas.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs

/**
 * 播放页(v1.8,B 站风格):
 *
 * 竖屏:
 *  - 顶部 16:9 纯黑视频(返回 + 中央播放键)
 *  - 下方可滚动内容区:标题、概要、进度+时间、操作行(倍速/字幕/画中画/更多)、简介、选集
 *
 * 横屏(全屏沉浸):
 *  - 点按切换控制层显隐,3 秒无操作自动隐藏
 *  - 左半屏竖向滑动调亮度、右半屏竖向滑动调音量、横向滑动调进度(松手生效)
 *  - 双击播放/暂停
 *
 * 面板:倍速(0.5~2.0)、字幕(关 + 各轨)、睡眠定时、选集抽屉。
 * 保留:画中画、续播记忆、自动连播、离线优先、字幕(VTT)。
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

        private const val HIDE_DELAY = 3000L

        /** 睡眠定时选项(分钟);0 表示关闭,-1 表示播完本集。 */
        private val SLEEP_OPTIONS = listOf(0, 10, 20, 30, 60, -1)
    }

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var hintView: TextView
    private lateinit var videoContainer: View
    private lateinit var contentScroll: View
    private lateinit var portraitVideoOverlay: View
    private lateinit var overlay: View
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPortraitPlay: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var seekBarLand: SeekBar
    private lateinit var tvPos: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvPosP: TextView
    private lateinit var tvDurP: TextView
    private lateinit var btnSpeed: MaterialButton
    private lateinit var btnSleep: MaterialButton
    private lateinit var btnEpisodes: MaterialButton
    private lateinit var audioManager: AudioManager
    private lateinit var landEpisodePanel: View
    private lateinit var rvLandEpisodes: RecyclerView
    private var longPressSpeed = 1f
    private var longPressing = false

    private val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = speeds.indexOf(1.0f).coerceAtLeast(0)
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
    private var shouldRestorePlay = true
    private var userSeeking = false
    private var introLoading = false

    // ---------- 睡眠定时 ----------
    private var sleepMinutes = 0       // 0 = 关
    private var sleepEndsAt = 0L       // 定时时刻(毫秒)
    private var sleepAtEnd = false     // 播完本集

    // ---------- 字幕轨 ----------
    private val subTracks = mutableListOf<SubtitleTrack>()
    private var subsLoaded = false

    // ---------- 播放列表 ----------
    private val client = LinNasClient()
    private var playlist = emptyList<Pair<String, String>>() // (vpath, name)
    private var playIdx = -1

    // ---------- 倍速 / 更多面板 ----------
    private var speedDialog: AlertDialog? = null

    private val handler = Handler(Looper.getMainLooper())

    // ---------- 进度与界面刷新 ----------

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

    // 睡眠定时检查
    private val sleepRunnable = object : Runnable {
        override fun run() {
            val remain = sleepEndsAt - System.currentTimeMillis()
            if (remain > 0L) {
                updateSleepButton()
                handler.postDelayed(this, minOf(remain, 15_000L))
            } else {
                sleepEndsAt = 0L
                updateSleepButton()
                player?.pause()
                toast("睡眠定时已到，已暂停")
                showControls()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题先于 super 设置,确保控件解析主题属性色
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
        currentMediaUrl = url
        currentSubtitleUrl = subtitleUrl

        // 视图绑定
        view = findViewById(R.id.player_view)
        videoContainer = findViewById(R.id.videoContainer)
        contentScroll = findViewById(R.id.contentScroll)
        portraitVideoOverlay = findViewById(R.id.portraitVideoOverlay)
        overlay = findViewById(R.id.playerOverlay)
        hintView = findViewById(R.id.gestureHint)
        btnPlay = findViewById(R.id.btn_play)
        btnPortraitPlay = findViewById(R.id.btn_portrait_play)
        seekBar = findViewById(R.id.player_seek)
        seekBarLand = findViewById(R.id.player_seek_land)
        tvPos = findViewById(R.id.tv_pos)
        tvDur = findViewById(R.id.tv_dur)
        tvPosP = findViewById(R.id.tv_pos_p)
        tvDurP = findViewById(R.id.tv_dur_p)
        btnSpeed = findViewById(R.id.btn_speed)
        btnSleep = findViewById(R.id.btn_sleep)
        btnEpisodes = findViewById(R.id.btn_episodes)
        landEpisodePanel = findViewById(R.id.landEpisodePanel)
        rvLandEpisodes = findViewById(R.id.rv_land_episodes)
        rvLandEpisodes.layoutManager = LinearLayoutManager(this)
        findViewById<View>(R.id.landEpisodeClose).setOnClickListener { toggleLandEpisodes(false) }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 标题
        findViewById<TextView>(R.id.tv_detail_title).text = title
        findViewById<TextView>(R.id.tv_detail_meta).text = "离线优先 · 自动连播 · 画中画"

        // 播放列表
        playlist = parsePlaylist(intent.getStringExtra(EXTRA_PLAYLIST))
        playIdx = intent.getIntExtra(EXTRA_PLAY_INDEX, -1)
        setupPlaylistButtons()
        refreshEpisodes()

        // 续播:优先用传入起点,否则用本地记录
        startPosition = intent.getLongExtra(EXTRA_START, 0L)
        if (startPosition <= 0) startPosition = PlaybackStore.position(this, playbackKey)

        // 构建播放器
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
                    if (sleepAtEnd) {
                        sleepAtEnd = false
                        updateSleepButton()
                        p.pause()
                        toast("本集播放完毕，睡眠定时已触发")
                        showControls()
                    } else if (playIdx in 0 until playlist.size - 1) {
                        loadListIndex(playIdx + 1)
                    } else {
                        showControls()
                    }
                }
                updatePlayIcon()
                updateProgress()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscape()) exitFullscreen() else finish()
            }
        })

        setupButtons(p)
        setupGestures()
        setupSeekBar(seekBar)
        setupSeekBar(seekBarLand)

        // 初始布局:竖屏 16:9 / 横屏全屏
        updateVideoSize()
        loadIntro()
        updateSpeedButton()
        updateSleepButton()
        showControls()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Manifest 已声明 configChanges,旋屏不重建,这里重排视频区
        updateVideoSize()
    }

    /** 竖屏视频区按 16:9 定高;横屏全屏沉浸并显示控制层。 */
    private fun updateVideoSize() {
        // 竖屏内容置于系统栏之下,横屏全屏沉浸
        WindowCompat.setDecorFitsSystemWindows(window, !isLandscape())
        val params = videoContainer.layoutParams
        if (isLandscape()) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            contentScroll.visibility = View.GONE
            overlay.visibility = View.VISIBLE
            portraitVideoOverlay.visibility = View.GONE
            applyImmersion(false)
        } else {
            val w = resources.displayMetrics.widthPixels
            params.height = (w * 9 / 16.0).toInt().coerceAtLeast(0)
            contentScroll.visibility = View.VISIBLE
            overlay.visibility = View.GONE
            portraitVideoOverlay.visibility = View.VISIBLE
            applyImmersion(true)
        }
        videoContainer.layoutParams = params
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // ---------- 控制层显隐 ----------

    private fun showControls() {
        if (!isLandscape()) return
        overlay.visibility = View.VISIBLE
        applyImmersion(false)
        scheduleHide()
    }

    private fun hideControls() {
        if (!isLandscape()) return
        overlay.visibility = View.GONE
    }

    private fun scheduleHide() {
        if (!isLandscape()) return
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, HIDE_DELAY)
    }

    /** 全屏时隐藏系统栏;竖屏恢复显示。 */
    private fun applyImmersion(showBars: Boolean) {
        if (isLandscape()) setSystemBarsVisible(showBars)
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
        if (!isLandscape()) return
        if (overlay.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayIcon()
        if (isLandscape()) showControls()
    }

    private fun updatePlayIcon() {
        val playing = player?.isPlaying == true
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (playing) "暂停" else "播放"
        btnPortraitPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPortraitPlay.contentDescription = if (playing) "暂停" else "播放"
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0L)
        val max = dur.toInt().coerceAtLeast(1)
        seekBar.max = max
        seekBarLand.max = max
        if (!userSeeking) {
            val cur = p.currentPosition.toInt().coerceIn(0, max)
            seekBar.progress = cur
            seekBarLand.progress = cur
        }
        tvPos.text = fmtTime(p.currentPosition)
        tvDur.text = fmtTime(dur)
        tvPosP.text = fmtTime(p.currentPosition)
        tvDurP.text = fmtTime(dur)
    }

    private fun setupSeekBar(sb: SeekBar) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPos.text = fmtTime(progress.toLong())
                    tvPosP.text = fmtTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seek: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seek: SeekBar) {
                userSeeking = false
                player?.seekTo(seek.progress.toLong())
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
        val hasList = playlist.size > 1
        findViewById<View>(R.id.btn_prev).visibility = if (hasList) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_next).visibility = if (hasList) View.VISIBLE else View.GONE
        btnEpisodes.visibility = if (hasList) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_prev_p).visibility = if (hasList) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_next_p).visibility = if (hasList) View.VISIBLE else View.GONE

        findViewById<View>(R.id.btn_prev).setOnClickListener { loadListIndex(playIdx - 1) }
        findViewById<View>(R.id.btn_next).setOnClickListener { loadListIndex(playIdx + 1) }
        findViewById<View>(R.id.btn_prev_p).setOnClickListener { loadListIndex(playIdx - 1) }
        findViewById<View>(R.id.btn_next_p).setOnClickListener { loadListIndex(playIdx + 1) }
        btnEpisodes.setOnClickListener { showEpisodeSheet() }
    }

    /** 切到播放列表第 i 集:本地缓存/转码/原流 + 服务端字幕 + 每集独立续播。 */
    private fun loadListIndex(i: Int) {
        if (i < 0 || i >= playlist.size) return
        val p = player ?: return
        val (vp, name) = playlist[i]
        playIdx = i
        playbackKey = vp
        // 切集后"播完本集"睡眠作废
        sleepAtEnd = false
        updateSleepButton()
        updateTitleWithIndex(name)
        refreshEpisodes()
        loadIntro()
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
            subTracks.clear()
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
        findViewById<TextView>(R.id.tv_detail_title).text = name
    }

    // ---------- 选集列表 ----------

    private fun refreshEpisodes() {
        val header = findViewById<TextView>(R.id.tv_episode_header)
        header.text = if (playlist.isEmpty()) "选集" else "选集(${playlist.size})"
        val rv = findViewById<RecyclerView>(R.id.rv_episodes)
        rv.visibility = if (playlist.isEmpty()) View.GONE else View.VISIBLE
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = EpisodeAdapter(playlist, playIdx) { i -> loadListIndex(i) }
    }

    /** 选集:横屏从右侧滑出面板;竖屏用底部弹层。 */
    private fun showEpisodeSheet() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isLandscape()) {
            toggleLandEpisodes(true)
            return
        }
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_player_sheet, null)
        content.findViewById<TextView>(R.id.sheet_title).text = "选集(${playlist.size})"
        val rv = content.findViewById<RecyclerView>(R.id.rv_sheet_episodes)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = EpisodeAdapter(playlist, playIdx) { i -> loadListIndex(i) }

        val sheet = BottomSheetDialog(this)
        sheet.setContentView(content)
        sheet.setOnDismissListener { if (player?.isPlaying == true) scheduleHide() }
        sheet.show()
    }

    /** v1.8.1:横屏右侧选集面板的显示/隐藏。 */
    private fun toggleLandEpisodes(show: Boolean) {
        if (show) {
            findViewById<TextView>(R.id.landEpisodeTitle).text = "选集(${playlist.size})"
            rvLandEpisodes.adapter = EpisodeAdapter(playlist, playIdx) { i ->
                toggleLandEpisodes(false)
                loadListIndex(i)
            }
            landEpisodePanel.visibility = View.VISIBLE
            landEpisodePanel.translationX = dp(320).toFloat()
            landEpisodePanel.animate().translationX(0f).setDuration(180).start()
        } else {
            landEpisodePanel.animate().translationX(dp(320).toFloat()).setDuration(160)
                .withEndAction { landEpisodePanel.visibility = View.GONE }
                .start()
        }
    }

    private inner class EpisodeAdapter(
        private val items: List<Pair<String, String>>,
        private val selected: Int,
        private val onPick: (Int) -> Unit,
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val index: TextView = root.findViewById(R.id.ep_index)
            val name: TextView = root.findViewById(R.id.ep_name)
            val badge: TextView = root.findViewById(R.id.ep_badge)
            val thumb: android.widget.ImageView = root.findViewById(R.id.ep_thumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ctx = holder.itemView.context
            val cur = position == selected
            holder.index.text = (position + 1).toString()
            holder.name.text = items[position].second
            holder.badge.visibility = if (cur) View.VISIBLE else View.GONE
            // 选集封面缩略图(服务端 ffmpeg 抽帧)
            val vp = items[position].first
            holder.thumb.load(client.thumbUrl(vp, 240)) {
                crossfade(true)
                placeholder(R.drawable.bg_player_action)
                error(R.drawable.bg_player_action)
            }
            holder.name.setTextColor(
                if (cur) ThemeColors.color(ctx, R.attr.lPrimary) else ThemeColors.text(ctx)
            )
            holder.index.setTextColor(
                if (cur) ThemeColors.color(ctx, R.attr.lPrimary) else ThemeColors.textDim(ctx)
            )
            holder.root.setOnClickListener { onPick(position) }
        }
    }

    // ---------- 简介 ----------

    private fun loadIntro() {
        if (introLoading) return
        introLoading = true
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try { client.info(playbackKey) } catch (_: Exception) { null }
            }
            introLoading = false
            val tv = findViewById<TextView>(R.id.tv_intro) ?: return@launch
            tv.text = buildIntroText(info)
        }
    }

    private fun buildIntroText(info: FileInfo?): String {
        val lines = mutableListOf<String>()
        lines.add("路径：${(info?.path ?: "").ifBlank { playbackKey }}")
        val size = if (info != null && info.size > 0) info.size else introSizeFallback()
        lines.add("大小：${if (size > 0) fmtSize(size) else "未知"}")
        if (info != null && info.width > 0 && info.height > 0) {
            lines.add("分辨率：${info.width}×${info.height}")
        }
        if (info != null && info.duration > 0) {
            lines.add("时长：${fmtTime((info.duration * 1000).toLong())}")
        }
        if (!info?.videoCodec.isNullOrBlank()) {
            val audio = info.audioCodec
            lines.add("编码：${info.videoCodec}${if (audio.isNotBlank()) " / $audio" else ""}")
        }
        lines.add("服务器：${serverLabel()}")
        return lines.joinToString("\n")
    }

    /** /api/info 失败时用离线登记表里的大小兜底。 */
    private fun introSizeFallback(): Long =
        try { OfflineStore.get(this, playbackKey)?.size ?: 0L } catch (_: Exception) { 0L }

    private fun serverLabel(): String {
        val base = ServerConfig.baseUrl.trim()
        return base.ifBlank { "未连接" }
    }

    private fun fmtSize(bytes: Long): String {
        val v = bytes.toDouble()
        return when {
            v >= (1L shl 30) -> String.format(Locale.US, "%.2f GB", v / (1L shl 30))
            v >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", v / (1L shl 20))
            v >= (1L shl 10) -> String.format(Locale.US, "%.1f KB", v / (1L shl 10))
            else -> "$bytes B"
        }
    }

    // ---------- 面板 ----------

    private fun updateSpeedButton() {
        btnSpeed.text = String.format(Locale.US, "%.2fx", speeds[speedIdx]).replace(".00", ".0")
    }

    /** 倍速面板:0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0。 */
    private fun showSpeedPanel() {
        handler.removeCallbacks(hideControlsRunnable)
        speedDialog?.dismiss()
        val labels = speeds.map { s -> String.format(Locale.US, "%.2fx", s).replace(".00", ".0") }.toTypedArray()
        speedDialog = AlertDialog.Builder(this)
            .setTitle("倍速播放")
            .setSingleChoiceItems(labels, speedIdx) { _, which ->
                speedIdx = which
                player?.setPlaybackSpeed(speeds[which])
                updateSpeedButton()
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { speedDialog = null }
            .show()
    }

    /** 字幕面板:关闭 + 各字幕轨。 */
    private fun showSubtitlePanel() {
        handler.removeCallbacks(hideControlsRunnable)
        if (!subsLoaded) {
            subsLoaded = true
            lifecycleScope.launch {
                val tracks = withContext(Dispatchers.IO) {
                    try { client.subtitles(playbackKey) } catch (_: Exception) { emptyList() }
                }
                subTracks.clear()
                subTracks.addAll(tracks)
                showSubtitlePanel()
            }
            return
        }
        val labels = arrayListOf<String>()
        labels.add("关闭")
        subTracks.forEach { labels.add(it.label.ifBlank { it.name }.ifBlank { it.lang }) }
        var checked = 0
        if (currentSubtitleUrl != null) {
            val pos = subTracks.indexOfFirst { track ->
                try { client.absoluteUrl(track.url) == currentSubtitleUrl } catch (_: Exception) { false }
            }
            if (pos >= 0) checked = pos + 1
        }
        AlertDialog.Builder(this)
            .setTitle("字幕")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { _, which ->
                if (which == 0) {
                    applySubtitle(null)
                } else {
                    val track = subTracks[which - 1]
                    val url = try { client.absoluteUrl(track.url) } catch (_: Exception) { null }
                    applySubtitle(url)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun applySubtitle(sub: String?) {
        val p = player ?: return
        currentSubtitleUrl = sub
        val pos = p.currentPosition.coerceAtLeast(0)
        p.setMediaItem(buildMediaItem(currentMediaUrl, sub), pos)
        p.prepare()
        p.playWhenReady = p.isPlaying
    }

    private fun updateSleepButton() {
        btnSleep.text = when {
            sleepAtEnd -> "本集结束"
            sleepMinutes > 0 -> "${sleepMinutes}分钟"
            else -> "定时"
        }
    }

    /** 睡眠定时面板:关 / 10 / 20 / 30 / 60 分钟 / 播完本集。 */
    private fun showSleepPanel() {
        handler.removeCallbacks(hideControlsRunnable)
        val labels = SLEEP_OPTIONS.map {
            when (it) {
                0 -> "关闭"
                -1 -> "播完本集"
                else -> "${it} 分钟"
            }
        }.toTypedArray()
        var checked = SLEEP_OPTIONS.indexOf(sleepMinutes)
        if (sleepAtEnd) checked = SLEEP_OPTIONS.indexOf(-1)
        if (checked < 0) checked = 0
        AlertDialog.Builder(this)
            .setTitle("睡眠定时")
            .setSingleChoiceItems(labels, checked) { _, which ->
                applySleepOption(SLEEP_OPTIONS[which])
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun applySleepOption(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        sleepAtEnd = minutes == -1
        sleepMinutes = if (minutes == -1) 0 else minutes
        sleepEndsAt = if (minutes in 1..60) System.currentTimeMillis() + minutes * 60_000L else 0L
        if (sleepMinutes > 0) {
            handler.postDelayed(sleepRunnable, minOf(sleepEndsAt - System.currentTimeMillis(), 15_000L))
            toast("${sleepMinutes} 分钟后自动暂停")
        } else if (sleepAtEnd) {
            toast("本集播放完毕后暂停")
        } else {
            toast("已关闭睡眠定时")
        }
        updateSleepButton()
    }

    /**
     * 「更多」面板:图标 + 文案网格(不再用简陋的列表弹窗)。
     * 画中画、睡眠定时、字幕等入口都收在这里,横屏/竖屏通用。
     */
    private fun showMorePanel() {
        handler.removeCallbacks(hideControlsRunnable)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_player_more, null)
        val grid = view.findViewById<LinearLayout>(R.id.more_grid)
        val entries = listOf(
            Triple(R.drawable.ic_player_speed, "倍速") { showSpeedPanel() },
            Triple(R.drawable.ic_player_subtitle, "字幕") { showSubtitlePanel() },
            Triple(R.drawable.ic_player_pip, "画中画") { enterPictureInPictureIfPossible() },
            Triple(R.drawable.ic_player_sleep, "定时") { showSleepPanel() },
            Triple(R.drawable.ic_player_aspect, "画面比例") { cycleResize() },
            Triple(R.drawable.ic_rotate, "锁定横屏") { toggleRotationLock() },
            Triple(R.drawable.ic_more, "外部播放器") { openExternalPlayer() },
        )
        entries.chunked(4).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
            }
            rowItems.forEach { (iconRes, label, action) ->
                row.addView(moreCell(iconRes, label, action),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row)
        }
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(view)
        sheet.setOnDismissListener { if (player?.isPlaying == true) scheduleHide() }
        sheet.show()
    }

    /** 「更多」面板里的一格:图标 + 文案。 */
    private fun moreCell(iconRes: Int, label: String, action: () -> Unit): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_ripple)
            setOnClickListener { action() }
        }
        cell.addView(android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(ThemeColors.primary(this@PlayerActivity))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        cell.addView(TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(ThemeColors.text(this@PlayerActivity))
            setPadding(0, dp(6), 0, 0)
        })
        return cell
    }

    /** 进入横屏全屏(B 站式):切横屏、隐藏系统栏、显示控制层。 */
    private fun enterFullscreen() {
        locked = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        showHint("横屏播放")
    }

    /** 退出横屏回竖屏详情页。 */
    private fun exitFullscreen() {
        locked = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /** 锁定/恢复横屏(沿用 v1.5 的旋转锁定能力)。 */
    private fun toggleRotationLock() {
        locked = !locked
        requestedOrientation = if (locked) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        showHint(if (locked) "已锁定横屏" else "已恢复自动旋转")
    }

    private fun cycleResize() {
        resizeIdx = (resizeIdx + 1) % resizeModes.size
        view.resizeMode = resizeModes[resizeIdx]
        val label = when (resizeIdx) { 0 -> "适应"; 1 -> "裁剪"; else -> "拉伸" }
        (findViewById<View>(R.id.btn_aspect) as MaterialButton).text = label
        showHint("画面:$label")
    }

    // ---------- 按钮 ----------

    private fun setupButtons(p: ExoPlayer) {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_back_portrait).setOnClickListener { finish() }
        // v1.8.0 横屏播放:竖屏点全屏 → 横屏沉浸;横屏点退出 → 回竖屏
        findViewById<View>(R.id.btn_fullscreen).setOnClickListener { enterFullscreen() }
        findViewById<View>(R.id.btn_exit_fullscreen).setOnClickListener { exitFullscreen() }
        btnPlay.setOnClickListener { togglePlay() }
        btnPortraitPlay.setOnClickListener { togglePlay() }
        findViewById<MaterialButton>(R.id.btn_seek_back).setOnClickListener {
            seekBy(p, -10_000)
            showHint("快退 10 秒")
        }
        findViewById<MaterialButton>(R.id.btn_seek_fwd).setOnClickListener {
            seekBy(p, 10_000)
            showHint("快进 10 秒")
        }
        findViewById<View>(R.id.btn_aspect).setOnClickListener { cycleResize() }

        btnSpeed.text = "1.0x"
        btnSpeed.setOnClickListener { showSpeedPanel() }
        findViewById<View>(R.id.btn_subtitle).setOnClickListener { showSubtitlePanel() }
        btnEpisodes.setOnClickListener { showEpisodeSheet() }
        btnSleep.setOnClickListener { showSleepPanel() }
        findViewById<View>(R.id.btn_pip).setOnClickListener { enterPictureInPictureIfPossible() }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMorePanel() }

        // 竖屏操作行
        findViewById<View>(R.id.act_speed).setOnClickListener { showSpeedPanel() }
        findViewById<View>(R.id.act_subtitle).setOnClickListener { showSubtitlePanel() }
        findViewById<View>(R.id.act_pip).setOnClickListener { enterPictureInPictureIfPossible() }
        findViewById<View>(R.id.act_more).setOnClickListener { showMorePanel() }
    }

    private fun enterPictureInPictureIfPossible() {
        val p = player ?: return
        if (!isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build())
            } catch (_: Exception) {
                toast("当前场景无法进入画中画")
            }
        } else if (isInPictureInPictureMode) {
            // 退出画中画:结束页面即可关闭 PiP 小窗(兼容性最好)
            try {
                finish()
            } catch (_: Exception) {
            }
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
        var downX = 0f
        var downY = 0f
        var startVol = 0
        var startBright = 0f
        var mode = 0 // 0 未判定 1 音量 2 亮度 3 水平拖动

        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { downX = e.x; downY = e.y; mode = 0; return true }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLandscape()) toggleControls() else togglePlay()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlay()
                return true
            }

            /** v1.8.1:长按视频临时 2 倍速,松手恢复原倍速。 */
            override fun onLongPress(e: MotionEvent) {
                val p = player ?: return
                if (longPressing) return
                longPressing = true
                longPressSpeed = p.playbackParameters.speed
                p.setPlaybackSpeed(2f)
                showHint("2.0x 倍速播放")
            }
        })

        videoContainer.setOnTouchListener { _, ev ->
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
                    // v1.8.1:竖屏也支持左右滑动调进度(左右滑动优先于上下滑动)
                    if (!isLandscape() && abs(dx) <= abs(dy) && abs(dy) > 24) {
                        return@setOnTouchListener true
                    }
                    if (mode == 0) {
                        val half = videoContainer.width / 2f
                        if (abs(dx) > 12 && abs(dx) >= abs(dy)) {
                            mode = 3
                        } else if (abs(dy) > 24 && abs(dy) > abs(dx)) {
                            mode = if (downX < half) 2 else 1 // 左半亮度,右半音量
                        }
                    }
                    when (mode) {
                        1 -> { // 音量
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val delta = (-dy / videoContainer.height * max).toInt()
                            val nv = (startVol + delta).coerceIn(0, max)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                            showHint("音量 $nv / $max")
                        }
                        2 -> { // 亮度
                            val delta = (-dy / videoContainer.height * 1.0f)
                            val nb = (startBright + delta).coerceIn(0.01f, 1f)
                            setBrightness(nb)
                            showHint("亮度 ${(nb * 100).toInt()}%")
                        }
                        3 -> { // 水平拖动进度(松手生效)
                            val p = player ?: return@setOnTouchListener true
                            val total = p.duration.coerceAtLeast(1)
                            val deltaMs = (dx / videoContainer.width * total).toLong()
                            val target = (p.currentPosition + deltaMs).coerceIn(0, total)
                            val deltaSec = deltaMs / 1000
                            // v1.8.1:提示同时给出目标时间与偏移量,滑动时一目了然
                            showHint("→ ${fmtTime(target)}  (${if (deltaSec >= 0) "+" else ""}$deltaSec 秒)")
                            lastSeekPreview = target
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressing) {
                        longPressing = false
                        player?.setPlaybackSpeed(longPressSpeed)
                    }
                    if (mode == 3 && lastSeekPreview != null) {
                        player?.seekTo(lastSeekPreview!!)
                        updateProgress()
                        lastSeekPreview = null
                    }
                    mode = 0
                    hintView.postDelayed({ hintView.visibility = View.GONE }, 700)
                    true
                }
            }
            true
        }
    }

    private var lastSeekPreview: Long? = null

    private fun currentBrightness(): Float {
        val lp = window.attributes
        return if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f
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

    /** dp 转像素(工程内常用辅助)。 */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- 播放错误与外部打开 ----------

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
            updateVideoSize()
            showControls()
            if (shouldRestorePlay) player?.play()
        }
    }

    override fun onStart() {
        super.onStart()
        if (player != null) {
            handler.post(uiRunnable)
            handler.postDelayed(saveProgressRunnable, 5000)
            if (!isInPictureInPictureMode) showControls()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(uiRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(sleepRunnable)
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
        speedDialog?.dismiss()
        handler.removeCallbacks(saveProgressRunnable)
        handler.removeCallbacks(uiRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(sleepRunnable)
        player?.let { PlaybackStore.save(this, playbackKey, it.currentPosition, it.duration) }
        player?.release()
        player = null
        super.onDestroy()
    }
}
