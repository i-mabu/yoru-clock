import jp.yoru.clock.AlarmItem
import jp.yoru.clock.ClockLogic
import java.util.Calendar
import java.util.TimeZone

// よる時計の純ロジック単体テスト (Android 非依存クラスのみを対象にする)
// 実行: tests/run-tests.sh
//   パス 1: 通常テスト (user.timezone=Asia/Tokyo)
//   パス 2: 引数 "dst" で DST 専用テスト (user.timezone=America/New_York)
// 基準日 2026-11-17 は火曜日。
object TestMain {
    private var passedCount = 0

    // ----------------------------------------
    // 小さなテストハーネス
    // ----------------------------------------
    private fun verify(condition: Boolean, name: String) {
        if (!condition) throw AssertionError("FAILED: " + name)
        passedCount++
        println("ok: " + name)
    }

    private fun verifyText(actual: String, expected: String, name: String) {
        verify(expected == actual, name + " (expected=" + expected + ", actual=" + actual + ")")
    }

    /** ローカルタイムゾーンで year-month1-day hour:minute (秒・ミリ秒は 0) の epoch millis */
    private fun at(year: Int, month1: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month1 - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun describe(millis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        return cal.get(Calendar.YEAR).toString() + "-" + (cal.get(Calendar.MONTH) + 1) + "-" +
            cal.get(Calendar.DAY_OF_MONTH) + " " +
            ClockLogic.formatHourMinute(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)) +
            " dow=" + cal.get(Calendar.DAY_OF_WEEK)
    }

