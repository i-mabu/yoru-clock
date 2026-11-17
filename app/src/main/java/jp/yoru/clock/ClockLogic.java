package jp.yoru.clock;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

// ===== 1. 純ロジック (Android非依存。単体テスト対象) =====
// アラーム次回発火計算 / 世界時計の時差 / ストップウォッチ・タイマーの整形を担う。
// 曜日配列の規約: index 0=月, 1=火, 2=水, 3=木, 4=金, 5=土, 6=日
public final class ClockLogic {

    private ClockLogic() {}

    public static final String[] WEEKDAY_JA = {"日", "月", "火", "水", "木", "金", "土"};
    // repeat 配列 index (0=月..6=日) に対応する表示名
    public static final String[] REPEAT_DAY_NAMES = {"月", "火", "水", "木", "金", "土", "日"};

    // ----- 1.1 世界時計プリセット -----
    public static final class City {
        public final String name;
        public final String tzId;
        public City(String name, String tzId) { this.name = name; this.tzId = tzId; }
    }

    /** 既定で表示する都市 (順序固定) */
    public static final List<City> DEFAULT_CITIES = List.of(
        new City("東京", "Asia/Tokyo"),
        new City("ソウル", "Asia/Seoul"),
        new City("ロンドン", "Europe/London"),
        new City("ニューヨーク", "America/New_York"),
        new City("ロサンゼルス", "America/Los_Angeles"),
        new City("シドニー", "Australia/Sydney")
    );

    /** 追加候補のカタログ (DEFAULT_CITIES を含む全候補) */
    public static final List<City> CITY_CATALOG = List.of(
        new City("東京", "Asia/Tokyo"),
        new City("ソウル", "Asia/Seoul"),
        new City("北京", "Asia/Shanghai"),
        new City("香港", "Asia/Hong_Kong"),
        new City("シンガポール", "Asia/Singapore"),
        new City("バンコク", "Asia/Bangkok"),
        new City("ドバイ", "Asia/Dubai"),
        new City("モスクワ", "Europe/Moscow"),
        new City("パリ", "Europe/Paris"),
        new City("ベルリン", "Europe/Berlin"),
        new City("ロンドン", "Europe/London"),
        new City("ニューヨーク", "America/New_York"),
        new City("シカゴ", "America/Chicago"),
        new City("ロサンゼルス", "America/Los_Angeles"),
        new City("バンクーバー", "America/Vancouver"),
        new City("ホノルル", "Pacific/Honolulu"),
        new City("シドニー", "Australia/Sydney"),
        new City("オークランド", "Pacific/Auckland")
    );

    public static City findCityByTz(String tzId) {
        for (City c : CITY_CATALOG) if (c.tzId.equals(tzId)) return c;
        return null;
    }

    // ----- 1.2 曜日変換 -----
    /** Calendar.DAY_OF_WEEK (SUNDAY=1..SATURDAY=7) → repeat index (0=月..6=日) */
    public static int repeatIndexForCalendarDow(int calendarDow) {
        // 月(2)->0, 火(3)->1, 水(4)->2, 木(5)->3, 金(6)->4, 土(7)->5, 日(1)->6
        return (calendarDow + 5) % 7;
    }

    public static boolean hasRepeat(boolean[] repeat) {
        if (repeat == null) return false;
        for (boolean b : repeat) if (b) return true;
        return false;
    }

