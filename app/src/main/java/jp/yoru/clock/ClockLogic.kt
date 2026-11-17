package jp.yoru.clock

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

// ===== 1. 純ロジック (Android非依存。単体テスト対象) =====
// アラーム次回発火計算 / 世界時計の時差 / ストップウォッチ・タイマーの整形を担う。
// 曜日配列の規約: index 0=月, 1=火, 2=水, 3=木, 4=金, 5=土, 6=日
object ClockLogic {

    val WEEKDAY_JA = arrayOf("日", "月", "火", "水", "木", "金", "土")

    // repeat 配列 index (0=月..6=日) に対応する表示名
    val REPEAT_DAY_NAMES = arrayOf("月", "火", "水", "木", "金", "土", "日")

    // ----- 1.1 世界時計プリセット -----
    class City(val name: String, val tzId: String)

    /** 既定で表示する都市 (順序固定) */
    val DEFAULT_CITIES: List<City> = listOf(
        City("東京", "Asia/Tokyo"),
        City("ソウル", "Asia/Seoul"),
        City("ロンドン", "Europe/London"),
        City("ニューヨーク", "America/New_York"),
        City("ロサンゼルス", "America/Los_Angeles"),
        City("シドニー", "Australia/Sydney")
    )

    /** 追加候補のカタログ (DEFAULT_CITIES を含む全候補) */
    val CITY_CATALOG: List<City> = listOf(
        City("東京", "Asia/Tokyo"),
        City("ソウル", "Asia/Seoul"),
        City("北京", "Asia/Shanghai"),
        City("香港", "Asia/Hong_Kong"),
        City("シンガポール", "Asia/Singapore"),
        City("バンコク", "Asia/Bangkok"),
        City("ドバイ", "Asia/Dubai"),
        City("モスクワ", "Europe/Moscow"),
        City("パリ", "Europe/Paris"),
        City("ベルリン", "Europe/Berlin"),
        City("ロンドン", "Europe/London"),
        City("ニューヨーク", "America/New_York"),
        City("シカゴ", "America/Chicago"),
        City("ロサンゼルス", "America/Los_Angeles"),
        City("バンクーバー", "America/Vancouver"),
        City("ホノルル", "Pacific/Honolulu"),
        City("シドニー", "Australia/Sydney"),
        City("オークランド", "Pacific/Auckland")
    )

    fun findCityByTz(tzId: String): City? {
        for (c in CITY_CATALOG) if (c.tzId == tzId) return c
        return null
    }

    // ----- 1.2 曜日変換 -----
    /** Calendar.DAY_OF_WEEK (SUNDAY=1..SATURDAY=7) → repeat index (0=月..6=日) */
    fun repeatIndexForCalendarDow(calendarDow: Int): Int {
        // 月(2)->0, 火(3)->1, 水(4)->2, 木(5)->3, 金(6)->4, 土(7)->5, 日(1)->6
        return (calendarDow + 5) % 7
    }

    fun hasRepeat(repeat: BooleanArray?): Boolean {
        if (repeat == null) return false
        for (b in repeat) if (b) return true
        return false
    }

    /** repeat[idx] を安全に読む: 配列が短い・null の場合、範囲外は false 扱い */
    fun repeatOn(repeat: BooleanArray?, idx: Int): Boolean {
        return repeat != null && idx >= 0 && idx < repeat.size && repeat[idx]
    }

    /** 繰り返しの要約表示。なし="1回のみ"、全曜日="毎日"、それ以外は "月・水・金" 形式 */
    fun repeatSummary(repeat: BooleanArray?): String {
        if (!hasRepeat(repeat)) return "1回のみ"
        var all = true
        for (i in 0 until 7) if (!repeatOn(repeat, i)) { all = false; break }
        if (all) return "毎日"
        val sb = StringBuilder()
        for (i in 0 until 7) {
            if (repeatOn(repeat, i)) {
                if (sb.length > 0) sb.append("・")
                sb.append(REPEAT_DAY_NAMES[i])
            }
        }
        return sb.toString()
    }

