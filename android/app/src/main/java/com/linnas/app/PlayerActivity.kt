package com.linnas.app

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
import com.google.android.material.materialswitch.MaterialSwitch
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

        /** 「定时关闭」分段预设(分钟);最后一项 -2 代表「自定义」。 */
        private val SLEEP_PRESETS = listOf(0, 15, 30, 60, -2)
    }

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var hintView: TextView
    private lateinit var videoContainer: View
    private lateinit var contentScroll: View
    private lateinit var episodeArea: View
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
    private lateinit var btnSpeed: TextView
    private lateinit var btnEpisodes: TextView
    private lateinit var tvQuality: TextView
    private lateinit var audioManager: AudioManager
    private lateinit var landEpisodePanel: View
    private lateinit var landEpisodeScrim: View
    private lateinit var rvLandEpisodes: RecyclerView
    // 横屏右侧「更多/二级面板」容器(与选集面板同款玻璃样式,两者互斥)
    private lateinit var playerMorePanel: View
    private lateinit var playerMoreScrim: View
    private lateinit var playerMoreTitle: TextView
    private lateinit var playerMoreBody: ViewGroup
    private lateinit var detailBody: View
    private lateinit var tvDetailToggle: TextView
    private lateinit var imgDetailChevron: ImageView
    private var longPressSpeed = 1f
    private var longPressing = false

    /** linnas_ui:更多面板的开关/分段与竖屏详情折叠状态都存在这里。 */
    private val uiPrefs by lazy { getSharedPreferences("linnas_ui", Context.MODE_PRIVATE) }
    private var autoNext = true              // 自动连播(播完自动下一集)
    private var loopMode = "none"            // 循环播放:single | list | none
    private var mirror = false               // 镜像翻转
    private var landEpisodesOpen = false     // 横屏选集面板是否打开(打开时暂停控制层自动隐藏)

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

    // ---------- 更多 / 二级面板 ----------
    // 横屏右侧面板(与选集面板互斥):更多、倍速、字幕、播放错误都复用这块玻璃浮层
    private var landSheetOpen = false                   // 横屏右侧面板是否打开(打开时暂停控制层自动隐藏)
    private var moreSheet: BottomSheetDialog? = null    // 竖屏「更多」弹窗(内容较复杂,单独持有以便关闭)
    private var sheetPanel: BottomSheetDialog? = null   // 竖屏二级面板(倍速/字幕/播放错误)

    private val handler = Handler(Looper.getMainLooper())

    // ---------- 进度与界面刷新 ----------

    private var lastServerSave = 0L

    /** 把进度同步到服务端（跨设备续播）；仅同步虚拟路径，离线/本地播放跳过。 */
    private fun saveProgressToServer(position: Long, duration: Long, force: Boolean = false) {
        val vp = playbackKey
        if (!vp.startsWith("/") || duration <= 0) return
        val now = System.currentTimeMillis()
        if (!force && now - lastServerSave < 15_000) return
        lastServerSave = now
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { client.putPlayback(vp, position, duration) } }
        }
    }

    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.isPlaying) {
                PlaybackStore.save(this@PlayerActivity, playbackKey, p.currentPosition, p.duration)
                saveProgressToServer(p.currentPosition, p.duration)
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

    // 选集面板打开期间不自动隐藏控制层(否则面板关不掉)
    private val hideControlsRunnable = Runnable {
        if (landEpisodesOpen || landSheetOpen) return@Runnable
        if (landSheetOpen) return@Runnable
        if (player?.isPlaying == true) hideControls()
    }

    // 睡眠定时检查
    private val sleepRunnable = object : Runnable {
        override fun run() {
            val remain = sleepEndsAt - System.currentTimeMillis()
            if (remain > 0L) {
                // 剩余时间只在「更多」弹窗打开时由分段控件展示,这里只需继续轮询
                handler.postDelayed(this, minOf(remain, 15_000L))
            } else {
                sleepEndsAt = 0L
                sleepMinutes = 0
                // 计时结束即取消,不再持久化,避免下次进页面又莫名暂停
                uiPrefs.edit().putInt("player_sleep", 0).apply()
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
        episodeArea = findViewById(R.id.episodeArea)
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
        btnEpisodes = findViewById(R.id.btn_episodes)
        tvQuality = findViewById(R.id.tv_quality)
        landEpisodePanel = findViewById(R.id.landEpisodePanel)
        landEpisodeScrim = findViewById(R.id.landEpisodeScrim)
        rvLandEpisodes = findViewById(R.id.rv_land_episodes)
        rvLandEpisodes.layoutManager = LinearLayoutManager(this)
        // 选集面板必须能关:关闭按钮 + 点面板外 scrim 都收起(原来只绑了按钮,面板一开容易退不出去)
        findViewById<View>(R.id.landEpisodeClose).setOnClickListener { toggleLandEpisodes(false) }
        landEpisodeScrim.setOnClickListener { toggleLandEpisodes(false) }
        // 横屏右侧「更多/二级面板」:关闭按钮 + 点面板外 scrim 都能收起
        playerMorePanel = findViewById(R.id.playerMorePanel)
        playerMoreScrim = findViewById(R.id.playerMoreScrim)
        playerMoreTitle = findViewById(R.id.playerMoreTitle)
        playerMoreBody = findViewById(R.id.playerMoreBody)
        playerMoreScrim.setOnClickListener { closeLandSheet() }
        findViewById<View>(R.id.playerMoreClose).setOnClickListener { closeLandSheet() }
        // 竖屏「简介」折叠条
        detailBody = findViewById(R.id.detailBody)
        tvDetailToggle = findViewById(R.id.tv_detail_toggle)
        imgDetailChevron = findViewById(R.id.img_detail_chevron)
        findViewById<View>(R.id.detailToggleRow).setOnClickListener {
            applyDetailCollapsed(detailBody.visibility != View.GONE, true)
        }
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
        if (startPosition <= 0) {
            startPosition = PlaybackStore.position(this, playbackKey)
            if (startPosition <= 0 && playbackKey.startsWith("/")) {
                lifecycleScope.launch {
                    val remote = withContext(Dispatchers.IO) {
                        runCatching { client.getPlayback(playbackKey) }.getOrNull()
                    }
                    if (remote != null && remote.positionMs > startPosition) {
                        startPosition = remote.positionMs
                        player?.seekTo(remote.positionMs)
                    }
                }
            }
        }

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
                    saveProgressToServer(0, p.duration, true)
                    when {
                        // 「播完本集」睡眠优先,触发后不再连播
                        sleepAtEnd -> {
                            sleepAtEnd = false
                            uiPrefs.edit().putInt("player_sleep", 0).apply()
                            p.pause()
                            toast("本集播放完毕，睡眠定时已触发")
                            showControls()
                        }
                        // 单集循环:从头再播
                        loopMode == "single" -> {
                            p.seekTo(0)
                            p.play()
                        }
                        // 列表循环:整张播放列表循环
                        loopMode == "list" -> {
                            if (playlist.size > 1) {
                                loadListIndex((playIdx.coerceAtLeast(0) + 1) % playlist.size)
                            } else {
                                p.seekTo(0)
                                p.play()
                            }
                        }
                        // 自动连播:播到列表末尾为止;关闭则停在当前集
                        autoNext && playIdx >= 0 && playIdx < playlist.size - 1 -> loadListIndex(playIdx + 1)
                        else -> showControls()
                    }
                }
                updatePlayIcon()
                updateProgress()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 面板开着时,返回键先关面板(再退全屏/页面),避免一下跳走
                when {
                    landSheetOpen -> closeLandSheet()
                    landEpisodesOpen -> toggleLandEpisodes(false)
                    isLandscape() -> exitFullscreen()
                    else -> finish()
                }
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
        restoreUiPrefs()
        showControls()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Manifest 已声明 configChanges,旋屏不重建,这里重排视频区
        updateVideoSize()
    }

    /** 竖屏视频区按 16:9 定高(不超过 235dp);横屏全屏沉浸并显示控制层。 */
    private fun updateVideoSize() {
        // 竖屏内容置于系统栏之下,横屏全屏沉浸
        WindowCompat.setDecorFitsSystemWindows(window, !isLandscape())
        val params = videoContainer.layoutParams
        // 两个右侧浮层都是 videoContainer 的子视图:切回竖屏前必须先收起,
        // 否则它们会盖在只有 235dp 高的竖屏视频区上(控制层/面板残留)
        if (landEpisodesOpen) toggleLandEpisodes(false)
        if (landSheetOpen) closeLandSheet()
        if (isLandscape()) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            contentScroll.visibility = View.GONE
            episodeArea.visibility = View.GONE
            overlay.visibility = View.VISIBLE
            portraitVideoOverlay.visibility = View.GONE
            applyImmersion(false)
        } else {
            val w = resources.displayMetrics.widthPixels
            // 16:9 但封顶 235dp:控制层(顶栏 + 中央按钮 + 底部进度行/按钮行)定高约 240dp,
            // 视频区再高也不会更宽松;窄屏(如 360dp 宽)的 9/16 只有约 202dp,给个下限免得进度行被裁
            params.height = minOf(dp(235), (w * 9 / 16.0).toInt()).coerceAtLeast(dp(160))
            contentScroll.visibility = View.VISIBLE
            episodeArea.visibility = View.VISIBLE
            overlay.visibility = View.GONE
            portraitVideoOverlay.visibility = View.VISIBLE
            applyImmersion(true)
        }
        videoContainer.layoutParams = params
        // 手势区高度补进 bottomBar,否则横屏进度条会被系统手势条压住
        applyBottomBarInsets()
        // 选集按钮只在横屏且有多集时出现(竖屏藏在下方内容区)
        updateEpisodeButtonVisibility()
    }

    /**
     * 把手势/导航区高度补进 bottomBar。
     * 横屏全屏用了 decorFitsSystemWindows=false,布局会延伸到手势条下面,
     * bottomBar 固定 8dp 的 paddingBottom 不够,进度条/按钮会被系统手势条压住;
     * 这里按真实 WindowInsets 动态补齐(保留 8dp 视觉留白),旋转/进入横屏都会重算。
     */
    private fun applyBottomBarInsets() {
        overlay.post {
            val insets = overlay.rootWindowInsets ?: return@post
            val nav = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(
                    WindowInsets.Type.navigationBars() or WindowInsets.Type.mandatorySystemGestures()
                ).bottom
            } else {
                @Suppress("DEPRECATION") insets.stableInsetBottom
            }
            val bar = overlay.findViewById<View>(R.id.bottomBar) ?: return@post
            val pad = dp(8) + nav
            if (bar.paddingBottom != pad) {
                bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, pad)
            }
        }
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
        // 选集面板还开着时不隐藏控制层(否则找不到关闭入口)
        if (landEpisodesOpen || landSheetOpen) return
        overlay.visibility = View.GONE
    }

    private fun scheduleHide() {
        if (!isLandscape()) return
        // 面板打开期间暂停自动隐藏,关闭后再恢复
        if (landEpisodesOpen || landSheetOpen) return
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
        // 选集按钮:横屏才显示(竖屏用下方内容区的选集列表),且需要多集
        updateEpisodeButtonVisibility()
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
        uiPrefs.edit().putInt("player_sleep", if (sleepMinutes > 0) sleepMinutes else 0).apply()
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
            lifecycleScope.launch {
                val remote = withContext(Dispatchers.IO) {
                    runCatching { client.getPlayback(vp) }.getOrNull()
                }
                if (remote != null && remote.positionMs > resume) p.seekTo(remote.positionMs)
            }
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
        // 选集列表已在布局里放进独立的 episodeArea(自身滚动),
        // 滚动它只动选集列表,不会带动上方简介/标题。
        rv.isNestedScrollingEnabled = true
        applyEpisodeAreaVisibility()
        // 打开就对齐到「正在播放」的那一集,长列表不用自己找
        if (playIdx in playlist.indices) rv.post { rv.scrollToPosition(playIdx) }
    }

    /** 竖屏且有多集时才占位;没有选集或横屏时整块收起,把空间还给详情区。 */
    private fun applyEpisodeAreaVisibility() {
        episodeArea.visibility =
            if (!isLandscape() && playlist.isNotEmpty()) View.VISIBLE else View.GONE
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
        // 竖屏弹层高度按屏幕算:列表本身可滚,但整块不能超过屏幕的 62%,
        // 否则底部抽屉的拖拽手势会抢走列表的滑动(表现为「只能看到前 5 集、滑不动」)。
        val maxH = (resources.displayMetrics.heightPixels * 0.62f).toInt()
        rv.layoutParams = rv.layoutParams.apply {
            height = (maxH - dp(96)).coerceAtLeast(dp(180))
        }
        rv.isNestedScrollingEnabled = true
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = EpisodeAdapter(playlist, playIdx) { i -> loadListIndex(i) }

        val sheet = BottomSheetDialog(this)
        sheet.setContentView(content)
        sheet.setOnDismissListener { if (player?.isPlaying == true) scheduleHide() }
        sheet.show()
        // 弹层直接展开到位,列表吃满可用高度;peekHeight 同步调高避免抽屉停在半屏。
        sheet.behavior.peekHeight = maxH
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        if (playIdx in playlist.indices) rv.post { rv.scrollToPosition(playIdx) }
    }

    /**
     * v1.9:横屏右侧选集面板的显示/隐藏。
     * 打开:面板宽 min(300dp, 屏宽*42%)、铺一层可点关闭的 scrim、暂停控制层自动隐藏;
     * 关闭:面板滑出后 GONE,scrim 同时消失,恢复自动隐藏。
     */
    private fun toggleLandEpisodes(show: Boolean) {
        if (show) {
            // 与右侧「更多/二级面板」互斥:先收起它再开选集,两块浮层不会叠
            if (landSheetOpen) closeLandSheet(animate = false)
            landEpisodesOpen = true
            val panelW = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.42f).toInt())
                .coerceAtLeast(dp(160))
            val lp = landEpisodePanel.layoutParams
            lp.width = panelW
            landEpisodePanel.layoutParams = lp
            findViewById<TextView>(R.id.landEpisodeTitle).text = "选集(${playlist.size})"
            rvLandEpisodes.adapter = EpisodeAdapter(playlist, playIdx, onDark = true) { i ->
                // 选完某一集自动收起面板
                toggleLandEpisodes(false)
                loadListIndex(i)
            }
            if (playIdx in playlist.indices) {
                rvLandEpisodes.post { rvLandEpisodes.scrollToPosition(playIdx) }
            }
            landEpisodeScrim.visibility = View.VISIBLE
            landEpisodePanel.visibility = View.VISIBLE
            landEpisodePanel.translationX = (panelW + dp(20)).toFloat()
            landEpisodePanel.animate().translationX(0f).setDuration(180).start()
            // 面板开着时暂停自动隐藏,并保证控制层可见(关闭按钮在面板里)
            handler.removeCallbacks(hideControlsRunnable)
            overlay.visibility = View.VISIBLE
        } else {
            landEpisodesOpen = false
            landEpisodeScrim.visibility = View.GONE
            val w = if (landEpisodePanel.width > 0) landEpisodePanel.width else dp(300)
            landEpisodePanel.animate().translationX((w + dp(20)).toFloat()).setDuration(160)
                .withEndAction { landEpisodePanel.visibility = View.GONE }
                .start()
            if (player?.isPlaying == true) scheduleHide()
        }
    }

    /** 选集按钮仅横屏且有多集时显示(竖屏隐藏在下方内容区)。 */
    private fun updateEpisodeButtonVisibility() {
        btnEpisodes.visibility = if (playlist.size > 1 && isLandscape()) View.VISIBLE else View.GONE
    }

    private inner class EpisodeAdapter(
        private val items: List<Pair<String, String>>,
        private val selected: Int,
        private val onDark: Boolean = false,   // 横屏半透明深色面板固定白字
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
            // 「播放中」这一行额外加粗,长列表里一眼能定位当前集
            holder.name.setTypeface(null, if (cur) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            // 选集封面缩略图(服务端 ffmpeg 抽帧)
            val vp = items[position].first
            holder.thumb.load(client.thumbUrl(vp, 240)) {
                crossfade(true)
                placeholder(R.drawable.bg_player_action)
                error(R.drawable.bg_player_action)
            }
            if (onDark) {
                // 半透明深色面板固定白字,不随浅色主题变成黑字
                holder.name.setTextColor(if (cur) Color.parseColor("#FFFFFF") else Color.parseColor("#E6FFFFFF"))
                holder.index.setTextColor(if (cur) Color.parseColor("#FFFFFF") else Color.parseColor("#80FFFFFF"))
                holder.badge.setTextColor(Color.parseColor("#F2F2F7"))
            } else {
                holder.name.setTextColor(
                    if (cur) ThemeColors.color(ctx, R.attr.lPrimary) else ThemeColors.text(ctx)
                )
                holder.index.setTextColor(
                    if (cur) ThemeColors.color(ctx, R.attr.lPrimary) else ThemeColors.textDim(ctx)
                )
            }
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
            // 横屏左下角清晰度标签(用 /api/info 的分辨率估算)
            updateQualityLabel(info)
        }
    }

    /** 用视频分辨率估算清晰度标签(4K/1080P/720P…),拿不到就显示"自动"。 */
    private fun updateQualityLabel(info: FileInfo?) {
        val h = info?.height ?: 0
        tvQuality.text = when {
            h >= 2000 -> "4K"
            h >= 1000 -> "1080P"
            h >= 700 -> "720P"
            h > 0 -> "${h}P"
            else -> "自动"
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

    /** 倍速面板:0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0,玻璃面板选中态列表(替代 AlertDialog 单选)。 */
    private fun showSpeedPanel() {
        handler.removeCallbacks(hideControlsRunnable)
        showPlayerSheet("倍速播放") { body ->
            speeds.forEachIndexed { i, speed ->
                val label = String.format(Locale.US, "%.2fx", speed).replace(".00", ".0")
                body.addView(optionRow(label, i == speedIdx) {
                    speedIdx = i
                    player?.setPlaybackSpeed(speed)
                    updateSpeedButton()
                    dismissPlayerSheets()
                }, optionRowParams())
            }
        }
    }

    /** 字幕面板:关闭 + 各字幕轨,玻璃面板列表并显示当前选中。 */
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
        var checked = 0
        if (currentSubtitleUrl != null) {
            val pos = subTracks.indexOfFirst { track ->
                try { client.absoluteUrl(track.url) == currentSubtitleUrl } catch (_: Exception) { false }
            }
            if (pos >= 0) checked = pos + 1
        }
        showPlayerSheet("字幕") { body ->
            body.addView(optionRow("关闭", checked == 0) {
                applySubtitle(null)
                dismissPlayerSheets()
            }, optionRowParams())
            subTracks.forEachIndexed { i, track ->
                val label = track.label.ifBlank { track.name }.ifBlank { track.lang }
                body.addView(optionRow(label, checked == i + 1) {
                    val url = try { client.absoluteUrl(track.url) } catch (_: Exception) { null }
                    applySubtitle(url)
                    dismissPlayerSheets()
                }, optionRowParams())
            }
        }
    }

    private fun applySubtitle(sub: String?) {
        val p = player ?: return
        currentSubtitleUrl = sub
        val pos = p.currentPosition.coerceAtLeast(0)
        p.setMediaItem(buildMediaItem(currentMediaUrl, sub), pos)
        p.prepare()
        p.playWhenReady = p.isPlaying
    }

    /** 应用定时关闭(分钟;0=关闭,-1=播完本集)。状态写 linnas_ui,下次进页面沿用。 */
    private fun applySleepOption(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        sleepAtEnd = minutes == -1
        sleepMinutes = if (minutes == -1) 0 else minutes
        sleepEndsAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        uiPrefs.edit().putInt("player_sleep", if (sleepAtEnd) -1 else sleepMinutes).apply()
        if (sleepMinutes > 0) {
            handler.postDelayed(sleepRunnable, minOf(sleepEndsAt - System.currentTimeMillis(), 15_000L))
            toast("${sleepMinutes} 分钟后自动暂停")
        } else if (sleepAtEnd) {
            toast("本集播放完毕后暂停")
        } else {
            toast("已关闭睡眠定时")
        }
    }

    /**
     * 「更多」面板入口:横竖屏共用同一份内容(buildMoreContent),只是承载容器不同。
     * 竖屏 = 底部 BottomSheetDialog;横屏 = 右侧滑出的 playerMorePanel(与选集面板同款)。
     */
    private fun showMorePanel() {
        handler.removeCallbacks(hideControlsRunnable)
        if (isLandscape()) {
            openLandSheet("更多") { body -> attachMoreContent(body) }
        } else {
            // 竖屏弹窗不叠层:先把已开的收起再开
            moreSheet?.dismiss()
            moreSheet = null
            sheetPanel?.dismiss()
            sheetPanel = null
            showPortraitMoreSheet()
        }
    }

    /** 竖屏「更多」:玻璃底 + 12dp 外边距 + 透明 BottomSheet,内容即 buildMoreContent()。 */
    private fun showPortraitMoreSheet() {
        val content = buildMoreContent()
        padForNavIfNeeded(content)
        moreSheet = BottomSheetDialog(this).apply {
            setContentView(content)
            // 去掉 BottomSheet 默认白色圆角背板,改用内容自带的深色半透明圆角
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
            // 压暗程度调低:面板本身已半透明,再叠默认 0.32 遮罩会显得像不透明的实心灰块
            window?.setDimAmount(0.12f)
            setOnDismissListener { moreSheet = null; if (player?.isPlaying == true) scheduleHide() }
        }
        moreSheet?.show()
    }

    /** 横屏右侧面板承载「更多」:容器已有玻璃背景,清掉弹层自己的玻璃底/外边距,并隐藏底部把手。 */
    private fun attachMoreContent(body: ViewGroup) {
        val content = buildMoreContent()
        body.addView(content)
        content.background = null
        content.layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        content.findViewById<View>(R.id.more_handle)?.visibility = View.GONE
    }

    /**
     * 构建「更多」面板内容(横竖屏共用):
     * 图标入口(3 列 × 2 行)+ 定时关闭分段 + 开关行;状态全部真实生效并写 linnas_ui。
     */
    private fun buildMoreContent(): View {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_player_more, null)

        // ---- 第一段:图标入口(3 列 × 2 行;每格 minWidth 64dp、高 64dp、相邻水平间距 12dp) ----
        val grid = content.findViewById<LinearLayout>(R.id.more_grid)
        val entries = listOf(
            Triple(R.drawable.ic_player_speed, "倍速播放") { showSpeedPanel() },
            Triple(R.drawable.ic_player_subtitle, "字幕") { showSubtitlePanel() },
            Triple(R.drawable.ic_player_aspect, "画面比例") { cycleResize() },
            Triple(R.drawable.ic_player_pip, "画中画") { enterPictureInPictureIfPossible() },
            Triple(R.drawable.ic_player_external, "外部播放器") { openExternalPlayer() },
            Triple(R.drawable.ic_rotate, "锁定横屏") { toggleRotationLock() },
        )
        entries.chunked(3).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // 每行顶部/底部各 4dp:图标行整块与把手、与下一段都留出呼吸空间
                setPadding(0, dp(4), 0, dp(4))
            }
            rowItems.forEach { (iconRes, label, action) ->
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64))
                // 左右各 6dp:相邻两格之间恰好 12dp 水平间距
                lp.marginStart = dp(6)
                lp.marginEnd = dp(6)
                row.addView(moreCell(iconRes, label) {
                    // 竖屏先收起弹窗再开二级面板,避免叠两层;横屏 openLandSheet 原地换内容,不闪
                    if (!isLandscape()) dismissPlayerSheets()
                    action()
                }, lp)
            }
            grid.addView(row)
        }

        // ---- 第二段:定时关闭分段 ----
        val sleepIds = listOf(
            R.id.seg_sleep_off, R.id.seg_sleep_15, R.id.seg_sleep_30, R.id.seg_sleep_60, R.id.seg_sleep_custom,
        )
        sleepIds.forEachIndexed { i, id ->
            content.findViewById<TextView>(id).setOnClickListener {
                val preset = SLEEP_PRESETS[i]
                if (preset == -2) {
                    showCustomSleepPicker(content)
                } else {
                    applySleepOption(preset)
                    updateSleepSegment(content)
                }
            }
        }
        updateSleepSegment(content)

        // ---- 第三段:自动连播 ----
        val swAuto = content.findViewById<MaterialSwitch>(R.id.sw_autonext)
        swAuto.isChecked = autoNext
        swAuto.setOnCheckedChangeListener { _, checked ->
            autoNext = checked
            uiPrefs.edit().putBoolean("player_autonext", checked).apply()
        }
        content.findViewById<View>(R.id.more_row_autonext).setOnClickListener { swAuto.toggle() }

        // ---- 第三段:循环播放 ----
        val loopIds = listOf(
            R.id.seg_loop_single to "single",
            R.id.seg_loop_list to "list",
            R.id.seg_loop_none to "none",
        )
        loopIds.forEach { (id, mode) ->
            content.findViewById<TextView>(id).setOnClickListener {
                loopMode = mode
                uiPrefs.edit().putString("player_loop", mode).apply()
                updateLoopSegment(content)
            }
        }
        updateLoopSegment(content)

        // ---- 第三段:镜像翻转 ----
        val swMirror = content.findViewById<MaterialSwitch>(R.id.sw_mirror)
        swMirror.isChecked = mirror
        swMirror.setOnCheckedChangeListener { _, checked -> applyMirror(checked) }
        content.findViewById<View>(R.id.more_row_mirror).setOnClickListener { swMirror.toggle() }

        return content
    }

    /**
     * 通用玻璃面板构建器(替代原 AlertDialog 单选/提示弹窗):
     * build 往容器里追加行;竖屏装进底部玻璃弹层(panel_player_sheet),横屏装进右侧滑出的
     * playerMorePanel,保证倍速/字幕/播放失败这些二三级界面与「更多」的圆角、半透明、间距一致。
     */
    private fun showPlayerSheet(title: String, build: (LinearLayout) -> Unit) {
        handler.removeCallbacks(hideControlsRunnable)
        if (isLandscape()) {
            openLandSheet(title) { body ->
                // 条目多/小屏时允许滚动,避免内容被面板高度裁掉
                val scroll = ScrollView(this)
                scroll.isVerticalScrollBarEnabled = false
                scroll.clipToPadding = false
                val list = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(12))
                }
                build(list)
                scroll.addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                body.addView(scroll)
            }
        } else {
            // 竖屏弹窗不叠层:先把已开的收起
            moreSheet?.dismiss()
            moreSheet = null
            sheetPanel?.dismiss()
            sheetPanel = null
            val content = LayoutInflater.from(this).inflate(R.layout.panel_player_sheet, null)
            content.findViewById<TextView>(R.id.panel_sheet_title).text = title
            val body = content.findViewById<LinearLayout>(R.id.panel_sheet_body)
            build(body)
            content.findViewById<View>(R.id.panel_sheet_close).setOnClickListener {
                sheetPanel?.dismiss()
            }
            padForNavIfNeeded(content)
            sheetPanel = BottomSheetDialog(this).apply {
                setContentView(content)
                // 与「更多」同一套做法:去掉默认白色背板,只留内容自带的深色玻璃圆角
                findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?.setBackgroundColor(Color.TRANSPARENT)
                window?.setDimAmount(0.12f)
                setOnDismissListener { sheetPanel = null; if (player?.isPlaying == true) scheduleHide() }
            }
            sheetPanel?.show()
        }
    }

    /**
     * 打开横屏右侧面板(「更多」/倍速/字幕/播放错误共用):
     * 与选集面板互斥;width = min(340dp, 屏宽 44%),首次打开从右侧滑入 180ms。
     * 若面板已经开着(如「更多」→「倍速」),只原地换内容,不再滑一次,避免闪。
     */
    private fun openLandSheet(title: String, build: (ViewGroup) -> Unit) {
        // 互斥:选集面板开着就先收起,两块浮层不会叠在一起
        if (landEpisodesOpen) toggleLandEpisodes(false)
        val wasOpen = landSheetOpen
        landSheetOpen = true
        val panelW = minOf(dp(340), (resources.displayMetrics.widthPixels * 0.44f).toInt())
            .coerceAtLeast(dp(200))
        val lp = playerMorePanel.layoutParams
        lp.width = panelW
        playerMorePanel.layoutParams = lp
        playerMoreTitle.text = title
        playerMoreBody.removeAllViews()
        build(playerMoreBody)
        playerMoreScrim.visibility = View.VISIBLE
        playerMorePanel.visibility = View.VISIBLE
        if (wasOpen) {
            // 已经是打开状态:内容已换,保持贴右不动
            playerMorePanel.translationX = 0f
        } else {
            playerMorePanel.translationX = (panelW + dp(20)).toFloat()
            playerMorePanel.animate().translationX(0f).setDuration(180).start()
        }
        // 面板开着时暂停控制层自动隐藏,并保证控制层可见(关闭入口在面板里)
        handler.removeCallbacks(hideControlsRunnable)
        overlay.visibility = View.VISIBLE
    }

    /** 关闭横屏右侧面板:滑出后 GONE 并清空内容(下次打开重建,状态总是最新的)。 */
    private fun closeLandSheet(animate: Boolean = true) {
        if (!landSheetOpen) return
        landSheetOpen = false
        playerMoreScrim.visibility = View.GONE
        playerMorePanel.animate().cancel()
        if (!animate) {
            playerMorePanel.visibility = View.GONE
            playerMoreBody.removeAllViews()
            if (player?.isPlaying == true) scheduleHide()
            return
        }
        val w = if (playerMorePanel.width > 0) playerMorePanel.width else dp(340)
        playerMorePanel.animate().translationX((w + dp(20)).toFloat()).setDuration(160)
            .withEndAction {
                playerMorePanel.visibility = View.GONE
                playerMoreBody.removeAllViews()
            }
            .start()
        if (player?.isPlaying == true) scheduleHide()
    }

    /** 收起当前打开的任意玻璃面板(竖屏 BottomSheet / 横屏右侧面板)。 */
    private fun dismissPlayerSheets() {
        moreSheet?.dismiss()
        moreSheet = null
        sheetPanel?.dismiss()
        sheetPanel = null
        closeLandSheet(animate = true)
    }

    /**
     * 底部弹层内容预留系统手势区高度:否则最后一行开关会贴着导航条/手势条,
     * 既难点也容易被手势条挡住(和 bottomBar 是同一个原因)。
     */
    private fun padForNavIfNeeded(v: View) {
        v.post {
            val insets = v.rootWindowInsets ?: return@post
            val nav = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.stableInsetBottom
            }
            if (nav > v.paddingBottom) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, nav)
            }
        }
    }

    /** 玻璃面板里的单选行:选中 = 白字加粗 + #22FFFFFF 圆角底,未选 = 60% 白。 */
    private fun optionRow(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#99FFFFFF"))
            setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = ContextCompat.getDrawable(
                this@PlayerActivity,
                if (selected) R.drawable.bg_player_option_on else R.drawable.bg_ripple,
            )
            setOnClickListener { onClick() }
        }

    /** 单选行的布局参数:48dp 高、左右 12dp、行间 4dp(配合容器 4dp 上内边距 = 8dp 间距)。 */
    private fun optionRowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            topMargin = dp(4)
        }

    /** 玻璃面板里的操作按钮(重试 / 其他播放器):半透明白底、白字、44dp 高。 */
    private fun sheetButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            setTypeface(null, Typeface.BOLD)
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_player_button)
            setOnClickListener { onClick() }
        }

    /** 定时关闭选中态:选中段白字加粗 + 底部 2dp 下划线(bg_player_seg_on),未选 60% 白。 */
    private fun updateSleepSegment(root: View) {
        val ids = listOf(
            R.id.seg_sleep_off, R.id.seg_sleep_15, R.id.seg_sleep_30, R.id.seg_sleep_60, R.id.seg_sleep_custom,
        )
        val checked = when {
            sleepAtEnd -> -1
            sleepMinutes == 0 -> 0
            sleepMinutes == 15 -> 1
            sleepMinutes == 30 -> 2
            sleepMinutes == 60 -> 3
            sleepMinutes > 0 -> 4
            else -> 0
        }
        ids.forEachIndexed { i, id ->
            val tv = root.findViewById<TextView>(id)
            val on = i == checked
            tv.setBackgroundResource(if (on) R.drawable.bg_player_seg_on else R.drawable.bg_player_seg)
            tv.setTextColor(if (on) Color.parseColor("#F2F2F7") else Color.parseColor("#99FFFFFF"))
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        // 自定义段显示已选时长(h:mm),否则回到「自定义」
        root.findViewById<TextView>(R.id.seg_sleep_custom).text =
            if (checked == 4) String.format(Locale.US, "%d:%02d", sleepMinutes / 60, sleepMinutes % 60) else "自定义"
    }

    /** 循环播放三段文本:选中项白色加粗,未选 60% 白。 */
    private fun updateLoopSegment(root: View) {
        val pairs = listOf(
            R.id.seg_loop_single to "single",
            R.id.seg_loop_list to "list",
            R.id.seg_loop_none to "none",
        )
        pairs.forEach { (id, mode) ->
            val tv = root.findViewById<TextView>(id)
            val on = loopMode == mode
            tv.setTextColor(if (on) Color.parseColor("#FFFFFF") else Color.parseColor("#99FFFFFF"))
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** 自定义定时:系统时间选择器(24h),得到 h:mm 后换算成分钟。 */
    private fun showCustomSleepPicker(root: View) {
        val base = if (sleepMinutes in 1..1439) sleepMinutes else 0
        TimePickerDialog(this, { _, hour, minute ->
            applySleepOption(hour * 60 + minute)
            updateSleepSegment(root)
        }, base / 60, base % 60, true).show()
    }

    /** 「更多」面板里的一格:图标 26dp + 12sp 单行文案,最小宽 64dp,图标与文字间距 6dp。 */
    private fun moreCell(iconRes: Int, label: String, action: () -> Unit): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            // 3 列布局里每格至少 64dp,4~5 个字的入口(「外部播放器」)也放得开
            minimumWidth = dp(64)
            background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_ripple)
            setOnClickListener { action() }
        }
        cell.addView(android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.parseColor("#E6FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        cell.addView(TextView(this).apply {
            text = label
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E6FFFFFF"))
            // 图标与文字垂直间距 6dp
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
        findViewById<TextView>(R.id.btn_aspect).text = label
        showHint("画面:$label")
    }

    // ---------- 更多面板开关的持久化与竖屏详情折叠 ----------

    /** 恢复更多面板的开关/分段与竖屏「简介」折叠状态(linnas_ui)。 */
    private fun restoreUiPrefs() {
        autoNext = uiPrefs.getBoolean("player_autonext", true)
        loopMode = uiPrefs.getString("player_loop", "none") ?: "none"
        applyMirror(uiPrefs.getBoolean("player_mirror", false))
        // 定时关闭:恢复上次选择并继续计时(状态真实生效)
        val savedSleep = uiPrefs.getInt("player_sleep", 0)
        when {
            savedSleep == -1 -> sleepAtEnd = true
            savedSleep > 0 -> {
                sleepMinutes = savedSleep
                sleepEndsAt = System.currentTimeMillis() + savedSleep * 60_000L
                handler.postDelayed(sleepRunnable, minOf(sleepEndsAt - System.currentTimeMillis(), 15_000L))
            }
        }
        // 竖屏简介默认收起(B 站默认折叠),用户展开后记住
        applyDetailCollapsed(uiPrefs.getBoolean("player_detail_collapsed", true), false)
    }

    /** 镜像翻转:只翻 PlayerView 画面本身(scaleX = -1),字幕层以外的 UI 不动。 */
    private fun applyMirror(on: Boolean) {
        mirror = on
        view.scaleX = if (on) -1f else 1f
        uiPrefs.edit().putBoolean("player_mirror", on).apply()
    }

    /** 竖屏「简介」折叠:收起 meta + 简介区,150ms 过渡,状态持久化。 */
    private fun applyDetailCollapsed(collapsed: Boolean, animate: Boolean) {
        uiPrefs.edit().putBoolean("player_detail_collapsed", collapsed).apply()
        tvDetailToggle.text = if (collapsed) "简介 · 展开" else "简介 · 收起"
        imgDetailChevron.animate().rotation(if (collapsed) 90f else -90f)
            .setDuration(if (animate) 150 else 0).start()
        detailBody.animate().cancel()
        if (collapsed) {
            if (animate) {
                detailBody.animate().alpha(0f).setDuration(150).withEndAction {
                    detailBody.visibility = View.GONE
                    detailBody.alpha = 1f
                }.start()
            } else {
                detailBody.visibility = View.GONE
                detailBody.alpha = 1f
            }
        } else {
            detailBody.visibility = View.VISIBLE
            if (animate) {
                detailBody.alpha = 0f
                detailBody.animate().alpha(1f).setDuration(150).start()
            } else {
                detailBody.alpha = 1f
            }
        }
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

    /**
     * 播放失败提示:改用玻璃面板(标题 + 说明 + 「重试 / 用其他播放器打开」),
     * 和倍速/字幕/更多同一套样式;横屏时同样从右侧滑出,不再弹系统 AlertDialog。
     */
    private fun showPlaybackError(error: PlaybackException) {
        if (isFinishing || isDestroyed) return
        showControls()
        player?.let {
            PlaybackStore.save(this, playbackKey, it.currentPosition, it.duration)
        }
        dismissPlayerSheets()
        showPlayerSheet("播放失败") { body ->
            body.addView(TextView(this).apply {
                text = "${error.errorCodeName}。可重试，或改用其他播放器打开。"
                textSize = 13f
                setTextColor(Color.parseColor("#B3FFFFFF"))
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(16), dp(4), dp(16), dp(12))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), 0, dp(12), 0)
            }
            buttons.addView(sheetButton("重试") {
                dismissPlayerSheets()
                retryPlayback()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            // 「用其他播放器打开」会切走 App,先收起面板,回来时不会还挂着一个错误弹层
            buttons.addView(sheetButton("用其他播放器打开") {
                dismissPlayerSheets()
                openExternalPlayer()
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            body.addView(buttons, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
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
        player?.let { p -> PlaybackStore.save(this, playbackKey, p.currentPosition, p.duration); saveProgressToServer(p.currentPosition, p.duration, true) }
    }

    override fun onDestroy() {
        // 面板都挂在 Activity 上,销毁前必须收掉,否则会泄漏窗口/动画
        moreSheet?.dismiss()
        moreSheet = null
        sheetPanel?.dismiss()
        sheetPanel = null
        // onDestroy 可能在 onCreate 中途异常时被调用,lateinit 视图要先判初始化
        if (::playerMorePanel.isInitialized) playerMorePanel.animate().cancel()
        handler.removeCallbacks(saveProgressRunnable)
        handler.removeCallbacks(uiRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(sleepRunnable)
        player?.let { p -> PlaybackStore.save(this, playbackKey, p.currentPosition, p.duration); saveProgressToServer(p.currentPosition, p.duration, true) }
        player?.release()
        player = null
        super.onDestroy()
    }
}
