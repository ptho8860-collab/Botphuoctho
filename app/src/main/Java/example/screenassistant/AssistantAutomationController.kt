package com.example.screenassistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.random.Random

data class GameActionRule(
    val name: String,
    val keywords: List<String> = emptyList(),
    val leftRatio: Float,
    val topRatio: Float,
    val rightRatio: Float,
    val bottomRatio: Float,
    val fallbackTapIfNoOcr: Boolean = false,
    val cooldownMs: Long = 3_500L
)

class AssistantAutomationController(
    private val getService: () -> ScreenAssistantService?
) {
    enum class Mode {
        IDLE,
        PLAYING_MUSIC,
        SCROLLING_TIKTOK,
        GAME_ASSIST
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentMode: Mode = Mode.IDLE
    private var isGestureBusy: Boolean = false
    private var tiktokCount: Int = 0
    private var maxTiktokVideos: Int? = null
    private var baseDwellMs: Long = 6_000L
    private var jitterMs: Long = 1_500L

    private val lastTappedAtMs = mutableMapOf<String, Long>()
    private var gameRules: List<GameActionRule> = emptyList()
    private var gameScanIntervalMs: Long = 1_200L

    var onStateChanged: ((Mode, String) -> Unit)? = null

    companion object {
        private const val TAG = "AssistantController"
        const val PKG_YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
        const val PKG_SPOTIFY = "com.spotify.music"
        const val PKG_TIKTOK = "com.zhiliaoapp.musically"
    }

    fun isRunning(): Boolean = currentMode != Mode.IDLE

    fun stopAll(reason: String = "Đã dừng bởi người dùng") {
        handler.removeCallbacksAndMessages(null)
        currentMode = Mode.IDLE
        isGestureBusy = false
        Log.d(TAG, "Dừng tác vụ: $reason")
        onStateChanged?.invoke(Mode.IDLE, reason)
    }

    fun openMusicAndPlay(
        musicPackageName: String = PKG_YOUTUBE_MUSIC,
        startupWaitMs: Long = 3_000L,
        maxRetries: Int = 4
    ) {
        val service = getService()
        if (service == null) {
            onStateChanged?.invoke(Mode.IDLE, "Chưa bật quyền AccessibilityService")
            return
        }

        stopAll("Khởi động luồng bật nhạc")
        currentMode = Mode.PLAYING_MUSIC
        onStateChanged?.invoke(Mode.PLAYING_MUSIC, "Đang mở ứng dụng nhạc...")

        val opened = service.launchApp(musicPackageName)
        if (!opened) {
            stopAll("Không mở được ứng dụng nhạc")
            return
        }

        var attempt = 0
        val tryFindAndPlay = object : Runnable {
            override fun run() {
                if (currentMode != Mode.PLAYING_MUSIC) return
                val activeService = getService() ?: run {
                    stopAll("Mất kết nối dịch vụ")
                    return
                }

                if (isGestureBusy) {
                    handler.postDelayed(this, 400L)
                    return
                }

                attempt++
                val playKeywords = listOf("Play", "Phát", "Resume", "Tiếp tục")
                val targetCoords = playKeywords.firstNotNullOfOrNull { keyword ->
                    activeService.findCenterOfText(keyword)
                }

                if (targetCoords != null) {
                    isGestureBusy = true
                    onStateChanged?.invoke(Mode.PLAYING_MUSIC, "Đã thấy nút phát, đang bấm...")
                    activeService.tap(targetCoords.first, targetCoords.second) { success ->
                        isGestureBusy = false
                        if (success) {
                            currentMode = Mode.IDLE
                            onStateChanged?.invoke(Mode.IDLE, "Đã bấm phát nhạc thành công")
                        } else if (attempt < maxRetries) {
                            handler.postDelayed(this, 1_200L)
                        } else {
                            stopAll("Chạm nút phát không thành công")
                        }
                    }
                } else if (attempt < maxRetries) {
                    onStateChanged?.invoke(
                        Mode.PLAYING_MUSIC,
                        "Đang đợi giao diện nhạc tải ($attempt/$maxRetries)..."
                    )
                    handler.postDelayed(this, 1_200L)
                } else {
                    val dm = activeService.resources.displayMetrics
                    val fallbackX = dm.widthPixels * 0.85f
                    val fallbackY = dm.heightPixels * 0.88f
                    isGestureBusy = true
                    activeService.tap(fallbackX, fallbackY) {
                        isGestureBusy = false
                        currentMode = Mode.IDLE
                        onStateChanged?.invoke(Mode.IDLE, "Đã chạm vùng phát dự phòng")
                    }
                }
            }
        }

        handler.postDelayed(tryFindAndPlay, startupWaitMs)
    }

    fun startTikTokScrollLoop(
        dwellTimeMs: Long = 6_000L,
        jitterMs: Long = 1_500L,
        maxVideos: Int? = null,
        openAppFirst: Boolean = true,
        initialLaunchDelayMs: Long = 3_500L
    ) {
        val service = getService()
        if (service == null) {
            onStateChanged?.invoke(Mode.IDLE, "Chưa bật quyền AccessibilityService")
            return
        }

        stopAll("Khởi động vòng lặp lướt TikTok")
        currentMode = Mode.SCROLLING_TIKTOK
        baseDwellMs = dwellTimeMs.coerceAtLeast(1_500L)
        this.jitterMs = jitterMs.coerceAtLeast(0L)
        maxTiktokVideos = maxVideos
        tiktokCount = 0

        if (openAppFirst) {
            onStateChanged?.invoke(Mode.SCROLLING_TIKTOK, "Đang mở TikTok...")
            if (!service.launchApp(PKG_TIKTOK)) {
                stopAll("Không mở được TikTok")
                return
            }
            handler.postDelayed(scrollRunnable, initialLaunchDelayMs)
        } else {
            onStateChanged?.invoke(Mode.SCROLLING_TIKTOK, "Bắt đầu lướt TikTok...")
            handler.postDelayed(scrollRunnable, computeNextDelay())
        }
    }

    private fun computeNextDelay(): Long {
        val offset = if (jitterMs > 0L) Random.nextLong(-jitterMs, jitterMs + 1L) else 0L
        return (baseDwellMs + offset).coerceAtLeast(1_500L)
    }

    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (currentMode != Mode.SCROLLING_TIKTOK) return

            val limit = maxTiktokVideos
            if (limit != null && tiktokCount >= limit) {
                stopAll("Đã lướt đủ $limit video")
                return
            }

            val activeService = getService() ?: run {
                stopAll("Mất kết nối dịch vụ")
                return
            }

            if (isGestureBusy) {
                handler.postDelayed(this, 400L)
                return
            }

            isGestureBusy = true
            val dm = activeService.resources.displayMetrics
            val xOffset = Random.nextInt(-30, 31).toFloat()
            val startX = (dm.widthPixels * 0.5f) + xOffset
            val endX = startX + Random.nextInt(-15, 16).toFloat()
            val startY = dm.heightPixels * Random.nextFloat().coerceIn(0.74f, 0.82f)
            val endY = dm.heightPixels * Random.nextFloat().coerceIn(0.22f, 0.30f)
            val swipeDuration = Random.nextLong(260L, 400L)

            activeService.swipe(startX, startY, endX, endY, swipeDuration) { success ->
                isGestureBusy = false
                if (currentMode != Mode.SCROLLING_TIKTOK) return@swipe

                if (success) {
                    tiktokCount++
                    val nextDelay = computeNextDelay()
                    onStateChanged?.invoke(
                        Mode.SCROLLING_TIKTOK,
                        "Đã lướt $tiktokCount video — video sau sau ${nextDelay / 1000.0}s"
                    )
                    handler.postDelayed(this, nextDelay)
                } else {
                    Log.w(TAG, "Cử chỉ vuốt bị hủy, thử lại sau 2s")
                    handler.postDelayed(this, 2_000L)
                }
            }
        }
    }

    fun defaultBasicGameRules(): List<GameActionRule> = listOf(
        GameActionRule(
            name = "NutNhan",
            keywords = listOf("Nhận", "Claim", "Thu thập"),
            leftRatio = 0.35f,
            topRatio = 0.65f,
            rightRatio = 0.65f,
            bottomRatio = 0.90f,
            fallbackTapIfNoOcr = false,
            cooldownMs = 3_000L
        ),
        GameActionRule(
            name = "NutTiepTuc",
            keywords = listOf("Tiếp tục", "Continue", "Bắt đầu", "Start"),
            leftRatio = 0.65f,
            topRatio = 0.70f,
            rightRatio = 0.95f,
            bottomRatio = 0.95f,
            fallbackTapIfNoOcr = false,
            cooldownMs = 4_000L
        )
    )

    fun startGameBasicAssist(
        rules: List<GameActionRule> = defaultBasicGameRules(),
        scanIntervalMs: Long = 1_200L
    ) {
        val service = getService()
        if (service == null) {
            onStateChanged?.invoke(Mode.IDLE, "Chưa bật quyền AccessibilityService")
            return
        }

        stopAll("Khởi động hỗ trợ game cơ bản")
        currentMode = Mode.GAME_ASSIST
        gameRules = rules
        gameScanIntervalMs = scanIntervalMs.coerceAtLeast(600L)
        lastTappedAtMs.clear()

        onStateChanged?.invoke(Mode.GAME_ASSIST, "Đang theo dõi nút trong game...")
        handler.postDelayed(gameScanRunnable, 600L)
    }

    private val gameScanRunnable = object : Runnable {
        override fun run() {
            if (currentMode != Mode.GAME_ASSIST) return

            val activeService = getService() ?: run {
                stopAll("Mất kết nối dịch vụ")
                return
            }

            if (isGestureBusy) {
                handler.postDelayed(this, 350L)
                return
            }

            val now = System.currentTimeMillis()
            val candidate = gameRules.firstOrNull { rule ->
                val lastTime = lastTappedAtMs[rule.name] ?: 0L
                (now - lastTime) >= rule.cooldownMs
            }

            if (candidate == null) {
                handler.postDelayed(this, gameScanIntervalMs)
                return
            }

            evaluateAndTapRule(activeService, candidate) { tapped ->
                if (currentMode != Mode.GAME_ASSIST) return@evaluateAndTapRule
                val nextDelay = if (tapped) 1_000L else gameScanIntervalMs
                handler.postDelayed(this, nextDelay)
            }
        }
    }

    private fun evaluateAndTapRule(
        service: ScreenAssistantService,
        rule: GameActionRule,
        onFinished: (Boolean) -> Unit
    ) {
        for (kw in rule.keywords) {
            val nodeCenter = service.findCenterOfText(kw)
            if (nodeCenter != null) {
                performRuleTap(service, rule, nodeCenter.first, nodeCenter.second, onFinished)
                return
            }
        }

        if (rule.keywords.isNotEmpty()) {
            service.recognizeTextInNormalizedRegion(
                rule.leftRatio,
                rule.topRatio,
                rule.rightRatio,
                rule.bottomRatio
            ) { ocrText ->
                if (currentMode != Mode.GAME_ASSIST) {
                    onFinished(false)
                    return@recognizeTextInNormalizedRegion
                }

                val matched = rule.keywords.any { kw ->
                    ocrText.contains(kw, ignoreCase = true)
                }

                if (matched) {
                    val dm = service.resources.displayMetrics
                    val cx = dm.widthPixels * ((rule.leftRatio + rule.rightRatio) * 0.5f)
                    val cy = dm.heightPixels * ((rule.topRatio + rule.bottomRatio) * 0.5f)
                    performRuleTap(service, rule, cx, cy, onFinished)
                } else if (rule.fallbackTapIfNoOcr) {
                    tapZoneCenter(service, rule, onFinished)
                } else {
                    onFinished(false)
                }
            }
        } else if (rule.fallbackTapIfNoOcr) {
            tapZoneCenter(service, rule, onFinished)
        } else {
            onFinished(false)
        }
    }

    private fun tapZoneCenter(
        service: ScreenAssistantService,
        rule: GameActionRule,
        onFinished: (Boolean) -> Unit
    ) {
        val dm = service.resources.displayMetrics
        val minX = dm.widthPixels * rule.leftRatio
        val maxX = dm.widthPixels * rule.rightRatio
        val minY = dm.heightPixels * rule.topRatio
        val maxY = dm.heightPixels * rule.bottomRatio

        val padX = (maxX - minX) * 0.15f
        val padY = (maxY - minY) * 0.15f
        val tx = Random.nextFloat().coerceIn(0f, 1f) * ((maxX - minX - 2 * padX).coerceAtLeast(1f)) + (minX + padX)
        val ty = Random.nextFloat().coerceIn(0f, 1f) * ((maxY - minY - 2 * padY).coerceAtLeast(1f)) + (minY + padY)

        performRuleTap(service, rule, tx, ty, onFinished)
    }

    private fun performRuleTap(
        service: ScreenAssistantService,
        rule: GameActionRule,
        x: Float,
        y: Float,
        onFinished: (Boolean) -> Unit
    ) {
        isGestureBusy = true
        lastTappedAtMs[rule.name] = System.currentTimeMillis()
        onStateChanged?.invoke(Mode.GAME_ASSIST, "Chạm [${rule.name}]")

        service.tap(x, y, durationMs = Random.nextLong(55L, 95L)) { success ->
            isGestureBusy = false
            if (!success) {
                Log.w(TAG, "Chạm quy tắc ${rule.name} thất bại")
            }
            onFinished(success)
        }
    }
}
