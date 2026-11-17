package jp.yoru.clock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

// ===== よる時計 メイン画面 (時計 / アラーム / タイマー / ストップウォッチ の4タブ) =====
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private val panels = arrayOfNulls<View>(4)
    private val tabButtons = arrayOfNulls<Button>(4)
    private var currentTab = 0

    // 時計タブ
    private lateinit var digitalView: TextView
    private lateinit var dateView: TextView
    private lateinit var analogView: AnalogClockView
    private lateinit var worldContainer: LinearLayout
    private val worldCities = ArrayList<ClockLogic.City>()

    // アラームタブ
    private lateinit var alarmContainer: LinearLayout
    private lateinit var alarmEmptyView: TextView

    // タイマータブ
    private lateinit var hourPicker: NumberPicker
    private lateinit var minPicker: NumberPicker
    private lateinit var secPicker: NumberPicker
    private lateinit var timerRemainView: TextView
    private lateinit var timerProgress: ProgressBar
    private lateinit var timerStartPauseBtn: Button
    private lateinit var timerPickerRow: LinearLayout
    private var timerPresetRow1: LinearLayout? = null
    private var timerPresetRow2: LinearLayout? = null

    // ストップウォッチタブ
    private lateinit var swView: TextView
    private lateinit var lapContainer: LinearLayout
    private lateinit var swStartPauseBtn: Button
    private var swRunning = false
    private var swBase: Long = 0        // 開始時点の elapsedRealtime - 蓄積分
    private var swAccumulated: Long = 0 // 一時停止までの累計
    private var swLastLapCum: Long = 0
    private var lapCount = 0

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun makeText(s: String, sp: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        return t
    }

    private fun makeButton(s: String): Button {
        val b = Button(this)
        b.text = s
        b.textSize = 15f
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        panels[0] = buildClockPanel()
        panels[1] = buildAlarmPanel()
        panels[2] = buildTimerPanel()
        panels[3] = buildStopwatchPanel()
        for (p in panels) content.addView(p!!)

        // 自前タブ列 (BottomNavigation相当)
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.setBackgroundColor(PANEL)
        val labels = arrayOf("時計", "アラーム", "タイマー", "ストップウォッチ")
        for (i in 0 until 4) {
            val idx = i
            val b = Button(this)
            b.text = labels[i]
            b.textSize = 14f
            b.setOnClickListener { selectTab(idx) }
            tabButtons[i] = b
            tabRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabRow)

        setContentView(root)
        // Android 13+: 鳴動通知の表示許可を求める (主経路は高優先度通知のため)
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        loadWorldCities()
        renderWorldCities()
        selectTab(0)
        // バックアップ復元・アプリ更新直後の初回起動でも登録を正規化する
        AlarmScheduler.rescheduleAll(this)
        restoreTimerState()
    }

    private fun selectTab(idx: Int) {
        currentTab = idx
        for (i in 0 until 4) {
            panels[i]!!.visibility = if (i == idx) View.VISIBLE else View.GONE
            tabButtons[i]!!.setBackgroundColor(if (i == idx) TAB_ON else Color.TRANSPARENT)
            tabButtons[i]!!.setTextColor(if (i == idx) GOLD else SUB)
        }
        if (idx == 1) renderAlarms()
    }

    private fun wrapScroll(inner: LinearLayout): ScrollView {
        val s = ScrollView(this)
        s.setBackgroundColor(BG)
        s.addView(inner)
        return s
    }

    private fun basePanel(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(16), dp(16), dp(16))
        return l
    }

    // ===== 時計タブ =====
    private fun buildClockPanel(): View {
        val l = basePanel()

        digitalView = makeText("00:00:00", 56f, TEXT)
        digitalView.gravity = Gravity.CENTER
        digitalView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        l.addView(digitalView)

        dateView = makeText("", 18f, GOLD)
        dateView.gravity = Gravity.CENTER
        l.addView(dateView)

        analogView = AnalogClockView(this)
        val ap = LinearLayout.LayoutParams(dp(280), dp(280))
        ap.gravity = Gravity.CENTER_HORIZONTAL
        ap.setMargins(0, dp(16), 0, dp(8))
        l.addView(analogView, ap)

        val worldTitle = makeText("世界時計", 18f, ACCENT)
        val wt = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        wt.setMargins(0, dp(12), 0, dp(4))
        l.addView(worldTitle, wt)

        worldContainer = LinearLayout(this)
        worldContainer.orientation = LinearLayout.VERTICAL
        l.addView(worldContainer)

        val add = makeButton("＋ 都市を追加")
        add.setOnClickListener { showAddCityDialog() }
        val ab = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ab.setMargins(0, dp(8), 0, 0)
        l.addView(add, ab)

        val hint = makeText("都市を長押しで削除", 12f, SUB)
        l.addView(hint)
        return wrapScroll(l)
    }

    private fun loadWorldCities() {
        worldCities.clear()
        val sp = getSharedPreferences("world", MODE_PRIVATE)
        val json = sp.getString("cities_json", null)
        if (json == null) {
            // 保存キー自体がない初回のみ既定6都市。空配列は「全て削除した」状態として尊重する
            worldCities.addAll(ClockLogic.DEFAULT_CITIES)
            return
        }
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val c = ClockLogic.findCityByTz(arr.getString(i)) ?: continue
                var dup = false
                for (w in worldCities) if (w.tzId == c.tzId) { dup = true; break }
                if (!dup) worldCities.add(c)
            }
        } catch (ignored: Exception) {}
    }

    private fun saveWorldCities() {
        try {
            val arr = JSONArray()
            for (c in worldCities) arr.put(c.tzId)
            getSharedPreferences("world", MODE_PRIVATE).edit()
                .putString("cities_json", arr.toString()).apply()
        } catch (ignored: Exception) {}
    }

    private fun renderWorldCities() {
        if (!::worldContainer.isInitialized) return
        worldContainer.removeAllViews()
        val now = System.currentTimeMillis()
        for (city in worldCities) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(dp(10), dp(10), dp(10), dp(10))
            row.setBackgroundColor(PANEL)
            val rp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            rp.setMargins(0, 0, 0, dp(6))
            row.layoutParams = rp

            val name = makeText(city.name, 17f, TEXT)
            row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val off = makeText(ClockLogic.formatOffsetFromJapan(city.tzId, now), 14f, SUB)
            off.gravity = Gravity.END
            off.tag = "worldoffset:" + city.tzId
            row.addView(off, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))

            val time = makeText(ClockLogic.formatTimeInZone(city.tzId, now), 22f, GOLD)
            time.gravity = Gravity.END
            time.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            time.tag = "worldtime:" + city.tzId
            row.addView(time, LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.WRAP_CONTENT))

            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(city.name + " を削除しますか？")
                    .setPositiveButton("削除") { _, _ ->
                        worldCities.remove(city)
                        saveWorldCities()
                        renderWorldCities()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                true
            }
            worldContainer.addView(row)
        }
    }

    private fun refreshWorldTimes() {
        if (!::worldContainer.isInitialized) return
        val now = System.currentTimeMillis()
        for (i in 0 until worldContainer.childCount) {
            val row = worldContainer.getChildAt(i)
            if (row is LinearLayout) {
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    val tag = child.tag
                    if (child is TextView && tag is String) {
                        if (tag.startsWith("worldtime:")) {
                            val tz = tag.substring("worldtime:".length)
                            child.text = ClockLogic.formatTimeInZone(tz, now)
                        } else if (tag.startsWith("worldoffset:")) {
                            // 夏時間の切り替わりで時差も変わるため、時刻と一緒に更新する
                            val tz = tag.substring("worldoffset:".length)
                            child.text = ClockLogic.formatOffsetFromJapan(tz, now)
                        }
                    }
                }
            }
        }
    }

    private fun showAddCityDialog() {
        val candidates = ArrayList<ClockLogic.City>()
        for (c in ClockLogic.CITY_CATALOG) {
            var exists = false
            for (w in worldCities) if (w.tzId == c.tzId) { exists = true; break }
            if (!exists) candidates.add(c)
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "追加できる都市がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val names = Array<CharSequence>(candidates.size) { i -> candidates[i].name }
        AlertDialog.Builder(this)
            .setTitle("都市を追加")
            .setItems(names) { _, which ->
                worldCities.add(candidates[which])
                saveWorldCities()
                renderWorldCities()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // 1秒境界に合わせた時計更新
    private val clockTick: Runnable = Runnable { runClockTick() }

    private fun runClockTick() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        // デジタル時計は端末ローカルTZ (アナログ・日付表示と揃える)
        digitalView.text = ClockLogic.formatTimeSecInZone(TimeZone.getDefault().id, now)
        dateView.text = ClockLogic.formatDateJa(cal)
        analogView.invalidate()
        refreshWorldTimes()
        val delay = 1000 - (now % 1000)
        handler.postDelayed(clockTick, delay)
    }

    // ===== アラームタブ =====
    private fun buildAlarmPanel(): View {
        val l = basePanel()
        val title = makeText("アラーム", 20f, ACCENT)
        l.addView(title)

        alarmEmptyView = makeText("アラームはまだありません", 15f, SUB)
        val ep = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ep.setMargins(0, dp(16), 0, 0)
        l.addView(alarmEmptyView, ep)

        alarmContainer = LinearLayout(this)
        alarmContainer.orientation = LinearLayout.VERTICAL
        l.addView(alarmContainer)

        val add = makeButton("＋ アラームを追加")
        add.textSize = 17f
        add.setOnClickListener {
            val intent = Intent(this, AlarmEditActivity::class.java)
            intent.putExtra(AlarmEditActivity.EXTRA_ID, -1L)
            startActivity(intent)
        }
        val ab = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ab.setMargins(0, dp(12), 0, 0)
        l.addView(add, ab)
        return wrapScroll(l)
    }

    private fun renderAlarms() {
        if (!::alarmContainer.isInitialized) return
        alarmContainer.removeAllViews()
        val items = AlarmStore.load(this)
        alarmEmptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(dp(12), dp(10), dp(12), dp(10))
            row.setBackgroundColor(PANEL)
            val rp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            rp.setMargins(0, dp(8), 0, 0)
            row.layoutParams = rp

            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            val time = makeText(item.timeText(), 30f, if (item.enabled) TEXT else SUB)
            time.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            texts.addView(time)
            var sub = item.repeatText()
            if (item.label.isNotEmpty()) sub = item.label + " ・ " + sub
            if (item.enabled) {
                sub += " ・ 次回 " + ClockLogic.describeTrigger(
                    item.nextTriggerMillis(System.currentTimeMillis()), System.currentTimeMillis())
            }
            texts.addView(makeText(sub, 13f, SUB))
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val sw = Switch(this)
            sw.isChecked = item.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                val all = AlarmStore.load(this)
                val target = AlarmStore.findById(all, item.id) ?: return@setOnCheckedChangeListener
                target.enabled = checked
                AlarmStore.save(this, all)
                if (checked) {
                    val trigger = AlarmScheduler.scheduleAlarm(this, target)
                    Toast.makeText(this, "ON: 次回 " + ClockLogic.describeTrigger(trigger, System.currentTimeMillis()),
                        Toast.LENGTH_SHORT).show()
                } else {
                    AlarmScheduler.cancelAlarm(this, target)
                    AlarmScheduler.cancelSnoozeAlarm(this, target.rc)
                    Toast.makeText(this, "アラームをOFFにしました", Toast.LENGTH_SHORT).show()
                }
                renderAlarms()
            }
            row.addView(sw)

            row.setOnClickListener {
                val intent = Intent(this, AlarmEditActivity::class.java)
                intent.putExtra(AlarmEditActivity.EXTRA_ID, item.id)
                startActivity(intent)
            }
            alarmContainer.addView(row)
        }
    }

    // ===== タイマータブ =====
    private fun buildTimerPanel(): View {
        val l = basePanel()
        val title = makeText("タイマー", 20f, ACCENT)
        l.addView(title)

        timerRemainView = makeText("00:00", 54f, TEXT)
        timerRemainView.gravity = Gravity.CENTER
        timerRemainView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val tp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tp.setMargins(0, dp(16), 0, 0)
        l.addView(timerRemainView, tp)

        timerProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        timerProgress.max = 1000
        timerProgress.progress = 0
        val pp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        pp.setMargins(0, dp(8), 0, 0)
        l.addView(timerProgress, pp)

        timerPickerRow = LinearLayout(this)
        timerPickerRow.orientation = LinearLayout.HORIZONTAL
        timerPickerRow.gravity = Gravity.CENTER
        hourPicker = makePicker(0, 23)
        minPicker = makePicker(0, 59)
        secPicker = makePicker(0, 59)
        minPicker.value = 5
        timerPickerRow.addView(pickerWithLabel(hourPicker, "時間"))
        timerPickerRow.addView(pickerWithLabel(minPicker, "分"))
        timerPickerRow.addView(pickerWithLabel(secPicker, "秒"))
        l.addView(timerPickerRow)

        // プリセット
        timerPresetRow1 = LinearLayout(this)
        timerPresetRow1!!.orientation = LinearLayout.HORIZONTAL
        timerPresetRow2 = LinearLayout(this)
        timerPresetRow2!!.orientation = LinearLayout.HORIZONTAL
        val presets = intArrayOf(1, 3, 5, 10, 15, 30, 60)
        for (i in presets.indices) {
            val mins = presets[i]
            val b = makeButton(mins.toString() + "分")
            b.setOnClickListener {
                hourPicker.value = mins / 60
                minPicker.value = mins % 60
                secPicker.value = 0
            }
            val row = if (i < 4) timerPresetRow1!! else timerPresetRow2!!
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        l.addView(timerPresetRow1)
        l.addView(timerPresetRow2)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        timerStartPauseBtn = makeButton("開始")
        timerStartPauseBtn.textSize = 18f
        timerStartPauseBtn.setOnClickListener { onTimerStartPause() }
        btnRow.addView(timerStartPauseBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val cancel = makeButton("キャンセル")
        cancel.textSize = 18f
        cancel.setOnClickListener { cancelTimer() }
        btnRow.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bp.setMargins(0, dp(16), 0, 0)
        l.addView(btnRow, bp)
        return wrapScroll(l)
    }

    private fun makePicker(min: Int, max: Int): NumberPicker {
        val p = NumberPicker(this)
        p.minValue = min
        p.maxValue = max
        p.setFormatter { v -> "%02d".format(Locale.US, v) }
        return p
    }

    /** タイマーの入力系 (ピッカー+プリセット) の表示をまとめて切り替える */
    private fun setTimerInputVisibility(vis: Int) {
        timerPickerRow.visibility = vis
        if (timerPresetRow1 != null) timerPresetRow1!!.visibility = vis
        if (timerPresetRow2 != null) timerPresetRow2!!.visibility = vis
    }

    private fun pickerWithLabel(p: NumberPicker, label: String): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.addView(p)
        val t = makeText(label, 13f, SUB)
        t.gravity = Gravity.CENTER
        col.addView(t)
        return col
    }

    private fun timerPrefs(): SharedPreferences {
        return getSharedPreferences(AlarmScheduler.TIMER_PREFS, MODE_PRIVATE)
    }

    private fun restoreTimerState() {
        val sp = timerPrefs()
        val running = sp.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
        val total = sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0)
        if (running) {
            val remain = ClockLogic.timerRemainingMillis(sp.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis())
            if (remain > 0) {
                timerStartPauseBtn.text = "一時停止"
                setTimerInputVisibility(View.GONE)
                updateTimerDisplay(remain, total)
                return
            }
            // 動作中のはずが残り0以下: 終了状態に正規化してから初期表示へ
            sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0).apply()
        }
        val remain = sp.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
        if (total > 0 && remain > 0) {
            timerStartPauseBtn.text = "再開"
            setTimerInputVisibility(View.GONE)
            updateTimerDisplay(remain, total)
        } else {
            timerStartPauseBtn.text = "開始"
            setTimerInputVisibility(View.VISIBLE)
            updateTimerDisplay(0, 0)
        }
    }

    private fun updateTimerDisplay(remainMs: Long, totalMs: Long) {
        timerRemainView.text = ClockLogic.formatDuration(remainMs)
        if (totalMs > 0) {
            val prog = (1000L * (totalMs - remainMs) / totalMs).toInt()
            timerProgress.progress = max(0, min(1000, prog))
        } else {
            timerProgress.progress = 0
        }
    }

    private fun onTimerStartPause() {
        val sp = timerPrefs()
        val running = sp.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
        val now = System.currentTimeMillis()
        if (running) {
            // 一時停止
            val remain = ClockLogic.timerRemainingMillis(sp.getLong(AlarmScheduler.KEY_TIMER_END, 0), now)
            sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply()
            AlarmScheduler.cancelTimer(this)
            timerStartPauseBtn.text = "再開"
            updateTimerDisplay(remain, sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0))
            return
        }
        var total = sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0)
        var remain = sp.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
        if (total <= 0 || remain <= 0) {
            // 新規開始
            total = ClockLogic.timerTotalMillis(hourPicker.value, minPicker.value, secPicker.value)
            if (total <= 0) {
                Toast.makeText(this, "時間を設定してください", Toast.LENGTH_SHORT).show()
                return
            }
            remain = total
        }
        val end = now + remain
        sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, true)
            .putLong(AlarmScheduler.KEY_TIMER_END, end)
            .putLong(AlarmScheduler.KEY_TIMER_TOTAL, total)
            .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply()
        AlarmScheduler.scheduleTimer(this, end, "タイマー")
        timerStartPauseBtn.text = "一時停止"
        setTimerInputVisibility(View.GONE)
        updateTimerDisplay(remain, total)
    }

    private fun cancelTimer() {
        AlarmScheduler.cancelTimer(this)
        AlarmScheduler.cancelSnoozeTimer(this)
        timerPrefs().edit().clear().apply()
        timerStartPauseBtn.text = "開始"
        setTimerInputVisibility(View.VISIBLE)
        updateTimerDisplay(0, 0)
    }

    private val timerTick: Runnable = Runnable { runTimerTick() }

    private fun runTimerTick() {
        val sp = timerPrefs()
        if (sp.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)) {
            val remain = ClockLogic.timerRemainingMillis(
                sp.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis())
            updateTimerDisplay(remain, sp.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0))
            if (remain <= 0) {
                // 終了処理は AlarmReceiver → RingActivity が担う。状態を終了に正規化し、表示は初期状態へ
                sp.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                    .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0).apply()
                timerStartPauseBtn.text = "開始"
                setTimerInputVisibility(View.VISIBLE)
            }
        }
        handler.postDelayed(timerTick, 200)
    }

    // ===== ストップウォッチタブ =====
    private fun buildStopwatchPanel(): View {
        val l = basePanel()
        val title = makeText("ストップウォッチ", 20f, ACCENT)
        l.addView(title)

        swView = makeText("00:00.00", 48f, TEXT)
        swView.gravity = Gravity.CENTER
        swView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val tp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tp.setMargins(0, dp(20), 0, 0)
        l.addView(swView, tp)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        swStartPauseBtn = makeButton("開始")
        swStartPauseBtn.textSize = 17f
        swStartPauseBtn.setOnClickListener { onStopwatchStartPause() }
        btnRow.addView(swStartPauseBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val lap = makeButton("ラップ")
        lap.textSize = 17f
        lap.setOnClickListener { onLap() }
        btnRow.addView(lap, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val reset = makeButton("リセット")
        reset.textSize = 17f
        reset.setOnClickListener { onStopwatchReset() }
        btnRow.addView(reset, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bp.setMargins(0, dp(16), 0, 0)
        l.addView(btnRow, bp)

        lapContainer = LinearLayout(this)
        lapContainer.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(16), 0, 0)
        l.addView(lapContainer, lp)
        return wrapScroll(l)
    }

    private fun swElapsed(): Long {
        if (swRunning) return SystemClock.elapsedRealtime() - swBase
        return swAccumulated
    }

    private fun onStopwatchStartPause() {
        if (swRunning) {
            swAccumulated = swElapsed()
            swRunning = false
            swStartPauseBtn.text = "再開"
        } else {
            swBase = SystemClock.elapsedRealtime() - swAccumulated
            swRunning = true
            swStartPauseBtn.text = "一時停止"
        }
        swView.text = ClockLogic.formatStopwatch(swElapsed())
    }

    private fun onStopwatchReset() {
        swRunning = false
        swAccumulated = 0
        swLastLapCum = 0
        lapCount = 0
        swStartPauseBtn.text = "開始"
        swView.text = "00:00.00"
        lapContainer.removeAllViews()
    }

    private fun onLap() {
        if (!swRunning) return // ラップは計測中のみ
        val cum = swElapsed()
        if (cum <= 0) return
        lapCount++
        val lapTime = cum - swLastLapCum
        swLastLapCum = cum
        val row = makeText(
            "ラップ " + lapCount + "　　" + ClockLogic.formatStopwatch(lapTime)
                + "　　累計 " + ClockLogic.formatStopwatch(cum), 16f, TEXT)
        row.typeface = Typeface.MONOSPACE
        row.setPadding(dp(8), dp(6), dp(8), dp(6))
        row.setBackgroundColor(PANEL)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, dp(5))
        row.layoutParams = lp
        // 新しいラップを先頭に
        lapContainer.addView(row, 0)
    }

    private val swTick: Runnable = Runnable { runSwTick() }

    private fun runSwTick() {
        if (swRunning) swView.text = ClockLogic.formatStopwatch(swElapsed())
        handler.postDelayed(swTick, 31)
    }

    // ===== ライフサイクル =====
    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        handler.removeCallbacks(timerTick)
        handler.post(timerTick)
        handler.removeCallbacks(swTick)
        handler.post(swTick)
        renderAlarms()
        restoreTimerState()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(timerTick)
        handler.removeCallbacks(swTick)
    }

    companion object {
        private val BG = Color.parseColor("#0B1026")
        private val PANEL = Color.parseColor("#161D38")
        private val TEXT = Color.parseColor("#F2F3FA")
        private val SUB = Color.parseColor("#9AA3C7")
        private val ACCENT = Color.parseColor("#8FA8FF")
        private val GOLD = Color.parseColor("#FFC46B")
        private val TAB_ON = Color.parseColor("#2A3560")
    }
}
