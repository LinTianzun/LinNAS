package com.linnas.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * 应用内图片浏览器:左右滑动切换、双指缩放 / 双击放大、顶部信息与保存 / 分享 / 详情。
 * 目的是不依赖系统相册或外部看图应用,解决「点图片只弹提示 / 被迫跳外部 App」的问题。
 */
class ImageActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_VPATHS = "vpaths"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_INDEX = "start_index"

        /**
         * urls 与 vpaths/names 一一对应;url 可以是 http(s) 绝对地址(已含 token 查询参数),
         * 也可以是 file:// 或本地绝对路径。
         */
        fun start(
            ctx: Context,
            vpaths: ArrayList<String>,
            names: ArrayList<String>,
            urls: ArrayList<String>,
            startIndex: Int,
        ) {
            val n = maxOf(vpaths.size, names.size, urls.size)
            // 容错:长度不一致时不足补空串,保证三项一一对应,避免越界。
            val v = ArrayList<String>(n)
            val nm = ArrayList<String>(n)
            val us = ArrayList<String>(n)
            for (i in 0 until n) {
                v.add(vpaths.getOrElse(i) { "" })
                nm.add(names.getOrElse(i) { "" })
                us.add(urls.getOrElse(i) { "" })
            }
            val intent = Intent(ctx, ImageActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_VPATHS, v)
                putStringArrayListExtra(EXTRA_NAMES, nm)
                putStringArrayListExtra(EXTRA_URLS, us)
                putExtra(EXTRA_INDEX, startIndex.coerceIn(0, maxOf(0, n - 1)))
                if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
    }

    private lateinit var vpaths: ArrayList<String>
    private lateinit var names: ArrayList<String>
    private lateinit var urls: ArrayList<String>

    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var tvName: TextView
    private lateinit var tvIndex: TextView
    private lateinit var btnSave: ImageButton

    private var currentIndex = 0
    private var chromeVisible = true
    private val httpClient by lazy { OkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内容延伸到系统栏下方,图片全屏铺满。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_image)

        findViewById<View>(R.id.ivRoot).setBackgroundColor(Color.BLACK)
        // 黑色背景上把状态栏 / 导航栏图标切成浅色,避免看不清。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        vpaths = intent.getStringArrayListExtra(EXTRA_VPATHS) ?: arrayListOf()
        names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        normalize()

        if (urls.isEmpty()) {
            toast("没有可显示的图片")
            finish()
            return
        }
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.size - 1)

        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        tvName = findViewById(R.id.tvName)
        tvIndex = findViewById(R.id.tvIndex)
        btnSave = findViewById(R.id.btnSave)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnShare).setOnClickListener { shareCurrent() }
        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener { showInfo() }
        btnSave.setOnClickListener { saveCurrent() }

        val pager = findViewById<ViewPager2>(R.id.imagePager)
        pager.adapter = ImagePagerAdapter(urls, names)
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(currentIndex, false)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateChrome()
            }
        })
        updateChrome()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    /** 三个列表对齐到同一长度(不足补空串),让下标访问始终安全。 */
    private fun normalize() {
        val n = maxOf(vpaths.size, names.size, urls.size)
        if (vpaths.size == n && names.size == n && urls.size == n) return
        val v = ArrayList<String>(n)
        val nm = ArrayList<String>(n)
        val us = ArrayList<String>(n)
        for (i in 0 until n) {
            v.add(vpaths.getOrElse(i) { "" })
            nm.add(names.getOrElse(i) { "" })
            us.add(urls.getOrElse(i) { "" })
        }
        vpaths = v
        names = nm
        urls = us
    }

    private fun updateChrome() {
        tvName.text = names.getOrElse(currentIndex) { "" }.ifBlank { "图片" }
        tvIndex.text = "${currentIndex + 1} / ${urls.size}"
        // 保存只对 http(s) 网络图片开放,其余置灰但不隐藏。
        val canSave = isHttp(urls.getOrElse(currentIndex) { "" })
        btnSave.isEnabled = canSave
        btnSave.alpha = if (canSave) 1f else 0.4f
    }

    /** 点击画面切换顶栏 / 底栏显隐(200ms alpha 动画)。 */
    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        animateBar(topBar, chromeVisible)
        animateBar(bottomBar, chromeVisible)
    }

    private fun animateBar(view: View, show: Boolean) {
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(200).start()
        } else {
            view.animate().alpha(0f).setDuration(200).withEndAction {
                if (!chromeVisible) view.visibility = View.GONE
            }.start()
        }
    }

    // ---------- 保存 ----------

    private fun saveCurrent() {
        val url = urls.getOrElse(currentIndex) { "" }
        if (!isHttp(url)) {
            toast("仅网络图片可保存")
            return
        }
        val tree = UiPrefs.downloadTreeUri(this)
        if (tree.isBlank()) {
            toast("请先在设置里选择下载目录")
            return
        }
        val name = names.getOrElse(currentIndex) { "" }.ifBlank { "image_${System.currentTimeMillis()}.jpg" }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { saveToTree(tree, url, name) }
            val where = UiPrefs.downloadTreeName(this@ImageActivity).ifBlank { "下载目录" }
            toast(if (ok) "已保存到「$where」" else "保存失败,请重试")
        }
    }

    /** 下载 http(s) 图片字节流,写入用户选的 SAF 下载目录。 */
    private fun saveToTree(treeUri: String, url: String, fileName: String): Boolean {
        return try {
            val tree = Uri.parse(treeUri)
            // tree 只是授权根,createDocument 要的是「目录文档」URI,与 MainActivity.copyToDownloadTree 一致。
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val doc = DocumentsContract.createDocument(contentResolver, parent, "image/*", fileName)
                    ?: return false
                contentResolver.openOutputStream(doc)?.use { out ->
                    body.byteStream().use { input -> input.copyTo(out) }
                } ?: return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- 分享 ----------

    private fun shareCurrent() {
        val url = urls.getOrElse(currentIndex) { "" }
        if (url.isBlank()) {
            toast("没有可分享的图片")
            return
        }
        val name = names.getOrElse(currentIndex) { "" }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { prepareShareUri(url, name) }
            if (uri == null) {
                toast("分享失败,请重试")
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, "分享图片"))
            } catch (_: Exception) {
                toast("没有可用的分享方式")
            }
        }
    }

    private fun prepareShareUri(url: String, rawName: String): Uri? {
        return try {
            if (isHttp(url)) {
                var safe = sanitize(rawName)
                if (!safe.contains('.')) safe += ".jpg"
                val file = File(cacheDir, "share_${System.currentTimeMillis()}_$safe")
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body ?: return null
                    file.outputStream().use { out ->
                        body.byteStream().use { input -> input.copyTo(out) }
                    }
                }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } else {
                val file: File? = if (url.startsWith("file://")) {
                    Uri.parse(url).path?.let { File(it) }
                } else {
                    File(url)
                }
                if (file == null || !file.isFile) return null
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 详情 ----------

    private fun showInfo() {
        val name = names.getOrElse(currentIndex) { "" }
        val vp = vpaths.getOrElse(currentIndex) { "" }
        val url = urls.getOrElse(currentIndex) { "" }
        Sheet(this, "图片详情", name.ifBlank { "图片" })
            .keyValue("文件名", name.ifBlank { "—" })
            .keyValue("虚拟路径", vp.ifBlank { "—" }, true)
            .keyValue("地址", url.ifBlank { "—" }, true)
            .primary("关闭") {}
            .show()
    }

    // ---------- 工具 ----------

    private fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

    private fun sanitize(name: String): String =
        name.replace(Regex("[^\\w.\\-]"), "_").trim('_', '.', '-')
            .ifBlank { "image_${System.currentTimeMillis()}.jpg" }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------- 动图(GIF) ----------

    /** 按文件名或 URL(去查询串)判断是否为 GIF,交给动画解码而不是 Coil 的静态首帧。 */
    private fun isGifUrl(url: String, name: String): Boolean {
        if (name.lowercase().endsWith(".gif")) return true
        return url.lowercase().substringBefore('?').endsWith(".gif")
    }

    /** 取图片原始字节:网络走本 Activity 的 OkHttp(URL 已含 token),本地直接读文件。 */
    private fun fetchBytes(url: String): ByteArray? = try {
        when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                }
            }
            url.isBlank() -> null
            else -> {
                val f = if (url.startsWith("file://")) Uri.parse(url).path?.let { File(it) } else File(url)
                f?.takeIf { it.isFile }?.readBytes()
            }
        }
    } catch (_: Exception) {
        null
    }

    /** 把 GIF 字节解码成可播放动画的 Drawable:API 28+ 用 ImageDecoder,旧版本退回 Movie。 */
    private fun decodeAnimated(bytes: ByteArray): Drawable? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // AnimatedImageDrawable 不会自己开始播放,必须显式 start(),否则只停在第一帧。
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            if (d is android.graphics.drawable.AnimatedImageDrawable) {
                d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                d.start()
            }
            d
        } else {
            Movie.decodeByteArray(bytes, 0, bytes.size)?.let { MovieDrawable(it) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * API 28 以下的 GIF 兜底实现:`android.graphics.Movie` 虽已废弃但可用,
     * 按自身 intrinsic 尺寸绘制(ImageView 的 MATRIX 会先把画布缩放到目标大小)。
     */
    private class MovieDrawable(private val movie: Movie) : Drawable() {
        private var startMs = 0L
        private val tick = Runnable { invalidateSelf() }

        override fun getIntrinsicWidth(): Int = movie.width()
        override fun getIntrinsicHeight(): Int = movie.height()

        override fun draw(canvas: Canvas) {
            val now = android.os.SystemClock.uptimeMillis()
            if (startMs == 0L) startMs = now
            val dur = movie.duration().let { if (it <= 0) 1000 else it }
            movie.setTime(((now - startMs) % dur).toInt())
            movie.draw(canvas, 0f, 0f)
            unscheduleSelf(tick)
            scheduleSelf(tick, now + 16)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---------- 可缩放图片 ----------

    /**
     * 支持双指缩放(1x~5x,中心跟随手指)、双击 2.5x/复原、放大后单指拖动平移。
     * 放大时禁止父级 ViewPager2 拦截横向滑动,避免与翻页冲突。
     */
    class ZoomableImageView(ctx: Context) : AppCompatImageView(ctx) {

        /** 单击(非双击)回调,用于切换顶栏/底栏显隐。 */
        var onTap: (() -> Unit)? = null

        private val maxScale = 5f
        private var zoom = 1f // 相对「适屏基准」的缩放倍数,1f = 适屏
        private val matrix = Matrix()
        private val vals = FloatArray(9)
        private var lastX = 0f
        private var lastY = 0f

        private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (zoom * detector.scaleFactor).coerceIn(1f, maxScale)
                val applied = target / zoom
                if (applied != 1f) {
                    // 以双指焦点为缩放中心,跟随手指。
                    matrix.postScale(applied, applied, detector.focusX, detector.focusY)
                    zoom = target
                    imageMatrix = matrix
                    fixTranslate()
                }
                return true
            }
        })

        private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.05f) {
                    resetToFit()
                } else {
                    val target = 2.5f
                    val applied = target / zoom
                    matrix.postScale(applied, applied, e.x, e.y)
                    zoom = target
                    imageMatrix = matrix
                    fixTranslate()
                }
                return true
            }
        })

        init {
            // Kotlin 不继承父类的嵌套类型,ScaleType 需全限定。
            scaleType = android.widget.ImageView.ScaleType.MATRIX
            isClickable = true
        }

        override fun setImageDrawable(drawable: Drawable?) {
            super.setImageDrawable(drawable)
            // 图片尺寸要等 View 完成布局才知道,post 一下再算初始矩阵。
            post { resetToFit() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // 未放大时跟随尺寸变化重新适屏(如旋转、分屏)。
            if (zoom <= 1.02f) post { resetToFit() }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            // 放大状态禁止父级(ViewPager2)拦截,否则横滑会被翻页吃掉。
            parent?.requestDisallowInterceptTouchEvent(zoom > 1.02f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && zoom > 1.02f) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) + abs(dy) > 2f) {
                            matrix.postTranslate(dx, dy)
                            imageMatrix = matrix
                            fixTranslate()
                        }
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        /** 按 drawable 尺寸与 View 尺寸计算居中适屏初始矩阵。 */
        fun resetToFit() {
            val d = drawable
            val dw = d?.intrinsicWidth?.toFloat() ?: 0f
            val dh = d?.intrinsicHeight?.toFloat() ?: 0f
            val vw = width.toFloat()
            val vh = height.toFloat()
            matrix.reset()
            zoom = 1f
            if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) {
                imageMatrix = matrix
                return
            }
            val fit = min(vw / dw, vh / dh)
            matrix.postScale(fit, fit)
            matrix.postTranslate((vw - dw * fit) / 2f, (vh - dh * fit) / 2f)
            imageMatrix = matrix
        }

        /** 限制平移:内容小于视口时居中,大于视口时不允许拖出边界。 */
        private fun fixTranslate() {
            val d = drawable ?: return
            val dw = d.intrinsicWidth.toFloat()
            val dh = d.intrinsicHeight.toFloat()
            if (dw <= 0f || dh <= 0f) return
            matrix.getValues(vals)
            val total = vals[Matrix.MSCALE_X]
            val cw = dw * total
            val ch = dh * total
            val vw = width.toFloat()
            val vh = height.toFloat()
            vals[Matrix.MTRANS_X] = if (cw <= vw) (vw - cw) / 2f else vals[Matrix.MTRANS_X].coerceIn(vw - cw, 0f)
            vals[Matrix.MTRANS_Y] = if (ch <= vh) (vh - ch) / 2f else vals[Matrix.MTRANS_Y].coerceIn(vh - ch, 0f)
            matrix.setValues(vals)
            imageMatrix = matrix
        }
    }

    // ---------- 翻页适配器 ----------

    private inner class ImagePagerAdapter(
        private val urls: List<String>,
        private val names: List<String>,
    ) : RecyclerView.Adapter<ImagePagerAdapter.Holder>() {

        inner class Holder(
            itemView: View,
            val zoom: ZoomableImageView,
            val progress: CircularProgressIndicator,
            val errorBox: LinearLayout,
        ) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            // 每页 = 可缩放图片 + 加载中圈 + 失败提示,全部代码构建,避免额外布局文件。
            val root = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
            }
            val zoom = ZoomableImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                onTap = { toggleChrome() }
            }
            val progress = CircularProgressIndicator(ctx).apply {
                isIndeterminate = true
                setIndicatorColor(Color.WHITE)
                trackThickness = dp(3)
                layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
            }
            val errorBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                addView(TextView(ctx).apply {
                    text = "图片加载失败"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                })
                addView(TextView(ctx).apply {
                    text = "点击重试"
                    setTextColor(Color.parseColor("#7FB4FF"))
                    textSize = 14f
                    setPadding(0, dp(10), 0, 0)
                })
            }
            root.addView(zoom)
            root.addView(progress)
            root.addView(errorBox)
            return Holder(root, zoom, progress, errorBox)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.zoom.resetToFit()
            bindLoad(holder, position)
        }

        override fun getItemCount(): Int = urls.size

        private fun bindLoad(holder: Holder, position: Int) {
            holder.progress.visibility = View.VISIBLE
            holder.errorBox.visibility = View.GONE
            holder.errorBox.setOnClickListener { bindLoad(holder, position) }
            val url = urls.getOrElse(position) { "" }
            // GIF 动图:Coil 默认只解首帧,这里单独取字节解码成动画 Drawable 播放。
            if (isGifUrl(url, names.getOrElse(position) { "" })) {
                Thread {
                    val bytes = fetchBytes(url)
                    val d = bytes?.let { decodeAnimated(it) }
                    holder.zoom.post {
                        holder.progress.visibility = View.GONE
                        if (d != null) {
                            holder.errorBox.visibility = View.GONE
                            holder.zoom.setImageDrawable(d)
                        } else {
                            holder.errorBox.visibility = View.VISIBLE
                        }
                    }
                }.start()
                return
            }
            holder.zoom.load(toData(url)) {
                crossfade(true)
                listener(object : ImageRequest.Listener {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        holder.progress.visibility = View.GONE
                        holder.errorBox.visibility = View.GONE
                    }

                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        holder.progress.visibility = View.GONE
                        holder.errorBox.visibility = View.VISIBLE
                    }
                })
            }
        }

        /** http/file/content 直接交给 Coil;裸本地路径转 File,避免解析成非法 Uri。 */
        private fun toData(url: String): Any {
            return when {
                url.startsWith("http://") || url.startsWith("https://") ||
                    url.startsWith("file://") || url.startsWith("content://") -> url
                url.isBlank() -> ""
                else -> File(url)
            }
        }
    }
}
