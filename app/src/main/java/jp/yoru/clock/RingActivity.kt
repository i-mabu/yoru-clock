package jp.yoru.clock

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

// ===== 1. 鳴動画面 (アラーム/タイマー共通): 音 + バイブ + スヌーズ5分 / 停止 =====
class RingActivity : Activity() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var kind: String = "alarm"
    private var alarmId: Long = -1
    private var label: String = ""
    private var notifId: Int = -1
    private var alarmRc: Int = 0

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val ringTimeout: Runnable = Runnable { onRingTimeout() }

    private fun onRingTimeout() {
        stopRinging()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ロック画面の上に出し、画面を点ける
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val intent = intent
        kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: "alarm"
        alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        notifId = intent.getIntExtra(AlarmScheduler.EXTRA_NOTIF_ID, -1)
        // スヌーズの requestCode 分離用に、このアラームの rc を引いておく
        if ("alarm" == kind && alarmId >= 0) {
            val self = AlarmStore.findById(AlarmStore.load(this), alarmId)
            if (self != null) alarmRc = self.rc
        }
        val hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1)

        val timeText: String
        if (hour >= 0) {
            timeText = ClockLogic.formatHourMinute(hour, minute)
        } else {
            val now = Calendar.getInstance()
            timeText = ClockLogic.formatHourMinute(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        }
        val title = if ("timer" == kind) "タイマー終了" else "アラーム"
        val displayLabel = if (label.isEmpty()) title else label

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0B1026"))
        root.setPadding(48, 48, 48, 48)

        val titleView = TextView(this)
        titleView.text = title
        titleView.setTextColor(Color.parseColor("#8FA8FF"))
        titleView.textSize = 20f
        titleView.gravity = Gravity.CENTER
        root.addView(titleView)

        val timeView = TextView(this)
        timeView.text = timeText
        timeView.setTextColor(Color.parseColor("#F2F3FA"))
        timeView.textSize = 72f
        timeView.gravity = Gravity.CENTER
        root.addView(timeView)

        val labelView = TextView(this)
        labelView.text = displayLabel
        labelView.setTextColor(Color.parseColor("#FFC46B"))
        labelView.textSize = 26f
        labelView.gravity = Gravity.CENTER
        root.addView(labelView)

        val snooze = Button(this)
        snooze.text = "スヌーズ（5分）"
        snooze.textSize = 18f
        snooze.setOnClickListener {
            AlarmScheduler.scheduleSnooze(this, kind, alarmId, if (label.isEmpty()) title else label.replace("（スヌーズ）", ""), alarmRc)
            stopRinging()
            finish()
        }
        val bp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bp.setMargins(0, 64, 0, 16)
        root.addView(snooze, bp)

        val stop = Button(this)
        stop.text = "停止"
        stop.textSize = 20f
        stop.setOnClickListener {
            stopRinging()
            finish()
        }
        root.addView(stop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        startRinging()
        timeoutHandler.removeCallbacks(ringTimeout)
        timeoutHandler.postDelayed(ringTimeout, AUTO_STOP_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 通知経路と直接起動が重なった場合は、新しい Intent で画面を作り直して一本化する
        setIntent(intent)
        stopRinging()
        timeoutHandler.removeCallbacks(ringTimeout)
        // 作り直した新しいインスタンス側で通知を消すため、旧インスタンスの onDestroy では消さない
        notifId = -1
        recreate()
    }

    // ----- 1.1 鳴動の開始と停止 -----
    private fun startRinging() {
        // デフォルトアラーム音 → なければ通知音
        var uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri != null) {
            try {
                player = MediaPlayer()
                player!!.setDataSource(this, uri)
                player!!.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                player!!.isLooping = true
                player!!.prepare()
                player!!.start()
            } catch (ignored: Exception) {
                if (player != null) {
                    try { player!!.release() } catch (ignored2: Exception) {}
                    player = null
                }
            }
        }
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null) {
            val pattern = longArrayOf(0, 500, 300, 500, 700)
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator!!.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator!!.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRinging() {
        if (player != null) {
            try { player!!.stop() } catch (ignored: Exception) {}
            player!!.release()
            player = null
        }
        if (vibrator != null) vibrator!!.cancel()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(ringTimeout)
        stopRinging()
        // 対応する高優先度通知も消す
        if (notifId > 0) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) nm.cancel(notifId)
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 誤操作防止: 戻るでは閉じない (停止/スヌーズボタンを使う)
    }

    companion object {
        private const val AUTO_STOP_MS = 10L * 60L * 1000L // 鳴動は最大10分で自動停止
    }
}
