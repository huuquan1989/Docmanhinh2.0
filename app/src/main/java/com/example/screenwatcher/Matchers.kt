package com.example.screenwatcher

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Kết quả khớp: toạ độ tâm (trong bitmap được quét) + độ giống 0..1 */
data class Hit(val x: Int, val y: Int, val score: Double, val scale: Double = 1.0)

/* ------------------------------------------------------------------ */
/*  Nhận diện CHỮ (OCR bằng ML Kit, offline)                          */
/* ------------------------------------------------------------------ */
object TextMatcher {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Chữ thường, bỏ dấu tiếng Việt, bỏ khoảng trắng/ký tự đặc biệt -> so khớp bền hơn với lỗi OCR. */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("đ", "d")
        return buildString {
            for (c in n) {
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt() && c.isLetterOrDigit()) append(c)
            }
        }
    }

    /** Tìm chuỗi con gần giống nhất (Sellers). Trả về độ giống nếu >= minRatio. */
    private fun approx(text: String, pat: String, minRatio: Double): Double? {
        if (pat.isEmpty() || text.isEmpty()) return null
        if (text.contains(pat)) return 1.0
        val m = pat.length
        val allowed = floor(m * (1 - minRatio)).toInt()
        if (allowed <= 0) return null
        var prev = IntArray(m + 1) { it }
        for (c in text) {
            val cur = IntArray(m + 1)
            for (i in 1..m) {
                cur[i] = minOf(prev[i] + 1, cur[i - 1] + 1, prev[i - 1] + if (pat[i - 1] == c) 0 else 1)
            }
            if (cur[m] <= allowed) return 1.0 - cur[m].toDouble() / m
            prev = cur
        }
        return null
    }

    private suspend fun recognize(bmp: Bitmap): Text = suspendCancellableCoroutine { c ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    /** rule.text có thể chứa nhiều lựa chọn, ngăn cách bằng dấu | (khớp 1 trong số đó là đủ). */
    suspend fun find(bmp: Bitmap, rule: Rule): Hit? {
        val pats = rule.text.split("|").map { norm(it) }.filter { it.isNotEmpty() }
        if (pats.isEmpty()) return null
        val ratio = if (rule.fuzzy) rule.threshold else 1.0
        val res = recognize(bmp)
        for (line in res.textBlocks.flatMap { it.lines }) {
            val t = norm(line.text)
            for (p in pats) {
                val s = approx(t, p, ratio) ?: continue
                val r = line.boundingBox ?: continue
                return Hit(r.centerX(), r.centerY(), s)
            }
        }
        // Cụm chữ nằm vắt qua nhiều dòng
        val all = norm(res.text)
        for (p in pats) {
            val s = approx(all, p, ratio) ?: continue
            return Hit(bmp.width / 2, bmp.height / 2, s)
        }
        return null
    }
}

/* ------------------------------------------------------------------ */
/*  Nhận diện HÌNH ẢNH (template matching NCC, đa tỉ lệ, thuần Kotlin) */
/* ------------------------------------------------------------------ */
object TemplateMatcher {
    private class G(val w: Int, val h: Int, val d: FloatArray)

