package com.devtudio.vrcinema

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * VR Cinema: local/direct-stream cinema player with a camera passthrough,
 * side-by-side eye views, touch repositioning and rotation-vector tracking.
 *
 * YouTube pages and Telegram post URLs are handed to their official apps.
 * Videos shared/exported from those apps and direct media URLs can be played here.
 */
class MainActivity : AppCompatActivity(), android.hardware.SensorEventListener {

    companion object {
        private const val PICK_VIDEO = 41
        private const val CAMERA_PERMISSION = 42
    }

    private lateinit var root: FrameLayout
    private var sourceUri: Uri? = null
    private var sourceTitle: String = "Видео"

    private var player: ExoPlayer? = null
    private var vrPlayer: ExoPlayer? = null
    private var mainTexture: androidx.media3.ui.PlayerView? = null
    private var vrTextureLeft: androidx.media3.ui.PlayerView? = null
    private var vrTextureRight: androidx.media3.ui.PlayerView? = null

    private var theatreScreen: CurvedScreenFrame? = null
    private var vrRoot: FrameLayout? = null
    private var vrPair: FrameLayout? = null
    private var cameraPreview: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var sizeProgress = 66
    private var distanceProgress = 38
    private var lastBackgroundTap = 0L
    private var baseYaw: Float? = null
    private var basePitch: Float? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rotationSensor: android.hardware.Sensor? = null
    private lateinit var sensorManager: android.hardware.SensorManager

