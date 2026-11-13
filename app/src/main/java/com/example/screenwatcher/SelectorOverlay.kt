package com.example.screenwatcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lớp phủ toàn màn hình để người dùng kéo chọn vùng (nhiều vùng) / ảnh mẫu / điểm chạm
 * ngay trên màn hình thật của ứng dụng khác.
 *
 * mode: "REGIONS" (nhiều vùng) | "TEMPLATE" (1 vùng -> cắt làm ảnh mẫu) | "POINT" (1 điểm chạm)
 *       | "SWIPE" (kéo từ điểm đầu tới điểm cuối của cú vuốt)
 */
class SelectorOverlay(
    private val ctx: Context,
    private val mode: String,
    private val onFinish: (List<Region>, Point?, Pair<Point, Point>?) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rects = mutableListOf<Region>()
    private var cur: Region? = null
    private var point: Point? = null
    private var sw1: Point? = null
    private var sw2: Point? = null
    private var drawing = true

    private val dv = DrawView(ctx)
    private val bar = LinearLayout(ctx)

    private val dvLp = params(
        LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 0,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    private val barLp = params(
        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
    ).apply { y = 120 }

    private fun params(w: Int, h: Int, g: Int, flags: Int) = LayoutParams(
        w, h, LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = g
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    fun show() {
        val hint = TextView(ctx).apply {
            text = when (mode) {
                "REGIONS" -> "Kéo để chọn vùng (có thể chọn nhiều vùng)"
                "TEMPLATE" -> "Kéo để khoanh đúng hình cần nhận diện"
                "SWIPE" -> "Kéo từ điểm bắt đầu đến điểm kết thúc của cú vuốt"
                else -> "Chạm vào vị trí cần tự động chạm"
            }
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
        }
        val toggle = Button(ctx)
        fun mk(t: String, f: () -> Unit) = Button(ctx).apply {
            text = t; isAllCaps = false; setOnClickListener { f() }
        }
        toggle.apply {
            text = "✋ Thao tác"; isAllCaps = false
            setOnClickListener {
                drawing = !drawing
                dvLp.flags = if (drawing) dvLp.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else dvLp.flags or LayoutParams.FLAG_NOT_TOUCHABLE
                text = if (drawing) "✋ Thao tác" else "✏ Vẽ"
                runCatching { wm.updateViewLayout(dv, dvLp) }
                toast(if (drawing) "Đang ở chế độ vẽ" else "Có thể chuyển sang ứng dụng/màn hình khác, xong bấm ✏ Vẽ")
            }
        }
        row.addView(mk("✔ Xong") { done() })
        row.addView(toggle)
        row.addView(mk("⌫ Xoá") { rects.clear(); cur = null; point = null; sw1 = null; sw2 = null; dv.invalidate() })
        row.addView(mk("✖ Huỷ") { hide(); onCancel() })
        bar.orientation = LinearLayout.VERTICAL
        bar.addView(hint)
        bar.addView(row)

        wm.addView(dv, dvLp)
        wm.addView(bar, barLp)
    }

    fun hide() {
        runCatching { wm.removeView(dv) }
        runCatching { wm.removeView(bar) }
    }

    private fun done() {
        val ok = when (mode) {
            "POINT" -> point != null
            "SWIPE" -> sw1 != null && sw2 != null
            else -> rects.isNotEmpty()
        }
        if (!ok) {
            toast("Chưa chọn gì cả"); return
        }
        hide()
        val a = sw1
        val b = sw2
        onFinish(rects.toList(), point, if (a != null && b != null) a to b else null)
    }

    private inner class DrawView(c: Context) : View(c) {
        private val fill = Paint().apply { color = 0x3300AAFF; style = Paint.Style.FILL }
        private val stroke = Paint().apply { color = 0xFF00AAFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val solid = Paint().apply { color = 0xFF00AAFF.toInt(); style = Paint.Style.FILL }
        private val txt = Paint().apply { color = Color.WHITE; textSize = 40f; isFakeBoldText = true }
        private val loc = IntArray(2)
        private var sx = 0
        private var sy = 0

        override fun onDraw(c: Canvas) {
            getLocationOnScreen(loc)
            c.drawColor(0x44000000)
            c.save()
            c.translate(-loc[0].toFloat(), -loc[1].toFloat())
            (rects + listOfNotNull(cur)).forEachIndexed { i, r ->
                val rf = RectF(r.l.toFloat(), r.t.toFloat(), r.r.toFloat(), r.b.toFloat())
                c.drawRect(rf, fill)
                c.drawRect(rf, stroke)
                c.drawText("${i + 1}", r.l + 10f, r.t + 44f, txt)
            }
            point?.let {
                c.drawCircle(it.x.toFloat(), it.y.toFloat(), 36f, stroke)
                c.drawLine(it.x - 60f, it.y.toFloat(), it.x + 60f, it.y.toFloat(), stroke)
                c.drawLine(it.x.toFloat(), it.y - 60f, it.x.toFloat(), it.y + 60f, stroke)
            }
            val a = sw1
            val b = sw2
            if (a != null && b != null) {
                c.drawLine(a.x.toFloat(), a.y.toFloat(), b.x.toFloat(), b.y.toFloat(), stroke)
                c.drawCircle(a.x.toFloat(), a.y.toFloat(), 30f, stroke)
                c.drawCircle(b.x.toFloat(), b.y.toFloat(), 14f, solid)
                val ang = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                for (d in doubleArrayOf(2.6, -2.6)) {
                    c.drawLine(
                        b.x.toFloat(), b.y.toFloat(),
                        b.x + (60 * cos(ang + d)).toFloat(), b.y + (60 * sin(ang + d)).toFloat(), stroke,
                    )
                }
            }
            c.restore()
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.rawX.toInt()
            val y = e.rawY.toInt()
            when (mode) {
                "POINT" -> if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE ||
                    e.action == MotionEvent.ACTION_UP
                ) point = Point(x, y)
                "SWIPE" -> when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sw1 = Point(x, y); sw2 = null }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                        sw2 = Point(x, y)
                        val a = sw1
                        if (e.action == MotionEvent.ACTION_UP && a != null && hypot((x - a.x).toDouble(), (y - a.y).toDouble()) < 30) {
                            sw2 = null // quá ngắn, coi như chưa vuốt
                        }
                    }
                }
                else -> when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = x; sy = y; cur = null }
                    MotionEvent.ACTION_MOVE -> cur = Region(min(sx, x), min(sy, y), max(sx, x), max(sy, y))
                    MotionEvent.ACTION_UP -> {
                        cur?.let {
                            if (it.w > 20 && it.h > 20) {
                                if (mode == "TEMPLATE") rects.clear()
                                rects.add(it)
                            }
                        }
                        cur = null
                    }
                }
            }
            invalidate()
            return true
        }
    }
}
