package jp.yoru.clock

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

// ========================================
// ✏️ アラーム追加・編集画面
// ========================================
class AlarmEditActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editingId = intent.getLongExtra(EXTRA_ID, -1)
        val existing = if (editingId >= 0) {
            AlarmStore.findById(AlarmStore.load(this), editingId)
        } else {
            null
        }
        setContent {
            YoruClockTheme {
                AlarmEditScreen(editingId = editingId, existing = existing, onDone = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_ID = "edit_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditScreen(editingId: Long, existing: AlarmItem?, onDone: () -> Unit) {
    val context = LocalContext.current
    // 新規追加時の初期時刻は現在時刻
    val nowCalendar = remember { Calendar.getInstance() }
    var hour by remember {
        mutableIntStateOf(existing?.hour ?: nowCalendar.get(Calendar.HOUR_OF_DAY))
    }
    var minute by remember {
        mutableIntStateOf(existing?.minute ?: nowCalendar.get(Calendar.MINUTE))
    }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    val checkedDays = remember {
        mutableStateListOf<Boolean>().apply {
            for (i in 0 until 7) add(existing?.repeat?.get(i) ?: false)
        }
    }
    val dayNames = listOf("月", "火", "水", "木", "金", "土", "日")

    fun saveAndFinish() {
        val items = ArrayList(AlarmStore.load(context))
        val item: AlarmItem
        if (editingId >= 0) {
            val found = AlarmStore.findById(items, editingId)
            if (found == null) {
                // 編集対象が消えていた場合は、その ID で作り直す
                item = AlarmItem()
                item.id = editingId
                item.rc = AlarmItem.nextRequestCode(items) // 自分を加える前のリストで採番する
                item.enabled = true
                items.add(item)
            } else {
                item = found // 編集では ON/OFF を変えない
            }
        } else {
            item = AlarmItem()
            item.id = AlarmStore.newId(items)
            item.rc = AlarmItem.nextRequestCode(items)
            item.enabled = true
            items.add(item)
        }
        item.hour = hour
        item.minute = minute
        item.label = label.trim { it <= ' ' } // 前後の空白類 (コード ' ' 以下) を除く
        for (i in 0 until 7) item.repeat[i] = checkedDays[i]
        // 時刻順 (同刻は ID 順) に並べ替えて保存する
        items.sortWith { a, b ->
            val byTime = (a.hour * 60 + a.minute).compareTo(b.hour * 60 + b.minute)
            if (byTime != 0) byTime else a.id.compareTo(b.id)
        }
        AlarmStore.save(context, items)

        val message: String
        if (item.enabled) {
            val trigger = AlarmScheduler.scheduleAlarm(context, item)
            message = if (trigger > 0) {
                "アラームを設定: " + ClockLogic.describeTrigger(trigger, System.currentTimeMillis())
            } else {
                "アラームを保存しました"
            }
        } else {
            // OFF のまま保存されたら、登録とスヌーズを取り消す
            AlarmScheduler.cancelAlarm(context, item)
            AlarmScheduler.cancelSnoozeAlarm(context, item.rc)
            message = "アラームを保存しました（OFF）"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightBg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = if (existing == null) "アラームを追加" else "アラームを編集",
            color = NightText, fontSize = 22.sp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimePartDropdown("時", hour, 0..23, Modifier.weight(1f)) { hour = it }
            Text(":", color = NightText, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 4.dp))
            TimePartDropdown("分", minute, 0..59, Modifier.weight(1f)) { minute = it }
        }
        Text(
            text = "ラベル", color = NightSub, fontSize = 15.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            placeholder = { Text("例: 起床、学校", color = NightHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "繰り返し（なし = 1回のみ）", color = NightSub, fontSize = 15.sp,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Checkbox(
                        checked = checkedDays[i],
                        onCheckedChange = { checkedDays[i] = it }
                    )
                    Text(dayNames[i], color = NightText, fontSize = 13.sp)
                }
            }
        }
        Button(
            onClick = { saveAndFinish() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, bottom = 8.dp)
        ) { Text("保存", fontSize = 18.sp) }
        if (existing != null) {
            Button(
                onClick = {
                    val items = ArrayList(AlarmStore.load(context))
                    val target = AlarmStore.findById(items, editingId)
                    if (target != null) {
                        AlarmScheduler.cancelAlarm(context, target)
                        AlarmScheduler.cancelSnoozeAlarm(context, target.rc)
                        items.remove(target)
                        AlarmStore.save(context, items)
                    }
                    Toast.makeText(context, "アラームを削除しました", Toast.LENGTH_SHORT).show()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("削除") }
        }
        Button(
            onClick = { onDone() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("キャンセル") }
    }
}

// ----------------------------------------
// 🕐 時刻のドロップダウン (24 時間表記・2 桁表示)
// ----------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePartDropdown(
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
        modifier = modifier
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
