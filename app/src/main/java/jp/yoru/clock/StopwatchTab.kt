package jp.yoru.clock

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ===== 1. ストップウォッチタブ (1/100秒表示 + ラップ) =====
private data class LapEntry(val number: Int, val lapMs: Long, val cumMs: Long)

@Composable
internal fun StopwatchTab() {
    var running by remember { mutableStateOf(false) }
    var base by remember { mutableLongStateOf(0) }         // 開始時の elapsedRealtime() - 蓄積分
    var accumulated by remember { mutableLongStateOf(0) }  // 一時停止までの累計
    var lastLapCum by remember { mutableLongStateOf(0) }
    var displayMs by remember { mutableLongStateOf(0) }
    val laps = remember { mutableStateListOf<LapEntry>() }

    fun elapsed(): Long = if (running) SystemClock.elapsedRealtime() - base else accumulated

    // 約30ms間隔の表示更新 (旧 Handler 実装と同じ)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            displayMs = elapsed()
            delay(31)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "ストップウォッチ", color = NightAccent, fontSize = 20.sp)
        Text(
            text = ClockLogic.formatStopwatch(displayMs),
            color = NightText, fontSize = 48.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            Button(
                onClick = {
                    if (running) {
                        accumulated = elapsed()
                        running = false
                    } else {
                        base = SystemClock.elapsedRealtime() - accumulated
                        running = true
                    }
                    displayMs = elapsed()
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) { Text(if (running) "一時停止" else if (accumulated > 0) "再開" else "開始", fontSize = 17.sp) }
            Button(
                onClick = {
                    if (running) {
                        val cum = elapsed()
                        if (cum > 0) {
                            val lapTime = cum - lastLapCum
                            lastLapCum = cum
                            // 先頭に追加して一覧を新しい順に保つ (番号は追加順に増える)
                            laps.add(0, LapEntry(laps.size + 1, lapTime, cum))
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            ) { Text("ラップ", fontSize = 17.sp) }
            Button(
                onClick = {
                    running = false
                    accumulated = 0
                    lastLapCum = 0
                    displayMs = 0
                    laps.clear()
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) { Text("リセット", fontSize = 17.sp) }
        }
        Column(modifier = Modifier.padding(top = 16.dp)) {
            for (lap in laps) {
                Text(
                    text = "ラップ " + lap.number + "　　" + ClockLogic.formatStopwatch(lap.lapMs)
                        + "　　累計 " + ClockLogic.formatStopwatch(lap.cumMs),
                    color = NightText, fontSize = 16.sp, fontFamily = MonoFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 5.dp)
                        .background(NightPanel)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}
