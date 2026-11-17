package jp.yoru.clock;

// ===== アラーム1件のデータ (Android非依存) =====
public final class AlarmItem {
    public long id;
    public int hour;
    public int minute;
    public String label;
    public boolean enabled;
    /** index 0=月 .. 6=日。全 false は単発 */
    public boolean[] repeat = new boolean[7];

    public AlarmItem() {}

    public AlarmItem(long id, int hour, int minute, String label, boolean enabled, boolean[] repeat) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.label = label == null ? "" : label;
        this.enabled = enabled;
        if (repeat != null && repeat.length == 7) this.repeat = repeat.clone();
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
        return new AlarmItem(id, hour, minute, label, enabled, repeat);
    }
}