    private val syncVrVideo = object : Runnable {
        override fun run() {
            val main = player
            val eye = vrPlayer
            if (vrRoot != null && main != null && eye != null) {
                val drift = abs(main.currentPosition - eye.currentPosition)
                if (drift > 180L) eye.seekTo(main.currentPosition)
                if (main.isPlaying && !eye.isPlaying) eye.play()
                if (!main.isPlaying && eye.isPlaying) eye.pause()
                mainHandler.postDelayed(this, 650L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        rotationSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        root = FrameLayout(this)
        setContentView(root)
        showHome()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val sharedUri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                if (sharedUri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            sharedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) { }
                    loadSource(sharedUri, displayName(sharedUri))
                } else {
                    val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) submitUrl(sharedText.trim())
                }
            }
            Intent.ACTION_VIEW -> incoming.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") submitUrl(uri.toString())
                else loadSource(uri, displayName(uri))
            }
        }
    }

    private fun showHome() {
        root.removeAllViews()
        root.setBackgroundColor(Color.rgb(8, 10, 16))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setClipToPadding(false)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(30))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        content.addView(label("VR CINEMA", 13f, Color.rgb(167, 139, 250), true))
        content.addView(label("Твой личный кинотеатр", 29f, Color.WHITE, true).apply {
            setPadding(0, dp(8), 0, dp(6))
        })
        content.addView(label(
            "Смотри видео на большом изогнутом экране. Настраивай его размер и расстояние, а затем вставляй телефон в VR-гарнитуру.",
            15f, Color.rgb(177, 183, 198), false
        ).apply { setPadding(0, 0, 0, dp(22)) })

        val fileCard = panel()
        fileCard.orientation = LinearLayout.VERTICAL
        fileCard.addView(label("1. Выбери видео", 18f, Color.WHITE, true))
        fileCard.addView(label(
            "Файл из памяти телефона или видео, которым ты поделился из другого приложения.",
            13f, Color.rgb(170, 177, 192), false
        ).apply { setPadding(0, dp(7), 0, dp(14)) })
        fileCard.addView(actionButton("Выбрать видео", true) { pickVideo() })
        content.addView(fileCard, matchWrap().apply { bottomMargin = dp(14) })

        val linkCard = panel()
        linkCard.orientation = LinearLayout.VERTICAL
        linkCard.addView(label("2. Или вставь ссылку", 18f, Color.WHITE, true))
        val urlInput = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setHintTextColor(Color.rgb(111, 120, 139))
            setTextColor(Color.WHITE)
            textSize = 14f
            singleLine = true
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(22, 27, 40), dp(14), Color.rgb(54, 62, 81))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        linkCard.addView(urlInput, matchWrap().apply { topMargin = dp(12); bottomMargin = dp(12) })
        linkCard.addView(actionButton("Открыть видеопоток", true) {
            submitUrl(urlInput.text?.toString().orEmpty())
        })
        content.addView(linkCard, matchWrap().apply { bottomMargin = dp(14) })

        val note = panel()
        note.orientation = LinearLayout.VERTICAL
        note.addView(label("Поддержка ссылок", 16f, Color.WHITE, true))
        note.addView(label(
            "• Работают прямые MP4, WebM, HLS (.m3u8) и DASH (.mpd), если сервер разрешает доступ.\n" +
            "• YouTube открывается в официальном приложении: ссылка на страницу не является прямым видеопотоком.\n" +
            "• Для Telegram открой видео и нажми «Поделиться → VR Cinema». Закрытый пост доступен только твоему аккаунту и не обходится приложением.",
            13f, Color.rgb(170, 177, 192), false
        ).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(note, matchWrap())

        content.addView(label(
            "VR-режим использует камеру телефона как фон. Для эффекта стерео помести телефон в VR-гарнитуру с двумя линзами.",
            12f, Color.rgb(115, 125, 145), false
        ).apply { setPadding(dp(3), dp(18), dp(3), 0) })
    }

    private fun pickVideo() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(pick, PICK_VIDEO)
    }

    @Deprecated("Uses the Storage Access Framework for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            loadSource(uri, displayName(uri))
        }
    }

    private fun submitUrl(raw: String) {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) {
            toast("Сначала вставь ссылку на видео")
            return
        }
        val uri = try { Uri.parse(cleaned) } catch (_: Exception) {
            toast("Некорректная ссылка")
            return
        }
        val host = (uri.host ?: "").lowercase()
        if (host == "youtu.be" || host.endsWith("youtube.com") ||
            host.endsWith("youtube-nocookie.com")) {
            toast("YouTube откроется в официальном приложении. Для встроенного плеера нужна прямая ссылка на разрешённый видеопоток.")
            openExternal(uri)
            return
        }
        if (host == "t.me" || host.endsWith(".t.me") || host == "telegram.me" ||
            host.endsWith(".telegram.me")) {
            toast("Открой видео в Telegram и выбери «Поделиться → VR Cinema». Приватные публикации требуют доступа твоего аккаунта.")
            openExternal(uri)
            return
        }
        if (uri.scheme != "https" && uri.scheme != "http") {
            toast("Поддерживаются ссылки HTTP и HTTPS")
            return
        }
        val title = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "Сетевое видео"
        loadSource(uri, title)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            toast("Не найдено приложение для этой ссылки")
        }
    }

    private fun loadSource(uri: Uri, title: String) {
        sourceUri = uri
        sourceTitle = title.ifBlank { "Видео" }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        mainHandler.postDelayed({ buildTheatre() }, 280L)
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0) ?: "Видео"
                }
            } catch (_: Exception) { }
        }
        return uri.lastPathSegment ?: "Видео"
    }

    private fun ensurePlayer() {
        val uri = sourceUri ?: return
        if (player != null) return
        player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    toast("Не удалось открыть видео: проверь ссылку или формат")
                }
            })
            prepare()
        }
    }

    private fun buildTheatre() {
        if (sourceUri == null) return
        if (vrRoot != null) exitVr(rebuildTheatre = false)
        ensurePlayer()
        root.removeAllViews()
        root.setBackgroundColor(Color.rgb(5, 7, 12))

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val baseW = (w * 0.87f).toInt()
        val baseH = (h * 0.53f).toInt()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        header.addView(label("VR CINEMA", 13f, Color.rgb(167, 139, 250), true),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label(sourceTitle, 13f, Color.rgb(193, 199, 211), false))
        root.addView(header, FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP))

        val screen = CurvedScreenFrame(this).apply {
            setBackgroundColor(Color.BLACK)
            elevation = dp(12).toFloat()
        }
        theatreScreen = screen
        mainTexture = attachVideo(screen, player!!)
        val scaledW = (baseW * sizeFactor() * distanceFactor()).toInt()
        val scaledH = (baseH * sizeFactor() * distanceFactor()).toInt()
        root.addView(screen, FrameLayout.LayoutParams(scaledW, scaledH, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(58)
        })

        val controls = panel().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val playButton = actionButton(if (player?.isPlaying == true) "Пауза" else "▶ Воспроизвести", true) {
            togglePlayback()
        }
        buttons.addView(playButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        buttons.addView(actionButton("VR-режим", true) { requestVr() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        controls.addView(buttons)

        controls.addView(label("Размер экрана", 12f, Color.rgb(196, 201, 214), true)
            .apply { setPadding(0, dp(7), 0, 0) })
        val sizeBar = SeekBar(this).apply {
            max = 100
            progress = sizeProgress
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(167, 139, 250))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(simpleSeek { sizeProgress = it; updateTheatreScreen(baseW, baseH) })
        }
        controls.addView(sizeBar)

        controls.addView(label("Дальность: дальше — экран визуально меньше", 12f, Color.rgb(196, 201, 214), true))
        val distanceBar = SeekBar(this).apply {
            max = 100
            progress = distanceProgress
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(167, 139, 250))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(simpleSeek { distanceProgress = it; updateTheatreScreen(baseW, baseH) })
        }
        controls.addView(distanceBar)

        controls.addView(actionButton("← К выбору видео", false) {
            releasePlayers()
            sourceUri = null
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showHome()
        }.apply { setPadding(dp(4), 0, dp(4), 0) }, matchWrap().apply { topMargin = dp(7) })

        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(10))
        })
        root.post { updateTheatreScreen(baseW, baseH) }
    }

    private fun updateTheatreScreen(baseW: Int, baseH: Int) {
        val screen = theatreScreen ?: return
        val params = screen.layoutParams as? FrameLayout.LayoutParams ?: return
        params.width = (baseW * sizeFactor() * distanceFactor()).toInt().coerceAtLeast(dp(180))
        params.height = (baseH * sizeFactor() * distanceFactor()).toInt().coerceAtLeast(dp(110))
        screen.layoutParams = params
    }

    private fun sizeFactor(): Float = 0.62f + (sizeProgress / 100f) * 0.66f
    private fun distanceFactor(): Float = 1.08f - (distanceProgress / 100f) * 0.55f

    private fun attachVideo(container: FrameLayout, exo: ExoPlayer): androidx.media3.ui.PlayerView {
        val view = layoutInflater.inflate(R.layout.video_surface, container, false) as androidx.media3.ui.PlayerView
        view.player = exo
        view.useController = false
        view.setKeepContentOnPlayerReset(true)
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        return view
    }

    private fun togglePlayback() {
        val main = player ?: return
        if (main.isPlaying) {
            main.pause()
            vrPlayer?.pause()
        } else {
            main.play()
            vrPlayer?.play()
        }
    }

    private fun requestVr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        } else {
            enterVr()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) enterVr()
            else toast("Камера нужна для фона VR. Можно продолжить без VR-режима.")
        }
    }

    private fun enterVr() {
        val main = player ?: return
        mainTexture?.player = null
        vrPlayer?.release()
        vrPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(sourceUri!!))
            volume = 0f
            prepare()
            seekTo(main.currentPosition)
            playWhenReady = main.isPlaying
        }

        root.removeAllViews()
        val scene = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        vrRoot = scene
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))

        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.rgb(9, 11, 18))
        }
        cameraPreview = preview
        scene.addView(preview, FrameLayout.LayoutParams(-1, -1))
        startCamera(preview)

        val pair = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        vrPair = pair
        val eyes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val left = CurvedScreenFrame(this).apply {
            setBackgroundColor(Color.BLACK)
            elevation = dp(8).toFloat()
        }
        val right = CurvedScreenFrame(this).apply {
            setBackgroundColor(Color.BLACK)
            elevation = dp(8).toFloat()
        }
        vrTextureLeft = attachVideo(left, main)
        vrTextureRight = attachVideo(right, vrPlayer!!)
        eyes.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(3) })
        eyes.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(3) })
        pair.addView(eyes, FrameLayout.LayoutParams(-1, -1))
        scene.addView(pair, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(0xAA090B12.toInt(), dp(18))
        }
        top.addView(actionButton("Выйти", false) { exitVr() })
        top.addView(actionButton("Перецентрировать", false) {
            baseYaw = null
            basePitch = null
            pair.translationX = 0f
            pair.translationY = 0f
        }, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(5) })
        top.addView(actionButton("−", false) { resizeVr(-0.08f) },
            LinearLayout.LayoutParams(dp(48), dp(42)).apply { leftMargin = dp(5) })
        top.addView(actionButton("+", false) { resizeVr(0.08f) },
            LinearLayout.LayoutParams(dp(48), dp(42)).apply { leftMargin = dp(5) })
        scene.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(10)
        })

        val play = actionButton(if (main.isPlaying) "Ⅱ Пауза" else "▶ Play", true) { togglePlayback() }
        play.setOnClickListener {
            togglePlayback()
            play.text = if (main.isPlaying) "Ⅱ Пауза" else "▶ Play"
        }
        scene.addView(play, FrameLayout.LayoutParams(dp(150), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(18)
        })

        preview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackgroundTap < 330L) {
                    relocateVrScreen(event.x, event.y)
                    lastBackgroundTap = 0L
                } else {
                    lastBackgroundTap = now
                }
            }
            true
        }

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        pair.post { updateVrPairSize() }
        baseYaw = null
        basePitch = null
        rotationSensor?.let { sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME) }
        mainHandler.removeCallbacks(syncVrVideo)
        mainHandler.postDelayed(syncVrVideo, 650L)
        toast("VR включён: двойное касание фона перемещает экран")
    }

    private fun startCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (_: Exception) {
                toast("Не удалось запустить камеру. VR-фон будет пустым.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun resizeVr(delta: Float) {
        sizeProgress = (sizeProgress + (delta * 100).toInt()).coerceIn(10, 100)
        updateVrPairSize()
    }

    private fun updateVrPairSize() {
        val pair = vrPair ?: return
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val factor = sizeFactor() * distanceFactor()
        val targetW = (w * 0.92f * factor).toInt().coerceAtLeast(dp(180))
        val targetH = (h * 0.66f * factor).toInt().coerceAtLeast(dp(120))
        val params = pair.layoutParams as? FrameLayout.LayoutParams
        if (params == null) {
            pair.layoutParams = FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)
        } else {
            params.width = targetW
            params.height = targetH
            pair.layoutParams = params
        }
    }

    private fun relocateVrScreen(touchX: Float, touchY: Float) {
        val pair = vrPair ?: return
        val parent = vrRoot ?: return
        val width = pair.width.takeIf { it > 0 } ?: (parent.width * 0.9f).toInt()
        val height = pair.height.takeIf { it > 0 } ?: (parent.height * 0.65f).toInt()
        val left = (touchX - width / 2f).coerceIn(0f, max(0f, (parent.width - width).toFloat()))
        val top = (touchY - height / 2f).coerceIn(dp(42).toFloat(), max(dp(42).toFloat(), (parent.height - height - dp(55)).toFloat()))
        val params = FrameLayout.LayoutParams(width, height)
        pair.layoutParams = params
        pair.x = left
        pair.y = top
        pair.translationX = 0f
        pair.translationY = 0f
        baseYaw = null
        basePitch = null
    }

    private fun exitVr(rebuildTheatre: Boolean = true) {
        if (vrRoot == null) return
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(syncVrVideo)
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        cameraPreview = null
        vrTextureLeft?.player = null
        vrTextureRight?.player = null
        vrPlayer?.release()
        vrPlayer = null
        vrTextureLeft = null
        vrTextureRight = null
        mainTexture?.player = null
        mainTexture = null
        vrPair = null
        vrRoot = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        if (rebuildTheatre) buildTheatre()
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        if (event == null || vrRoot == null || event.sensor.type != android.hardware.Sensor.TYPE_ROTATION_VECTOR) return
        val matrix = FloatArray(9)
        android.hardware.SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val orientation = FloatArray(3)
        android.hardware.SensorManager.getOrientation(matrix, orientation)
        val yaw = orientation[0]
        val pitch = orientation[1]
        if (baseYaw == null) baseYaw = yaw
        if (basePitch == null) basePitch = pitch
        val yawDelta = normalizeAngle(yaw - (baseYaw ?: yaw))
        val pitchDelta = normalizeAngle(pitch - (basePitch ?: pitch))
        val pair = vrPair ?: return
        val horizontal = (yawDelta * 260f).coerceIn(-resources.displayMetrics.widthPixels * 0.34f, resources.displayMetrics.widthPixels * 0.34f)
        val vertical = (pitchDelta * 210f).coerceIn(-resources.displayMetrics.heightPixels * 0.24f, resources.displayMetrics.heightPixels * 0.24f)
        pair.translationX = -horizontal
        pair.translationY = vertical
    }

    private fun normalizeAngle(value: Float): Float {
        var result = value
        while (result > Math.PI.toFloat()) result -= (2f * Math.PI.toFloat())
        while (result < -Math.PI.toFloat()) result += (2f * Math.PI.toFloat())
        return result
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit

    override fun onPause() {
        super.onPause()
        if (vrRoot != null) sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (vrRoot != null) rotationSensor?.let {
            sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun releasePlayers() {
        mainHandler.removeCallbacks(syncVrVideo)
        vrPlayer?.release()
        vrPlayer = null
        player?.release()
        player = null
        mainTexture?.player = null
        mainTexture = null
        vrTextureLeft?.player = null
        vrTextureLeft = null
        vrTextureRight = null
    }

    override fun onBackPressed() {
        when {
            vrRoot != null -> exitVr()
            sourceUri != null -> {
                releasePlayers()
                sourceUri = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                showHome()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        releasePlayers()
        super.onDestroy()
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.rgb(17, 21, 32), dp(20), Color.rgb(38, 45, 62))
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

    private fun actionButton(text: String, primary: Boolean, action: () -> Unit): com.google.android.material.button.MaterialButton {
        return com.google.android.material.button.MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (primary) Color.rgb(111, 73, 205) else Color.rgb(39, 46, 64)
            )
            setOnClickListener { action() }
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    private fun simpleSeek(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private class CurvedScreenFrame(context: android.content.Context) : FrameLayout(context) {
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2f
            color = Color.rgb(133, 105, 207)
        }

        override fun dispatchDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val bend = (width * 0.075f).coerceAtLeast(12f)
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                quadTo(w - bend, h * 0.5f, w, h)
                lineTo(0f, h)
                quadTo(bend, h * 0.5f, 0f, 0f)
                close()
            }
            val save = canvas.save()
            canvas.clipPath(path)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
            edge.alpha = 210
            canvas.drawPath(path, edge)
        }
    }
}
