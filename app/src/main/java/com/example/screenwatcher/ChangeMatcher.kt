package com.example.screenwatcher

import android.content.Context
import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phát hiện THAY ĐỔI của một vùng (hình hoặc chữ) so với lúc ban đầu.
 * Mốc gốc chụp một lần và giữ cố định; các lần quét sau so với mốc đó, khác từ ngưỡng trở lên thì báo có thay đổi.
 */
object ChangeMatcher {
    private const val CELLS = 12_000      // số ô xám tối đa của một vùng khi so sánh hình
    private const val PIX_TOL = 28        // điểm ảnh lệch quá mức này (0..255) mới tính là khác

    /** Ảnh xám thu nhỏ của một vùng. */
    class Snap(val w: Int, val h: Int, val g: IntArray)

    /** Mốc gốc của một luật: [sig] đổi (sửa vùng/kiểu/độ phân giải) thì mốc cũ không còn dùng được. */
    class Base(val sig: String, val snaps: List<Snap>, val texts: List<String>)

    /** Luật đã báo thay đổi rồi: phải quay về gần mốc gốc mới được báo lại (chỉ lúc chạy thật, chỉ trong bộ nhớ). */
    private val fired = ConcurrentHashMap<String, Boolean>()
    fun resetLatches() = fired.clear()

    /* ---------- mốc gốc được lưu vào file, không bị ghi đè khi thử hay chạy ---------- */

    fun baseFile(c: Context, id: String) = File(c.filesDir, "base_$id.bin")

    fun savedAt(c: Context, id: String): Long = baseFile(c, id).let { if (it.exists()) it.lastModified() else 0L }

    fun delete(c: Context, id: String) { baseFile(c, id).delete(); fired.remove(id) }

    private fun save(c: Context, id: String, b: Base) {
        runCatching {
            DataOutputStream(baseFile(c, id).outputStream().buffered()).use { o ->
                o.writeUTF(b.sig)
                o.writeInt(b.snaps.size)
                for (sn in b.snaps) { o.writeInt(sn.w); o.writeInt(sn.h); for (v in sn.g) o.writeShort(v) }
                o.writeInt(b.texts.size)
                for (t in b.texts) o.writeUTF(t)
            }
        }
    }

