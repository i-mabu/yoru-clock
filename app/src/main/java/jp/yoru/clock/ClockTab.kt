package jp.yoru.clock

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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

// ========================================
// 🕐 時計タブ (デジタル + アナログ + 世界時計)
// ========================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClockTab() {
    val context = LocalContext.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 1 秒の境界に合わせて更新する
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            nowMillis = now
            delay(1000 - (now % 1000))
        }
    }

    val cities = remember {
        mutableStateListOf<ClockLogic.City>().apply { addAll(loadWorldCities(context)) }
    }
    var cityPendingDelete by remember { mutableStateOf<ClockLogic.City?>(null) }
    var addDialogVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = ClockLogic.formatTimeSecInZone(TimeZone.getDefault().id, nowMillis),
            color = NightText, fontSize = 56.sp,
            fontFamily = MonoFamily, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = ClockLogic.formatDateJa(Calendar.getInstance().apply { timeInMillis = nowMillis }),
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
                    .combinedClickable(onClick = {}, onLongClick = { cityPendingDelete = city })
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = city.name, color = NightText, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Text(
                    text = ClockLogic.formatOffsetFromJapan(city.tzId, nowMillis),
                    color = NightSub, fontSize = 14.sp, textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = ClockLogic.formatTimeInZone(city.tzId, nowMillis),
                    color = NightGold, fontSize = 22.sp,
                    fontFamily = MonoFamily, fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                )
            }
        }
        Button(
            onClick = { addDialogVisible = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) { Text("＋ 都市を追加") }
        Text(text = "都市を長押しで削除", color = NightSub, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }

    cityPendingDelete?.let { city ->
        AlertDialog(
            onDismissRequest = { cityPendingDelete = null },
            title = { Text(city.name + " を削除しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        cities.remove(city)
                        saveWorldCities(context, cities)
                        cityPendingDelete = null
                    }
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { cityPendingDelete = null }) { Text("キャンセル") }
            }
        )
    }

    if (addDialogVisible) {
        val candidates = ClockLogic.CITY_CATALOG.filter { catalogCity ->
            cities.none { it.tzId == catalogCity.tzId }
        }
        AlertDialog(
            onDismissRequest = { addDialogVisible = false },
            title = { Text("都市を追加") },
            text = {
                if (candidates.isEmpty()) {
                    Text("追加できる都市がありません")
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        for (candidate in candidates) {
                            TextButton(
                                onClick = {
                                    cities.add(candidate)
                                    saveWorldCities(context, cities)
                                    addDialogVisible = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(candidate.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { addDialogVisible = false }) { Text("キャンセル") }
            }
        )
    }
}

// ----------------------------------------
// 🌍 世界時計の保存 (SharedPreferences "world" / "cities_json": tzId の JSON 配列)
// ----------------------------------------
private const val WORLD_PREFS = "world"
private const val WORLD_KEY = "cities_json"

private fun loadWorldCities(context: Context): List<ClockLogic.City> {
    val prefs = context.getSharedPreferences(WORLD_PREFS, Context.MODE_PRIVATE)
    val json = prefs.getString(WORLD_KEY, null)
        ?: return ClockLogic.DEFAULT_CITIES // 保存キー自体が無い初回だけ既定の 6 都市
    val loaded = ArrayList<ClockLogic.City>()
    try {
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val city = ClockLogic.findCityByTz(array.getString(i)) ?: continue
            if (loaded.none { it.tzId == city.tzId }) loaded.add(city)
        }
    } catch (ignored: Exception) {
        // JSON が壊れている場合は既定へ戻さず、読めた分 (なければ空) を返す
    }
    return loaded
}

private fun saveWorldCities(context: Context, cities: List<ClockLogic.City>) {
    try {
        val array = JSONArray()
        for (city in cities) array.put(city.tzId)
        context.getSharedPreferences(WORLD_PREFS, Context.MODE_PRIVATE)
            .edit().putString(WORLD_KEY, array.toString()).apply()
    } catch (ignored: Exception) {
    }
}
