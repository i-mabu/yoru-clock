package jp.yoru.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// ===== 再起動後に有効なアラームと実行中タイマーを再登録 =====
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AlarmScheduler.rescheduleAll(context);
        }
    }
}
