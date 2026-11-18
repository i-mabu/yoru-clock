package jp.yoru.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

// ===== 1. AlarmManager への登録 (setAlarmClock を使い、正確アラーム権限なしで正確に発火させる) =====
// PendingIntent の requestCode 帯域:
//   アラーム本体: AlarmItem.rc (連番, 1〜) / スヌーズ: 600000+rc (タイマーは 1900001)
//   通知ID: 700000+rc (タイマーは 1900002) / タイマー本体: 900001
//   タイマー用はアラーム帯 (rc 連番・旧データ由来の最大 1400999) と重ならない帯に置く
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

    // タイマー状態の保存キー (TimerTab / AlarmReceiver と共有)
    const val TIMER_PREFS = "timer"
    const val KEY_TIMER_END = "end_millis"
    const val KEY_TIMER_TOTAL = "total_millis"
    const val KEY_TIMER_REMAINING = "remaining_millis"
    const val KEY_TIMER_RUNNING = "running"

    /** アラーム本体の requestCode: 採番済み rc を優先し、旧データ (rc=0) は id 由来で互換を保つ */
    fun requestCodeFor(item: AlarmItem): Int {
        return if (item.rc > 0) item.rc else (item.id % 800000).toInt() + 1000
    }

    /** 通知ID: 採番済み rc を優先し、不明時は 700001 */
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
        return PendingIntent.getBroadcast(ctx, requestCodeFor(item), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 有効なアラームを次回発火時刻で登録。無効なら解除。登録した発火時刻を返す (無効時は -1) */
    fun scheduleAlarm(ctx: Context, item: AlarmItem): Long {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return -1
        val pi = alarmPendingIntent(ctx, item)
        if (!item.enabled) {
            am.cancel(pi)
            return -1
        }
        val trigger = item.nextTriggerMillis(System.currentTimeMillis())
        val show = Intent(ctx, MainActivity::class.java)
        val showPi = PendingIntent.getActivity(ctx, requestCodeFor(item), show,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // setAlarmClock はアラームアプリ向けAPIで、SCHEDULE_EXACT_ALARM 権限なしで正確に発火できる
        am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showPi), pi)
        // 発火時刻をストアにも書き戻す (再登録時の「電源OFF中に過ぎた単発」判定用)
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
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(alarmPendingIntent(ctx, item))
    }

    // ----- 1.1 タイマー -----
    private fun timerPendingIntent(ctx: Context, label: String?): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
        intent.putExtra(EXTRA_KIND, "timer")
        intent.putExtra(EXTRA_LABEL, label ?: "タイマー")
        return PendingIntent.getBroadcast(ctx, TIMER_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun scheduleTimer(ctx: Context, endMillis: Long, label: String?) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val show = Intent(ctx, MainActivity::class.java)
        val showPi = PendingIntent.getActivity(ctx, TIMER_REQUEST_CODE, show,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(endMillis, showPi), timerPendingIntent(ctx, label))
    }

    fun cancelTimer(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(timerPendingIntent(ctx, "タイマー"))
    }

    // ----- 1.2 スヌーズ (5分後)。本体と requestCode を分離し、アラーム/タイマー間で上書きしない -----
    fun scheduleSnooze(ctx: Context, kind: String?, alarmId: Long, label: String?, rc: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val trigger = System.currentTimeMillis() + 5L * 60L * 1000L
        val isTimer = "timer" == kind
        val reqCode = if (isTimer) SNOOZE_TIMER_REQUEST_CODE
            else SNOOZE_BASE + (if (rc > 0) rc else (alarmId % 800000).toInt() + 1000)
        val intent = Intent(ctx, AlarmReceiver::class.java)
        intent.putExtra(EXTRA_KIND, kind ?: "alarm")
        intent.putExtra(EXTRA_ALARM_ID, alarmId)
        intent.putExtra(EXTRA_LABEL, (label ?: "") + "（スヌーズ）")
        intent.putExtra(EXTRA_SNOOZE, true)
        val pi = PendingIntent.getBroadcast(ctx, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val show = Intent(ctx, MainActivity::class.java)
        val showPi = PendingIntent.getActivity(ctx, reqCode, show,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showPi), pi)
    }

    /** 指定アラーム由来のスヌーズを取り消す (アラーム削除・OFF時) */
    fun cancelSnoozeAlarm(ctx: Context, rc: Int) {
        if (rc <= 0) return
        cancelSnooze(ctx, SNOOZE_BASE + rc)
    }

    /** タイマー由来のスヌーズを取り消す (タイマーキャンセル時) */
    fun cancelSnoozeTimer(ctx: Context) {
        cancelSnooze(ctx, SNOOZE_TIMER_REQUEST_CODE)
    }

    private fun cancelSnooze(ctx: Context, reqCode: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        // 登録時と同じ requestCode で PI を作り直して取り消す (extras は同一視に無関係)
        val pi = PendingIntent.getBroadcast(ctx, reqCode, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        pi.cancel()
    }

    /** 再起動・時刻/タイムゾーン変更・アプリ更新時: 有効な全アラーム + 実行中タイマーを再登録 */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val items = AlarmStore.load(ctx)
        var changed = false
        for (item in items) {
            // 単発で、登録済み発火時刻を電源OFF中に過ぎていたら鳴らさず OFF にする (60秒の猶予つき)
            if (item.enabled && !item.hasRepeat()
                && item.triggerMillis > 0 && item.triggerMillis <= now - 60_000L) {
                item.enabled = false
                changed = true
            }
        }
        if (changed) AlarmStore.save(ctx, items)
        for (item in items) {
            if (item.enabled) scheduleAlarm(ctx, item)
        }
        val sp = ctx.getSharedPreferences(TIMER_PREFS, Context.MODE_PRIVATE)
        val running = sp.getBoolean(KEY_TIMER_RUNNING, false)
        val end = sp.getLong(KEY_TIMER_END, 0)
        if (running && end > now) {
            scheduleTimer(ctx, end, "タイマー")
        } else if (running) {
            // 終了を過ぎているタイマーは状態を正規化して再登録しない
            sp.edit().putBoolean(KEY_TIMER_RUNNING, false)
                .putLong(KEY_TIMER_REMAINING, 0).apply()
        }
    }
}
