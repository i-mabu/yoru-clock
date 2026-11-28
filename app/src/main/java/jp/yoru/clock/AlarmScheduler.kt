package jp.yoru.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

// ========================================
// ⏰ AlarmManager への登録 (setAlarmClock: 正確な発火に特別権限が要らない方式)
// ========================================
// requestCode の帯域:
//   アラーム本体 : AlarmItem.rc (連番、1〜)
//   スヌーズ     : 600000 + rc (タイマーは 1900001)
//   通知 ID      : 700000 + rc (タイマーは 1900002)
//   タイマー本体 : 900001
// タイマー用はアラーム帯 (rc 連番、旧データ由来でも最大 1400999) と重ならない帯に置く。
object AlarmScheduler {
    const val EXTRA_KIND = "kind" // "alarm" / "timer"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_LABEL = "label"
    const val EXTRA_HOUR = "hour"
    const val EXTRA_MINUTE = "minute"
    const val EXTRA_SNOOZE = "snooze_flag"
    const val EXTRA_NOTIF_ID = "notif_id"

    const val TIMER_REQUEST_CODE = 900001
    const val SNOOZE_BASE = 600000
    const val SNOOZE_TIMER_REQUEST_CODE = 1900001
    const val NOTIF_BASE = 700000
    const val NOTIF_TIMER_ID = 1900002

    // タイマー状態の保存先 (TimerTab / AlarmReceiver と共有)
    const val TIMER_PREFS = "timer"
    const val KEY_TIMER_END = "end_millis"
    const val KEY_TIMER_TOTAL = "total_millis"
    const val KEY_TIMER_REMAINING = "remaining_millis"
    const val KEY_TIMER_RUNNING = "running"

    /** アラーム本体の requestCode: 採番済み rc を優先し、旧データ (rc=0) は id 由来の値で互換を保つ */
    fun requestCodeFor(item: AlarmItem): Int {
        return if (item.rc > 0) item.rc else (item.id % 800000).toInt() + 1000
    }

    /** 通知 ID: 採番済み rc を優先し、不明な場合は 700001 */
    fun notifIdFor(item: AlarmItem?): Int {
        return NOTIF_BASE + (if (item != null && item.rc > 0) item.rc else 1)
    }

