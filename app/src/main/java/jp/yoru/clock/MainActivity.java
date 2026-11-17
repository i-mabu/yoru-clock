package jp.yoru.clock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

// ===== よる時計 メイン画面 (時計 / アラーム / タイマー / ストップウォッチ の4タブ) =====
public final class MainActivity extends Activity {
    private static final int BG = Color.parseColor("#0B1026");
    private static final int PANEL = Color.parseColor("#161D38");
    private static final int TEXT = Color.parseColor("#F2F3FA");
    private static final int SUB = Color.parseColor("#9AA3C7");
    private static final int ACCENT = Color.parseColor("#8FA8FF");
    private static final int GOLD = Color.parseColor("#FFC46B");
    private static final int TAB_ON = Color.parseColor("#2A3560");

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View[] panels = new View[4];
    private final Button[] tabButtons = new Button[4];
    private int currentTab = 0;

    // 時計タブ
    private TextView digitalView;
    private TextView dateView;
    private AnalogClockView analogView;
    private LinearLayout worldContainer;
    private final List<ClockLogic.City> worldCities = new ArrayList<>();

    // アラームタブ
    private LinearLayout alarmContainer;
    private TextView alarmEmptyView;

    // タイマータブ
    private NumberPicker hourPicker, minPicker, secPicker;
    private TextView timerRemainView;
    private ProgressBar timerProgress;
    private Button timerStartPauseBtn;
    private LinearLayout timerPickerRow;
    private LinearLayout timerPresetRow1;
    private LinearLayout timerPresetRow2;

    // ストップウォッチタブ
    private TextView swView;
    private LinearLayout lapContainer;
    private Button swStartPauseBtn;
    private boolean swRunning = false;
    private long swBase = 0;        // 開始時点の elapsedRealtime - 蓄積分
    private long swAccumulated = 0; // 一時停止までの累計
    private long swLastLapCum = 0;
    private int lapCount = 0;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView makeText(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button makeButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        panels[0] = buildClockPanel();
        panels[1] = buildAlarmPanel();
        panels[2] = buildTimerPanel();
        panels[3] = buildStopwatchPanel();
        for (View p : panels) content.addView(p);

        // 自前タブ列 (BottomNavigation相当)
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setBackgroundColor(PANEL);
        String[] labels = {"時計", "アラーム", "タイマー", "ストップウォッチ"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(14);
            b.setOnClickListener(v -> selectTab(idx));
            tabButtons[i] = b;
            tabRow.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabRow);

        setContentView(root);
        // Android 13+: 鳴動通知の表示許可を求める (主経路は高優先度通知のため)
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        loadWorldCities();
        renderWorldCities();
        selectTab(0);
        // バックアップ復元・アプリ更新直後の初回起動でも登録を正規化する
        AlarmScheduler.rescheduleAll(this);
        restoreTimerState();
    }

