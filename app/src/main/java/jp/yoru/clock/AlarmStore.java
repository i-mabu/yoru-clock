package jp.yoru.clock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// ===== アラームの永続化 (SharedPreferences + org.json) =====
public final class AlarmStore {
    private static final String PREFS = "alarms";
    private static final String KEY_JSON = "alarms_json";

    private AlarmStore() {}

    public static List<AlarmItem> load(Context ctx) {
        List<AlarmItem> out = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_JSON, null);
        if (json == null || json.isEmpty()) return out;
        JSONArray arr;
        try {
            arr = new JSONArray(json);
        } catch (Exception ignored) {
            // JSON全体が壊れている場合は空として扱う (アプリを落とさない)
            return out;
        }
        Set<Long> seenIds = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            // 1件ごとの try: 1件の破損で残りのアラームまで切り捨てない
            try {
                JSONObject o = arr.getJSONObject(i);
                AlarmItem item = new AlarmItem();
                item.id = o.getLong("id");
                item.hour = o.getInt("hour");
                item.minute = o.getInt("minute");
                if (item.hour < 0 || item.hour > 23 || item.minute < 0 || item.minute > 59) continue;
                if (!seenIds.add(item.id)) continue; // ID重複は後のものをスキップ
                item.label = o.optString("label", "");
                item.enabled = o.optBoolean("enabled", true);
                item.rc = o.optInt("rc", 0);
                item.triggerMillis = o.optLong("triggerMillis", 0);
                JSONArray rep = o.optJSONArray("repeat");
                if (rep != null) {
                    for (int d = 0; d < 7 && d < rep.length(); d++) item.repeat[d] = rep.optBoolean(d, false);
                }
                out.add(item);
            } catch (Exception ignored) {
                // この1件だけスキップして次へ
            }
        }
        // rc の正規化 (メモリ上のみ): 未採番 (<=0) または重複は、リスト順に決定的に採番し直す
        List<AlarmItem> fixed = new ArrayList<>();
        for (AlarmItem item : out) {
            boolean dup = false;
            for (AlarmItem f : fixed) if (f.rc == item.rc) { dup = true; break; }
            if (item.rc <= 0 || dup) item.rc = AlarmItem.nextRequestCode(fixed);
            fixed.add(item);
        }
        return out;
    }

    public static void save(Context ctx, List<AlarmItem> items) {
        try {
            JSONArray arr = new JSONArray();
            for (AlarmItem item : items) {
                JSONObject o = new JSONObject();
                o.put("id", item.id);
                o.put("hour", item.hour);
                o.put("minute", item.minute);
                o.put("label", item.label == null ? "" : item.label);
                o.put("enabled", item.enabled);
                o.put("rc", item.rc);
                o.put("triggerMillis", item.triggerMillis);
                JSONArray rep = new JSONArray();
                for (int d = 0; d < 7; d++) rep.put(item.repeat[d]);
                o.put("repeat", rep);
                arr.put(o);
            }
            // アラーム登録と不整合にならないよう同期的に書き込む
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, arr.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    public static AlarmItem findById(List<AlarmItem> items, long id) {
        for (AlarmItem item : items) if (item.id == id) return item;
        return null;
    }

    /** 新規ID: 現在時刻ミリ秒ベース (衝突時はインクリメント) */
    public static long newId(List<AlarmItem> items) {
        long id = System.currentTimeMillis();
        while (findById(items, id) != null) id++;
        return id;
    }
}
