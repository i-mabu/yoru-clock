package jp.yoru.clock

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

// ========================================
// 🏠 メイン画面 (時計 / アラーム / タイマー / ストップウォッチの 4 タブ)
// ========================================
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 13+: 鳴動通知 (主経路) の表示許可を求める
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        // バックアップ復元やアプリ更新の直後でも、登録状態を正規化しておく
        AlarmScheduler.rescheduleAll(this)
        setContent {
            YoruClockTheme {
                MainScreen()
            }
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
    }
}

@Composable
private fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabLabels = listOf("時計", "アラーム", "タイマー", "ストップウォッチ")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightBg)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ClockTab()
                1 -> AlarmTab()
                2 -> TimerTab()
                else -> StopwatchTab()
            }
        }
        // 自前のタブ列 (画面下部に固定)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NightPanel)
        ) {
            for (index in tabLabels.indices) {
                val selected = index == selectedTab
                Button(
                    onClick = { selectedTab = index },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) NightTabOn else Color.Transparent,
                        contentColor = if (selected) NightGold else NightSub
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tabLabels[index], fontSize = 14.sp)
                }
            }
        }
    }
}
