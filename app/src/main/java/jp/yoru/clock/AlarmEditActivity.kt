package jp.yoru.clock

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast

// ===== 1. アラーム追加・編集画面 =====
class AlarmEditActivity : Activity() {
    private lateinit var timePicker: TimePicker
    private lateinit var labelEdit: EditText
    private val dayBoxes = arrayOfNulls<CheckBox>(7)
    private var editingId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingId = intent.getLongExtra(EXTRA_ID, -1)

        var existing: AlarmItem? = null
        if (editingId >= 0) {
            existing = AlarmStore.findById(AlarmStore.load(this), editingId)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0B1026"))
        root.setPadding(40, 40, 40, 40)

        val title = TextView(this)
        title.text = if (existing == null) "アラームを追加" else "アラームを編集"
        title.setTextColor(Color.parseColor("#F2F3FA"))
        title.textSize = 22f
        root.addView(title)

        timePicker = TimePicker(this)
        timePicker.setIs24HourView(true)
        if (existing != null) {
            timePicker.hour = existing.hour
            timePicker.minute = existing.minute
        }
        root.addView(timePicker)

        val labelTitle = TextView(this)
        labelTitle.text = "ラベル"
        labelTitle.setTextColor(Color.parseColor("#9AA3C7"))
        labelTitle.textSize = 15f
        root.addView(labelTitle)

        labelEdit = EditText(this)
        labelEdit.hint = "例: 起床、学校"
        labelEdit.setTextColor(Color.parseColor("#F2F3FA"))
        labelEdit.setHintTextColor(Color.parseColor("#5A648C"))
        if (existing != null) labelEdit.setText(existing.label)
        root.addView(labelEdit)

        val repeatTitle = TextView(this)
        repeatTitle.text = "繰り返し（なし = 1回のみ）"
        repeatTitle.setTextColor(Color.parseColor("#9AA3C7"))
        repeatTitle.textSize = 15f
        root.addView(repeatTitle)

        val days = LinearLayout(this)
        days.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("月", "火", "水", "木", "金", "土", "日")
        for (i in 0 until 7) {
            dayBoxes[i] = CheckBox(this)
            dayBoxes[i]!!.text = names[i]
            dayBoxes[i]!!.setTextColor(Color.parseColor("#F2F3FA"))
            if (existing != null) dayBoxes[i]!!.isChecked = existing.repeat[i]
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            days.addView(dayBoxes[i], lp)
        }
        root.addView(days)

        val save = Button(this)
        save.text = "保存"
        save.textSize = 18f
        save.setOnClickListener { saveAndFinish() }
        val sp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        sp.setMargins(0, 48, 0, 16)
        root.addView(save, sp)

        if (existing != null) {
            val delete = Button(this)
            delete.text = "削除"
            delete.setOnClickListener {
                val items = ArrayList(AlarmStore.load(this))
                val target = AlarmStore.findById(items, editingId)
                if (target != null) {
                    AlarmScheduler.cancelAlarm(this, target)
                    AlarmScheduler.cancelSnoozeAlarm(this, target.rc)
                    items.remove(target)
                    AlarmStore.save(this, items)
                }
                Toast.makeText(this, "アラームを削除しました", Toast.LENGTH_SHORT).show()
                finish()
            }
            root.addView(delete)
        }

        val cancel = Button(this)
        cancel.text = "キャンセル"
        cancel.setOnClickListener { finish() }
        root.addView(cancel)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B1026"))
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun saveAndFinish() {
        val items = ArrayList(AlarmStore.load(this))
        val item: AlarmItem
        if (editingId >= 0) {
            val found = AlarmStore.findById(items, editingId)
            if (found == null) {
                item = AlarmItem()
                item.id = editingId
                // 自分を add する前のリストで rc を採番する (既存と重複しない)
                item.rc = AlarmItem.nextRequestCode(items)
                item.enabled = true
                items.add(item)
            } else {
                item = found
            }
            // 編集時は既存の enabled (ON/OFF) をそのまま保持する (ここでは変更しない)
        } else {
            item = AlarmItem()
            item.id = AlarmStore.newId(items)
            // 自分を add する前のリストで rc を採番する (既存と重複しない)
            item.rc = AlarmItem.nextRequestCode(items)
            item.enabled = true
            items.add(item)
        }
        item.hour = timePicker.hour
        item.minute = timePicker.minute
        item.label = labelEdit.text.toString().trim { it <= ' ' } // 前後の空白類を除去する (対象は ' ' 以下の文字)
        for (i in 0 until 7) item.repeat[i] = dayBoxes[i]!!.isChecked
        // 時刻順に並べ替えて保存
        items.sortWith { a, b ->
            val t = (a.hour * 60 + a.minute).compareTo(b.hour * 60 + b.minute)
            if (t != 0) t else a.id.compareTo(b.id)
        }
        AlarmStore.save(this, items)
        val msg: String
        if (item.enabled) {
            val trigger = AlarmScheduler.scheduleAlarm(this, item)
            msg = if (trigger > 0)
                "アラームを設定: " + ClockLogic.describeTrigger(trigger, System.currentTimeMillis())
            else
                "アラームを保存しました"
        } else {
            // OFF のまま保存されたら登録もスヌーズも取り消す
            AlarmScheduler.cancelAlarm(this, item)
            AlarmScheduler.cancelSnoozeAlarm(this, item.rc)
            msg = "アラームを保存しました（OFF）"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_ID = "edit_id"
    }
}
