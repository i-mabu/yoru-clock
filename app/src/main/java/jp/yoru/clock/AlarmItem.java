package jp.yoru.clock;

import java.util.List;

// ===== アラーム1件のデータ (Android非依存) =====
public final class AlarmItem {
    public long id;
    public int hour;
    public int minute;
    public String label;
    public boolean enabled;
    /** index 0=月 .. 6=日。全 false は単発 */
    public boolean[] repeat = new boolean[7];
    /** PendingIntent の requestCode 専用番号 (連番)。0=未採番 */
    public int rc;
    /** 最後に登録した発火時刻 (epoch millis)。0=未登録。単発の「過ぎた」判定に使う */
    public long triggerMillis;

    public AlarmItem() {}

    public AlarmItem(long id, int hour, int minute, String label, boolean enabled, boolean[] repeat) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.label = label == null ? "" : label;
        this.enabled = enabled;
        if (repeat != null && repeat.length == 7) this.repeat = repeat.clone();
    }

    /** 次に使う requestCode: 既存の最大 rc + 1 (なければ 1) */
    public static int nextRequestCode(List<AlarmItem> items) {
        int max = 0;
        if (items != null) {
            for (AlarmItem item : items) if (item != null && item.rc > max) max = item.rc;
        }
        return max + 1;
    }

    public boolean hasRepeat() {
        return ClockLogic.hasRepeat(repeat);
    }

    public long nextTriggerMillis(long nowMillis) {
        return ClockLogic.nextTriggerMillis(nowMillis, hour, minute, repeat);
    }

    public String timeText() {
        return ClockLogic.formatHourMinute(hour, minute);
    }

    public String repeatText() {
        return ClockLogic.repeatSummary(repeat);
    }

    public AlarmItem copy() {
        AlarmItem c = new AlarmItem(id, hour, minute, label, enabled, repeat);
        c.rc = rc;
        c.triggerMillis = triggerMillis;
        return c;
    }
}
