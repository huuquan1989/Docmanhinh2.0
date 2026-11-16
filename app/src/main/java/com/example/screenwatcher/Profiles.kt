package com.example.screenwatcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Một bộ luật đã lưu: tên, thời điểm lưu, số luật (chỉ để hiển thị). */
data class Profile(val id: String, val name: String, val savedAt: Long, val ruleCount: Int)

/**
 * Bộ luật cài sẵn ("preset"): chụp lại toàn bộ danh sách luật hiện tại + ảnh mẫu + các cài đặt chạy
 * (tuần tự, số vòng, chu kỳ quét) dưới một cái tên. Mỗi tác vụ lưu một bộ riêng, sau này chỉ cần chọn và chạy.
 *
 * Lưu ở  filesDir/profiles/<id>/profile.json  và các ảnh mẫu  tpl_<ruleId>.png  cùng thư mục.
 */
object ProfileStore {
    private const val FILE = "profile.json"

    private fun root(c: Context) = File(c.filesDir, "profiles").apply { mkdirs() }
    private fun dir(c: Context, id: String) = File(root(c), id)

    private fun readJson(c: Context, id: String): JSONObject? = runCatching {
        JSONObject(File(dir(c, id), FILE).readText())
    }.getOrNull()

    fun list(c: Context): List<Profile> =
        (root(c).listFiles() ?: emptyArray()).mapNotNull { d ->
            val o = readJson(c, d.name) ?: return@mapNotNull null
            Profile(d.name, o.optString("name", "Bộ luật"), o.optLong("savedAt", 0L), o.optJSONArray("rules")?.length() ?: 0)
        }.sortedBy { it.name.lowercase() }

    fun findByName(c: Context, name: String): Profile? =
        list(c).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Lưu trạng thái hiện tại thành bộ luật tên [name]. Trùng tên thì ghi đè. Trả về bộ luật đã lưu. */
    fun saveCurrent(c: Context, name: String): Profile {
        val clean = name.trim().ifEmpty { "Bộ luật" }
        val id = findByName(c, clean)?.id ?: UUID.randomUUID().toString()
        val d = dir(c, id)
        d.deleteRecursively()
        d.mkdirs()

        val rules = RuleStore.load(c)
        val now = System.currentTimeMillis()
        val o = JSONObject()
            .put("name", clean).put("savedAt", now)
            .put("seqOn", RuleStore.seqOn(c)).put("seqCycles", RuleStore.seqCycles(c))
            .put("interval", RuleStore.interval(c))
            .put("rules", JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } })
        File(d, FILE).writeText(o.toString())

        for (r in rules) {
            val src = RuleStore.templateFile(c, r.id)
            if (src.exists()) src.copyTo(File(d, src.name), overwrite = true)
            val bf = ChangeMatcher.baseFile(c, r.id)
            if (bf.exists()) bf.copyTo(File(d, bf.name), overwrite = true)
        }
        return Profile(id, clean, now, rules.size)
    }

    /** Nạp bộ luật: thay toàn bộ luật + ảnh mẫu + cài đặt hiện tại bằng nội dung đã lưu. */
    fun load(c: Context, id: String): Boolean {
        val o = readJson(c, id) ?: return false
        val a = o.optJSONArray("rules") ?: JSONArray()
        val rules = runCatching { List(a.length()) { Rule.from(a.getJSONObject(it)) } }.getOrNull() ?: return false

        // xoá ảnh mẫu của bộ luật đang dùng rồi chép ảnh mẫu của bộ luật được chọn
        (c.filesDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("tpl_") && it.name.endsWith(".png") }
            .forEach { it.delete() }
        (dir(c, id).listFiles() ?: emptyArray())
            .filter { it.name.startsWith("tpl_") && it.name.endsWith(".png") }
            .forEach { it.copyTo(File(c.filesDir, it.name), overwrite = true) }

        // mốc gốc "thay đổi" đi theo bộ luật
        (c.filesDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("base_") && it.name.endsWith(".bin") }
            .forEach { it.delete() }
        (dir(c, id).listFiles() ?: emptyArray())
            .filter { it.name.startsWith("base_") && it.name.endsWith(".bin") }
            .forEach { it.copyTo(File(c.filesDir, it.name), overwrite = true) }

        RuleStore.saveAll(c, rules)
        RuleStore.setSeqOn(c, o.optBoolean("seqOn", false))
        RuleStore.setSeqCycles(c, o.optInt("seqCycles", 1))
        RuleStore.setInterval(c, o.optLong("interval", 700L))
        return true
    }

    fun rename(c: Context, id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        val other = findByName(c, clean)
        if (other != null && other.id != id) return false
        val o = readJson(c, id) ?: return false
        File(dir(c, id), FILE).writeText(o.put("name", clean).toString())
        return true
    }

    fun delete(c: Context, id: String) {
        dir(c, id).deleteRecursively()
    }
}