    // ----- 1.3 アラーム次回発火計算 -----
    /**
     * 次回発火時刻 (epoch millis) を返す。
     * - 繰り返しなし: 今日の hour:minute が未来なら今日、過ぎていれば明日。
     * - 繰り返しあり: 今日から最大7日先まで走査し、対象曜日で未来になる最初の時刻。
     * 候補が now と完全一致する場合は「過ぎた」扱い (翌候補へ回す)。
     */
    fun nextTriggerMillis(nowMillis: Long, hour: Int, minute: Int, repeat: BooleanArray?): Long {
        val base = Calendar.getInstance()
        base.timeInMillis = nowMillis
        val repeating = hasRepeat(repeat)
        for (offset in 0..7) {
            val cand = base.clone() as Calendar
            cand.add(Calendar.DAY_OF_YEAR, offset)
            cand.set(Calendar.HOUR_OF_DAY, hour)
            cand.set(Calendar.MINUTE, minute)
            cand.set(Calendar.SECOND, 0)
            cand.set(Calendar.MILLISECOND, 0)
            if (cand.timeInMillis <= nowMillis) continue
            if (!repeating) return cand.timeInMillis
            val idx = repeatIndexForCalendarDow(cand.get(Calendar.DAY_OF_WEEK))
            if (repeatOn(repeat, idx)) return cand.timeInMillis
        }
        // 到達しないはずだが、安全側として翌日同時刻を返す
        val fallback = base.clone() as Calendar
        fallback.add(Calendar.DAY_OF_YEAR, 1)
        fallback.set(Calendar.HOUR_OF_DAY, hour)
        fallback.set(Calendar.MINUTE, minute)
        fallback.set(Calendar.SECOND, 0)
        fallback.set(Calendar.MILLISECOND, 0)
        return fallback.timeInMillis
    }