    /** Pixel của một bitmap, đọc đúng 1 lần để dùng lại cho nhiều mức thu nhỏ. */
    private class Px(val w: Int, val h: Int, val px: IntArray) {
        constructor(b: Bitmap) : this(
            b.width, b.height,
            IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) },
        )
    }

    // Các tỉ lệ thử (ảnh trong vùng quét / ảnh mẫu): 1.0, 1.15, 0.87, 1.32, 0.76 ... trong [0.4 .. 2.5]
    private const val SCALE_MIN = 0.4
    private const val SCALE_MAX = 2.5
    private const val SCALE_STEP = 1.15
    private val SCALES: List<Double> = run {
        val l = mutableListOf(1.0)
        var k = SCALE_STEP
        while (k <= SCALE_MAX) {
            l.add(k)
            if (1.0 / k >= SCALE_MIN) l.add(1.0 / k)
            k *= SCALE_STEP
        }
        l
    }

    private fun gray(p: Px, s: Int): G {
        val w = p.w
        val gw = w / s
        val gh = p.h / s
        val out = FloatArray(gw * gh)
        val inv = 1f / (s * s)
        for (y in 0 until gh) for (x in 0 until gw) {
            var acc = 0f
            for (dy in 0 until s) {
                val row = (y * s + dy) * w + x * s
                for (dx in 0 until s) {
                    val q = p.px[row + dx]
                    acc += 0.299f * ((q shr 16) and 255) + 0.587f * ((q shr 8) and 255) + 0.114f * (q and 255)
                }
            }
            out[y * gw + x] = acc * inv
        }
        return G(gw, gh, out)
    }

    private fun gray(b: Bitmap, s: Int): G = gray(Px(b), s)

    /** Trả về (x, y góc trên-trái, điểm NCC) tốt nhất. */
    private fun search(img: G, t: G): Triple<Int, Int, Double>? {
        val tw = t.w
        val th = t.h
        val n = tw * th
        if (tw < 3 || th < 3 || img.w < tw || img.h < th) return null
        var mean = 0f
        for (v in t.d) mean += v
        mean /= n
        val tz = FloatArray(n) { t.d[it] - mean }
        var tn = 0.0
        for (v in tz) tn += v * v
        tn = sqrt(tn)
        if (tn < 1e-3) return null // mẫu trơn, không có chi tiết

        val iw = img.w + 1
        val sum = DoubleArray(iw * (img.h + 1))
        val sq = DoubleArray(iw * (img.h + 1))
        for (y in 1..img.h) {
            var rs = 0.0
            var rq = 0.0
            for (x in 1..img.w) {
                val v = img.d[(y - 1) * img.w + x - 1].toDouble()
                rs += v
                rq += v * v
                sum[y * iw + x] = sum[(y - 1) * iw + x] + rs
                sq[y * iw + x] = sq[(y - 1) * iw + x] + rq
            }
        }
        var best = -2.0
        var bx = 0
        var by = 0
        for (y in 0..img.h - th) for (x in 0..img.w - tw) {
            val a = (y + th) * iw + x + tw
            val b = y * iw + x + tw
            val c = (y + th) * iw + x
            val d = y * iw + x
            val s = sum[a] - sum[b] - sum[c] + sum[d]
            val q = sq[a] - sq[b] - sq[c] + sq[d]
            val v = q - s * s / n
            if (v < 1e-3) continue
            var num = 0.0
            for (j in 0 until th) {
                val ro = (y + j) * img.w + x
                val to = j * tw
                for (i in 0 until tw) num += img.d[ro + i] * tz[to + i]
            }
            val sc = num / (sqrt(v) * tn)
            if (sc > best) {
                best = sc; bx = x; by = y
            }
        }
        return if (best < -1) null else Triple(bx, by, best)
    }

    /** Hệ số thu nhỏ cho bước tìm thô: mẫu ~10px, và giới hạn khối lượng tính. */
    private fun coarseFactor(rw: Int, rh: Int, tw: Int, th: Int): Int {
        val tmin = min(tw, th)
        var s = max(1, tmin / 10)
        while (tmin / (s + 1) >= 4 &&
            (rw / s).toLong() * (rh / s) * ((tw / s) * (th / s)) > 80_000_000L
        ) s++
        return s
    }

    private fun scaled(tpl: Bitmap, w: Int, h: Int): Bitmap =
        if (w == tpl.width && h == tpl.height) tpl else Bitmap.createScaledBitmap(tpl, w, h, true)

    /**
     * Tìm [tpl] trong [region], chấp nhận ảnh trong vùng quét có tỉ lệ khác ảnh mẫu.
     * 1) Thô: thử nhiều tỉ lệ (0.4x..2.5x) ở độ phân giải thấp, chọn tỉ lệ có điểm cao nhất.
     * 2) Tinh: quanh vị trí đó, thử thêm tỉ lệ lân cận (±6%) ở độ phân giải cao.
     */
    fun find(region: Bitmap, tpl: Bitmap, threshold: Double): Hit? {
        val rpx = Px(region)
        val cache = HashMap<Int, G>() // ảnh xám của vùng quét theo hệ số thu nhỏ

        var bestK = 0.0
        var bestScore = -2.0
        var bx = 0
        var by = 0
        var bs = 1
        var bsw = 0
        var bsh = 0
        for (k in SCALES) {
            val sw = (tpl.width * k).roundToInt()
            val sh = (tpl.height * k).roundToInt()
            if (sw < 4 || sh < 4 || sw > region.width || sh > region.height) continue
            val st = scaled(tpl, sw, sh)
            val s = coarseFactor(region.width, region.height, sw, sh)
            val rg = cache.getOrPut(s) { gray(rpx, s) }
            val c = search(rg, gray(st, s))
            if (st !== tpl) st.recycle()
            if (c == null) continue
            if (c.third > bestScore) {
                bestScore = c.third; bestK = k
                bx = c.first; by = c.second; bs = s; bsw = sw; bsh = sh
            }
            if (bestScore >= max(0.9, threshold)) break // đã đủ tốt, khỏi thử tiếp
        }
        if (bestK == 0.0 || bestScore < threshold - 0.2) return null

        // Tâm của vị trí tìm thô (toạ độ vùng quét)
        val cx = bx * bs + bsw / 2
        val cy = by * bs + bsh / 2

        var out: Hit? = null
        var outScore = -2.0
        for (d in doubleArrayOf(1.0, 0.94, 1.06)) {
            val k = bestK * d
            val sw = (tpl.width * k).roundToInt()
            val sh = (tpl.height * k).roundToInt()
            if (sw < 4 || sh < 4 || sw > region.width || sh > region.height) continue
            val st = scaled(tpl, sw, sh)

            val rw = min(region.width, sw + 4 * bs)
            val rh = min(region.height, sh + 4 * bs)
            val rx = (cx - sw / 2 - 2 * bs).coerceIn(0, region.width - rw)
            val ry = (cy - sh / 2 - 2 * bs).coerceIn(0, region.height - rh)
            val sub = Bitmap.createBitmap(region, rx, ry, rw, rh)

            // Mẫu lớn thì tinh ở độ phân giải vừa phải để không quá nặng
            var fs = 1
            while (fs < bs &&
                ((rw - sw) / fs + 1).toLong() * ((rh - sh) / fs + 1) * ((sw / fs) * (sh / fs)) > 40_000_000L
            ) fs++

            val f = search(gray(sub, fs), gray(st, fs))
            if (st !== tpl) st.recycle()
            if (f == null) continue
            if (f.third > outScore) {
                outScore = f.third
                out = Hit(rx + f.first * fs + sw / 2, ry + f.second * fs + sh / 2, f.third, k)
            }
        }
        return if (out != null && outScore >= threshold) out else null
    }
}
