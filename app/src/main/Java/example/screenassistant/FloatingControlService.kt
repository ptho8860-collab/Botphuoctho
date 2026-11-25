package com.example.screenassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class FloatingControlService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var tvMiniStatus: TextView? = null
    private var btnVoice: Button? = null
    private var btnStop: Button? = null

    private val handler = Handler(Looper.getMainLooper())
    private var controller: AssistantAutomationController? = null
    private var voiceManager: VoiceCommandManager? = null

    companion object {
        private const val CHANNEL_ID = "floating_bot_channel"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FloatingControlService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingControlService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Nút điều khiển nhanh đang bật"))

        val autoController = AssistantAutomationController {
            ScreenAssistantService.instance
        }
        val voiceCmd = VoiceCommandManager(applicationContext, autoController)

        controller = autoController
        voiceManager = voiceCmd

        setupFloatingView(autoController, voiceCmd)
        bindCallbacks(autoController, voiceCmd)
    }

    private fun bindCallbacks(
        autoController: AssistantAutomationController,
        voiceCmd: VoiceCommandManager
    ) {
        autoController.onStateChanged = { mode, status ->
            handler.post {
                val running = mode != AssistantAutomationController.Mode.IDLE
                btnStop?.visibility = if (running) View.VISIBLE else View.GONE
                tvMiniStatus?.text = if (running) status else "Sẵn sàng"
                updateNotification(if (running) "Đang chạy: $status" else "Nút điều khiển đang bật")
            }
        }

        voiceCmd.onVoiceStatus = { voiceStatus ->
            handler.post {
                tvMiniStatus?.text = voiceStatus
            }
        }
    }

    private fun setupFloatingView(
        autoController: AssistantAutomationController,
        voiceCmd: VoiceCommandManager
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 12, 18, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC18181B"))
                cornerRadius = 48f
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
        }

        val statusView = TextView(this).apply {
            text = "Sẵn sàng"
            setTextColor(Color.parseColor("#E4E4E7"))
            textSize = 11f
            maxWidth = 220
            maxLines = 1
            setPadding(0, 0, 12, 0)
        }

        val voiceButton = Button(this).apply {
            text = "Mic"
            textSize = 12f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(22, 10, 22, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2563EB"))
                cornerRadius = 36f
            }
            setOnClickListener {
                voiceCmd.startListening(keepListeningAfterCommand = true)
            }
        }

        val stopButton = Button(this).apply {
            text = "Dừng"
            textSize = 12f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(22, 10, 22, 10)
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DC2626"))
                cornerRadius = 36f
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 10
            }
            layoutParams = lp
            setOnClickListener {
                voiceCmd.stopListening()
                autoController.stopAll("Đã bấm nút Dừng nổi")
            }
        }

        container.addView(statusView)
        container.addView(voiceButton)
        container.addView(stopButton)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 260
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                    }
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    windowManager.updateViewLayout(container, params)
                    true
                }

                MotionEvent.ACTION_UP -> moved
                else -> false
            }
        }

        windowManager.addView(container, params)
        floatingView = container
        tvMiniStatus = statusView
        btnVoice = voiceButton
        btnStop = stopButton
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Điều khiển Bot nổi",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenAssistant")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(content))
    }

    override fun onDestroy() {
        voiceManager?.destroy()
        controller?.stopAll("Tắt nút nổi")
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        floatingView = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
