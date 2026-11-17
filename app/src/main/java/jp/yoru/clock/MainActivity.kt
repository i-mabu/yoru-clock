package jp.yoru.clock

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// ===== 1. メイン画面 (時計 / アラーム / タイマー / ストップウォッチ の4タブ, Jetpack Compose) =====
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+: 鳴動通知の表示許可を求める (主経路は高優先度通知のため)
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        // バックアップ復元・アプリ更新直後の初回起動でも登録を正規化する
        AlarmScheduler.rescheduleAll(this)
        setContent {
            YoruClockTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("時計", "アラーム", "タイマー", "ストップウォッチ")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightBg)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                0 -> ClockTab()
                1 -> AlarmTab()
                2 -> TimerTab()
                else -> StopwatchTab()
            }
        }
        // 自前タブ列 (BottomNavigation相当、従来View版と同じ見た目)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NightPanel)
        ) {
            for (i in labels.indices) {
                Button(
                    onClick = { tab = i },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (i == tab) NightTabOn else Color.Transparent,
                        contentColor = if (i == tab) NightGold else NightSub
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(labels[i], fontSize = 14.sp) }
            }
        }
    }
}
