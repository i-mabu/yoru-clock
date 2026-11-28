package jp.yoru.clock

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

// ========================================
// 🔊 鳴動画面 (アラーム/タイマー共通): 音 + バイブ、スヌーズ 5 分 / 停止
// ========================================
class RingActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var kind: String = "alarm"
    private var alarmId: Long = -1
    private var label: String = ""
    private var notifId: Int = -1
    private var alarmRc: Int = 0

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable {
        stopRinging()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ロック画面の上に表示し、画面を点ける
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val intent = intent
        kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: "alarm"
        alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        notifId = intent.getIntExtra(AlarmScheduler.EXTRA_NOTIF_ID, -1)
        // スヌーズの requestCode を分離するため、このアラームの rc を引いておく
        if (kind == "alarm" && alarmId >= 0) {
            val self = AlarmStore.findById(AlarmStore.load(this), alarmId)
            if (self != null) alarmRc = self.rc
        }
        val hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1)
        val timeText = if (hour >= 0) {
            ClockLogic.formatHourMinute(hour, minute)
        } else {
            val now = Calendar.getInstance()
            ClockLogic.formatHourMinute(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        }
        val title = if (kind == "timer") "タイマー終了" else "アラーム"
        val shownLabel = if (label.isEmpty()) title else label

        setContent {
            // 誤操作防止: 戻るボタンでは閉じない (停止 / スヌーズを使う)
            BackHandler { }
            YoruClockTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NightBg)
                        .padding(48.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = title, color = NightAccent, fontSize = 20.sp, textAlign = TextAlign.Center)
                    Text(text = timeText, color = NightText, fontSize = 72.sp, textAlign = TextAlign.Center)
                    Text(text = shownLabel, color = NightGold, fontSize = 26.sp, textAlign = TextAlign.Center)
                    Button(
                        onClick = {
                            // スヌーズ表示用の接尾辞は重ねないよう外してから登録する
                            val baseLabel = if (label.isEmpty()) title else label.replace("（スヌーズ）", "")
                            AlarmScheduler.scheduleSnooze(this@RingActivity, kind, alarmId, baseLabel, alarmRc)
                            stopRinging()
                            finish()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 64.dp, bottom = 16.dp)
                    ) { Text("スヌーズ（5分）", fontSize = 18.sp) }
                    Button(
                        onClick = {
                            stopRinging()
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("停止", fontSize = 20.sp) }
                }
            }
        }
        startRinging()
        timeoutHandler.removeCallbacks(autoStop)
        timeoutHandler.postDelayed(autoStop, AUTO_STOP_MILLIS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 通知経路と直接起動が重なった場合は、新しい Intent で画面を作り直して一本化する
        setIntent(intent)
        stopRinging()
        timeoutHandler.removeCallbacks(autoStop)
        // 作り直した側のインスタンスが通知を消すので、旧インスタンスの onDestroy では消さない
        notifId = -1
        recreate()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(autoStop)
        stopRinging()
        // 対応する高優先度通知も消す
        if (notifId > 0) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(notifId)
        }
        super.onDestroy()
    }

    // ----------------------------------------
    // 🔊 鳴動の開始と停止
    // ----------------------------------------
    private fun startRinging() {
        // 既定のアラーム音、無ければ通知音をループ再生する
        val soundUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (soundUri != null) {
            try {
                val player = MediaPlayer()
                mediaPlayer = player
                player.setDataSource(this, soundUri)
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.isLooping = true
                player.prepare()
                player.start()
            } catch (ignored: Exception) {
                releasePlayer()
            }
        }
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator = vib
        if (vib != null) {
            val pattern = longArrayOf(0, 500, 300, 500, 700)
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRinging() {
        releasePlayer()
        vibrator?.cancel()
    }

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        try {
            player.stop()
        } catch (ignored: Exception) {
        }
        player.release()
        mediaPlayer = null
    }

    companion object {
        /** 鳴動は最大 10 分で自動停止する */
        private const val AUTO_STOP_MILLIS = 10L * 60L * 1000L
    }
}
