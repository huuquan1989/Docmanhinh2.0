package com.example.screenwatcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Dịch vụ trợ năng: thực hiện chạm / vuốt / Back / Home thay người dùng. */
class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoClickService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private suspend fun gesture(p: Path, dur: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceIn(1, GestureDescription.getMaxGestureDuration())))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    suspend fun tap(x: Float, y: Float, dur: Long = 60): Boolean =
        gesture(Path().apply { moveTo(x, y) }, dur)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, dur)
}

/**
 * Macro: mỗi dòng một lệnh
 *   tap_match [dx dy] [ms=..]     chạm vào vị trí vừa tìm thấy (có thể lệch dx, dy; ms=.. để giữ)
 *   tap x y [ms]                  chạm toạ độ (mặc định 60 ms)
 *   longtap x y [ms]              chạm và giữ (mặc định 800 ms)
 *   swipe x1 y1 x2 y2 [ms]        vuốt (mặc định 300 ms)
 *   wait ms                       chờ
 *   back | home | recents         phím hệ thống
 *   # ghi chú
 * Các lệnh chạm/giữ/vuốt có thể thêm  times=N  (số lần lặp) và  gap=ms  (nghỉ giữa các lần, mặc định 150).
 *   vd: swipe 540 1600 540 600 300 times=5 gap=400
 */
object Macro {
    private suspend fun repeatAction(times: Int, gap: Long, f: suspend () -> Unit) {
        for (i in 0 until times) {
            if (!AppState.monitoring) return
            f()
            if (i < times - 1) delay(gap)
        }
    }

    /** Hậu tố ghi vào macro cho số lần / nghỉ giữa các lần. */
    fun suffix(times: Int, gap: Long): String = if (times > 1) " times=$times gap=$gap" else ""

    /** Chạy macro của luật, lặp theo cài đặt của luật (repeatCount: 1 = 1 lần, 0 = mãi mãi). */
    suspend fun runRule(rule: Rule, mx: Int, my: Int) {
        var n = 0
        while (AppState.monitoring) {
            run(rule.macro, mx, my)
            n++
            if (rule.repeatCount != 0 && n >= rule.repeatCount) break
            delay(rule.repeatDelayMs.coerceAtLeast(50))
        }
        if (n > 1) AppState.log("↻ '${rule.name}' đã chạy $n lần")
    }

    suspend fun run(script: String, mx: Int, my: Int) {
        val svc = AutoClickService.instance
        if (svc == null) {
            AppState.log("⚠ Chưa bật Dịch vụ trợ năng nên không thể chạm")
            return
        }
        for (raw in script.lines()) {
            if (!AppState.monitoring) return
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val p = line.split(Regex("\\s+"))
            val rest = p.drop(1)
            val opts = rest.filter { it.contains('=') }
                .associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            val a = rest.filter { !it.contains('=') }.mapNotNull { it.toFloatOrNull() }
            val times = (opts["times"]?.toIntOrNull() ?: 1).coerceIn(1, 100_000)
            val gap = (opts["gap"]?.toLongOrNull() ?: 150L).coerceAtLeast(0)
            val ms = opts["ms"]?.toLongOrNull()
            when (p[0].lowercase()) {
                "tap_match" -> repeatAction(times, gap) {
                    svc.tap(mx + (a.getOrNull(0) ?: 0f), my + (a.getOrNull(1) ?: 0f), ms ?: 60L)
                }
                "tap" -> if (a.size >= 2) repeatAction(times, gap) {
                    svc.tap(a[0], a[1], (a.getOrNull(2)?.toLong()) ?: ms ?: 60L)
                }
                "longtap" -> if (a.size >= 2) repeatAction(times, gap) {
                    svc.tap(a[0], a[1], (a.getOrNull(2)?.toLong()) ?: ms ?: 800L)
                }
                "swipe" -> if (a.size >= 4) repeatAction(times, gap) {
                    svc.swipe(a[0], a[1], a[2], a[3], (a.getOrNull(4)?.toLong()) ?: ms ?: 300L)
                }
                "wait" -> delay((a.getOrNull(0) ?: 500f).toLong())
                "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                else -> AppState.log("⚠ Lệnh macro không hợp lệ: $line")
            }
            delay(120)
        }
    }
}
