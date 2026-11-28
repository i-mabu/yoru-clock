package jp.yoru.clock

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

// ========================================
// 🧮 純ロジック (Android 非依存・単体テスト対象)
// ========================================
// アラームの次回発火計算、世界時計の時差、各種フォーマットを担う。
// 曜日の規約: repeat 配列の index は 0=月, 1=火, 2=水, 3=木, 4=金, 5=土, 6=日。
object ClockLogic {

    /** Calendar.DAY_OF_WEEK (1=日 .. 7=土) に対応する日本語の曜日名 */
    val WEEKDAY_JA = arrayOf("日", "月", "火", "水", "木", "金", "土")

    /** repeat 配列の index (0=月 .. 6=日) に対応する表示名 */
    val REPEAT_DAY_NAMES = arrayOf("月", "火", "水", "木", "金", "土", "日")

    // ----------------------------------------
    // 🌍 世界時計の都市
    // ----------------------------------------
    class City(val name: String, val tzId: String)

    /** 初期表示の都市 (順序固定・変更不可) */
    val DEFAULT_CITIES: List<City> = Collections.unmodifiableList(
        listOf(
            City("東京", "Asia/Tokyo"),
            City("ソウル", "Asia/Seoul"),
            City("ロンドン", "Europe/London"),
            City("ニューヨーク", "America/New_York"),
            City("ロサンゼルス", "America/Los_Angeles"),
            City("シドニー", "Australia/Sydney")
        )
    )

