package jp.yoru.clock;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.List;

// ===== AlarmManager への登録 (setAlarmClock 優先で正確アラーム権限問題を回避) =====
// PendingIntent の requestCode 帯域:
//   アラーム本体: AlarmItem.rc (連番, 1〜) / スヌーズ: 600000+rc (タイマーは 600999)
//   通知ID: 700000+rc (タイマーは 700999) / タイマー本体: 900001
public final class AlarmScheduler {
    public static final String EXTRA_KIND = "kind";       // "alarm" / "timer"
    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTE = "minute";
    public static final String EXTRA_SNOOZE = "snooze_flag";
    public static final String EXTRA_NOTIF_ID = "notif_id";

    public static final int TIMER_REQUEST_CODE = 900001;
    public static final int SNOOZE_BASE = 600000;
    public static final int SNOOZE_TIMER_REQUEST_CODE = 600999;
    public static final int NOTIF_BASE = 700000;
    public static final int NOTIF_TIMER_ID = 700999;

    // タイマー状態の保存キー (MainActivity と共有)
    public static final String TIMER_PREFS = "timer";
    public static final String KEY_TIMER_END = "end_millis";
    public static final String KEY_TIMER_TOTAL = "total_millis";
    public static final String KEY_TIMER_REMAINING = "remaining_millis";
    public static final String KEY_TIMER_RUNNING = "running";

    private AlarmScheduler() {}

    /** アラーム本体の requestCode: 採番済み rc を優先し、旧データ (rc=0) は id 由来で互換を保つ */
    public static int requestCodeFor(AlarmItem item) {
        return item.rc > 0 ? item.rc : (int) (item.id % 800000) + 1000;
    }

    /** 通知ID: 採番済み rc を優先し、不明時は 700001 */
    public static int notifIdFor(AlarmItem item) {
        return NOTIF_BASE + (item != null && item.rc > 0 ? item.rc : 1);
    }

    private static PendingIntent alarmPendingIntent(Context ctx, AlarmItem item) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(EXTRA_KIND, "alarm");
        intent.putExtra(EXTRA_ALARM_ID, item.id);
        intent.putExtra(EXTRA_LABEL, item.label);
        intent.putExtra(EXTRA_HOUR, item.hour);
        intent.putExtra(EXTRA_MINUTE, item.minute);
        return PendingIntent.getBroadcast(ctx, requestCodeFor(item), intent,
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
        PendingIntent showPi = PendingIntent.getActivity(ctx, requestCodeFor(item), show,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // setAlarmClock はアラームアプリ向けAPIで、SCHEDULE_EXACT_ALARM 権限なしで正確に発火できる
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, showPi), pi);
        // 発火時刻をストアにも書き戻す (再登録時の「電源OFF中に過ぎた単発」判定用)
        item.triggerMillis = trigger;
        List<AlarmItem> stored = AlarmStore.load(ctx);
        AlarmItem target = AlarmStore.findById(stored, item.id);
        if (target != null && target.triggerMillis != trigger) {
            target.triggerMillis = trigger;
            AlarmStore.save(ctx, stored);
        }
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

    // ----- スヌーズ (5分後)。本体と requestCode を分離し、アラーム/タイマー間で上書きしない -----
    public static void scheduleSnooze(Context ctx, String kind, long alarmId, String label, int rc) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long trigger = System.currentTimeMillis() + 5L * 60L * 1000L;
        boolean isTimer = "timer".equals(kind);
        int reqCode = isTimer ? SNOOZE_TIMER_REQUEST_CODE
            : SNOOZE_BASE + (rc > 0 ? rc : (int) (alarmId % 800000) + 1000);
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra(EXTRA_KIND, kind == null ? "alarm" : kind);
        intent.putExtra(EXTRA_ALARM_ID, alarmId);
        intent.putExtra(EXTRA_LABEL, (label == null ? "" : label) + "（スヌーズ）");
        intent.putExtra(EXTRA_SNOOZE, true);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(ctx, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(ctx, reqCode, show,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(trigger, showPi), pi);
    }

    /** 指定アラーム由来のスヌーズを取り消す (アラーム削除・OFF時) */
    public static void cancelSnoozeAlarm(Context ctx, int rc) {
        if (rc <= 0) return;
        cancelSnooze(ctx, SNOOZE_BASE + rc);
    }

    /** タイマー由来のスヌーズを取り消す (タイマーキャンセル時) */
    public static void cancelSnoozeTimer(Context ctx) {
        cancelSnooze(ctx, SNOOZE_TIMER_REQUEST_CODE);
    }

    private static void cancelSnooze(Context ctx, int reqCode) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // 登録時と同じ requestCode で PI を作り直して取り消す (extras は同一視に無関係)
        PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, new Intent(ctx, AlarmReceiver.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        pi.cancel();
    }

    /** 再起動・時刻/タイムゾーン変更・アプリ更新時: 有効な全アラーム + 実行中タイマーを再登録 */
    public static void rescheduleAll(Context ctx) {
        long now = System.currentTimeMillis();
        List<AlarmItem> items = AlarmStore.load(ctx);
        boolean changed = false;
        for (AlarmItem item : items) {
            // 単発で、登録済み発火時刻を電源OFF中に過ぎていたら鳴らさず OFF にする (60秒の猶予つき)
            if (item.enabled && !item.hasRepeat()
                && item.triggerMillis > 0 && item.triggerMillis <= now - 60_000L) {
                item.enabled = false;
                changed = true;
            }
        }
        if (changed) AlarmStore.save(ctx, items);
        for (AlarmItem item : items) {
            if (item.enabled) scheduleAlarm(ctx, item);
        }
        SharedPreferences sp = ctx.getSharedPreferences(TIMER_PREFS, Context.MODE_PRIVATE);
        boolean running = sp.getBoolean(KEY_TIMER_RUNNING, false);
        long end = sp.getLong(KEY_TIMER_END, 0);
        if (running && end > now) {
            scheduleTimer(ctx, end, "タイマー");
        } else if (running) {
            // 終了を過ぎているタイマーは状態を正規化して再登録しない
            sp.edit().putBoolean(KEY_TIMER_RUNNING, false)
                .putLong(KEY_TIMER_REMAINING, 0).apply();
        }
    }
}
