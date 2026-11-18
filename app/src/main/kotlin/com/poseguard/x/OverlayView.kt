package com.poseguard.x

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮层：给每个人画一个「角标方框 + 完整骨架」。
 *
 * 防闪的三层手段：
 * 1) 只在有新结果时替换数据，不每帧清空；
 * 2) 框和点带有平滑（新旧结果按距离插值），不会一跳一跳；
 * 3) 目标短暂丢失时保留上一次画面若干帧，避免框瞬间消失又出现。
 */
class OverlayView(ctx: Context) : View(ctx) {

    companion object {
        /** 丢失后保留多少毫秒再清除 */
        private const val HOLD_MS = 260L
        /** 平滑系数，越小越稳但越滞后 */
        private const val SMOOTH = 0.45f
    }

    private data class Track(
        val id: Int,
        var kps: List<android.graphics.PointF>,
        var conf: FloatArray,
        var score: Float,
        var box: RectF,
        var lastSeen: Long
    )

    @Volatile private var tracks: List<Track> = emptyList()
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile private var fps = 0
    @Volatile private var msg: String? = null
    @Volatile private var showMsg = false
    private var lastDiag = 0L

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.SQUARE
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.WHITE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val hudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99000000.toInt()
        style = Paint.Style.FILL
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x44FFFFFF
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }

    private val beat = object : Runnable {
        override fun run() {
            tick()
            postDelayed(this, 120L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(beat, 120L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(beat)
        super.onDetachedFromWindow()
    }

    /** 有新结果就更新，旧数据平滑过渡 */
    fun submit(persons: List<Person>, fpsNow: Int, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        srcW = w
        srcH = h
        fps = fpsNow

        val old = tracks
        val next = ArrayList<Track>(persons.size)

        persons.forEach { p ->
            val prev = old.firstOrNull { it.id == p.id }
            val kps = ArrayList<android.graphics.PointF>(p.kps.size)
            for (i in p.kps.indices) {
                val nw = p.kps[i]
                if (prev != null && i < prev.kps.size) {
                    val ov = prev.kps[i]
                    kps.add(
                        android.graphics.PointF(
                            ov.x + (nw.x - ov.x) * SMOOTH,
                            ov.y + (nw.y - ov.y) * SMOOTH
                        )
                    )
                } else {
                    kps.add(android.graphics.PointF(nw.x, nw.y))
                }
            }

            val nb = RectF(p.box[0], p.box[1], p.box[2], p.box[3])
            if (prev != null) {
                nb.set(
                    prev.box.left + (nb.left - prev.box.left) * SMOOTH,
                    prev.box.top + (nb.top - prev.box.top) * SMOOTH,
                    prev.box.right + (nb.right - prev.box.right) * SMOOTH,
                    prev.box.bottom + (nb.bottom - prev.box.bottom) * SMOOTH
                )
            }

            next.add(Track(p.id, kps, p.kpConf, p.score, nb, now))
        }

        // 保留刚丢失、还在保持期内的旧目标，避免闪
        old.forEach { t ->
            if (next.none { it.id == t.id } && now - t.lastSeen < HOLD_MS) {
                next.add(t)
            }
        }

        tracks = next
        // 每 2 秒打一次几何，便于诊断框是否在屏幕内
        if (next.isNotEmpty() && now - lastDiag > 2000) {
            lastDiag = now
            val t0 = next[0]
            val sb = StringBuilder()
            sb.append("帧 ${w}x${h} 屏幕 ${width}x${height} ")
            sb.append("box=[${t0.box.left.toInt()},${t0.box.top.toInt()},")
            sb.append("${t0.box.right.toInt()},${t0.box.bottom.toInt()}] ")
            sb.append("kps=${t0.kps.size} 人数=${next.size}")
            RunLog.i("Overlay", sb.toString())
        }
        invalidate()
    }

    fun setError(t: String?) {
        msg = t
        showMsg = t != null
        invalidate()
    }

    fun tick() {
        val now = System.currentTimeMillis()
        if (tracks.any { now - it.lastSeen > HOLD_MS }) {
            tracks = tracks.filter { now - it.lastSeen <= HOLD_MS }
        }
        invalidate()
    }

    /**
     * FILL_CENTER：画面铺满屏幕，超出部分裁掉，居中。
     * 返回 (缩放比, 偏移X, 偏移Y)，绘制时 x*scale+offX。
     */
    private fun fit(): FloatArray {
        if (srcW <= 0 || srcH <= 0) return floatArrayOf(1f, 0f, 0f)
        val s = max(width.toFloat() / srcW, height.toFloat() / srcH)
        val offX = (width - srcW * s) / 2f
        val offY = (height - srcH * s) / 2f
        return floatArrayOf(s, offX, offY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = fit()
        val s = f[0]
        val offX = f[1]
        val offY = f[2]
        val list = tracks
        val now = System.currentTimeMillis()

        list.forEach { t ->
            val color = Kps.COLORS[t.id % Kps.COLORS.size]
            // 过期目标淡出
            val age = now - t.lastSeen
            val alpha = if (age > HOLD_MS) 0 else (255 - (age * 255 / HOLD_MS).toInt()).coerceIn(60, 255)

            val r = RectF(
                t.box.left * s + offX, t.box.top * s + offY,
                t.box.right * s + offX, t.box.bottom * s + offY
            )

            // 框：细线 + 四角加粗
            boxPaint.color = color
            boxPaint.alpha = alpha / 2
            canvas.drawRect(r, boxPaint)

            cornerPaint.color = color
            cornerPaint.alpha = alpha
            drawCorners(canvas, r)

            // 骨架
            bonePaint.color = color
            bonePaint.alpha = alpha
            for (b in Kps.BONES) {
                val a = b[0]; val c = b[1]
                if (a >= t.kps.size || c >= t.kps.size) continue
                if (t.conf[a] < Kps.THRESH || t.conf[c] < Kps.THRESH) continue
                val pa = t.kps[a]
                val pc = t.kps[c]
                canvas.drawLine(pa.x * s + offX, pa.y * s + offY,
                                pc.x * s + offX, pc.y * s + offY, bonePaint)
            }

            // 关节点
            jointPaint.color = color
            jointPaint.alpha = alpha
            for (i in t.kps.indices) {
                if (t.conf[i] < Kps.THRESH) continue
                val k = t.kps[i]
                val cx = k.x * s + offX
                val cy = k.y * s + offY
                canvas.drawCircle(cx, cy, 8f, haloPaint)
                canvas.drawCircle(cx, cy, 5f, jointPaint)
            }

            // 左上角标签
            val label = "#${t.id}  ${(t.score * 100).toInt()}%"
            val tw = textPaint.measureText(label)
            val lx = r.left
            val ly = max(r.top - 42f, 4f)
            labelBg.color = color
            labelBg.alpha = (alpha * 0.85f).toInt()
            canvas.drawRoundRect(lx, ly, lx + tw + 18f, ly + 34f, 6f, 6f, labelBg)
            textPaint.alpha = alpha
            canvas.drawText(label, lx + 9f, ly + 25f, textPaint)
        }

        // HUD
        textPaint.alpha = 255
        textPaint.color = Color.WHITE
        val hud = "人数 ${list.size}    FPS $fps"
        canvas.drawRoundRect(18f, 18f, 18f + textPaint.measureText(hud) + 26f, 78f, 10f, 10f, hudBg)
        canvas.drawText(hud, 31f, 57f, textPaint)

        if (showMsg) {
            msg?.let {
                textPaint.color = 0xFFFF6E6E.toInt()
                canvas.drawRoundRect(18f, 90f, 18f + textPaint.measureText(it) + 26f, 150f, 10f, 10f, hudBg)
                canvas.drawText(it, 31f, 129f, textPaint)
            }
        }
    }

    private fun drawCorners(canvas: Canvas, r: RectF) {
        val len = min(r.width(), r.height()) * 0.22f
        val l = max(len, 18f)
        // 左上
        canvas.drawLine(r.left, r.top, r.left + l, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + l, cornerPaint)
        // 右上
        canvas.drawLine(r.right, r.top, r.right - l, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + l, cornerPaint)
        // 左下
        canvas.drawLine(r.left, r.bottom, r.left + l, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left, r.bottom - l, cornerPaint)
        // 右下
        canvas.drawLine(r.right, r.bottom, r.right - l, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - l, cornerPaint)
    }
}

class OverlayHost(private val ctx: Context) {
    private var view: OverlayView? = null

    fun attach(): OverlayView? {
        view?.let { if (it.isAttachedToWindow) return it }
        return try {
            val v = OverlayView(ctx)
            val dm = ctx.resources.displayMetrics
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val lp = WindowManager.LayoutParams(
                dm.widthPixels, dm.heightPixels, type, flags, PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START

            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            view = v
            v
        } catch (t: Throwable) {
            RunLog.e("Overlay", "attach 失败: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    fun detach() {
        view?.let {
            runCatching {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        view = null
    }
}
