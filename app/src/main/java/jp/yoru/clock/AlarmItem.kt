package jp.yoru.clock

// ===== アラーム1件のデータ (Android非依存) =====
class AlarmItem {
    var id: Long = 0
    var hour: Int = 0
    var minute: Int = 0
    var label: String = ""
    var enabled: Boolean = false

    /** index 0=月 .. 6=日。全 false は単発 */
    var repeat: BooleanArray = BooleanArray(7)

    /** PendingIntent の requestCode 専用番号 (連番)。0=未採番 */
    var rc: Int = 0

    /** 最後に登録した発火時刻 (epoch millis)。0=未登録。単発の「過ぎた」判定に使う */
    var triggerMillis: Long = 0

    constructor()

    constructor(id: Long, hour: Int, minute: Int, label: String?, enabled: Boolean, repeat: BooleanArray?) {
        this.id = id
        this.hour = hour
        this.minute = minute
        this.label = label ?: ""
        this.enabled = enabled
        if (repeat != null && repeat.size == 7) this.repeat = repeat.clone()
    }

    fun hasRepeat(): Boolean {
        return ClockLogic.hasRepeat(repeat)
    }

    fun nextTriggerMillis(nowMillis: Long): Long {
        return ClockLogic.nextTriggerMillis(nowMillis, hour, minute, repeat)
    }

    fun timeText(): String {
        return ClockLogic.formatHourMinute(hour, minute)
    }

    fun repeatText(): String {
        return ClockLogic.repeatSummary(repeat)
    }

    fun copy(): AlarmItem {
        val c = AlarmItem(id, hour, minute, label, enabled, repeat)
        c.rc = rc
        c.triggerMillis = triggerMillis
        return c
    }

    companion object {
        /** 次に使う requestCode: 既存の最大 rc + 1 (なければ 1) */
        fun nextRequestCode(items: List<AlarmItem>?): Int {
            var max = 0
            if (items != null) {
                for (item in items) if (item.rc > max) max = item.rc
            }
            return max + 1
        }
    }
}