    /** 繰り返しの要約表示。なし="1回のみ"、全曜日="毎日"、それ以外は "月・水・金" 形式 */
    public static String repeatSummary(boolean[] repeat) {
        if (!hasRepeat(repeat)) return "1回のみ";
        boolean all = true;
        for (boolean b : repeat) if (!b) { all = false; break; }
        if (all) return "毎日";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (repeat[i]) {
                if (sb.length() > 0) sb.append("・");
                sb.append(REPEAT_DAY_NAMES[i]);
            }
        }
        return sb.toString();
    }

    // ----- 1.3 アラーム次回発火計算 -----
    /**
     * 次回発火時刻 (epoch millis) を返す。
     * - 繰り返しなし: 今日の hour:minute が未来なら今日、過ぎていれば明日。
     * - 繰り返しあり: 今日から最大7日先まで走査し、対象曜日で未来になる最初の時刻。
     * 候補が now と完全一致する場合は「過ぎた」扱い (翌候補へ回す)。
     */
    public static long nextTriggerMillis(long nowMillis, int hour, int minute, boolean[] repeat) {
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(nowMillis);
        boolean repeating = hasRepeat(repeat);
        for (int offset = 0; offset <= 7; offset++) {
            Calendar cand = (Calendar) base.clone();
            cand.add(Calendar.DAY_OF_YEAR, offset);
            cand.set(Calendar.HOUR_OF_DAY, hour);
            cand.set(Calendar.MINUTE, minute);
            cand.set(Calendar.SECOND, 0);
            cand.set(Calendar.MILLISECOND, 0);
            if (cand.getTimeInMillis() <= nowMillis) continue;
            if (!repeating) return cand.getTimeInMillis();
            int idx = repeatIndexForCalendarDow(cand.get(Calendar.DAY_OF_WEEK));
            if (repeat[idx]) return cand.getTimeInMillis();
        }
        // 到達しないはずだが、安全側として翌日同時刻を返す
        Calendar fallback = (Calendar) base.clone();
        fallback.add(Calendar.DAY_OF_YEAR, 1);
        fallback.set(Calendar.HOUR_OF_DAY, hour);
        fallback.set(Calendar.MINUTE, minute);
        fallback.set(Calendar.SECOND, 0);
        fallback.set(Calendar.MILLISECOND, 0);
        return fallback.getTimeInMillis();
    }

    // ----- 1.4 日付・時刻フォーマット -----
    /** "2026年11月17日（火）" 形式 */
    public static String formatDateJa(Calendar cal) {
        int y = cal.get(Calendar.YEAR);
        int m = cal.get(Calendar.MONTH) + 1;
        int d = cal.get(Calendar.DAY_OF_MONTH);
        String w = WEEKDAY_JA[cal.get(Calendar.DAY_OF_WEEK) - 1];
        return y + "年" + m + "月" + d + "日（" + w + "）";
    }

    public static String formatDateJa(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        return formatDateJa(cal);
    }

    /** 指定タイムゾーンの "HH:mm" */
    public static String formatTimeInZone(String tzId, long millis) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.JAPAN);
        f.setTimeZone(TimeZone.getTimeZone(tzId));
        return f.format(millis);
    }

    /** 指定タイムゾーンの "HH:mm:ss" */
    public static String formatTimeSecInZone(String tzId, long millis) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);
        f.setTimeZone(TimeZone.getTimeZone(tzId));
        return f.format(millis);
    }

    // ----- 1.5 世界時計の時差 -----
    /** 対象ゾーンと日本 (Asia/Tokyo) の時差 (時間単位、小数あり)。例: ロンドン冬=-9 */
    public static double offsetHoursFromJapan(String tzId, long millis) {
        long target = TimeZone.getTimeZone(tzId).getOffset(millis);
        long japan = TimeZone.getTimeZone("Asia/Tokyo").getOffset(millis);
        return (target - japan) / 3600000.0;
    }

    /** 時差の表示。例: -9 → "-9時間"、0 → "±0時間"、+2 → "+2時間"、+5.5 → "+5.5時間" */
    public static String formatOffset(double diffHours) {
        if (diffHours == 0.0) return "±0時間";
        String sign = diffHours > 0 ? "+" : "-";
        double abs = Math.abs(diffHours);
        String num;
        if (abs == Math.floor(abs)) num = String.valueOf((long) abs);
        else num = String.valueOf(abs);
        return sign + num + "時間";
    }

    public static String formatOffsetFromJapan(String tzId, long millis) {
        return formatOffset(offsetHoursFromJapan(tzId, millis));
    }

    // ----- 1.6 ストップウォッチ -----
    /** mm:ss.cc 形式 (分は60を超えても繰り上げない。例: 3600000ms → "60:00.00") */
    public static String formatStopwatch(long elapsedMs) {
        if (elapsedMs < 0) elapsedMs = 0;
        long totalSec = elapsedMs / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        long cc = (elapsedMs % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, cc);
    }

    /** ラップ一覧など詳細表示向け: 1時間以上は h:mm:ss.cc */
    public static String formatStopwatchLong(long elapsedMs) {
        if (elapsedMs < 3600000L) return formatStopwatch(elapsedMs);
        long h = elapsedMs / 3600000L;
        long rest = elapsedMs % 3600000L;
        long totalSec = rest / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        long cc = (rest % 1000) / 10;
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, min, sec, cc);
    }

    // ----- 1.7 タイマー -----
    /** 残り時間 (0未満にはならない) */
    public static long timerRemainingMillis(long endMillis, long nowMillis) {
        return Math.max(0L, endMillis - nowMillis);
    }

    /** 残り時間表示: 1時間未満は "MM:SS"、以上は "H:MM:SS" */
    public static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = (ms + 999) / 1000; // タイマー表示は切り上げ (残り1msでも 00:01 と見せる場面を避け表示用は下で調整)
        // ただし 0 は 0 のまま
        if (ms == 0) totalSec = 0;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** 秒ピッタリ表示用 (切り上げなし) */
    public static String formatDurationFloor(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    /** NumberPicker の値 → 合計ミリ秒 */
    public static long timerTotalMillis(int hours, int minutes, int seconds) {
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L;
    }

    /** "07:30" 形式 */
    public static String formatHourMinute(int hour, int minute) {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    /** 次回発火の説明文。例: "明日 07:30" / "今日 21:00" / "11月24日（火） 07:00" */
    public static String describeTrigger(long triggerMillis, long nowMillis) {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(triggerMillis);
        Calendar n = Calendar.getInstance();
        n.setTimeInMillis(nowMillis);
        String hm = formatHourMinute(t.get(Calendar.HOUR_OF_DAY), t.get(Calendar.MINUTE));
        boolean sameDay = t.get(Calendar.YEAR) == n.get(Calendar.YEAR)
            && t.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR);
        Calendar tomorrow = (Calendar) n.clone();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        boolean isTomorrow = t.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR)
            && t.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR);
        if (sameDay) return "今日 " + hm;
        if (isTomorrow) return "明日 " + hm;
        int m = t.get(Calendar.MONTH) + 1;
        int d = t.get(Calendar.DAY_OF_MONTH);
        String w = WEEKDAY_JA[t.get(Calendar.DAY_OF_WEEK) - 1];
        return m + "月" + d + "日（" + w + "） " + hm;
    }

    /** 保存済み都市リストの検証用: カタログにある tzId のみ残す */
    public static List<City> sanitizeCities(List<City> in) {
        List<City> out = new ArrayList<>();
        for (City c : in) if (findCityByTz(c.tzId) != null) out.add(c);
        return out;
    }
}