    private void selectTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < 4; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabButtons[i].setBackgroundColor(i == idx ? TAB_ON : Color.TRANSPARENT);
            tabButtons[i].setTextColor(i == idx ? GOLD : SUB);
        }
        if (idx == 1) renderAlarms();
    }

    private ScrollView wrapScroll(LinearLayout inner) {
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(BG);
        s.addView(inner);
        return s;
    }

    private LinearLayout basePanel() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        return l;
    }

    // ===== 時計タブ =====
    private View buildClockPanel() {
        LinearLayout l = basePanel();

        digitalView = makeText("00:00:00", 56, TEXT);
        digitalView.setGravity(Gravity.CENTER);
        digitalView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        l.addView(digitalView);

        dateView = makeText("", 18, GOLD);
        dateView.setGravity(Gravity.CENTER);
        l.addView(dateView);

        analogView = new AnalogClockView(this);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(280), dp(280));
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        ap.setMargins(0, dp(16), 0, dp(8));
        l.addView(analogView, ap);

        TextView worldTitle = makeText("世界時計", 18, ACCENT);
        LinearLayout.LayoutParams wt = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wt.setMargins(0, dp(12), 0, dp(4));
        l.addView(worldTitle, wt);

        worldContainer = new LinearLayout(this);
        worldContainer.setOrientation(LinearLayout.VERTICAL);
        l.addView(worldContainer);

        Button add = makeButton("＋ 都市を追加");
        add.setOnClickListener(v -> showAddCityDialog());
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ab.setMargins(0, dp(8), 0, 0);
        l.addView(add, ab);

        TextView hint = makeText("都市を長押しで削除", 12, SUB);
        l.addView(hint);
        return wrapScroll(l);
    }

    private void loadWorldCities() {
        worldCities.clear();
        SharedPreferences sp = getSharedPreferences("world", MODE_PRIVATE);
        String json = sp.getString("cities_json", null);
        if (json == null) {
            // 保存キー自体がない初回のみ既定6都市。空配列は「全て削除した」状態として尊重する
            worldCities.addAll(ClockLogic.DEFAULT_CITIES);
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                ClockLogic.City c = ClockLogic.findCityByTz(arr.getString(i));
                if (c == null) continue;
                boolean dup = false;
                for (ClockLogic.City w : worldCities) if (w.tzId.equals(c.tzId)) { dup = true; break; }
                if (!dup) worldCities.add(c);
            }
        } catch (Exception ignored) {}
    }

    private void saveWorldCities() {
        try {
            JSONArray arr = new JSONArray();
            for (ClockLogic.City c : worldCities) arr.put(c.tzId);
            getSharedPreferences("world", MODE_PRIVATE).edit()
                .putString("cities_json", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void renderWorldCities() {
        if (worldContainer == null) return;
        worldContainer.removeAllViews();
        long now = System.currentTimeMillis();
        for (ClockLogic.City city : worldCities) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackgroundColor(PANEL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, 0, 0, dp(6));
            row.setLayoutParams(rp);

            TextView name = makeText(city.name, 17, TEXT);
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView off = makeText(ClockLogic.formatOffsetFromJapan(city.tzId, now), 14, SUB);
            off.setGravity(Gravity.END);
            off.setTag("worldoffset:" + city.tzId);
            row.addView(off, new LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView time = makeText(ClockLogic.formatTimeInZone(city.tzId, now), 22, GOLD);
            time.setGravity(Gravity.END);
            time.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            time.setTag("worldtime:" + city.tzId);
            row.addView(time, new LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT));

            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle(city.name + " を削除しますか？")
                    .setPositiveButton("削除", (d, w) -> {
                        worldCities.remove(city);
                        saveWorldCities();
                        renderWorldCities();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
                return true;
            });
            worldContainer.addView(row);
        }
    }

    private void refreshWorldTimes() {
        if (worldContainer == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < worldContainer.getChildCount(); i++) {
            View row = worldContainer.getChildAt(i);
            if (row instanceof LinearLayout) {
                LinearLayout r = (LinearLayout) row;
                for (int j = 0; j < r.getChildCount(); j++) {
                    View child = r.getChildAt(j);
                    Object tag = child.getTag();
                    if (child instanceof TextView && tag instanceof String) {
                        String t = (String) tag;
                        if (t.startsWith("worldtime:")) {
                            String tz = t.substring("worldtime:".length());
                            ((TextView) child).setText(ClockLogic.formatTimeInZone(tz, now));
                        } else if (t.startsWith("worldoffset:")) {
                            // 夏時間の切り替わりで時差も変わるため、時刻と一緒に更新する
                            String tz = t.substring("worldoffset:".length());
                            ((TextView) child).setText(ClockLogic.formatOffsetFromJapan(tz, now));
                        }
                    }
                }
            }
        }
    }

    private void showAddCityDialog() {
        List<ClockLogic.City> candidates = new ArrayList<>();
        for (ClockLogic.City c : ClockLogic.CITY_CATALOG) {
            boolean exists = false;
            for (ClockLogic.City w : worldCities) if (w.tzId.equals(c.tzId)) { exists = true; break; }
            if (!exists) candidates.add(c);
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "追加できる都市がありません", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) names[i] = candidates.get(i).name;
        new AlertDialog.Builder(this)
            .setTitle("都市を追加")
            .setItems(names, (d, which) -> {
                worldCities.add(candidates.get(which));
                saveWorldCities();
                renderWorldCities();
            })
            .setNegativeButton("キャンセル", null)
            .show();
    }

    // 1秒境界に合わせた時計更新
    private final Runnable clockTick = this::runClockTick;

    private void runClockTick() {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        // デジタル時計は端末ローカルTZ (アナログ・日付表示と揃える)
        digitalView.setText(ClockLogic.formatTimeSecInZone(TimeZone.getDefault().getID(), now));
        dateView.setText(ClockLogic.formatDateJa(cal));
        analogView.invalidate();
        refreshWorldTimes();
        long delay = 1000 - (now % 1000);
        handler.postDelayed(clockTick, delay);
    }

    // ===== アラームタブ =====
    private View buildAlarmPanel() {
        LinearLayout l = basePanel();
        TextView title = makeText("アラーム", 20, ACCENT);
        l.addView(title);

        alarmEmptyView = makeText("アラームはまだありません", 15, SUB);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ep.setMargins(0, dp(16), 0, 0);
        l.addView(alarmEmptyView, ep);

        alarmContainer = new LinearLayout(this);
        alarmContainer.setOrientation(LinearLayout.VERTICAL);
        l.addView(alarmContainer);

        Button add = makeButton("＋ アラームを追加");
        add.setTextSize(17);
        add.setOnClickListener(v -> {
            Intent intent = new Intent(this, AlarmEditActivity.class);
            intent.putExtra(AlarmEditActivity.EXTRA_ID, -1L);
            startActivity(intent);
        });
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ab.setMargins(0, dp(12), 0, 0);
        l.addView(add, ab);
        return wrapScroll(l);
    }

    private void renderAlarms() {
        if (alarmContainer == null) return;
        alarmContainer.removeAllViews();
        List<AlarmItem> items = AlarmStore.load(this);
        alarmEmptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (AlarmItem item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(PANEL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, dp(8), 0, 0);
            row.setLayoutParams(rp);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView time = makeText(item.timeText(), 30, item.enabled ? TEXT : SUB);
            time.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            texts.addView(time);
            String sub = item.repeatText();
            if (item.label != null && !item.label.isEmpty()) sub = item.label + " ・ " + sub;
            if (item.enabled) {
                sub += " ・ 次回 " + ClockLogic.describeTrigger(
                    item.nextTriggerMillis(System.currentTimeMillis()), System.currentTimeMillis());
            }
            texts.addView(makeText(sub, 13, SUB));
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Switch sw = new Switch(this);
            sw.setChecked(item.enabled);
            sw.setOnCheckedChangeListener((button, checked) -> {
                List<AlarmItem> all = AlarmStore.load(this);
                AlarmItem target = AlarmStore.findById(all, item.id);
                if (target == null) return;
                target.enabled = checked;
                AlarmStore.save(this, all);
                if (checked) {
                    long trigger = AlarmScheduler.scheduleAlarm(this, target);
                    Toast.makeText(this, "ON: 次回 " + ClockLogic.describeTrigger(trigger, System.currentTimeMillis()),
                        Toast.LENGTH_SHORT).show();
                } else {
                    AlarmScheduler.cancelAlarm(this, target);
                    AlarmScheduler.cancelSnoozeAlarm(this, target.rc);
                    Toast.makeText(this, "アラームをOFFにしました", Toast.LENGTH_SHORT).show();
                }
                renderAlarms();
            });
            row.addView(sw);

            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, AlarmEditActivity.class);
                intent.putExtra(AlarmEditActivity.EXTRA_ID, item.id);
                startActivity(intent);
            });
            alarmContainer.addView(row);
        }
    }

    // ===== タイマータブ =====
    private View buildTimerPanel() {
        LinearLayout l = basePanel();
        TextView title = makeText("タイマー", 20, ACCENT);
        l.addView(title);

        timerRemainView = makeText("00:00", 54, TEXT);
        timerRemainView.setGravity(Gravity.CENTER);
        timerRemainView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.setMargins(0, dp(16), 0, 0);
        l.addView(timerRemainView, tp);

        timerProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        timerProgress.setMax(1000);
        timerProgress.setProgress(0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.setMargins(0, dp(8), 0, 0);
        l.addView(timerProgress, pp);

        timerPickerRow = new LinearLayout(this);
        timerPickerRow.setOrientation(LinearLayout.HORIZONTAL);
        timerPickerRow.setGravity(Gravity.CENTER);
        hourPicker = makePicker(0, 23);
        minPicker = makePicker(0, 59);
        secPicker = makePicker(0, 59);
        minPicker.setValue(5);
        timerPickerRow.addView(pickerWithLabel(hourPicker, "時間"));
        timerPickerRow.addView(pickerWithLabel(minPicker, "分"));
        timerPickerRow.addView(pickerWithLabel(secPicker, "秒"));
        l.addView(timerPickerRow);

        // プリセット
        timerPresetRow1 = new LinearLayout(this);
        timerPresetRow1.setOrientation(LinearLayout.HORIZONTAL);
        timerPresetRow2 = new LinearLayout(this);
        timerPresetRow2.setOrientation(LinearLayout.HORIZONTAL);
        int[] presets = {1, 3, 5, 10, 15, 30, 60};
        for (int i = 0; i < presets.length; i++) {
            final int mins = presets[i];
            Button b = makeButton(mins + "分");
            b.setOnClickListener(v -> {
                hourPicker.setValue(mins / 60);
                minPicker.setValue(mins % 60);
                secPicker.setValue(0);
            });
            LinearLayout row = i < 4 ? timerPresetRow1 : timerPresetRow2;
            row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        l.addView(timerPresetRow1);
        l.addView(timerPresetRow2);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        timerStartPauseBtn = makeButton("開始");
        timerStartPauseBtn.setTextSize(18);
        timerStartPauseBtn.setOnClickListener(v -> onTimerStartPause());
        btnRow.addView(timerStartPauseBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button cancel = makeButton("キャンセル");
        cancel.setTextSize(18);
        cancel.setOnClickListener(v -> cancelTimer());
        btnRow.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, dp(16), 0, 0);
        l.addView(btnRow, bp);
        return wrapScroll(l);
    }

    private NumberPicker makePicker(int min, int max) {
        NumberPicker p = new NumberPicker(this);
        p.setMinValue(min);
        p.setMaxValue(max);
        p.setFormatter(v -> String.format(java.util.Locale.US, "%02d", v));
        return p;
    }

    /** タイマーの入力系 (ピッカー+プリセット) の表示をまとめて切り替える */
    private void setTimerInputVisibility(int vis) {
        timerPickerRow.setVisibility(vis);
        if (timerPresetRow1 != null) timerPresetRow1.setVisibility(vis);
        if (timerPresetRow2 != null) timerPresetRow2.setVisibility(vis);
    }

    private LinearLayout pickerWithLabel(NumberPicker p, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(p);
        TextView t = makeText(label, 13, SUB);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        return col;
    }

    private SharedPreferences timerPrefs() {
        return getSharedPreferences(AlarmScheduler.TIMER_PREFS, MODE_PRIVATE);
    }

    private void restoreTimerState() {
        SharedPreferences sp = timerPrefs();
        boolean running = sp.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false);
        long total = sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0);
        if (running) {
            long remain = ClockLogic.timerRemainingMillis(sp.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis());
            if (remain > 0) {
                timerStartPauseBtn.setText("一時停止");
                setTimerInputVisibility(View.GONE);
                updateTimerDisplay(remain, total);
                return;
            }
            // 動作中のはずが残り0以下: 終了状態に正規化してから初期表示へ
            sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0).apply();
        }
        long remain = sp.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0);
        if (total > 0 && remain > 0) {
            timerStartPauseBtn.setText("再開");
            setTimerInputVisibility(View.GONE);
            updateTimerDisplay(remain, total);
        } else {
            timerStartPauseBtn.setText("開始");
            setTimerInputVisibility(View.VISIBLE);
            updateTimerDisplay(0, 0);
        }
    }

    private void updateTimerDisplay(long remainMs, long totalMs) {
        timerRemainView.setText(ClockLogic.formatDuration(remainMs));
        if (totalMs > 0) {
            int prog = (int) (1000L * (totalMs - remainMs) / totalMs);
            timerProgress.setProgress(Math.max(0, Math.min(1000, prog)));
        } else {
            timerProgress.setProgress(0);
        }
    }

    private void onTimerStartPause() {
        SharedPreferences sp = timerPrefs();
        boolean running = sp.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false);
        long now = System.currentTimeMillis();
        if (running) {
            // 一時停止
            long remain = ClockLogic.timerRemainingMillis(sp.getLong(AlarmScheduler.KEY_TIMER_END, 0), now);
            sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply();
            AlarmScheduler.cancelTimer(this);
            timerStartPauseBtn.setText("再開");
            updateTimerDisplay(remain, sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0));
            return;
        }
        long total = sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0);
        long remain = sp.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0);
        if (total <= 0 || remain <= 0) {
            // 新規開始
            total = ClockLogic.timerTotalMillis(hourPicker.getValue(), minPicker.getValue(), secPicker.getValue());
            if (total <= 0) {
                Toast.makeText(this, "時間を設定してください", Toast.LENGTH_SHORT).show();
                return;
            }
            remain = total;
        }
        long end = now + remain;
        sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, true)
            .putLong(AlarmScheduler.KEY_TIMER_END, end)
            .putLong(AlarmScheduler.KEY_TIMER_TOTAL, total)
            .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply();
        AlarmScheduler.scheduleTimer(this, end, "タイマー");
        timerStartPauseBtn.setText("一時停止");
        setTimerInputVisibility(View.GONE);
        updateTimerDisplay(remain, total);
    }

    private void cancelTimer() {
        AlarmScheduler.cancelTimer(this);
        AlarmScheduler.cancelSnoozeTimer(this);
        timerPrefs().edit().clear().apply();
        timerStartPauseBtn.setText("開始");
        setTimerInputVisibility(View.VISIBLE);
        updateTimerDisplay(0, 0);
    }

    private final Runnable timerTick = this::runTimerTick;

    private void runTimerTick() {
        SharedPreferences sp = timerPrefs();
        if (sp.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)) {
            long remain = ClockLogic.timerRemainingMillis(
                sp.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis());
            updateTimerDisplay(remain, sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0));
            if (remain <= 0) {
                // 終了処理は AlarmReceiver → RingActivity が担う。状態を終了に正規化し、表示は初期状態へ
                sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                    .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0).apply();
                timerStartPauseBtn.setText("開始");
                setTimerInputVisibility(View.VISIBLE);
            }
        }
        handler.postDelayed(timerTick, 200);
    }

    // ===== ストップウォッチタブ =====
    private View buildStopwatchPanel() {
        LinearLayout l = basePanel();
        TextView title = makeText("ストップウォッチ", 20, ACCENT);
        l.addView(title);

        swView = makeText("00:00.00", 48, TEXT);
        swView.setGravity(Gravity.CENTER);
        swView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.setMargins(0, dp(20), 0, 0);
        l.addView(swView, tp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        swStartPauseBtn = makeButton("開始");
        swStartPauseBtn.setTextSize(17);
        swStartPauseBtn.setOnClickListener(v -> onStopwatchStartPause());
        btnRow.addView(swStartPauseBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button lap = makeButton("ラップ");
        lap.setTextSize(17);
        lap.setOnClickListener(v -> onLap());
        btnRow.addView(lap, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button reset = makeButton("リセット");
        reset.setTextSize(17);
        reset.setOnClickListener(v -> onStopwatchReset());
        btnRow.addView(reset, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, dp(16), 0, 0);
        l.addView(btnRow, bp);

        lapContainer = new LinearLayout(this);
        lapContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, 0);
        l.addView(lapContainer, lp);
        return wrapScroll(l);
    }

    private long swElapsed() {
        if (swRunning) return SystemClock.elapsedRealtime() - swBase;
        return swAccumulated;
    }

    private void onStopwatchStartPause() {
        if (swRunning) {
            swAccumulated = swElapsed();
            swRunning = false;
            swStartPauseBtn.setText("再開");
        } else {
            swBase = SystemClock.elapsedRealtime() - swAccumulated;
            swRunning = true;
            swStartPauseBtn.setText("一時停止");
        }
        swView.setText(ClockLogic.formatStopwatch(swElapsed()));
    }

    private void onStopwatchReset() {
        swRunning = false;
        swAccumulated = 0;
        swLastLapCum = 0;
        lapCount = 0;
        swStartPauseBtn.setText("開始");
        swView.setText("00:00.00");
        lapContainer.removeAllViews();
    }

    private void onLap() {
        if (!swRunning) return; // ラップは計測中のみ
        long cum = swElapsed();
        if (cum <= 0) return;
        lapCount++;
        long lapTime = cum - swLastLapCum;
        swLastLapCum = cum;
        TextView row = makeText(
            "ラップ " + lapCount + "　　" + ClockLogic.formatStopwatch(lapTime)
                + "　　累計 " + ClockLogic.formatStopwatch(cum), 16, TEXT);
        row.setTypeface(Typeface.MONOSPACE);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(5));
        row.setLayoutParams(lp);
        // 新しいラップを先頭に
        lapContainer.addView(row, 0);
    }

    private final Runnable swTick = this::runSwTick;

    private void runSwTick() {
        if (swRunning) swView.setText(ClockLogic.formatStopwatch(swElapsed()));
        handler.postDelayed(swTick, 31);
    }

    // ===== ライフサイクル =====
    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(timerTick);
        handler.post(timerTick);
        handler.removeCallbacks(swTick);
        handler.post(swTick);
        renderAlarms();
        restoreTimerState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(timerTick);
        handler.removeCallbacks(swTick);
    }
}
