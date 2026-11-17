package jp.yoru.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ===== 再起動・時刻/タイムゾーン変更・アプリ更新後に有効なアラームと実行中タイマーを再登録 =====
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (Intent.ACTION_BOOT_COMPLETED == action
            || Intent.ACTION_TIMEZONE_CHANGED == action
            || Intent.ACTION_TIME_CHANGED == action
            || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            AlarmScheduler.rescheduleAll(context)
        }
    }
}
