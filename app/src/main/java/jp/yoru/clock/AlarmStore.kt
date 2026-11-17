package jp.yoru.clock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ===== 1. アラームの永続化 (SharedPreferences + org.json) =====
object AlarmStore {
    private const val PREFS = "alarms"
    private const val KEY_JSON = "alarms_json"

    // ----- 1.1 読み込み -----
    fun load(ctx: Context): MutableList<AlarmItem> {
        val out = ArrayList<AlarmItem>()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_JSON, null)
        if (json == null || json.isEmpty()) return out
        val arr: JSONArray
        try {
            arr = JSONArray(json)
        } catch (ignored: Exception) {
            // JSON全体が壊れている場合は空として扱う (アプリを落とさない)
            return out
        }
        val seenIds = HashSet<Long>()
        for (i in 0 until arr.length()) {
            // 1件ごとの try: 1件の破損で残りのアラームまで切り捨てない
            try {
                val o = arr.getJSONObject(i)
                val item = AlarmItem()
                item.id = o.getLong("id")
                item.hour = o.getInt("hour")
                item.minute = o.getInt("minute")
                if (item.hour < 0 || item.hour > 23 || item.minute < 0 || item.minute > 59) continue
                if (!seenIds.add(item.id)) continue // ID重複は後のものをスキップ
                item.label = o.optString("label", "")
                item.enabled = o.optBoolean("enabled", true)
                item.rc = o.optInt("rc", 0)
                item.triggerMillis = o.optLong("triggerMillis", 0)
                val rep = o.optJSONArray("repeat")
                if (rep != null) {
                    var d = 0
                    while (d < 7 && d < rep.length()) {
                        item.repeat[d] = rep.optBoolean(d, false)
                        d++
                    }
                }
                out.add(item)
            } catch (ignored: Exception) {
                // この1件だけスキップして次へ
            }
        }
        // rc の正規化 (メモリ上のみ): 未採番 (<=0) または重複は、リスト順に決定的に採番し直す
        val fixed = ArrayList<AlarmItem>()
        for (item in out) {
            var dup = false
            for (f in fixed) if (f.rc == item.rc) { dup = true; break }
            if (item.rc <= 0 || dup) item.rc = AlarmItem.nextRequestCode(fixed)
            fixed.add(item)
        }
        return out
    }

    // ----- 1.2 保存 -----
    fun save(ctx: Context, items: List<AlarmItem>) {
        try {
            val arr = JSONArray()
            for (item in items) {
                val o = JSONObject()
                o.put("id", item.id)
                o.put("hour", item.hour)
                o.put("minute", item.minute)
                o.put("label", item.label)
                o.put("enabled", item.enabled)
                o.put("rc", item.rc)
                o.put("triggerMillis", item.triggerMillis)
                val rep = JSONArray()
                for (d in 0 until 7) rep.put(item.repeat[d])
                o.put("repeat", rep)
                arr.put(o)
            }
            // アラーム登録と不整合にならないよう同期的に書き込む
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, arr.toString()).commit()
        } catch (ignored: Exception) {
        }
    }

    // ----- 1.3 検索と新規ID -----
    fun findById(items: List<AlarmItem>, id: Long): AlarmItem? {
        for (item in items) if (item.id == id) return item
        return null
    }

    /** 新規ID: 現在時刻ミリ秒ベース (衝突時はインクリメント) */
    fun newId(items: List<AlarmItem>): Long {
        var id = System.currentTimeMillis()
        while (findById(items, id) != null) id++
        return id
    }
}
