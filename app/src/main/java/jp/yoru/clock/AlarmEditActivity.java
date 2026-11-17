package jp.yoru.clock;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

// ===== アラーム追加・編集画面 =====
public final class AlarmEditActivity extends Activity {
    public static final String EXTRA_ID = "edit_id";

    private TimePicker timePicker;
    private EditText labelEdit;
    private final CheckBox[] dayBoxes = new CheckBox[7];
    private long editingId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        editingId = getIntent().getLongExtra(EXTRA_ID, -1);

        AlarmItem existing = null;
        if (editingId >= 0) {
            existing = AlarmStore.findById(AlarmStore.load(this), editingId);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1026"));
        root.setPadding(40, 40, 40, 40);

        TextView title = new TextView(this);
        title.setText(existing == null ? "アラームを追加" : "アラームを編集");
        title.setTextColor(Color.parseColor("#F2F3FA"));
        title.setTextSize(22);
        root.addView(title);

        timePicker = new TimePicker(this);
        timePicker.setIs24HourView(true);
        if (existing != null) {
            timePicker.setHour(existing.hour);
            timePicker.setMinute(existing.minute);
        }
        root.addView(timePicker);

        TextView labelTitle = new TextView(this);
        labelTitle.setText("ラベル");
        labelTitle.setTextColor(Color.parseColor("#9AA3C7"));
        labelTitle.setTextSize(15);
        root.addView(labelTitle);

        labelEdit = new EditText(this);
        labelEdit.setHint("例: 起床、学校");
        labelEdit.setTextColor(Color.parseColor("#F2F3FA"));
        labelEdit.setHintTextColor(Color.parseColor("#5A648C"));
        if (existing != null) labelEdit.setText(existing.label);
        root.addView(labelEdit);

        TextView repeatTitle = new TextView(this);
        repeatTitle.setText("繰り返し（なし = 1回のみ）");
        repeatTitle.setTextColor(Color.parseColor("#9AA3C7"));
        repeatTitle.setTextSize(15);
        root.addView(repeatTitle);

        LinearLayout days = new LinearLayout(this);
        days.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"月", "火", "水", "木", "金", "土", "日"};
        for (int i = 0; i < 7; i++) {
            dayBoxes[i] = new CheckBox(this);
            dayBoxes[i].setText(names[i]);
            dayBoxes[i].setTextColor(Color.parseColor("#F2F3FA"));
            if (existing != null) dayBoxes[i].setChecked(existing.repeat[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            days.addView(dayBoxes[i], lp);
        }
        root.addView(days);

        Button save = new Button(this);
        save.setText("保存");
        save.setTextSize(18);
        save.setOnClickListener(v -> saveAndFinish());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, 48, 0, 16);
        root.addView(save, sp);

        if (existing != null) {
            Button delete = new Button(this);
            delete.setText("削除");
            delete.setOnClickListener(v -> {
                List<AlarmItem> items = new ArrayList<>(AlarmStore.load(this));
                AlarmItem target = AlarmStore.findById(items, editingId);
                if (target != null) {
                    AlarmScheduler.cancelAlarm(this, target);
                    items.remove(target);
                    AlarmStore.save(this, items);
                }
                Toast.makeText(this, "アラームを削除しました", Toast.LENGTH_SHORT).show();
                finish();
            });
            root.addView(delete);
        }

        Button cancel = new Button(this);
        cancel.setText("キャンセル");
        cancel.setOnClickListener(v -> finish());
        root.addView(cancel);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B1026"));
        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveAndFinish() {
        List<AlarmItem> items = new ArrayList<>(AlarmStore.load(this));
        AlarmItem item;
        if (editingId >= 0) {
            item = AlarmStore.findById(items, editingId);
            if (item == null) {
                item = new AlarmItem();
                item.id = editingId;
                items.add(item);
            }
        } else {
            item = new AlarmItem();
            item.id = AlarmStore.newId(items);
            items.add(item);
        }
        item.hour = timePicker.getHour();
        item.minute = timePicker.getMinute();
        item.label = labelEdit.getText().toString().trim();
        for (int i = 0; i < 7; i++) item.repeat[i] = dayBoxes[i].isChecked();
        item.enabled = true;
        // 時刻順に並べ替えて保存
        items.sort((a, b) -> {
            int t = Integer.compare(a.hour * 60 + a.minute, b.hour * 60 + b.minute);
            return t != 0 ? t : Long.compare(a.id, b.id);
        });
        AlarmStore.save(this, items);
        long trigger = AlarmScheduler.scheduleAlarm(this, item);
        String msg = trigger > 0
            ? "アラームを設定: " + ClockLogic.describeTrigger(trigger, System.currentTimeMillis())
            : "アラームを保存しました";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finish();
    }
}
