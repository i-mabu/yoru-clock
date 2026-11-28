package jp.yoru.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

// ========================================
// 📣 発火レシーバー: 高優先度通知 (フルスクリーンインテント) を主経路に鳴動画面を出す
// ========================================
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND) ?: "alarm"
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL)
        val hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1)
        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)

        var item: AlarmItem? = null
        if (kind == "alarm" && alarmId >= 0) {
            val items = AlarmStore.load(context)
            item = AlarmStore.findById(items, alarmId)
            if (!isSnooze) {
                // 本体アラームの発火: 削除済み・無効化済みなら鳴らさない
                if (item == null || !item.enabled) return
                // 単発は発火後に OFF へ、繰り返しは次回分を再登録する
                if (item.hasRepeat()) {
                    AlarmScheduler.scheduleAlarm(context, item)
                } else {
                    item.enabled = false
                    AlarmStore.save(context, items)
                }
            }
        }
        if (kind == "timer") {
            // タイマーは終了状態にする (タイマータブ再開時に初期表示へ戻る)
            context.getSharedPreferences(AlarmScheduler.TIMER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
                .apply()
        }

        val notifId = if (kind == "timer") AlarmScheduler.NOTIF_TIMER_ID else AlarmScheduler.notifIdFor(item)

        val ringIntent = Intent(context, RingActivity::class.java)
        ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ringIntent.putExtra(AlarmScheduler.EXTRA_KIND, kind)
        ringIntent.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        ringIntent.putExtra(AlarmScheduler.EXTRA_LABEL, label ?: "")
        ringIntent.putExtra(AlarmScheduler.EXTRA_HOUR, hour)
        ringIntent.putExtra(AlarmScheduler.EXTRA_MINUTE, minute)
        ringIntent.putExtra(AlarmScheduler.EXTRA_NOTIF_ID, notifId)

        // 主経路: 高優先度通知 + フルスクリーンインテント (バックグラウンドでも鳴動画面を出せる)
        val ringPending = PendingIntent.getActivity(
            context, notifId, ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        postRingNotification(context, kind, label, hour, minute, notifId, ringPending)

        // 副経路: 直接起動も試みる (重なった場合は RingActivity の onNewIntent で吸収する)
        try {
            context.startActivity(ringIntent)
        } catch (ignored: Exception) {
            // バックグラウンド起動が制限される環境では通知経路に任せる
        }
    }

    // ----------------------------------------
    // 🔔 鳴動通知の投稿
    // ----------------------------------------
    private fun postRingNotification(
        context: Context, kind: String, label: String?,
        hour: Int, minute: Int, notifId: Int, ringPending: PendingIntent
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "アラーム", NotificationManager.IMPORTANCE_HIGH)
        )
        val isTimer = kind == "timer"
        val title = if (isTimer) "タイマー終了" else "アラーム"
        val timeText = if (hour >= 0) {
            ClockLogic.formatHourMinute(hour, minute)
        } else {
            val now = Calendar.getInstance()
            ClockLogic.formatHourMinute(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        }
        val text = if (label.isNullOrEmpty()) timeText else label + " ・ " + timeText
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setFullScreenIntent(ringPending, true)
            .setContentIntent(ringPending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(notifId, notification)
        } catch (ignored: Exception) {
            // 通知許可がない環境では直接起動の副経路に任せる
        }
    }

    companion object {
        const val CHANNEL_ID = "alarms"
    }
}
