package jp.yoru.clock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.List;

// ===== AlarmManager への登録 (setAlarmClock 優先で正確アラーム権限問題を回避) =====
public final class AlarmScheduler {
    public static final String EXTRA_KIND = "kind";       // "alarm" / "timer" / "snooze"
    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTE = "minute";

    public static final int TIMER_REQUEST_CODE = 900001;
    public static final int SNOOZE_REQUEST_CODE = 900002;

    // タイマー状態の保存キー (MainActivity と共有)
    public static final String TIMER_PREFS = "timer";
    public static final String KEY_TIMER_END = "end_millis";
    public static final String KEY_TIMER_TOTAL = "total_millis";
    public static final String KEY_TIMER_REMAINING = "remaining_millis";
    public static final String KEY_TIMER_RUNNING = "running";

    private AlarmScheduler() {}

    private static PendingIntent alarmPendingIntent(Context ctx, AlarmItem item) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(EXTRA_KIND, "alarm");
        intent.putExtra(EXTRA_ALARM_ID, item.id);
        intent.putExtra(EXTRA_LABEL, item.label);
        intent.putExtra(EXTRA_HOUR, item.hour);
        intent.putExtra(EXTRA_MINUTE, item.minute);
        int rc = (int) (item.id % 800000) + 1000;
        return PendingIntent.getBroadcast(ctx, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 有効なアラームを次回発火時刻で登録。無効なら解除。登録した発火時刻を返す (無効時は -1) */
    public static long scheduleAlarm(Context ctx, AlarmItem item) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return -1;
        PendingIntent pi = alarmPendingIntent(ctx, item);
        if (!item.enabled) {
            am.cancel(pi);
            return -1;
        }
        long trigger = item.nextTriggerMillis(System.currentTimeMillis());
        Intent show = new Intent(ctx, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(ctx, (int) (item.id % 800000) + 1000, show,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // setAlarmClock はアラームアプリ向けAPIで、SCHEDULE_EXACT_ALARM 権限なしで正確に発火できる
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, showPi), pi);
        return trigger;
    }

    public static void cancelAlarm(Context ctx, AlarmItem item) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmPendingIntent(ctx, item));
    }

    // ----- タイマー -----
    private static PendingIntent timerPendingIntent(Context ctx, String label) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(EXTRA_KIND, "timer");
        intent.putExtra(EXTRA_LABEL, label == null ? "タイマー" : label);
        return PendingIntent.getBroadcast(ctx, TIMER_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void scheduleTimer(Context ctx, long endMillis, String label) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent show = new Intent(ctx, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(ctx, TIMER_REQUEST_CODE, show,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(endMillis, showPi), timerPendingIntent(ctx, label));
    }

    public static void cancelTimer(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(timerPendingIntent(ctx, "タイマー"));
    }

    // ----- スヌーズ (5分後) -----
    public static void scheduleSnooze(Context ctx, String kind, long alarmId, String label) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long trigger = System.currentTimeMillis() + 5L * 60L * 1000L;
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(EXTRA_KIND, kind == null ? "alarm" : kind);
        intent.putExtra(EXTRA_ALARM_ID, alarmId);
        intent.putExtra(EXTRA_LABEL, (label == null ? "" : label) + "（スヌーズ）");
        PendingIntent pi = PendingIntent.getBroadcast(ctx, SNOOZE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(ctx, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(ctx, SNOOZE_REQUEST_CODE, show,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, showPi), pi);
    }

    /** 再起動時: 有効な全アラーム + 実行中タイマーを再登録 */
    public static void rescheduleAll(Context ctx) {
        List<AlarmItem> items = AlarmStore.load(ctx);
        for (AlarmItem item : items) {
            if (item.enabled) scheduleAlarm(ctx, item);
        }
        SharedPreferences sp = ctx.getSharedPreferences(TIMER_PREFS, Context.MODE_PRIVATE);
        boolean running = sp.getBoolean(KEY_TIMER_RUNNING, false);
        long end = sp.getLong(KEY_TIMER_END, 0);
        if (running && end > System.currentTimeMillis()) {
            scheduleTimer(ctx, end, "タイマー");
        }
    }
}
