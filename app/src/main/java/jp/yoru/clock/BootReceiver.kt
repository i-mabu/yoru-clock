package jp.yoru.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// ========================================
// 🔄 再起動・時刻/タイムゾーン変更・アプリ更新後に、有効なアラームと実行中タイマーを再登録する
// ========================================
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AlarmScheduler.rescheduleAll(context)
        }
    }
}
