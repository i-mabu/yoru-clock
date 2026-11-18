package jp.yoru.clock

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.util.Calendar
import java.util.TimeZone

// ===== 1. 時計タブ (デジタル + アナログ + 世界時計) =====
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ClockTab() {
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 1秒境界に合わせた更新 (旧 Handler 実装と同じ方式)
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMs = now
            delay(1000 - (now % 1000))
        }
    }

    val cities = remember { mutableStateListOf<ClockLogic.City>().apply { addAll(loadWorldCities(context)) } }
    var cityToDelete by remember { mutableStateOf<ClockLogic.City?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = ClockLogic.formatTimeSecInZone(TimeZone.getDefault().id, nowMs),
            color = NightText, fontSize = 56.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = ClockLogic.formatDateJa(Calendar.getInstance().apply { timeInMillis = nowMs }),
            color = NightGold, fontSize = 18.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        AnalogClock(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.CenterHorizontally)
                .padding(top = 16.dp, bottom = 8.dp)
        )
        Text(
            text = "世界時計", color = NightAccent, fontSize = 18.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        for (city in cities) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .background(NightPanel)
                    .combinedClickable(onLongClick = { cityToDelete = city }, onClick = {})
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = city.name, color = NightText, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(
                    text = ClockLogic.formatOffsetFromJapan(city.tzId, nowMs),
                    color = NightSub, fontSize = 14.sp, textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = ClockLogic.formatTimeInZone(city.tzId, nowMs),
                    color = NightGold, fontSize = 22.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                )
            }
        }
        Button(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) { Text("＋ 都市を追加") }
        Text(text = "都市を長押しで削除", color = NightSub, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }

    cityToDelete?.let { city ->
        AlertDialog(
            onDismissRequest = { cityToDelete = null },
            title = { Text(city.name + " を削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    cities.remove(city)
                    saveWorldCities(context, cities)
                    cityToDelete = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { cityToDelete = null }) { Text("キャンセル") }
            }
        )
    }

    if (showAddDialog) {
        val candidates = ClockLogic.CITY_CATALOG.filter { c -> cities.none { it.tzId == c.tzId } }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("都市を追加") },
            text = {
                if (candidates.isEmpty()) {
                    Text("追加できる都市がありません")
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        for (c in candidates) {
                            TextButton(
                                onClick = {
                                    cities.add(c)
                                    saveWorldCities(context, cities)
                                    showAddDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(c.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

// ----- 1.1 世界時計の保存 (従来と同じ SharedPreferences "world" / "cities_json") -----
private fun loadWorldCities(context: Context): List<ClockLogic.City> {
    val result = ArrayList<ClockLogic.City>()
    val sp = context.getSharedPreferences("world", Context.MODE_PRIVATE)
    val json = sp.getString("cities_json", null)
        ?: return ClockLogic.DEFAULT_CITIES // 保存キー自体がない初回のみ既定6都市
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val c = ClockLogic.findCityByTz(arr.getString(i)) ?: continue
            if (result.none { it.tzId == c.tzId }) result.add(c)
        }
    } catch (ignored: Exception) {
        // JSON が壊れている場合は既定に戻さず、読めた分 (なければ空) を返す
    }
    return result
}

private fun saveWorldCities(context: Context, cities: List<ClockLogic.City>) {
    try {
        val arr = JSONArray()
        for (c in cities) arr.put(c.tzId)
        context.getSharedPreferences("world", Context.MODE_PRIVATE).edit()
            .putString("cities_json", arr.toString()).apply()
    } catch (ignored: Exception) {
    }
}
