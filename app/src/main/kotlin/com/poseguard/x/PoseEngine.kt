package com.poseguard.x

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO11n-pose / ncnn 推理封装。
 * in0 [1,3,640,640] → out0 [56,8400]，通道优先。
 */
class PoseEngine(private val ctx: Context) {

    companion object {
        const val IN_W = 640
        const val IN_H = 640
        const val ANCHORS = 8400
        const val CHANNEL = 56

        const val CONF = 0.30f
        const val NMS_IOU = 0.45f

        private const val PARAM = "yolo11n_pose.ncnn.param"
        private const val BIN = "yolo11n_pose.ncnn.bin"
        private const val IN_NAME = "in0"
        private const val OUT_NAME = "out0"

        init {
            System.loadLibrary("ncnn")
            System.loadLibrary("poseguard")
        }
    }

    private var ready = false
    private var loadError: String? = null
    private val buf = FloatArray(CHANNEL * ANCHORS)

    fun init(): Boolean = try {
        val code = nativeInit(ctx.assets, PARAM, BIN)
        if (code != 0) {
            loadError = when (code) {
                -1 -> "AssetManager 无效"
                -2 -> "缺少 $PARAM"
                -3 -> "缺少 $BIN"
                -4 -> "param 解析失败"
                -5 -> "bin 读取失败"
                else -> "初始化失败 code=$code"
            }
            ready = false
            false
        } else {
            loadError = null
            ready = true
            true
        }
    } catch (t: Throwable) {
        loadError = "so 加载失败: ${t.javaClass.simpleName}"
        ready = false
        false
    }

    fun error(): String? = loadError

    fun detect(src: Bitmap): List<Person> {
        if (!ready || src.width <= 0 || src.height <= 0) return emptyList()
        var lb: Bitmap? = null
        try {
            val scale = min(IN_W.toFloat() / src.width, IN_H.toFloat() / src.height)
            val nw = max(1, (src.width * scale).toInt())
            val nh = max(1, (src.height * scale).toInt())
            val padX = (IN_W - nw) / 2
            val padY = (IN_H - nh) / 2

            val canvasBmp = Bitmap.createBitmap(IN_W, IN_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(canvasBmp)
            c.drawColor(Color.BLACK)
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            c.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
            if (scaled !== src) scaled.recycle()
            lb = canvasBmp

            val n = nativeDetect(canvasBmp, buf, IN_NAME, OUT_NAME)
            if (n <= 0) {
                loadError = when (n) {
                    -10 -> "输入名 $IN_NAME 不对"
                    -11 -> "输出名 $OUT_NAME 不对"
                    -12 -> "输出超缓冲"
                    else -> "推理失败 code=$n"
                }
                return emptyList()
            }

            return parse(buf).map { p ->
                Person(
                    id = p.id,
                    kps = p.kps.map {
                        PointF((it.x * IN_W - padX) / scale, (it.y * IN_H - padY) / scale)
                    },
                    kpConf = p.kpConf,
                    score = p.score,
                    box = floatArrayOf(
                        (p.box[0] * IN_W - padX) / scale,
                        (p.box[1] * IN_H - padY) / scale,
                        (p.box[2] * IN_W - padX) / scale,
                        (p.box[3] * IN_H - padY) / scale
                    )
                )
            }
        } catch (t: Throwable) {
            loadError = "推理异常: ${t.javaClass.simpleName} ${t.message}"
            return emptyList()
        } finally {
            lb?.recycle()
        }
    }

    /** out0 [56,8400]：c*8400+i */
    private fun parse(d: FloatArray): List<Person> {
        val raw = ArrayList<Person>(8)
        for (i in 0 until ANCHORS) {
            val s = d[4 * ANCHORS + i]
            if (s < CONF) continue

            val cx = d[i] / IN_W
            val cy = d[ANCHORS + i] / IN_H
            val bw = d[2 * ANCHORS + i] / IN_W
            val bh = d[3 * ANCHORS + i] / IN_H

            val kps = ArrayList<PointF>(17)
            val cf = FloatArray(17)
            for (k in 0 until 17) {
                val b = (5 + k * 3) * ANCHORS + i
                kps.add(PointF(d[b] / IN_W, d[b + ANCHORS] / IN_H))
                cf[k] = d[b + 2 * ANCHORS]
            }
            raw.add(
                Person(
                    0, kps, cf, s,
                    floatArrayOf(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
                )
            )
        }
        return nms(raw).mapIndexed { i, p -> p.copy(id = i) }
    }

    private fun iou(a: Person, b: Person): Float {
        val ix = max(0f, min(a.box[2], b.box[2]) - max(a.box[0], b.box[0]))
        val iy = max(0f, min(a.box[3], b.box[3]) - max(a.box[1], b.box[1]))
        val inter = ix * iy
        if (inter <= 0f) return 0f
        val ua = (a.box[2] - a.box[0]) * (a.box[3] - a.box[1])
        val ub = (b.box[2] - b.box[0]) * (b.box[3] - b.box[1])
        val uni = ua + ub - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    private fun nms(list: List<Person>): List<Person> {
        val s = list.sortedByDescending { it.score }.toMutableList()
        val keep = ArrayList<Person>()
        while (s.isNotEmpty()) {
            val h = s.removeAt(0)
            keep.add(h)
            s.removeAll { iou(h, it) > NMS_IOU }
        }
        return keep
    }

    fun release() {
        if (ready) runCatching { nativeRelease() }
        ready = false
    }

    private external fun nativeInit(
        am: android.content.res.AssetManager, param: String, bin: String
    ): Int

    private external fun nativeDetect(
        bmp: Bitmap, out: FloatArray, inName: String, outName: String
    ): Int

    private external fun nativeRelease()
}
