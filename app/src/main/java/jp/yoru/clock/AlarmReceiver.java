package jp.yoru.clock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;

// ===== 発火用レシーバー: 鳴動Activityを起動し、単発アラームはOFFにする =====
public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND);
        if (kind == null) kind = "alarm";
        long alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1);
        String label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL);
        int hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1);
        int minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1);

        if ("alarm".equals(kind) && alarmId >= 0) {
            // 単発アラームは発火後にOFFへ。繰り返しは次回分を再登録する。
            List<AlarmItem> items = AlarmStore.load(context);
            AlarmItem item = AlarmStore.findById(items, alarmId);
            if (item != null) {
                if (item.hasRepeat()) {
                    AlarmScheduler.scheduleAlarm(context, item);
                } else if (item.enabled) {
                    item.enabled = false;
                    AlarmStore.save(context, items);
                }
            }
        }
        if ("timer".equals(kind)) {
            // タイマーは終了状態にする (MainActivity再開時に 00:00 表示へ)
            context.getSharedPreferences(AlarmScheduler.TIMER_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
                .apply();
        }

        Intent ring = new Intent(context, RingActivity.class);
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ring.putExtra(AlarmScheduler.EXTRA_KIND, kind);
        ring.putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId);
        ring.putExtra(AlarmScheduler.EXTRA_LABEL, label == null ? "" : label);
        ring.putExtra(AlarmScheduler.EXTRA_HOUR, hour);
        ring.putExtra(AlarmScheduler.EXTRA_MINUTE, minute);
        context.startActivity(ring);
    }
}
