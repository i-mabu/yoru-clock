package jp.yoru.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// ===== 再起動・時刻/タイムゾーン変更・アプリ更新後に有効なアラームと実行中タイマーを再登録 =====
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
            || Intent.ACTION_TIME_CHANGED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AlarmScheduler.rescheduleAll(context);
        }
    }
}
