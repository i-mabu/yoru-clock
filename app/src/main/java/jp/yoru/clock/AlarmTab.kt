package jp.yoru.clock

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

// ========================================
// ⏰ アラームタブ (一覧・ON/OFF・編集画面への遷移)
// ========================================
@Composable
internal fun AlarmTab() {
    val context = LocalContext.current
    var items by remember { mutableStateOf(AlarmStore.load(context)) }
    // 編集画面から戻るたびに読み直す
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        items = AlarmStore.load(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = "アラーム", color = NightAccent, fontSize = 20.sp)
        if (items.isEmpty()) {
            Text(
                text = "アラームはまだありません", color = NightSub, fontSize = 15.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        for (item in items) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(NightPanel)
                    .clickable {
                        val intent = Intent(context, AlarmEditActivity::class.java)
                        intent.putExtra(AlarmEditActivity.EXTRA_ID, item.id)
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.timeText(),
                        color = if (item.enabled) NightText else NightSub,
                        fontSize = 30.sp, fontFamily = MonoFamily, fontWeight = FontWeight.Bold
                    )
                    Text(text = subTextFor(item), color = NightSub, fontSize = 13.sp)
                }
                Switch(
                    checked = item.enabled,
                    onCheckedChange = { checked -> onToggleAlarm(context, item.id, checked) { items = it } }
                )
            }
        }
        Button(
            onClick = {
                val intent = Intent(context, AlarmEditActivity::class.java)
                intent.putExtra(AlarmEditActivity.EXTRA_ID, -1L)
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) { Text("＋ アラームを追加", fontSize = 17.sp) }
    }
}

/** 一覧の補足文: [ラベル ・ ]繰り返し[ ・ 次回 ...] */
private fun subTextFor(item: AlarmItem): String {
    var text = item.repeatText()
    if (item.label.isNotEmpty()) text = item.label + " ・ " + text
    if (item.enabled) {
        val now = System.currentTimeMillis()
        text += " ・ 次回 " + ClockLogic.describeTrigger(item.nextTriggerMillis(now), now)
    }
    return text
}

private fun onToggleAlarm(
    context: android.content.Context,
    alarmId: Long,
    checked: Boolean,
    onReloaded: (MutableList<AlarmItem>) -> Unit
) {
    val all = AlarmStore.load(context)
    val target = AlarmStore.findById(all, alarmId) ?: run {
        onReloaded(all)
        return
    }
    target.enabled = checked
    AlarmStore.save(context, all)
    if (checked) {
        val trigger = AlarmScheduler.scheduleAlarm(context, target)
        Toast.makeText(
            context,
            "ON: 次回 " + ClockLogic.describeTrigger(trigger, System.currentTimeMillis()),
            Toast.LENGTH_SHORT
        ).show()
    } else {
        AlarmScheduler.cancelAlarm(context, target)
        AlarmScheduler.cancelSnoozeAlarm(context, target.rc)
        Toast.makeText(context, "アラームをOFFにしました", Toast.LENGTH_SHORT).show()
    }
    onReloaded(AlarmStore.load(context))
}
