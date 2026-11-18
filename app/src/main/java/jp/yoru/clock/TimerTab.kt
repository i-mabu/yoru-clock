package jp.yoru.clock

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

// ===== 1. タイマータブ (状態は SharedPreferences 永続化、旧実装と同一キー) =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimerTab() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AlarmScheduler.TIMER_PREFS, Context.MODE_PRIVATE) }

    var running by remember { mutableStateOf(false) }
    var hasPausedState by remember { mutableStateOf(false) }
    var remainMs by remember { mutableLongStateOf(0) }
    var totalMs by remember { mutableLongStateOf(0) }
    var hour by remember { mutableIntStateOf(0) }
    var minute by remember { mutableIntStateOf(5) }
    var second by remember { mutableIntStateOf(0) }

    fun restore() {
        val isRunning = prefs.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
        val total = prefs.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0)
        if (isRunning) {
            val remain = ClockLogic.timerRemainingMillis(
                prefs.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis())
            if (remain > 0) {
                running = true; hasPausedState = false; remainMs = remain; totalMs = total
                return
            }
            // 動作中のはずが残り0以下: 終了状態に正規化してから初期表示へ
            prefs.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0).apply()
        }
        val remain = prefs.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
        if (total > 0 && remain > 0) {
            running = false; hasPausedState = true; remainMs = remain; totalMs = total
        } else {
            running = false; hasPausedState = false; remainMs = 0; totalMs = 0
        }
    }

    LaunchedEffect(Unit) { restore() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { restore() }

    // 200ms 間隔の表示更新 (旧 Handler 実装と同じ)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            val remain = ClockLogic.timerRemainingMillis(
                prefs.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis())
            remainMs = remain
            if (remain <= 0) {
                // 終了処理は AlarmReceiver → RingActivity が担う。表示は初期状態へ
                prefs.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                    .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0).apply()
                running = false; hasPausedState = false; totalMs = 0
                break
            }
            delay(200)
        }
    }

    val showInput = !running && !hasPausedState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "タイマー", color = NightAccent, fontSize = 20.sp)
        Text(
            text = ClockLogic.formatDuration(remainMs),
            color = NightText, fontSize = 54.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )
        LinearProgressIndicator(
            progress = {
                if (totalMs > 0) ((totalMs - remainMs).toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        if (showInput) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NumberDropdown("時間", hour, 0..23, Modifier.weight(1f)) { hour = it }
                NumberDropdown("分", minute, 0..59, Modifier.weight(1f)) { minute = it }
                NumberDropdown("秒", second, 0..59, Modifier.weight(1f)) { second = it }
            }
            val presets = listOf(1, 3, 5, 10, 15, 30, 60)
            for (rowPresets in presets.chunked(4)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (mins in rowPresets) {
                        Button(
                            onClick = {
                                hour = mins / 60; minute = mins % 60; second = 0
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                        ) { Text(mins.toString() + "分") }
                    }
                }
            }
        }
        fun onStartPause() {
            if (running) {
                // 一時停止
                val now = System.currentTimeMillis()
                val remain = ClockLogic.timerRemainingMillis(
                    prefs.getLong(AlarmScheduler.KEY_TIMER_END, 0), now)
                prefs.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                    .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply()
                AlarmScheduler.cancelTimer(context)
                running = false; hasPausedState = true; remainMs = remain
                return
            }
            var total = prefs.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0)
            var remain = prefs.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
            if (total <= 0 || remain <= 0) {
                // 新規開始
                total = ClockLogic.timerTotalMillis(hour, minute, second)
                if (total <= 0) {
                    Toast.makeText(context, "時間を設定してください", Toast.LENGTH_SHORT).show()
                    return
                }
                remain = total
            }
            val now = System.currentTimeMillis()
            val end = now + remain
            prefs.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, true)
                .putLong(AlarmScheduler.KEY_TIMER_END, end)
                .putLong(AlarmScheduler.KEY_TIMER_TOTAL, total)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply()
            AlarmScheduler.scheduleTimer(context, end, "タイマー")
            running = true; hasPausedState = false; remainMs = remain; totalMs = total
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Button(
                onClick = { onStartPause() },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) { Text(if (running) "一時停止" else if (hasPausedState) "再開" else "開始", fontSize = 18.sp) }
            Button(
                onClick = {
                    // 本体・スヌーズの登録を取り消し、保存したタイマー状態も全て消す
                    AlarmScheduler.cancelTimer(context)
                    AlarmScheduler.cancelSnoozeTimer(context)
                    prefs.edit().clear().apply()
                    running = false; hasPausedState = false; remainMs = 0; totalMs = 0
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) { Text("キャンセル", fontSize = 18.sp) }
        }
    }
}

// ----- 1.1 数値ドロップダウン (旧 NumberPicker の代替) -----
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberDropdown(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.padding(horizontal = 4.dp)
    ) {
        OutlinedTextField(
            value = "%02d".format(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (v in range) {
                DropdownMenuItem(
                    text = { Text("%02d".format(v)) },
                    onClick = {
                        onSelect(v)
                        expanded = false
                    }
                )
            }
        }
    }
}
