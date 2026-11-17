import jp.yoru.clock.AlarmItem;
import jp.yoru.clock.ClockLogic;

import java.util.Calendar;

// よる時計 純ロジックの単体テスト (Android非依存クラスのみ)
// 実行: tests/run-tests.sh
// 基準日: 2026-11-17 (火)
public class TestMain {
    private static int passed = 0;

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("ok: " + name);
    }

    private static void eq(String actual, String expected, String name) {
        check(expected.equals(actual), name + " (expected=" + expected + ", actual=" + actual + ")");
    }

    private static long at(int year, int month1, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month1 - 1, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String ymdhm(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) + "-" + (c.get(Calendar.MONTH) + 1) + "-" + c.get(Calendar.DAY_OF_MONTH)
            + " " + ClockLogic.formatHourMinute(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            + " dow=" + c.get(Calendar.DAY_OF_WEEK);
    }

    private static boolean[] repeat(int... idx) {
        boolean[] r = new boolean[7];
        for (int i : idx) r[i] = true;
        return r;
    }

    public static void main(String[] args) {
        // "dst" 引数時は DST パス専用 (run-tests.sh が -Duser.timezone=America/New_York で実行)
        if (args.length > 0 && "dst".equals(args[0])) {
            testDstSpringForward();
            System.out.println("ALL DST TESTS PASSED: " + passed + " checks");
            return;
        }
        testNextTrigger();
        testOffset();
        testFormats();
        testRequestCodeAndShortRepeat();
        System.out.println("ALL TESTS PASSED: " + passed + " checks");
    }

    // ----- アラーム次回発火 -----
    private static void testNextTrigger() {
        long tue0800 = at(2026, 11, 17, 8, 0); // 2026-11-17 は火曜
        Calendar probe = Calendar.getInstance();
        probe.setTimeInMillis(tue0800);
        check(probe.get(Calendar.DAY_OF_WEEK) == Calendar.TUESDAY, "fixture 2026-11-17 is Tuesday");

        // 単発・未来 (今日 9:00)
        long t1 = ClockLogic.nextTriggerMillis(tue0800, 9, 0, new boolean[7]);
        check(t1 == at(2026, 11, 17, 9, 0), "one-shot future today -> " + ymdhm(t1));

        // 単発・過去 (今日 7:00 は過ぎた → 明日)
        long t2 = ClockLogic.nextTriggerMillis(tue0800, 7, 0, new boolean[7]);
        check(t2 == at(2026, 11, 18, 7, 0), "one-shot past -> tomorrow " + ymdhm(t2));

        // 単発・ちょうど現在時刻 → 明日 (一致は過ぎた扱い)
        long t3 = ClockLogic.nextTriggerMillis(tue0800, 8, 0, new boolean[7]);
        check(t3 == at(2026, 11, 18, 8, 0), "one-shot exactly now -> tomorrow " + ymdhm(t3));

        // 繰り返し: 火曜のみ・未来 → 今日
        long t4 = ClockLogic.nextTriggerMillis(tue0800, 9, 30, repeat(1));
        check(t4 == at(2026, 11, 17, 9, 30), "repeat Tue future -> today " + ymdhm(t4));

        // 繰り返し: 火曜のみ・今日は対象曜日だが過ぎた → 来週火曜 (11/24)
        long t5 = ClockLogic.nextTriggerMillis(tue0800, 7, 0, repeat(1));
        check(t5 == at(2026, 11, 24, 7, 0), "repeat Tue past -> next Tue " + ymdhm(t5));

        // 繰り返し: 水曜のみ → 明日
        long t6 = ClockLogic.nextTriggerMillis(tue0800, 7, 0, repeat(2));
        check(t6 == at(2026, 11, 18, 7, 0), "repeat Wed -> tomorrow " + ymdhm(t6));

        // 繰り返し: 月曜のみ → 11/23 (月)
        long t7 = ClockLogic.nextTriggerMillis(tue0800, 7, 0, repeat(0));
        check(t7 == at(2026, 11, 23, 7, 0), "repeat Mon -> 11/23 " + ymdhm(t7));

        // 繰り返し: 毎日・過去 → 明日
        long t8 = ClockLogic.nextTriggerMillis(tue0800, 7, 0, repeat(0, 1, 2, 3, 4, 5, 6));
        check(t8 == at(2026, 11, 18, 7, 0), "repeat daily past -> tomorrow " + ymdhm(t8));

        // 繰り返し: 日曜のみ → 11/22 (日)
        long t9 = ClockLogic.nextTriggerMillis(tue0800, 23, 59, repeat(6));
        check(t9 == at(2026, 11, 22, 23, 59), "repeat Sun -> 11/22 " + ymdhm(t9));

        // AlarmItem 経由でも同じ結果
        AlarmItem item = new AlarmItem(1, 7, 0, "起床", true, repeat(1));
        check(item.nextTriggerMillis(tue0800) == at(2026, 11, 24, 7, 0), "AlarmItem.nextTriggerMillis Tue past");
        check(item.hasRepeat(), "AlarmItem.hasRepeat true");
        check(!new AlarmItem(2, 7, 0, "", true, new boolean[7]).hasRepeat(), "AlarmItem.hasRepeat false");

        // 繰り返し要約
        eq(ClockLogic.repeatSummary(new boolean[7]), "1回のみ", "repeatSummary none");
        eq(ClockLogic.repeatSummary(repeat(0, 1, 2, 3, 4, 5, 6)), "毎日", "repeatSummary daily");
        eq(ClockLogic.repeatSummary(repeat(0, 2, 4)), "月・水・金", "repeatSummary Mon/Wed/Fri");
        eq(ClockLogic.repeatSummary(repeat(5, 6)), "土・日", "repeatSummary weekend");

        // 次回説明文
        eq(ClockLogic.describeTrigger(at(2026, 11, 17, 9, 0), tue0800), "今日 09:00", "describe today");
        eq(ClockLogic.describeTrigger(at(2026, 11, 18, 7, 0), tue0800), "明日 07:00", "describe tomorrow");
        eq(ClockLogic.describeTrigger(at(2026, 11, 24, 7, 0), tue0800), "11月24日（火） 07:00", "describe next week");
    }

    // ----- 世界時計の時差 (2026年11月 = ロンドンGMT / NY EST / シドニーAEDT) -----
    private static void testOffset() {
        // JVMのデフォルトTZに依存しないよう、東京TZで 2026-11-17 12:00 の瞬間を作る
        Calendar tokyoCal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        tokyoCal.set(2026, Calendar.NOVEMBER, 17, 12, 0, 0);
        tokyoCal.set(Calendar.MILLISECOND, 0);
        long nov = tokyoCal.getTimeInMillis();
        check(ClockLogic.offsetHoursFromJapan("Asia/Tokyo", nov) == 0.0, "offset Tokyo 0");
        check(ClockLogic.offsetHoursFromJapan("Asia/Seoul", nov) == 0.0, "offset Seoul 0");
        check(ClockLogic.offsetHoursFromJapan("Europe/London", nov) == -9.0, "offset London -9");
        check(ClockLogic.offsetHoursFromJapan("America/New_York", nov) == -14.0, "offset New York -14");
        check(ClockLogic.offsetHoursFromJapan("Australia/Sydney", nov) == 2.0, "offset Sydney +2");
        eq(ClockLogic.formatOffset(-9.0), "-9時間", "formatOffset -9");
        eq(ClockLogic.formatOffset(0.0), "±0時間", "formatOffset 0");
        eq(ClockLogic.formatOffset(2.0), "+2時間", "formatOffset +2");
        eq(ClockLogic.formatOffset(5.5), "+5.5時間", "formatOffset +5.5");
        eq(ClockLogic.formatOffsetFromJapan("Europe/London", nov), "-9時間", "formatOffsetFromJapan London");
        // ゾーン時刻: 東京 12:00 のときロンドンは 03:00
        eq(ClockLogic.formatTimeInZone("Europe/London", nov), "03:00", "London time when Tokyo noon");
        eq(ClockLogic.formatTimeInZone("Asia/Tokyo", nov), "12:00", "Tokyo time noon");

        // 夏時間 (2026年7月 = ロンドンBST / NY EDT): 冬より1時間ずつ日本に近づく
        Calendar tokyoJul = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        tokyoJul.set(2026, Calendar.JULY, 15, 12, 0, 0);
        tokyoJul.set(Calendar.MILLISECOND, 0);
        long jul = tokyoJul.getTimeInMillis();
        check(ClockLogic.offsetHoursFromJapan("Europe/London", jul) == -8.0, "offset London summer -8");
        check(ClockLogic.offsetHoursFromJapan("America/New_York", jul) == -13.0, "offset New York summer -13");

        // 負の小数オフセット
        eq(ClockLogic.formatOffset(-3.5), "-3.5時間", "formatOffset -3.5");
    }

    // ----- requestCode 採番 / 短い repeat 配列の安全性 -----
    private static void testRequestCodeAndShortRepeat() {
        // nextRequestCode: 空 → 1
        check(AlarmItem.nextRequestCode(new java.util.ArrayList<>()) == 1, "nextRequestCode empty -> 1");
        // nextRequestCode: 既存 {1, 3} → 4
        java.util.List<AlarmItem> items = new java.util.ArrayList<>();
        AlarmItem a = new AlarmItem(); a.rc = 1; items.add(a);
        AlarmItem b = new AlarmItem(); b.rc = 3; items.add(b);
        check(AlarmItem.nextRequestCode(items) == 4, "nextRequestCode {1,3} -> 4");

        long tue0800 = at(2026, 11, 17, 8, 0);
        // 短い repeat 配列 (長さ1, 全 false): 例外なく単発相当 (今日の未来時刻)
        long t1 = ClockLogic.nextTriggerMillis(tue0800, 9, 0, new boolean[1]);
        check(t1 == at(2026, 11, 17, 9, 0), "short repeat len1 one-shot -> today " + ymdhm(t1));
        // 短い repeat 配列 (長さ2, 火曜のみ): 対象曜日として扱われる
        boolean[] shortTue = new boolean[2];
        shortTue[1] = true;
        check(ClockLogic.hasRepeat(shortTue), "hasRepeat short array true");
        long t2 = ClockLogic.nextTriggerMillis(tue0800, 9, 30, shortTue);
        check(t2 == at(2026, 11, 17, 9, 30), "short repeat Tue -> today " + ymdhm(t2));
        // repeatSummary も短い配列で例外を投げない
        eq(ClockLogic.repeatSummary(new boolean[3]), "1回のみ", "repeatSummary short array safe");
    }

    // ----- DST パス: 春の前進で存在しない壁時刻 (America/New_York 前提で実行) -----
    private static void testDstSpringForward() {
        // 2026-03-08 は spring forward: 02:00 EST → 03:00 EDT に跳び、02:30 は存在しない。
        // 実験確認済み: java.util.Calendar は存在しない壁時刻を lenient に解決し、
        // 02:30 の指定は 03:30 EDT (跳んだ直後の有効時刻) になる。
        long now = at(2026, 3, 7, 12, 0); // 前日正午 (EST)
        long trigger = ClockLogic.nextTriggerMillis(now, 2, 30, new boolean[7]);
        Calendar expected = Calendar.getInstance();
        expected.set(2026, Calendar.MARCH, 8, 3, 30, 0);
        expected.set(Calendar.MILLISECOND, 0);
        check(trigger == expected.getTimeInMillis(),
            "DST gap 02:30 resolves to 03:30 EDT millis (actual=" + trigger + ")");
        Calendar back = Calendar.getInstance();
        back.setTimeInMillis(trigger);
        check(back.get(Calendar.DAY_OF_MONTH) == 8
            && back.get(Calendar.HOUR_OF_DAY) == 3
            && back.get(Calendar.MINUTE) == 30,
            "DST resolved fields are Mar 8 03:30");
        check(java.util.TimeZone.getTimeZone("America/New_York").getOffset(trigger) == -4L * 3600L * 1000L,
            "DST resolved offset is EDT (-4h)");
    }

    // ----- フォーマット類 -----
    private static void testFormats() {
        // 日付 (日本語)
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at(2026, 11, 17, 8, 0));
        eq(ClockLogic.formatDateJa(c), "2026年11月17日（火）", "formatDateJa 2026-11-17 Tue");
        Calendar c2 = Calendar.getInstance();
        c2.setTimeInMillis(at(2026, 11, 22, 8, 0));
        eq(ClockLogic.formatDateJa(c2), "2026年11月22日（日）", "formatDateJa 2026-11-22 Sun");

        // ストップウォッチ mm:ss.cc
        eq(ClockLogic.formatStopwatch(0), "00:00.00", "sw 0");
        eq(ClockLogic.formatStopwatch(61234), "01:01.23", "sw 61234");
        eq(ClockLogic.formatStopwatch(3599999), "59:59.99", "sw just under 1h");
        eq(ClockLogic.formatStopwatch(3600000), "60:00.00", "sw exactly 1h keeps minutes");
        eq(ClockLogic.formatStopwatch(-500), "00:00.00", "sw negative clamps");
        eq(ClockLogic.formatStopwatchLong(3661230), "1:01:01.23", "swLong over 1h");
        eq(ClockLogic.formatStopwatchLong(61234), "01:01.23", "swLong under 1h");

        // タイマー残り計算
        check(ClockLogic.timerRemainingMillis(10000, 4000) == 6000, "timer remaining 6000");
        check(ClockLogic.timerRemainingMillis(4000, 10000) == 0, "timer remaining clamps to 0");
        check(ClockLogic.timerRemainingMillis(5000, 5000) == 0, "timer remaining at end");

        // タイマー合計
        check(ClockLogic.timerTotalMillis(0, 5, 0) == 300000, "timerTotal 5min");
        check(ClockLogic.timerTotalMillis(1, 0, 30) == 3630000, "timerTotal 1h0m30s");
        check(ClockLogic.timerTotalMillis(0, 0, 0) == 0, "timerTotal zero");

        // 時間長フォーマット
        eq(ClockLogic.formatDurationFloor(0), "00:00", "duration 0");
        eq(ClockLogic.formatDurationFloor(65000), "01:05", "duration 65s");
        eq(ClockLogic.formatDurationFloor(3661000), "1:01:01", "duration 1h1m1s");
        eq(ClockLogic.formatDuration(300000), "05:00", "duration ceil 5min exact");

        // HH:mm
        eq(ClockLogic.formatHourMinute(7, 5), "07:05", "formatHourMinute pads");
        eq(ClockLogic.formatHourMinute(23, 59), "23:59", "formatHourMinute 23:59");

        // 都市プリセット
        check(ClockLogic.DEFAULT_CITIES.size() == 6, "default cities 6");
        check(ClockLogic.DEFAULT_CITIES.get(0).name.equals("東京"), "default city 0 Tokyo");
        check(ClockLogic.findCityByTz("Europe/London") != null, "catalog has London");
        check(ClockLogic.findCityByTz("Invalid/Zone") == null, "catalog rejects unknown");
    }
}
