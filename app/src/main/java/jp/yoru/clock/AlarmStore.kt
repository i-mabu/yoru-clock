package jp.yoru.clock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ========================================
// 💾 アラームの永続化 (SharedPreferences + org.json)
// ========================================
// 保存形式は旧バージョンと互換: prefs "alarms" の "alarms_json" に
// {id, hour, minute, label, enabled, rc, triggerMillis, repeat[7]} の配列を置く。
object AlarmStore {
    private const val PREFS_NAME = "alarms"
    private const val KEY_JSON = "alarms_json"

    // ----------------------------------------
    // 📥 読み込み
    // ----------------------------------------
    fun load(ctx: Context): MutableList<AlarmItem> {
        val items = ArrayList<AlarmItem>()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null)
        if (json.isNullOrEmpty()) return items
        val array: JSONArray = try {
            JSONArray(json)
        } catch (ignored: Exception) {
            // JSON 全体が壊れている場合は空として扱う (アプリを落とさない)
            return items
        }
        val seenIds = HashSet<Long>()
        for (i in 0 until array.length()) {
            // 1 件ごとに読み取り、壊れた 1 件で残りを切り捨てない
            try {
                val obj = array.getJSONObject(i)
                val item = AlarmItem()
                item.id = obj.getLong("id")
                item.hour = obj.getInt("hour")
                item.minute = obj.getInt("minute")
                if (item.hour !in 0..23 || item.minute !in 0..59) continue
                if (!seenIds.add(item.id)) continue // ID の重複は後のものを捨てる
                item.label = obj.optString("label", "")
                item.enabled = obj.optBoolean("enabled", true)
                item.rc = obj.optInt("rc", 0)
                item.triggerMillis = obj.optLong("triggerMillis", 0)
                val repeat = obj.optJSONArray("repeat")
                if (repeat != null) {
                    var day = 0
                    while (day < 7 && day < repeat.length()) {
                        item.repeat[day] = repeat.optBoolean(day, false)
                        day++
                    }
                }
                items.add(item)
            } catch (ignored: Exception) {
                // この 1 件だけ読み飛ばす
            }
        }
        // rc の正規化 (メモリ上のみ): 未採番 (<=0) や重複は、リスト順に決定的に振り直す
        val normalized = ArrayList<AlarmItem>()
        for (item in items) {
            var duplicate = false
            for (fixed in normalized) {
                if (fixed.rc == item.rc) {
                    duplicate = true
                    break
                }
            }
            if (item.rc <= 0 || duplicate) item.rc = AlarmItem.nextRequestCode(normalized)
            normalized.add(item)
        }
        return items
    }

    // ----------------------------------------
    // 💾 保存
    // ----------------------------------------
    fun save(ctx: Context, items: List<AlarmItem>) {
        try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("hour", item.hour)
                obj.put("minute", item.minute)
                obj.put("label", item.label)
                obj.put("enabled", item.enabled)
                obj.put("rc", item.rc)
                obj.put("triggerMillis", item.triggerMillis)
                val repeat = JSONArray()
                for (day in 0 until 7) repeat.put(item.repeat[day])
                obj.put("repeat", repeat)
                array.put(obj)
            }
            // アラーム登録とずれないよう、同期的 (commit) に書き込む
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, array.toString()).commit()
        } catch (ignored: Exception) {
        }
    }

    // ----------------------------------------
    // 🔍 検索と ID 採番
    // ----------------------------------------
    fun findById(items: List<AlarmItem>, id: Long): AlarmItem? {
        for (item in items) {
            if (item.id == id) return item
        }
        return null
    }

    /** 新規 ID: 現在時刻ミリ秒を基にし、衝突したらインクリメントする */
    fun newId(items: List<AlarmItem>): Long {
        var candidate = System.currentTimeMillis()
        while (findById(items, candidate) != null) candidate++
        return candidate
    }
}
