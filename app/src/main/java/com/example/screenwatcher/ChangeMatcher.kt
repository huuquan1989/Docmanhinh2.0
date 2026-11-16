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

/**
 * Phát hiện THAY ĐỔI của vùng đã khoanh (hình hoặc chữ).
 *
 * Không có "mốc gốc" riêng: ẢNH GỐC chính là ảnh của vùng người dùng khoanh, được lưu ngay lúc khoanh vùng.
 * Vùng quét cũng chính là vùng đó: mỗi lần quét cắt đúng vùng ấy trên màn hình hiện tại rồi TRỪ cho ảnh gốc;
 * điểm nào lệch quá ngưỡng là "khác". Điểm số lấy theo cả vùng lẫn từng khối cục bộ (icon nhỏ đổi chỗ vẫn bắt được),
 * và trả luôn tâm chỗ đổi. Chạy macro, Thử, dừng/chạy lại đều KHÔNG đổi ảnh gốc. Không dùng thư viện ngoài.
 */
object ChangeMatcher {
    private const val MAX_DIM = 720       // cạnh dài nhất của ảnh xám khi so sánh hình (giữ đủ chi tiết cho icon)
    private const val PIX_TOL = 30        // điểm ảnh lệch quá mức này (0..255) mới tính là khác

    /** Ảnh xám của một vùng (w*h byte). */
    class Snap(val w: Int, val h: Int, val g: ByteArray)

    /** Ảnh gốc của MỘT vùng: [key] = toạ độ vùng, [fw]x[fh] = kích thước màn hình lúc khoanh, kèm chữ OCR của vùng. */
    class Entry(val key: String, val fw: Int, val fh: Int, val snap: Snap, val text: String)

    class Base(val entries: List<Entry>, val stamp: Long)

    private val cache = ConcurrentHashMap<String, Base>()

    /** Luật đã báo thay đổi rồi: phải quay về giống ảnh gốc mới được báo lại (chỉ lúc chạy thật, chỉ trong bộ nhớ). */
    private val fired = ConcurrentHashMap<String, Boolean>()
    private val warned = HashSet<String>()

    @Synchronized fun resetLatches() { fired.clear(); warned.clear() }

    /* ---------- ảnh gốc lưu vào file (đi theo bộ luật) ---------- */

    fun baseFile(c: Context, id: String) = File(c.filesDir, "base_$id.bin")

    /** Số vùng của luật đã có ảnh gốc. */
    fun countOriginals(c: Context, rule: Rule): Int {
        val b = get(c, rule.id) ?: return 0
        val keys = rule.regions.map { key(it) }.toSet()
        return b.entries.count { it.key in keys }
    }

    fun delete(c: Context, id: String) { baseFile(c, id).delete(); cache.remove(id); fired.remove(id) }

    private fun key(r: Region) = "${r.l},${r.t},${r.r},${r.b}"