    private fun repeatOf(vararg indexes: Int): BooleanArray {
        val repeat = BooleanArray(7)
        for (index in indexes) repeat[index] = true
        return repeat
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() && args[0] == "dst") {
            testDstGapResolution()
            println("ALL DST TESTS PASSED: " + passedCount + " checks")
            return
        }
        testNextTrigger()
        testWeekdayConversion()
        testOffsets()
        testFormats()
        testCities()
        testRequestCodes()
        testShortAndNullRepeat()
        testAlarmItemBehavior()
        println("ALL TESTS PASSED: " + passedCount + " checks")
    }

    // ----------------------------------------
    // 次回発火計算
    // ----------------------------------------
    private fun testNextTrigger() {
        val tuesday0800 = at(2026, 11, 17, 8, 0)
        val probe = Calendar.getInstance()
        probe.timeInMillis = tuesday0800
        verify(probe.get(Calendar.DAY_OF_WEEK) == Calendar.TUESDAY, "fixture: 2026-11-17 is a Tuesday")

        // 単発: 今日の未来時刻なら今日
        val future = ClockLogic.nextTriggerMillis(tuesday0800, 9, 0, BooleanArray(7))
        verify(future == at(2026, 11, 17, 9, 0), "one-shot later today -> " + describe(future))

        // 単発: 過ぎていれば明日
        val past = ClockLogic.nextTriggerMillis(tuesday0800, 7, 0, BooleanArray(7))
        verify(past == at(2026, 11, 18, 7, 0), "one-shot already past -> tomorrow " + describe(past))

        // 単発: now と完全一致は「過ぎた」扱いで明日
        val exact = ClockLogic.nextTriggerMillis(tuesday0800, 8, 0, BooleanArray(7))
        verify(exact == at(2026, 11, 18, 8, 0), "one-shot exactly now -> tomorrow " + describe(exact))

        // 単発: repeat が null でも単発として扱う
        val nullRepeat = ClockLogic.nextTriggerMillis(tuesday0800, 9, 0, null)
        verify(nullRepeat == at(2026, 11, 17, 9, 0), "null repeat behaves as one-shot -> " + describe(nullRepeat))

        // 繰り返し (火曜のみ): 今日の未来なら今日
        val tueFuture = ClockLogic.nextTriggerMillis(tuesday0800, 9, 30, repeatOf(1))
        verify(tueFuture == at(2026, 11, 17, 9, 30), "repeat Tue later today -> today " + describe(tueFuture))

        // 繰り返し (火曜のみ): 今日分が過ぎていれば来週の火曜
        val tuePast = ClockLogic.nextTriggerMillis(tuesday0800, 7, 0, repeatOf(1))
        verify(tuePast == at(2026, 11, 24, 7, 0), "repeat Tue past -> next Tue " + describe(tuePast))

        // 繰り返し (水曜のみ): 明日
        val wed = ClockLogic.nextTriggerMillis(tuesday0800, 7, 0, repeatOf(2))
        verify(wed == at(2026, 11, 18, 7, 0), "repeat Wed -> tomorrow " + describe(wed))

        // 繰り返し (月曜のみ): 11/23
        val mon = ClockLogic.nextTriggerMillis(tuesday0800, 7, 0, repeatOf(0))
        verify(mon == at(2026, 11, 23, 7, 0), "repeat Mon -> 11/23 " + describe(mon))

        // 繰り返し (毎日): 過ぎていれば明日
        val daily = ClockLogic.nextTriggerMillis(tuesday0800, 7, 0, repeatOf(0, 1, 2, 3, 4, 5, 6))
        verify(daily == at(2026, 11, 18, 7, 0), "repeat daily past -> tomorrow " + describe(daily))

        // 繰り返し (日曜のみ): 11/22 の 23:59
        val sun = ClockLogic.nextTriggerMillis(tuesday0800, 23, 59, repeatOf(6))
        verify(sun == at(2026, 11, 22, 23, 59), "repeat Sun -> 11/22 " + describe(sun))

        // AlarmItem 経由でも同じ計算になる
        val item = AlarmItem(1, 7, 0, "起床", true, repeatOf(1))
        verify(item.nextTriggerMillis(tuesday0800) == at(2026, 11, 24, 7, 0), "AlarmItem.nextTriggerMillis matches")
        verify(item.hasRepeat(), "AlarmItem.hasRepeat is true for a repeating alarm")
        verify(!AlarmItem(2, 7, 0, "", true, BooleanArray(7)).hasRepeat(), "AlarmItem.hasRepeat is false for one-shot")

        // 繰り返しの要約
        verifyText(ClockLogic.repeatSummary(BooleanArray(7)), "1回のみ", "repeatSummary: none")
        verifyText(ClockLogic.repeatSummary(repeatOf(0, 1, 2, 3, 4, 5, 6)), "毎日", "repeatSummary: daily")
        verifyText(ClockLogic.repeatSummary(repeatOf(0, 2, 4)), "月・水・金", "repeatSummary: Mon/Wed/Fri")
        verifyText(ClockLogic.repeatSummary(repeatOf(5, 6)), "土・日", "repeatSummary: weekend")

        // 次回発火の説明文
        verifyText(ClockLogic.describeTrigger(at(2026, 11, 17, 9, 0), tuesday0800), "今日 09:00", "describeTrigger: today")
        verifyText(ClockLogic.describeTrigger(at(2026, 11, 18, 7, 0), tuesday0800), "明日 07:00", "describeTrigger: tomorrow")
        verifyText(ClockLogic.describeTrigger(at(2026, 11, 24, 7, 0), tuesday0800), "11月24日（火） 07:00", "describeTrigger: next week")
    }

    // ----------------------------------------
    // 曜日変換
    // ----------------------------------------
    private fun testWeekdayConversion() {
        // Calendar: 日=1, 月=2, 火=3, 水=4, 木=5, 金=6, 土=7 → repeat index: 月=0 .. 日=6
        verify(ClockLogic.repeatIndexForCalendarDow(Calendar.MONDAY) == 0, "dow Monday -> 0")
        verify(ClockLogic.repeatIndexForCalendarDow(Calendar.TUESDAY) == 1, "dow Tuesday -> 1")
        verify(ClockLogic.repeatIndexForCalendarDow(Calendar.WEDNESDAY) == 2, "dow Wednesday -> 2")
        verify(ClockLogic.repeatIndexForCalendarDow(Calendar.THURSDAY) == 3, "dow Thursday -> 3")
        verify(ClockLogic.repeatIndexForCalendarDow(Calendar.FRIDAY) == 4, "dow Friday -> 4")
        verify(ClockLogic.repeatIndexForCalendarDow(Calendar.SATURDAY) == 5, "dow Saturday -> 5")
        verify(ClockLogic.repeatIndexForCalendarDow(Calendar.SUNDAY) == 6, "dow Sunday -> 6")
    }

    // ----------------------------------------
    // 世界時計の時差
    // ----------------------------------------
    private fun testOffsets() {
        // 2026-11-17 12:00 (東京) の瞬間を、デフォルト TZ に依存しないよう東京 TZ で作る
        val november = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            set(2026, Calendar.NOVEMBER, 17, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        verify(ClockLogic.offsetHoursFromJapan("Asia/Tokyo", november) == 0.0, "offset: Tokyo is 0")
        verify(ClockLogic.offsetHoursFromJapan("Asia/Seoul", november) == 0.0, "offset: Seoul is 0")
        verify(ClockLogic.offsetHoursFromJapan("Europe/London", november) == -9.0, "offset: London winter is -9")
        verify(ClockLogic.offsetHoursFromJapan("America/New_York", november) == -14.0, "offset: New York winter is -14")
        verify(ClockLogic.offsetHoursFromJapan("Australia/Sydney", november) == 2.0, "offset: Sydney summer is +2")

        verifyText(ClockLogic.formatOffset(-9.0), "-9時間", "formatOffset: -9")
        verifyText(ClockLogic.formatOffset(0.0), "±0時間", "formatOffset: zero")
        verifyText(ClockLogic.formatOffset(2.0), "+2時間", "formatOffset: +2")
        verifyText(ClockLogic.formatOffset(5.5), "+5.5時間", "formatOffset: +5.5")
        verifyText(ClockLogic.formatOffset(-3.5), "-3.5時間", "formatOffset: -3.5")
        verifyText(ClockLogic.formatOffsetFromJapan("Europe/London", november), "-9時間", "formatOffsetFromJapan: London")
        verifyText(ClockLogic.formatOffsetFromJapan("Australia/Sydney", november), "+2時間", "formatOffsetFromJapan: Sydney")

        // 東京 12:00 のとき、ロンドンは 03:00
        verifyText(ClockLogic.formatTimeInZone("Europe/London", november), "03:00", "zone time: London at Tokyo noon")
        verifyText(ClockLogic.formatTimeInZone("Asia/Tokyo", november), "12:00", "zone time: Tokyo noon")
        verifyText(ClockLogic.formatTimeSecInZone("Asia/Tokyo", november), "12:00:00", "zone time with seconds: Tokyo noon")

        // 夏 (2026-07-15) はロンドン BST / ニューヨーク EDT で、冬より 1 時間ずつ日本へ近づく
        val july = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            set(2026, Calendar.JULY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        verify(ClockLogic.offsetHoursFromJapan("Europe/London", july) == -8.0, "offset: London summer is -8")
        verify(ClockLogic.offsetHoursFromJapan("America/New_York", july) == -13.0, "offset: New York summer is -13")
    }

    // ----------------------------------------
    // フォーマット類
    // ----------------------------------------
    private fun testFormats() {
        val tuesday = Calendar.getInstance().apply { timeInMillis = at(2026, 11, 17, 8, 0) }
        verifyText(ClockLogic.formatDateJa(tuesday), "2026年11月17日（火）", "formatDateJa: 2026-11-17 (Tue)")
        val sunday = Calendar.getInstance().apply { timeInMillis = at(2026, 11, 22, 8, 0) }
        verifyText(ClockLogic.formatDateJa(sunday), "2026年11月22日（日）", "formatDateJa: 2026-11-22 (Sun)")
        verifyText(ClockLogic.formatDateJa(at(2026, 11, 17, 8, 0)), "2026年11月17日（火）", "formatDateJa: millis overload")

        // ストップウォッチ (mm:ss.cc、分は繰り上げない)
        verifyText(ClockLogic.formatStopwatch(0), "00:00.00", "stopwatch: zero")
        verifyText(ClockLogic.formatStopwatch(61234), "01:01.23", "stopwatch: 61234")
        verifyText(ClockLogic.formatStopwatch(3599999), "59:59.99", "stopwatch: just under an hour")
        verifyText(ClockLogic.formatStopwatch(3600000), "60:00.00", "stopwatch: exactly an hour keeps 60 minutes")
        verifyText(ClockLogic.formatStopwatch(-500), "00:00.00", "stopwatch: negative clamps to zero")
        verifyText(ClockLogic.formatStopwatchLong(3661230), "1:01:01.23", "stopwatchLong: over an hour")
        verifyText(ClockLogic.formatStopwatchLong(61234), "01:01.23", "stopwatchLong: under an hour")

        // タイマーの残り計算
        verify(ClockLogic.timerRemainingMillis(10000, 4000) == 6000L, "timer remaining: 6000")
        verify(ClockLogic.timerRemainingMillis(4000, 10000) == 0L, "timer remaining: clamps to 0")
        verify(ClockLogic.timerRemainingMillis(5000, 5000) == 0L, "timer remaining: at the end")

        // タイマーの合計
        verify(ClockLogic.timerTotalMillis(0, 5, 0) == 300000L, "timerTotal: 5 minutes")
        verify(ClockLogic.timerTotalMillis(1, 0, 30) == 3630000L, "timerTotal: 1h 0m 30s")
        verify(ClockLogic.timerTotalMillis(0, 0, 0) == 0L, "timerTotal: zero")

        // 時間長フォーマット (floor 版)
        verifyText(ClockLogic.formatDurationFloor(0), "00:00", "durationFloor: zero")
        verifyText(ClockLogic.formatDurationFloor(65000), "01:05", "durationFloor: 65s")
        verifyText(ClockLogic.formatDurationFloor(59999), "00:59", "durationFloor: 59999 truncates")
        verifyText(ClockLogic.formatDurationFloor(3661000), "1:01:01", "durationFloor: 1h1m1s")

        // 時間長フォーマット (切り上げ版): 0 でない限り 00:00 にならない
        verifyText(ClockLogic.formatDuration(0), "00:00", "duration: zero stays zero")
        verifyText(ClockLogic.formatDuration(1), "00:01", "duration: 1ms rounds up to 1s")
        verifyText(ClockLogic.formatDuration(59999), "01:00", "duration: 59999 rounds up to 60s")
        verifyText(ClockLogic.formatDuration(300000), "05:00", "duration: exact 5 minutes")
        verifyText(ClockLogic.formatDuration(3600000), "1:00:00", "duration: exact hour uses H:MM:SS")
        verifyText(ClockLogic.formatDuration(-1000), "00:00", "duration: negative clamps to zero")

        // HH:mm
        verifyText(ClockLogic.formatHourMinute(7, 5), "07:05", "formatHourMinute: zero-padded")
        verifyText(ClockLogic.formatHourMinute(23, 59), "23:59", "formatHourMinute: 23:59")
    }

    // ----------------------------------------
    // 都市カタログ
    // ----------------------------------------
    private fun testCities() {
        verify(ClockLogic.DEFAULT_CITIES.size == 6, "default cities: 6 entries")
        verifyText(ClockLogic.DEFAULT_CITIES[0].name, "東京", "default cities: first is Tokyo")
        verifyText(ClockLogic.DEFAULT_CITIES[5].tzId, "Australia/Sydney", "default cities: last is Sydney")
        verify(ClockLogic.CITY_CATALOG.size == 18, "catalog: 18 entries")

        // 既定リストとカタログは変更不可
        var immutable = false
        try {
            (ClockLogic.DEFAULT_CITIES as MutableList<ClockLogic.City>).set(0, ClockLogic.DEFAULT_CITIES[0])
        } catch (expected: UnsupportedOperationException) {
            immutable = true
        }
        verify(immutable, "default cities: unmodifiable")

        verify(ClockLogic.findCityByTz("Europe/London") != null, "catalog: contains London")
        verify(ClockLogic.findCityByTz("Invalid/Zone") == null, "catalog: unknown zone is null")

        // sanitizeCities: カタログにある都市だけを、順序を保って残す
        val mixed = listOf(
            ClockLogic.City("東京", "Asia/Tokyo"),
            ClockLogic.City("未知", "Invalid/Zone"),
            ClockLogic.City("パリ", "Europe/Paris")
        )
        val sanitized = ClockLogic.sanitizeCities(mixed)
        verify(sanitized.size == 2, "sanitizeCities: drops unknown zones")
        verifyText(sanitized[0].tzId, "Asia/Tokyo", "sanitizeCities: keeps order (1)")
        verifyText(sanitized[1].tzId, "Europe/Paris", "sanitizeCities: keeps order (2)")
        verify(ClockLogic.sanitizeCities(emptyList()).isEmpty(), "sanitizeCities: empty stays empty")
    }

    // ----------------------------------------
    // requestCode の採番
    // ----------------------------------------
    private fun testRequestCodes() {
        verify(AlarmItem.nextRequestCode(ArrayList()) == 1, "nextRequestCode: empty -> 1")
        val first = AlarmItem(); first.rc = 1
        val third = AlarmItem(); third.rc = 3
        verify(AlarmItem.nextRequestCode(listOf(first, third)) == 4, "nextRequestCode: {1,3} -> 4")
        val withNull = ArrayList<AlarmItem?>()
        withNull.add(first); withNull.add(null); withNull.add(third)
        verify(AlarmItem.nextRequestCode(withNull) == 4, "nextRequestCode: null elements are skipped")
        verify(AlarmItem.nextRequestCode(null) == 1, "nextRequestCode: null list -> 1")
    }

    // ----------------------------------------
    // 短い repeat 配列・null の安全性
    // ----------------------------------------
    private fun testShortAndNullRepeat() {
        verify(!ClockLogic.hasRepeat(null), "hasRepeat: null is false")
        verify(!ClockLogic.repeatOn(null, 0), "repeatOn: null is false")
        verify(!ClockLogic.repeatOn(repeatOf(1), -1), "repeatOn: negative index is false")
        verify(!ClockLogic.repeatOn(repeatOf(1), 7), "repeatOn: index past the array is false")
        verify(ClockLogic.repeatOn(repeatOf(1), 1), "repeatOn: set day is true")

        val tuesday0800 = at(2026, 11, 17, 8, 0)
        // 長さ 1 (全て false) の短い配列: 例外なく単発相当
        val shortEmpty = ClockLogic.nextTriggerMillis(tuesday0800, 9, 0, BooleanArray(1))
        verify(shortEmpty == at(2026, 11, 17, 9, 0), "short repeat (len 1, empty) acts one-shot -> " + describe(shortEmpty))
        // 長さ 2 (火曜のみ): 対象曜日として扱われる
        val shortTuesday = BooleanArray(2)
        shortTuesday[1] = true
        verify(ClockLogic.hasRepeat(shortTuesday), "hasRepeat: short array with a day set")
        val shortResult = ClockLogic.nextTriggerMillis(tuesday0800, 9, 30, shortTuesday)
        verify(shortResult == at(2026, 11, 17, 9, 30), "short repeat (Tue) -> today " + describe(shortResult))
        // 要約も短い配列で例外を投げない
        verifyText(ClockLogic.repeatSummary(BooleanArray(3)), "1回のみ", "repeatSummary: short array is safe")
    }

    // ----------------------------------------
    // AlarmItem の振る舞い
    // ----------------------------------------
    private fun testAlarmItemBehavior() {
        // コンストラクタ: label が null なら空文字、repeat のサイズが 7 でなければ無視する
        val item = AlarmItem(10, 6, 45, null, true, BooleanArray(3))
        verifyText(item.label, "", "AlarmItem: null label becomes empty")
        verify(!item.hasRepeat(), "AlarmItem: wrong-sized repeat is ignored")
        verifyText(item.timeText(), "06:45", "AlarmItem: timeText")

        val repeating = AlarmItem(11, 7, 30, "通勤", true, repeatOf(0, 2, 4))
        verifyText(repeating.repeatText(), "月・水・金", "AlarmItem: repeatText")

        // copy(): rc / triggerMillis を含む全フィールドを引き継ぎ、repeat は独立した配列になる
        repeating.rc = 42
        repeating.triggerMillis = 123456789L
        val copied = repeating.copy()
        verify(copied.id == 11L && copied.hour == 7 && copied.minute == 30, "copy: identity fields")
        verify(copied.rc == 42 && copied.triggerMillis == 123456789L, "copy: rc and triggerMillis")
        verifyText(copied.label, "通勤", "copy: label")
        copied.repeat[1] = true
        verify(!repeating.repeat[1], "copy: repeat array is independent")
    }

    // ----------------------------------------
    // DST パス (America/New_York 前提): 春の前進で存在しない壁時刻
    // ----------------------------------------
    private fun testDstGapResolution() {
        // 2026-03-08 は spring forward: 02:00 EST → 03:00 EDT へ跳び、02:30 は存在しない。
        // java.util.Calendar は存在しない壁時刻を lenient に解決し、02:30 は
        // 03:30 EDT (跳んだ直後の有効時刻) になる (実験で確認済みの挙動)。
        val now = at(2026, 3, 7, 12, 0) // 前日正午 (EST)
        val trigger = ClockLogic.nextTriggerMillis(now, 2, 30, BooleanArray(7))
        val expected = Calendar.getInstance()
        expected.set(2026, Calendar.MARCH, 8, 3, 30, 0)
        expected.set(Calendar.MILLISECOND, 0)
        verify(
            trigger == expected.timeInMillis,
            "DST gap: 02:30 resolves to 03:30 EDT (actual=" + trigger + ")"
        )
        val resolved = Calendar.getInstance()
        resolved.timeInMillis = trigger
        verify(
            resolved.get(Calendar.DAY_OF_MONTH) == 8 &&
                resolved.get(Calendar.HOUR_OF_DAY) == 3 &&
                resolved.get(Calendar.MINUTE) == 30,
            "DST gap: resolved fields are Mar 8 03:30"
        )
        verify(
            TimeZone.getTimeZone("America/New_York").getOffset(trigger) == -4 * 3600 * 1000,
            "DST gap: resolved offset is EDT (-4h)"
        )
    }
}
