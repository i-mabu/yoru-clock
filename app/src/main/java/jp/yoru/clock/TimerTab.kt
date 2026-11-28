package jp.yoru.clock

import android.content.Context
import android.content.SharedPreferences
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

// ========================================
// ⏲️ タイマータブ (状態は SharedPreferences に永続化し、アプリを閉じても復元する)
// ========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimerTab() {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(AlarmScheduler.TIMER_PREFS, Context.MODE_PRIVATE)
    }

    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var remainMillis by remember { mutableLongStateOf(0) }
    var totalMillis by remember { mutableLongStateOf(0) }
    var inputHour by remember { mutableIntStateOf(0) }
    var inputMinute by remember { mutableIntStateOf(5) }
    var inputSecond by remember { mutableIntStateOf(0) }

    fun restoreFromPrefs() {
        val savedRunning = prefs.getBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
        val savedTotal = prefs.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0)
        if (savedRunning) {
            val remain = ClockLogic.timerRemainingMillis(
                prefs.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis()
            )
            if (remain > 0) {
                running = true
                paused = false
                remainMillis = remain
                totalMillis = savedTotal
                return
            }
            // 動作中のはずが残り 0 以下: 終了状態に正規化してから初期表示へ
            markTimerFinished(prefs)
        }
        val savedRemain = prefs.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
        if (savedTotal > 0 && savedRemain > 0) {
            running = false
            paused = true
            remainMillis = savedRemain
            totalMillis = savedTotal
        } else {
            running = false
            paused = false
            remainMillis = 0
            totalMillis = 0
        }
    }

    LaunchedEffect(Unit) { restoreFromPrefs() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { restoreFromPrefs() }

    // 動作中は 200ms 間隔で表示を更新する
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            val remain = ClockLogic.timerRemainingMillis(
                prefs.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis()
            )
            remainMillis = remain
            if (remain <= 0) {
                // 終了時の鳴動は AlarmReceiver → RingActivity が担う。表示は初期状態へ戻す
                markTimerFinished(prefs)
                running = false
                paused = false
                totalMillis = 0
                break
            }
            delay(200)
        }
    }

    val showInput = !running && !paused

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "タイマー", color = NightAccent, fontSize = 20.sp)
        Text(
            text = ClockLogic.formatDuration(remainMillis),
            color = NightText, fontSize = 54.sp,
            fontFamily = MonoFamily, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )
        LinearProgressIndicator(
            progress = {
                if (totalMillis > 0) {
                    ((totalMillis - remainMillis).toFloat() / totalMillis.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        if (showInput) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimerPartDropdown("時間", inputHour, 0..23, Modifier.weight(1f)) { inputHour = it }
                TimerPartDropdown("分", inputMinute, 0..59, Modifier.weight(1f)) { inputMinute = it }
                TimerPartDropdown("秒", inputSecond, 0..59, Modifier.weight(1f)) { inputSecond = it }
            }
            val presetMinutes = listOf(1, 3, 5, 10, 15, 30, 60)
            for (rowPresets in presetMinutes.chunked(4)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (preset in rowPresets) {
                        Button(
                            onClick = {
                                inputHour = preset / 60
                                inputMinute = preset % 60
                                inputSecond = 0
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                        ) { Text(preset.toString() + "分") }
                    }
                }
            }
        }

        fun startOrPause() {
            if (running) {
                // 一時停止: 残り時間を保存して登録を取り消す
                val remain = ClockLogic.timerRemainingMillis(
                    prefs.getLong(AlarmScheduler.KEY_TIMER_END, 0), System.currentTimeMillis()
                )
                prefs.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
                    .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply()
                AlarmScheduler.cancelTimer(context)
                running = false
                paused = true
                remainMillis = remain
                return
            }
            var total = prefs.getLong(AlarmScheduler.KEY_TIMER_TOTAL, 0)
            var remain = prefs.getLong(AlarmScheduler.KEY_TIMER_REMAINING, 0)
            if (total <= 0 || remain <= 0) {
                // 新規開始: 入力値から合計を作る
                total = ClockLogic.timerTotalMillis(inputHour, inputMinute, inputSecond)
                if (total <= 0) {
                    Toast.makeText(context, "時間を設定してください", Toast.LENGTH_SHORT).show()
                    return
                }
                remain = total
            }
            val end = System.currentTimeMillis() + remain
            prefs.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, true)
                .putLong(AlarmScheduler.KEY_TIMER_END, end)
                .putLong(AlarmScheduler.KEY_TIMER_TOTAL, total)
                .putLong(AlarmScheduler.KEY_TIMER_REMAINING, remain).apply()
            AlarmScheduler.scheduleTimer(context, end, "タイマー")
            running = true
            paused = false
            remainMillis = remain
            totalMillis = total
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Button(
                onClick = { startOrPause() },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    if (running) "一時停止" else if (paused) "再開" else "開始",
                    fontSize = 18.sp
                )
            }
            Button(
                onClick = {
                    // 本体とスヌーズの登録を取り消し、保存した状態も全て消す
                    AlarmScheduler.cancelTimer(context)
                    AlarmScheduler.cancelSnoozeTimer(context)
                    prefs.edit().clear().apply()
                    running = false
                    paused = false
                    remainMillis = 0
                    totalMillis = 0
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) { Text("キャンセル", fontSize = 18.sp) }
        }
    }
}

/** 終了状態への正規化 (動作フラグを落とし、残りを 0 にする) */
private fun markTimerFinished(prefs: SharedPreferences) {
    prefs.edit().putBoolean(AlarmScheduler.KEY_TIMER_RUNNING, false)
        .putLong(AlarmScheduler.KEY_TIMER_REMAINING, 0).apply()
}

// ----------------------------------------
// 🔢 数値ドロップダウン (時/分/秒の入力用)
// ----------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerPartDropdown(
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
            for (candidate in range) {
                DropdownMenuItem(
                    text = { Text("%02d".format(candidate)) },
                    onClick = {
                        onSelect(candidate)
                        expanded = false
                    }
                )
            }
        }
    }
}