    private fun save(c: Context, id: String, entries: List<Entry>) {
        val f = baseFile(c, id)
        if (entries.isEmpty()) { f.delete(); cache.remove(id); return }
        runCatching {
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeUTF("v5")
                o.writeInt(entries.size)
                for (e in entries) {
                    o.writeUTF(e.key); o.writeInt(e.fw); o.writeInt(e.fh)
                    o.writeInt(e.snap.w); o.writeInt(e.snap.h); o.write(e.snap.g)
                    o.writeUTF(e.text)
                }
            }
        }
        cache.remove(id)
        fired.remove(id)
    }

    private fun get(c: Context, id: String): Base? {
        val f = baseFile(c, id)
        if (!f.exists()) { cache.remove(id); return null }
        val st = f.lastModified()
        cache[id]?.let { if (it.stamp == st) return it }
        val b = runCatching {
            DataInputStream(f.inputStream().buffered()).use { i ->
                if (i.readUTF() != "v5") return@use null
                val list = List(i.readInt()) {
                    val k = i.readUTF(); val fw = i.readInt(); val fh = i.readInt()
                    val w = i.readInt(); val h = i.readInt()
                    val g = ByteArray(w * h).also { a -> i.readFully(a) }
                    Entry(k, fw, fh, Snap(w, h, g), i.readUTF())
                }
                Base(list, st)
            }
        }.getOrNull()
        if (b != null) cache[id] = b else cache.remove(id)
        return b
    }

    private fun clamp(rg: Region, frame: Bitmap): Region? {
        val l = rg.l.coerceIn(0, frame.width - 1)
        val t = rg.t.coerceIn(0, frame.height - 1)
        val r = rg.r.coerceAtMost(frame.width)
        val b = rg.b.coerceAtMost(frame.height)
        return if (r - l < 8 || b - t < 8) null else Region(l, t, r, b)
    }

    private suspend fun makeEntry(frame: Bitmap, rg: Region): Entry? {
        val bx = clamp(rg, frame) ?: return null
        val crop = Bitmap.createBitmap(frame, bx.l, bx.t, bx.w, bx.h)
        val sn = snap(crop)
        val txt = runCatching { TextMatcher.readNorm(crop) }.getOrDefault("")
        if (crop !== frame) crop.recycle()
        return Entry(key(rg), frame.width, frame.height, sn, txt)
    }

    /**
     * Lưu ảnh gốc cho các vùng [rects] vừa khoanh (cắt đúng các vùng đó từ [frame]).
     * Ảnh gốc của các vùng đã khoanh trước đó được giữ nguyên; ảnh của vùng không còn trong luật thì bỏ.
     */
    suspend fun addOriginals(c: Context, rule: Rule, rects: List<Region>, frame: Bitmap) {
        val keep = rule.regions.map { key(it) }.toSet()
        val newKeys = rects.map { key(it) }.toSet()
        val list = ArrayList<Entry>()
        get(c, rule.id)?.entries?.filter { it.key in keep && it.key !in newKeys }?.let { list.addAll(it) }
        for (r in rects) makeEntry(frame, r)?.let { list.add(it) }
        save(c, rule.id, list)
        AppState.log("🖼 Đã lưu ảnh gốc ${rects.size} vùng cho '${rule.name}'")
    }

    /** Chụp lại ảnh gốc của TẤT CẢ vùng đang có của luật từ màn hình hiện tại (người dùng bấm nút). */
    suspend fun recaptureOriginals(c: Context, rule: Rule, frame: Bitmap): Int {
        val list = rule.regions.mapNotNull { makeEntry(frame, it) }
        save(c, rule.id, list)
        AppState.log("🖼 Đã chụp lại ảnh gốc ${list.size} vùng cho '${rule.name}'")
        return list.size
    }

    @Synchronized private fun warnOnce(k: String, msg: String) { if (warned.add(k)) AppState.log(msg) }

    private fun snap(crop: Bitmap): Snap {
        val sc = min(1.0, MAX_DIM.toDouble() / max(crop.width, crop.height))
        val w = max(1, (crop.width * sc).toInt())
        val h = max(1, (crop.height * sc).toInt())
        val sm = if (w == crop.width && h == crop.height) crop else Bitmap.createScaledBitmap(crop, w, h, true)
        val px = IntArray(w * h)
        sm.getPixels(px, 0, w, 0, 0, w, h)
        if (sm !== crop) sm.recycle()
        val g = ByteArray(w * h)
        for (i in px.indices) {
            val q = px[i]
            g[i] = ((299 * ((q shr 16) and 255) + 587 * ((q shr 8) and 255) + 114 * (q and 255)) / 1000).toByte()
        }
        return Snap(w, h, g)
    }

    /** Kết quả so hình: [score] 0..1 và tâm chỗ đổi nhiều nhất (toạ độ trong ảnh xám). */
    private class Diff(val score: Double, val cx: Int, val cy: Int)

    /**
     * Trừ ảnh hiện tại cho ảnh mốc. Điểm = lớn hơn trong hai số:
     *  - tỉ lệ điểm ảnh khác trên TOÀN vùng;
     *  - tỉ lệ điểm khác trong khối cục bộ đổi nhiều nhất (khối ~ 1/10 cạnh ngắn; bỏ qua khối chỉ vài điểm lẻ = nhiễu).
     * Tâm chỗ đổi = trung bình toạ độ các điểm khác quanh khối đó (3x3 khối).
     */
    private fun imageDiff(a: Snap, b: Snap): Diff {
        if (a.w != b.w || a.h != b.h) return Diff(1.0, a.w / 2, a.h / 2)
        val w = a.w
        val h = a.h
        val bs = max(8, min(w, h) / 10)
        val bw = (w + bs - 1) / bs
        val bh = (h + bs - 1) / bs
        val cnt = IntArray(bw * bh)
        val sx = LongArray(bw * bh)
        val sy = LongArray(bw * bh)
        var total = 0
        for (y in 0 until h) {
            val row = y * w
            val by = (y / bs) * bw
            for (x in 0 until w) {
                val d = (a.g[row + x].toInt() and 255) - (b.g[row + x].toInt() and 255)
                if (abs(d) > PIX_TOL) {
                    total++
                    val k = by + x / bs
                    cnt[k]++; sx[k] += x.toLong(); sy[k] += y.toLong()
                }
            }
        }
        val minPix = max(6, bs * bs / 50)          // dưới ~2% khối = nhiễu lẻ tẻ
        var best = 0.0
        var bi = -1
        var bj = -1
        for (j in 0 until bh) for (i in 0 until bw) {
            val c = cnt[j * bw + i]
            if (c < minPix) continue
            val area = min(bs, w - i * bs) * min(bs, h - j * bs)
            if (area * 2 < bs * bs) continue
            val f = c.toDouble() / area
            if (f > best) { best = f; bi = i; bj = j }
        }
        if (bi < 0) return Diff(0.0, w / 2, h / 2)
        var n = 0L
        var tx = 0L
        var ty = 0L
        for (j in max(0, bj - 1)..min(bh - 1, bj + 1)) for (i in max(0, bi - 1)..min(bw - 1, bi + 1)) {
            val k = j * bw + i
            n += cnt[k]; tx += sx[k]; ty += sy[k]
        }
        val score = max(total.toDouble() / (w * h), best).coerceIn(0.0, 1.0)
        return Diff(score, (tx / n).toInt(), (ty / n).toInt())
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

    /**
     * Cắt đúng các vùng của [rule] trên [frame], trừ cho ảnh gốc đã lưu của từng vùng.
     * Trả Hit ở tâm chỗ đổi nhiều nhất (score = mức thay đổi 0..1) nếu >= ngưỡng.
     * Vùng nào chưa có ảnh gốc thì bỏ qua (nhắc trong nhật ký, KHÔNG tự chụp). [test] = true: chỉ so sánh,
     * không đổi trạng thái "đã báo" (dùng cho nút Thử).
     */
    suspend fun detect(c: Context, rule: Rule, frame: Bitmap, test: Boolean = false): Hit? {
        if (rule.regions.isEmpty()) {
            warnOnce("noreg:${rule.id}", "⚠ '${rule.name}': chưa khoanh vùng nào (vùng khoanh chính là ảnh gốc)")
            return null
        }
        val base = get(c, rule.id)
        val isText = rule.changeMode == Rule.CH_TEXT
        var bestD = 0.0
        var maxD = 0.0
        var hx = 0
        var hy = 0
        var used = 0
        for (rg in rule.regions) {
            val bx = clamp(rg, frame) ?: continue
            val e = base?.entries?.firstOrNull { it.key == key(rg) && it.fw == frame.width && it.fh == frame.height } ?: continue
            used++
            val crop = Bitmap.createBitmap(frame, bx.l, bx.t, bx.w, bx.h)
            var d: Double
            var px = bx.l + bx.w / 2
            var py = bx.t + bx.h / 2
            if (isText) {
                d = textDiff(e.text, TextMatcher.readNorm(crop))
            } else {
                val df = imageDiff(e.snap, snap(crop))
                d = df.score
                px = bx.l + df.cx * bx.w / e.snap.w   // tâm chỗ đổi, đổi ra toạ độ màn hình
                py = bx.t + df.cy * bx.h / e.snap.h
            }
            if (crop !== frame) crop.recycle()
            if (d > maxD) maxD = d
            if (d >= rule.diffThreshold && d > bestD) { bestD = d; hx = px; hy = py }
        }
        if (used == 0) {
            warnOnce(
                "noimg:${rule.id}",
                "⚠ '${rule.name}': chưa có ảnh gốc cho vùng (hoặc màn hình đổi kích thước). Khoanh lại vùng hoặc bấm \"Chụp lại ảnh gốc\"",
            )
            return null
        }
        if (!test && fired[rule.id] == true) {
            if (maxD < rule.diffThreshold) fired.remove(rule.id) // đã về như ảnh gốc -> báo lại được
            return null
        }
        if (bestD <= 0.0) return null
        if (!test) fired[rule.id] = true
        return Hit(hx, hy, bestD)
    }
}
