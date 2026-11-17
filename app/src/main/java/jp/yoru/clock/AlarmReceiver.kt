package jp.yoru.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

// ===== 1. 発火用レシーバー: 高優先度通知 (full-screen intent) を主経路に鳴動画面を出し、単発アラームはOFFにする =====
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        var kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND)
        if (kind == null) kind = "alarm"
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL)
        val hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1)
        val snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)

        var item: AlarmItem? = null
        if ("alarm" == kind && alarmId >= 0) {
            val items = AlarmStore.load(context)
            item = AlarmStore.findById(items, alarmId)
            if (!snooze) {
                // 本体アラームの発火: 対象が削除済み・無効化済みなら鳴らさない
                if (item == null || !item.enabled) return
                // 単発アラームは発火後にOFFへ。繰り返しは次回分を再登録する。
                if (item.hasRepeat()) {
                    AlarmScheduler.scheduleAlarm(context, item)
                } else {
                    item.enabled = false
                    AlarmStore.save(context, items)
                }
            }
        }
        if ("timer" == kind) {
            // タイマーは終了状態にする (MainActivity再開時に 00:00 表示へ)
            context.getSharedPreferences(AlarmScheduler.TIMER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
                .apply()
        }

        val notifId = if ("timer" == kind)
            AlarmScheduler.NOTIF_TIMER_ID
        else
            AlarmScheduler.notifIdFor(item)

        val ring = Intent(context, RingActivity::class.java)
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ring.putExtra(AlarmScheduler.EXTRA_KIND, kind)
        ring.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        ring.putExtra(AlarmScheduler.EXTRA_LABEL, label ?: "")
        ring.putExtra(AlarmScheduler.EXTRA_HOUR, hour)
        ring.putExtra(AlarmScheduler.EXTRA_MINUTE, minute)
        ring.putExtra(AlarmScheduler.EXTRA_NOTIF_ID, notifId)

        // 主経路: 高優先度通知 + full-screen intent (バックグラウンドでも鳴動画面を出せる)
        val ringPi = PendingIntent.getActivity(context, notifId, ring,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        postNotification(context, kind, label, hour, minute, notifId, ringPi)

        // 副経路: 直接起動も試みる (重なった場合は RingActivity の onNewIntent で吸収)
        try {
            context.startActivity(ring)
        } catch (ignored: Exception) {
            // バックグラウンド起動制限時は通知経路に任せる
        }
    }

    // ----- 1.1 鳴動通知の投稿 -----
    private fun postNotification(context: Context, kind: String, label: String?,
                                 hour: Int, minute: Int, notifId: Int, ringPi: PendingIntent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID, "アラーム", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)

        val isTimer = "timer" == kind
        val title = if (isTimer) "タイマー終了" else "アラーム"
        val timeText: String
        if (hour >= 0) {
            timeText = ClockLogic.formatHourMinute(hour, minute)
        } else {
            val now = Calendar.getInstance()
            timeText = ClockLogic.formatHourMinute(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        }
        val text = if (label == null || label.isEmpty()) timeText else label + " ・ " + timeText

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setFullScreenIntent(ringPi, true)
            .setContentIntent(ringPi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(notifId, notification)
        } catch (ignored: Exception) {
            // 通知許可がない環境では直接起動の副経路に任せる
        }
    }

    companion object {
        const val CHANNEL_ID = "alarms"
    }
}
