package jp.yoru.clock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.List;

// ===== 発火用レシーバー: 高優先度通知 (full-screen intent) を主経路に鳴動画面を出し、単発アラームはOFFにする =====
public final class AlarmReceiver extends BroadcastReceiver {
    public static final String CHANNEL_ID = "alarms";

    @Override
    public void onReceive(Context context, Intent intent) {
        String kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND);
        if (kind == null) kind = "alarm";
        long alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1);
        String label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL);
        int hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1);
        int minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1);
        boolean snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false);

        AlarmItem item = null;
        if ("alarm".equals(kind) && alarmId >= 0) {
            List<AlarmItem> items = AlarmStore.load(context);
            item = AlarmStore.findById(items, alarmId);
            if (!snooze) {
                // 本体アラームの発火: 対象が削除済み・無効化済みなら鳴らさない
                if (item == null || !item.enabled) return;
                // 単発アラームは発火後にOFFへ。繰り返しは次回分を再登録する。
                if (item.hasRepeat()) {
                    AlarmScheduler.scheduleAlarm(context, item);
                } else {
                    item.enabled = false;
                    AlarmStore.save(context, items);
                }
            }
        }
        if ("timer".equals(kind)) {
            // タイマーは終了状態にする (MainActivity再開時に 00:00 表示へ)
            context.getSharedPreferences(AlarmScheduler.TIMER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
                .apply();
        }

        int notifId = "timer".equals(kind)
            ? AlarmScheduler.NOTIF_TIMER_ID
            : AlarmScheduler.notifIdFor(item);

        Intent ring = new Intent(context, RingActivity.class);
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ring.putExtra(AlarmScheduler.EXTRA_KIND, kind);
        ring.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        ring.putExtra(AlarmScheduler.EXTRA_LABEL, label == null ? "" : label);
        ring.putExtra(AlarmScheduler.EXTRA_HOUR, hour);
        ring.putExtra(AlarmScheduler.EXTRA_MINUTE, minute);
        ring.putExtra(AlarmScheduler.EXTRA_NOTIF_ID, notifId);

        // 主経路: 高優先度通知 + full-screen intent (バックグラウンドでも鳴動画面を出せる)
        PendingIntent ringPi = PendingIntent.getActivity(context, notifId, ring,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        postNotification(context, kind, label, hour, minute, notifId, ringPi);

        // 副経路: 直接起動も試みる (重なった場合は RingActivity の onNewIntent で吸収)
        try {
            context.startActivity(ring);
        } catch (Exception ignored) {
            // バックグラウンド起動制限時は通知経路に任せる
        }
    }

    private void postNotification(Context context, String kind, String label,
                                  int hour, int minute, int notifId, PendingIntent ringPi) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "アラーム", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(channel);

        boolean isTimer = "timer".equals(kind);
        String title = isTimer ? "タイマー終了" : "アラーム";
        String timeText;
        if (hour >= 0) {
            timeText = ClockLogic.formatHourMinute(hour, minute);
        } else {
            Calendar now = Calendar.getInstance();
            timeText = ClockLogic.formatHourMinute(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        }
        String text = (label == null || label.isEmpty()) ? timeText : label + " ・ " + timeText;

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_HIGH)
            .setFullScreenIntent(ringPi, true)
            .setContentIntent(ringPi)
            .setAutoCancel(true)
            .build();
        try {
            nm.notify(notifId, notification);
        } catch (Exception ignored) {
            // 通知許可がない環境では直接起動の副経路に任せる
        }
    }
}
