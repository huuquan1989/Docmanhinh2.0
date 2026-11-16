package com.example.screenwatcher

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phát hiện THAY ĐỔI của một vùng (hình hoặc chữ) so với lúc ban đầu.
 * Lần quét đầu tiên chỉ chụp "mốc gốc"; các lần sau so với mốc đó, khác từ ngưỡng trở lên thì báo có thay đổi.
 */
object ChangeMatcher {
    private const val CELLS = 12_000      // số ô xám tối đa của một vùng khi so sánh hình
    private const val PIX_TOL = 28        // điểm ảnh lệch quá mức này (0..255) mới tính là khác

    /** Ảnh xám thu nhỏ của một vùng. */
    class Snap(val w: Int, val h: Int, val g: IntArray)

    /** Mốc gốc của một luật: [sig] đổi (sửa vùng/kiểu/độ phân giải) thì mốc cũ bỏ đi. */
    class Base(val sig: String, val snaps: List<Snap>, val texts: List<String>)

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

    /**
     * Quét các vùng của [rule] trên [frame]. Chưa có mốc gốc -> chụp mốc rồi trả null.
     * Có mốc -> trả Hit ở tâm vùng thay đổi nhiều nhất (score = mức thay đổi 0..1) nếu >= ngưỡng.
     */
    suspend fun detect(rule: Rule, frame: Bitmap, bases: MutableMap<String, Base>): Hit? {
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

        val base = bases[rule.id]
        if (base == null || base.sig != sig) {
            bases[rule.id] = Base(sig, snaps, texts)
            AppState.log("📌 Đã chụp mốc gốc cho '${rule.name}'")
            return null
        }

        var bestI = -1
        var bestD = 0.0
        for (i in boxes.indices) {
            val d = if (isText) textDiff(base.texts[i], texts[i]) else imageDiff(base.snaps[i], snaps[i])
            if (d >= rule.diffThreshold && d > bestD) { bestD = d; bestI = i }
        }
        if (bestI < 0) return null
        val bx = boxes[bestI]
        return Hit(bx.l + bx.w / 2, bx.t + bx.h / 2, bestD)
    }
}