    /** 追加候補の全カタログ (DEFAULT_CITIES を含む・変更不可) */
    val CITY_CATALOG: List<City> = Collections.unmodifiableList(
        listOf(
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
    )

    /** カタログから tzId で都市を探す (無ければ null) */
    fun findCityByTz(tzId: String): City? {
        for (city in CITY_CATALOG) {
            if (city.tzId == tzId) return city
        }
        return null
    }

    /** 保存済みリストの検証: カタログに存在する都市だけを残す */
    fun sanitizeCities(input: List<City>): List<City> {
        val kept = ArrayList<City>()
        for (city in input) {
            if (findCityByTz(city.tzId) != null) kept.add(city)
        }
        return kept
    }

    // ----------------------------------------
    // 📅 曜日の変換と繰り返しの要約
    // ----------------------------------------
    /** Calendar.DAY_OF_WEEK (日=1 .. 土=7) → repeat index (月=0 .. 日=6) */
    fun repeatIndexForCalendarDow(calendarDow: Int): Int {
        // 月(2)->0, 火(3)->1, 水(4)->2, 木(5)->3, 金(6)->4, 土(7)->5, 日(1)->6
        return (calendarDow + 5) % 7
    }

    /** 繰り返しが 1 日でも有効かどうか (null は false) */
    fun hasRepeat(repeat: BooleanArray?): Boolean {
        if (repeat == null) return false
        for (day in repeat) if (day) return true
        return false
    }

    /** repeat[idx] の安全な読み取り: null・範囲外・短い配列は false */
    fun repeatOn(repeat: BooleanArray?, idx: Int): Boolean {
        return repeat != null && idx in repeat.indices && repeat[idx]
    }

    /** 繰り返しの要約: なし="1回のみ"、全曜日="毎日"、他は "月・水・金" 形式 */
    fun repeatSummary(repeat: BooleanArray?): String {
        if (!hasRepeat(repeat)) return "1回のみ"
        var everyDay = true
        for (i in 0 until 7) {
            if (!repeatOn(repeat, i)) {
                everyDay = false
                break
            }
        }
        if (everyDay) return "毎日"
        val parts = ArrayList<String>()
        for (i in 0 until 7) {
            if (repeatOn(repeat, i)) parts.add(REPEAT_DAY_NAMES[i])
        }
        return parts.joinToString("・")
    }

    // ----------------------------------------
    // ⏰ アラームの次回発火計算
    // ----------------------------------------
    /**
     * 次回発火時刻 (epoch millis)。
     * - 繰り返しなし: 今日の hour:minute が未来なら今日、過ぎていれば明日。
     * - 繰り返しあり: 今日から 7 日先まで走査し、対象曜日で未来になる最初の時刻。
     * now と完全一致する候補は「過ぎた」ものとして扱い、次の候補へ回す。
     */
    fun nextTriggerMillis(nowMillis: Long, hour: Int, minute: Int, repeat: BooleanArray?): Long {
        val base = Calendar.getInstance()
        base.timeInMillis = nowMillis
        val repeating = hasRepeat(repeat)
        for (offset in 0..7) {
            val candidate = base.clone() as Calendar
            candidate.add(Calendar.DAY_OF_YEAR, offset)
            candidate.set(Calendar.HOUR_OF_DAY, hour)
            candidate.set(Calendar.MINUTE, minute)
            candidate.set(Calendar.SECOND, 0)
            candidate.set(Calendar.MILLISECOND, 0)
            if (candidate.timeInMillis <= nowMillis) continue
            if (!repeating) return candidate.timeInMillis
            val idx = repeatIndexForCalendarDow(candidate.get(Calendar.DAY_OF_WEEK))
            if (repeatOn(repeat, idx)) return candidate.timeInMillis
        }
        // ここへは到達しない想定だが、安全側として翌日の同時刻を返す
        val fallback = base.clone() as Calendar
        fallback.add(Calendar.DAY_OF_YEAR, 1)
        fallback.set(Calendar.HOUR_OF_DAY, hour)
        fallback.set(Calendar.MINUTE, minute)
        fallback.set(Calendar.SECOND, 0)
        fallback.set(Calendar.MILLISECOND, 0)
        return fallback.timeInMillis
    }

    /** 次回発火の説明文: "今日 07:30" / "明日 07:30" / "11月24日（火） 07:00" */
    fun describeTrigger(triggerMillis: Long, nowMillis: Long): String {
        val trigger = Calendar.getInstance()
        trigger.timeInMillis = triggerMillis
        val now = Calendar.getInstance()
        now.timeInMillis = nowMillis
        val hm = formatHourMinute(trigger.get(Calendar.HOUR_OF_DAY), trigger.get(Calendar.MINUTE))
        fun sameDay(a: Calendar, b: Calendar): Boolean {
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        if (sameDay(trigger, now)) return "今日 " + hm
        val tomorrow = now.clone() as Calendar
        tomorrow.add(Calendar.DAY_OF_YEAR, 1)
        if (sameDay(trigger, tomorrow)) return "明日 " + hm
        val month = trigger.get(Calendar.MONTH) + 1
        val day = trigger.get(Calendar.DAY_OF_MONTH)
        val weekday = WEEKDAY_JA[trigger.get(Calendar.DAY_OF_WEEK) - 1]
        return month.toString() + "月" + day + "日（" + weekday + "） " + hm
    }

    // ----------------------------------------
    // 🕐 日付・時刻のフォーマット
    // ----------------------------------------
    /** "2026年11月17日（火）" 形式 */
    fun formatDateJa(cal: Calendar): String {
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val weekday = WEEKDAY_JA[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return year.toString() + "年" + month + "月" + day + "日（" + weekday + "）"
    }

    fun formatDateJa(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return formatDateJa(cal)
    }

    /** 指定タイムゾーンでの "HH:mm" */
    fun formatTimeInZone(tzId: String, millis: Long): String {
        val format = SimpleDateFormat("HH:mm", Locale.JAPAN)
        format.timeZone = TimeZone.getTimeZone(tzId)
        return format.format(millis)
    }

    /** 指定タイムゾーンでの "HH:mm:ss" */
    fun formatTimeSecInZone(tzId: String, millis: Long): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
        format.timeZone = TimeZone.getTimeZone(tzId)
        return format.format(millis)
    }

    /** "07:30" 形式 */
    fun formatHourMinute(hour: Int, minute: Int): String {
        return "%02d:%02d".format(Locale.US, hour, minute)
    }

    // ----------------------------------------
    // 🌍 世界時計の時差
    // ----------------------------------------
    /** 対象ゾーンと日本 (Asia/Tokyo) の時差 (時間・小数あり)。例: ロンドン冬 = -9 */
    fun offsetHoursFromJapan(tzId: String, millis: Long): Double {
        val targetOffset = TimeZone.getTimeZone(tzId).getOffset(millis)
        val japanOffset = TimeZone.getTimeZone("Asia/Tokyo").getOffset(millis)
        return (targetOffset - japanOffset) / 3600000.0
    }

    /** 時差の表示: -9 → "-9時間"、0 → "±0時間"、+5.5 → "+5.5時間" */
    fun formatOffset(diffHours: Double): String {
        if (diffHours == 0.0) return "±0時間"
        val sign = if (diffHours > 0) "+" else "-"
        val magnitude = abs(diffHours)
        val number = if (magnitude == floor(magnitude)) magnitude.toLong().toString() else magnitude.toString()
        return sign + number + "時間"
    }

    fun formatOffsetFromJapan(tzId: String, millis: Long): String {
        return formatOffset(offsetHoursFromJapan(tzId, millis))
    }

    // ----------------------------------------
    // ⏱️ ストップウォッチ
    // ----------------------------------------
    /** mm:ss.cc 形式 (分は 60 を超えても繰り上げない。3600000ms → "60:00.00") */
    fun formatStopwatch(elapsedMs: Long): String {
        val elapsed = maxOf(0L, elapsedMs)
        val totalSeconds = elapsed / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val centiseconds = (elapsed % 1000) / 10
        return "%02d:%02d.%02d".format(Locale.US, minutes, seconds, centiseconds)
    }

    /** ラップ表示向け: 1 時間以上は h:mm:ss.cc、それ未満は formatStopwatch と同じ */
    fun formatStopwatchLong(elapsedMs: Long): String {
        if (elapsedMs < 3600000L) return formatStopwatch(elapsedMs)
        val hours = elapsedMs / 3600000L
        val rest = elapsedMs % 3600000L
        val totalSeconds = rest / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val centiseconds = (rest % 1000) / 10
        return "%d:%02d:%02d.%02d".format(Locale.US, hours, minutes, seconds, centiseconds)
    }

    // ----------------------------------------
    // ⏲️ タイマー
    // ----------------------------------------
    /** 残り時間 (負にはならない) */
    fun timerRemainingMillis(endMillis: Long, nowMillis: Long): Long {
        return maxOf(0L, endMillis - nowMillis)
    }

    /**
     * 残り時間の表示 (切り上げ): 残りが 0 より大きい間は 00:00 にならない。
     * 1 時間未満は "MM:SS"、以上は "H:MM:SS"。
     */
    fun formatDuration(ms: Long): String {
        val value = maxOf(0L, ms)
        var totalSeconds = (value + 999) / 1000
        if (value == 0L) totalSeconds = 0
        return formatHms(totalSeconds)
    }

    /** 秒ピッタリ表示用 (切り上げなし) */
    fun formatDurationFloor(ms: Long): String {
        val value = maxOf(0L, ms)
        return formatHms(value / 1000)
    }

    private fun formatHms(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        else "%02d:%02d".format(Locale.US, minutes, seconds)
    }

    /** 時/分/秒の入力値 → 合計ミリ秒 */
    fun timerTotalMillis(hours: Int, minutes: Int, seconds: Int): Long {
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L
    }
}