    // ----- 1.4 日付・時刻フォーマット -----
    /** "2026年11月17日（火）" 形式 */
    fun formatDateJa(cal: Calendar): String {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val w = WEEKDAY_JA[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return y.toString() + "年" + m + "月" + d + "日（" + w + "）"
    }

    fun formatDateJa(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return formatDateJa(cal)
    }

    /** 指定タイムゾーンの "HH:mm" */
    fun formatTimeInZone(tzId: String, millis: Long): String {
        val f = SimpleDateFormat("HH:mm", Locale.JAPAN)
        f.timeZone = TimeZone.getTimeZone(tzId)
        return f.format(millis)
    }

    /** 指定タイムゾーンの "HH:mm:ss" */
    fun formatTimeSecInZone(tzId: String, millis: Long): String {
        val f = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
        f.timeZone = TimeZone.getTimeZone(tzId)
        return f.format(millis)
    }

    // ----- 1.5 世界時計の時差 -----
    /** 対象ゾーンと日本 (Asia/Tokyo) の時差 (時間単位、小数あり)。例: ロンドン冬=-9 */
    fun offsetHoursFromJapan(tzId: String, millis: Long): Double {
        val target = TimeZone.getTimeZone(tzId).getOffset(millis)
        val japan = TimeZone.getTimeZone("Asia/Tokyo").getOffset(millis)
        return (target - japan) / 3600000.0
    }

    /** 時差の表示。例: -9 → "-9時間"、0 → "±0時間"、+2 → "+2時間"、+5.5 → "+5.5時間" */
    fun formatOffset(diffHours: Double): String {
        if (diffHours == 0.0) return "±0時間"
        val sign = if (diffHours > 0) "+" else "-"
        val abs = abs(diffHours)
        val num: String
        if (abs == floor(abs)) num = abs.toLong().toString()
        else num = abs.toString()
        return sign + num + "時間"
    }

    fun formatOffsetFromJapan(tzId: String, millis: Long): String {
        return formatOffset(offsetHoursFromJapan(tzId, millis))
    }

    // ----- 1.6 ストップウォッチ -----
    /** mm:ss.cc 形式 (分は60を超えても繰り上げない。例: 3600000ms → "60:00.00") */
    fun formatStopwatch(elapsedMs: Long): String {
        var elapsed = elapsedMs
        if (elapsed < 0) elapsed = 0
        val totalSec = elapsed / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val cc = (elapsed % 1000) / 10
        return "%02d:%02d.%02d".format(Locale.US, min, sec, cc)
    }

    /** ラップ一覧など詳細表示向け: 1時間以上は h:mm:ss.cc */
    fun formatStopwatchLong(elapsedMs: Long): String {
        if (elapsedMs < 3600000L) return formatStopwatch(elapsedMs)
        val h = elapsedMs / 3600000L
        val rest = elapsedMs % 3600000L
        val totalSec = rest / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val cc = (rest % 1000) / 10
        return "%d:%02d:%02d.%02d".format(Locale.US, h, min, sec, cc)
    }

    // ----- 1.7 タイマー -----
    /** 残り時間 (0未満にはならない) */
    fun timerRemainingMillis(endMillis: Long, nowMillis: Long): Long {
        return maxOf(0L, endMillis - nowMillis)
    }

    /** 残り時間表示: 1時間未満は "MM:SS"、以上は "H:MM:SS" */
    fun formatDuration(ms: Long): String {
        var value = ms
        if (value < 0) value = 0
        var totalSec = (value + 999) / 1000 // タイマー表示は切り上げ: 残りが 0 より大きい間は 00:00 にならない (ただし 0 は 0 のまま)
        if (value == 0L) totalSec = 0
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        if (h > 0) return "%d:%02d:%02d".format(Locale.US, h, m, s)
        return "%02d:%02d".format(Locale.US, m, s)
    }

    /** 秒ピッタリ表示用 (切り上げなし) */
    fun formatDurationFloor(ms: Long): String {
        var value = ms
        if (value < 0) value = 0
        val totalSec = value / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        if (h > 0) return "%d:%02d:%02d".format(Locale.US, h, m, s)
        return "%02d:%02d".format(Locale.US, m, s)
    }

    /** NumberPicker の値 → 合計ミリ秒 */
    fun timerTotalMillis(hours: Int, minutes: Int, seconds: Int): Long {
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L
    }

    /** "07:30" 形式 */
    fun formatHourMinute(hour: Int, minute: Int): String {
        return "%02d:%02d".format(Locale.US, hour, minute)
    }

    /** 次回発火の説明文。例: "明日 07:30" / "今日 21:00" / "11月24日（火） 07:00" */
    fun describeTrigger(triggerMillis: Long, nowMillis: Long): String {
        val t = Calendar.getInstance()
        t.timeInMillis = triggerMillis
        val n = Calendar.getInstance()
        n.timeInMillis = nowMillis
        val hm = formatHourMinute(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE))
        val sameDay = t.get(Calendar.YEAR) == n.get(Calendar.YEAR)
            && t.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR)
        val tomorrow = n.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        val isTomorrow = t.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR)
            && t.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return "今日 " + hm
        if (isTomorrow) return "明日 " + hm
        val m = t.get(Calendar.MONTH) + 1
        val d = t.get(Calendar.DAY_OF_MONTH)
        val w = WEEKDAY_JA[t.get(Calendar.DAY_OF_WEEK) - 1]
        return m.toString() + "月" + d + "日（" + w + "） " + hm
    }

    /** 保存済み都市リストの検証用: カタログにある tzId のみ残す */
    fun sanitizeCities(input: List<City>): List<City> {
        val out = ArrayList<City>()
        for (c in input) if (findCityByTz(c.tzId) != null) out.add(c)
        return out
    }
}
