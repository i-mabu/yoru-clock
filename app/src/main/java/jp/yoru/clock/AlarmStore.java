package jp.yoru.clock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AlarmItem item = new AlarmItem();
                item.id = o.getLong("id");
                item.hour = o.getInt("hour");
                item.minute = o.getInt("minute");
                item.label = o.optString("label", "");
                item.enabled = o.optBoolean("enabled", true);
                JSONArray rep = o.optJSONArray("repeat");
                if (rep != null) {
                    for (int d = 0; d < 7 && d < rep.length(); d++) item.repeat[d] = rep.optBoolean(d, false);
                }
                out.add(item);
            }
        } catch (Exception ignored) {
            // 壊れたデータは空として扱う (アプリを落とさない)
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
                JSONArray rep = new JSONArray();
                for (int d = 0; d < 7; d++) rep.put(item.repeat[d]);
                o.put("repeat", rep);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, arr.toString()).apply();
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
