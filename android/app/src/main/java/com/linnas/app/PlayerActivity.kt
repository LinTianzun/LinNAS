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
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
                            .setMimeType("application/x-subrip")
                            .setLanguage("zh")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            } catch (_: Exception) { }
        }
        return builder.build()
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

    // ---------- 生命周期 ----------

    override fun onStop() {
        super.onStop()
        player?.pause()
        player?.let { PlaybackStore.save(this, playbackKey, it.currentPosition, it.duration) }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveProgressRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }
}