    private fun alarmPendingIntent(ctx: Context, item: AlarmItem): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
        intent.putExtra(EXTRA_KIND, "alarm")
        intent.putExtra(EXTRA_ALARM_ID, item.id)
        intent.putExtra(EXTRA_LABEL, item.label)
        intent.putExtra(EXTRA_HOUR, item.hour)
        intent.putExtra(EXTRA_MINUTE, item.minute)
        return PendingIntent.getBroadcast(
            ctx, requestCodeFor(item), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPendingIntent(ctx: Context, requestCode: Int): PendingIntent {
        val show = Intent(ctx, MainActivity::class.java)
        return PendingIntent.getActivity(
            ctx, requestCode, show,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 有効なアラームを次回発火時刻で登録し、その発火時刻を返す。
     * 無効なアラームは登録を取り消して -1 を返す。
     */
    fun scheduleAlarm(ctx: Context, item: AlarmItem): Long {
        val manager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return -1
        val pending = alarmPendingIntent(ctx, item)
        if (!item.enabled) {
            manager.cancel(pending)
            return -1
        }
        val trigger = item.nextTriggerMillis(System.currentTimeMillis())
        // setAlarmClock はアラームアプリ向け API で、SCHEDULE_EXACT_ALARM 権限なしに正確に発火できる
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(trigger, showPendingIntent(ctx, requestCodeFor(item))),
            pending
        )
        // 発火時刻をストアへ書き戻す (電源 OFF 中に過ぎた単発アラームの判定に使う)
        item.triggerMillis = trigger
        val stored = AlarmStore.load(ctx)
        val target = AlarmStore.findById(stored, item.id)
        if (target != null && target.triggerMillis != trigger) {
            target.triggerMillis = trigger
            AlarmStore.save(ctx, stored)
        }
        return trigger
    }

    fun cancelAlarm(ctx: Context, item: AlarmItem) {
        val manager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(alarmPendingIntent(ctx, item))
    }

    // ----------------------------------------
    // ⏲️ タイマー
    // ----------------------------------------
    private fun timerPendingIntent(ctx: Context, label: String?): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
        intent.putExtra(EXTRA_KIND, "timer")
        intent.putExtra(EXTRA_LABEL, label ?: "タイマー")
        return PendingIntent.getBroadcast(
            ctx, TIMER_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleTimer(ctx: Context, endMillis: Long, label: String?) {
        val manager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(endMillis, showPendingIntent(ctx, TIMER_REQUEST_CODE)),
            timerPendingIntent(ctx, label)
        )
    }

    fun cancelTimer(ctx: Context) {
        val manager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(timerPendingIntent(ctx, "タイマー"))
    }

    // ----------------------------------------
    // 😴 スヌーズ (5 分後)。本体とは requestCode を分け、アラーム/タイマー間で上書きしない
    // ----------------------------------------
    fun scheduleSnooze(ctx: Context, kind: String?, alarmId: Long, label: String?, rc: Int) {
        val manager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val trigger = System.currentTimeMillis() + 5L * 60L * 1000L
        val isTimer = "timer" == kind
        val requestCode = if (isTimer) SNOOZE_TIMER_REQUEST_CODE
        else SNOOZE_BASE + (if (rc > 0) rc else (alarmId % 800000).toInt() + 1000)
        val intent = Intent(ctx, AlarmReceiver::class.java)
        intent.putExtra(EXTRA_KIND, kind ?: "alarm")
        intent.putExtra(EXTRA_ALARM_ID, alarmId)
        intent.putExtra(EXTRA_LABEL, (label ?: "") + "（スヌーズ）")
        intent.putExtra(EXTRA_SNOOZE, true)
        val pending = PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(trigger, showPendingIntent(ctx, requestCode)),
            pending
        )
    }

    /** 指定アラーム由来のスヌーズを取り消す (アラームの削除・OFF 時) */
    fun cancelSnoozeAlarm(ctx: Context, rc: Int) {
        if (rc <= 0) return
        cancelSnooze(ctx, SNOOZE_BASE + rc)
    }

    /** タイマー由来のスヌーズを取り消す (タイマーのキャンセル時) */
    fun cancelSnoozeTimer(ctx: Context) {
        cancelSnooze(ctx, SNOOZE_TIMER_REQUEST_CODE)
    }

    private fun cancelSnooze(ctx: Context, requestCode: Int) {
        val manager = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // 登録時と同じ requestCode で PendingIntent を作り直して取り消す (extras は同一視に関係しない)
        val pending = PendingIntent.getBroadcast(
            ctx, requestCode, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.cancel(pending)
        pending.cancel()
    }

    // ----------------------------------------
    // 🔄 再登録 (再起動・時刻/タイムゾーン変更・アプリ更新後)
    // ----------------------------------------
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val items = AlarmStore.load(ctx)
        var changed = false
        for (item in items) {
            // 単発アラームが登録済み発火時刻を電源 OFF 中に過ぎていたら、鳴らさず OFF にする (60 秒の猶予つき)
            if (item.enabled && !item.hasRepeat() &&
                item.triggerMillis > 0 && item.triggerMillis <= now - 60_000L
            ) {
                item.enabled = false
                changed = true
            }
        }
        if (changed) AlarmStore.save(ctx, items)
        for (item in items) {
            if (item.enabled) scheduleAlarm(ctx, item)
        }
        val prefs = ctx.getSharedPreferences(TIMER_PREFS, Context.MODE_PRIVATE)
        val running = prefs.getBoolean(KEY_TIMER_RUNNING, false)
        val end = prefs.getLong(KEY_TIMER_END, 0)
        if (running && end > now) {
            scheduleTimer(ctx, end, "タイマー")
        } else if (running) {
            // 終了を過ぎているタイマーは状態を正規化し、再登録しない
            prefs.edit().putBoolean(KEY_TIMER_RUNNING, false)
                .putLong(KEY_TIMER_REMAINING, 0).apply()
        }
    }
}