    private fun load(c: Context, id: String): Base? {
        val f = baseFile(c, id)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                val sig = i.readUTF()
                val snaps = List(i.readInt()) {
                    val w = i.readInt(); val h = i.readInt()
                    Snap(w, h, IntArray(w * h) { i.readShort().toInt() })
                }
                val texts = List(i.readInt()) { i.readUTF() }
                Base(sig, snaps, texts)
            }
        }.getOrNull()
    }

    private fun snap(crop: Bitmap): Snap {
        val area = crop.width.toDouble() * crop.height
        val sc = min(1.0, sqrt(CELLS / area))
        val w = max(1, (crop.width * sc).toInt())
        val h = max(1, (crop.height * sc).toInt())
        val s = if (w == crop.width && h == crop.height) crop else Bitmap.createScaledBitmap(crop, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== crop) s.recycle()
        for (i in px.indices) {
            val q = px[i]
            px[i] = (299 * ((q shr 16) and 255) + 587 * ((q shr 8) and 255) + 114 * (q and 255)) / 1000
        }
        return Snap(w, h, px)
    }

    /** Tỉ lệ điểm ảnh khác nhau (0..1). */
    private fun imageDiff(a: Snap, b: Snap): Double {
        if (a.w != b.w || a.h != b.h) return 1.0
        var n = 0
        for (i in a.g.indices) if (abs(a.g[i] - b.g[i]) > PIX_TOL) n++
        return n.toDouble() / a.g.size
    }

    /** Tỉ lệ ký tự khác nhau (khoảng cách Levenshtein / độ dài lớn hơn), 0..1. */
    private fun textDiff(a: String, b: String): Double {
        if (a == b) return 0.0
        val m = max(a.length, b.length)
        if (m == 0) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length].toDouble() / m
    }

    private class Obs(val sig: String, val boxes: List<Region>, val snaps: List<Snap>, val texts: List<String>, val isText: Boolean)

    private suspend fun observe(rule: Rule, frame: Bitmap): Obs? {
        val regs = if (rule.regions.isEmpty()) listOf(Region(0, 0, frame.width, frame.height)) else rule.regions
        val boxes = regs.mapNotNull { rg ->
            val l = rg.l.coerceIn(0, frame.width - 1)
            val t = rg.t.coerceIn(0, frame.height - 1)
            val r = rg.r.coerceAtMost(frame.width)
            val b = rg.b.coerceAtMost(frame.height)
            if (r - l < 8 || b - t < 8) null else Region(l, t, r, b)
        }
        if (boxes.isEmpty()) return null
        val isText = rule.changeMode == Rule.CH_TEXT
        val sig = "${rule.changeMode}|${frame.width}x${frame.height}|" +
            boxes.joinToString(";") { "${it.l},${it.t},${it.r},${it.b}" }
        val snaps = ArrayList<Snap>()
        val texts = ArrayList<String>()
        for (bx in boxes) {
            val crop = Bitmap.createBitmap(frame, bx.l, bx.t, bx.w, bx.h)
            if (isText) texts.add(TextMatcher.readNorm(crop)) else snaps.add(snap(crop))
            if (crop !== frame) crop.recycle()
        }
        return Obs(sig, boxes, snaps, texts, isText)
    }

    /** Mốc gốc hợp lệ hiện có (bộ nhớ đệm hoặc file) cho cấu hình vùng/kiểu hiện tại? */
    private fun validBase(c: Context, rule: Rule, sig: String, cache: MutableMap<String, Base>): Base? {
        val b = cache[rule.id] ?: load(c, rule.id)?.also { cache[rule.id] = it }
        return if (b != null && b.sig == sig) b else null
    }

    /**
     * Chụp mốc gốc. [force] = false: chỉ chụp khi CHƯA có mốc hợp lệ (không đụng vào mốc đã có).
     * [force] = true: ghi đè mốc (chỉ dùng khi người dùng bấm "Chụp mốc gốc mới").
     * Trả về true nếu vừa chụp mốc mới.
     */
    suspend fun capture(c: Context, rule: Rule, frame: Bitmap, cache: MutableMap<String, Base>, force: Boolean = false): Boolean {
        val o = observe(rule, frame) ?: return false
        if (!force && validBase(c, rule, o.sig, cache) != null) return false
        val b = Base(o.sig, o.snaps, o.texts)
        cache[rule.id] = b
        save(c, rule.id, b)
        fired.remove(rule.id)
        AppState.log("📌 Đã chụp mốc gốc cho '${rule.name}'")
        return true
    }

    /** Luật đã có mốc gốc hợp lệ để so sánh chưa? (không cần khung hình) */
    fun hasBase(c: Context, rule: Rule, cache: MutableMap<String, Base>): Boolean =
        (cache[rule.id] ?: load(c, rule.id)) != null

    /**
     * So khung hình với mốc gốc ĐÃ LƯU. Chưa có mốc (hoặc đã đổi vùng/độ phân giải) -> chụp mốc rồi trả null.
     * Có mốc -> trả Hit ở tâm vùng thay đổi nhiều nhất (score = mức thay đổi 0..1) nếu >= ngưỡng.
     * [test] = true: chỉ so sánh, không đổi trạng thái "đã báo" (dùng cho nút Thử).
     */
    suspend fun detect(
        c: Context, rule: Rule, frame: Bitmap, cache: MutableMap<String, Base>, test: Boolean = false,
    ): Hit? {
        val o = observe(rule, frame) ?: return null
        val base = validBase(c, rule, o.sig, cache)
        if (base == null) {
            capture(c, rule, frame, cache, force = true)
            return null
        }
        var bestI = -1
        var bestD = 0.0
        var maxD = 0.0
        for (i in o.boxes.indices) {
            val d = if (o.isText) textDiff(base.texts[i], o.texts[i]) else imageDiff(base.snaps[i], o.snaps[i])
            if (d > maxD) maxD = d
            if (d >= rule.diffThreshold && d > bestD) { bestD = d; bestI = i }
        }
        if (!test && fired[rule.id] == true) {
            if (maxD < rule.diffThreshold) fired.remove(rule.id) // đã về như mốc gốc -> báo lại được
            return null
        }
        if (bestI < 0) return null
        if (!test) fired[rule.id] = true
        val bx = o.boxes[bestI]
        return Hit(bx.l + bx.w / 2, bx.t + bx.h / 2, bestD)
    }
}
