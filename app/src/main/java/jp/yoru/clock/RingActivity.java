package jp.yoru.clock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

// ===== 鳴動画面 (アラーム/タイマー共通): 音 + バイブ + スヌーズ5分 / 停止 =====
public final class RingActivity extends Activity {
    private MediaPlayer player;
    private Vibrator vibrator;
    private String kind = "alarm";
    private long alarmId = -1;
    private String label = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ロック画面の上に出し、画面を点ける
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent intent = getIntent();
        kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND);
        if (kind == null) kind = "alarm";
        alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1);
        label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL);
        if (label == null) label = "";
        int hour = intent.getIntExtra(AlarmScheduler.EXTRA_HOUR, -1);
        int minute = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTE, -1);

        String timeText;
        if (hour >= 0) {
            timeText = ClockLogic.formatHourMinute(hour, minute);
        } else {
            Calendar now = Calendar.getInstance();
            timeText = ClockLogic.formatHourMinute(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE));
        }
        String title = "timer".equals(kind) ? "タイマー終了" : "アラーム";
        String displayLabel = label.isEmpty() ? title : label;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0B1026"));
        root.setPadding(48, 48, 48, 48);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(Color.parseColor("#8FA8FF"));
        titleView.setTextSize(20);
        titleView.setGravity(Gravity.CENTER);
        root.addView(titleView);

        TextView timeView = new TextView(this);
        timeView.setText(timeText);
        timeView.setTextColor(Color.parseColor("#F2F3FA"));
        timeView.setTextSize(72);
        timeView.setGravity(Gravity.CENTER);
        root.addView(timeView);

        TextView labelView = new TextView(this);
        labelView.setText(displayLabel);
        labelView.setTextColor(Color.parseColor("#FFC46B"));
        labelView.setTextSize(26);
        labelView.setGravity(Gravity.CENTER);
        root.addView(labelView);

        Button snooze = new Button(this);
        snooze.setText("スヌーズ（5分）");
        snooze.setTextSize(18);
        snooze.setOnClickListener(v -> {
            AlarmScheduler.scheduleSnooze(this, kind, alarmId, label.isEmpty() ? title : label.replace("（スヌーズ）", ""));
            stopRinging();
            finish();
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, 64, 0, 16);
        root.addView(snooze, bp);

        Button stop = new Button(this);
        stop.setText("停止");
        stop.setTextSize(20);
        stop.setOnClickListener(v -> {
            stopRinging();
            finish();
        });
        root.addView(stop, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        startRinging();
    }

    private void startRinging() {
        // デフォルトアラーム音 → なければ通知音
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (uri != null) {
            try {
                player = new MediaPlayer();
                player.setDataSource(this, uri);
                player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
                player.setLooping(true);
                player.prepare();
                player.start();
            } catch (Exception ignored) {
                player = null;
            }
        }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            long[] pattern = {0, 500, 300, 500, 700};
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private void stopRinging() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        if (vibrator != null) vibrator.cancel();
    }

    @Override
    protected void onDestroy() {
        stopRinging();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 誤操作防止: 戻るでは閉じない (停止/スヌーズボタンを使う)
    }
}
