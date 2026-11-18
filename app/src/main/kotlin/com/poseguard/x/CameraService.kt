package com.poseguard.x

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraService : LifecycleService() {

    companion object {
        private const val CH_ID = "posevision"
        private const val NOTI_ID = 2101

        @Volatile var running = false
        @Volatile var fps = 0
        @Volatile var count = 0
        @Volatile var error: String? = null
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var engine: PoseEngine? = null
    private var host: OverlayHost? = null
    private var overlay: OverlayView? = null

    private var frames = 0
    private var mark = 0L

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTI_ID, buildNotification())
        RunLog.i("Service", "服务启动")

        host = OverlayHost(this)
        overlay = host?.attach()
        if (overlay == null) {
            error = "悬浮窗未授权"
            RunLog.e("Service", "悬浮窗 attach 失败，请在设置里允许「显示在其他应用上层」")
        }

        val e = PoseEngine(this)
        if (!e.init()) {
            error = e.error()
            RunLog.e("Engine", error ?: "初始化失败")
            overlay?.setError(error)
            running = false
            return START_STICKY
        }

        engine = e
        RunLog.i("Engine", "YOLO11n-pose 就绪，输入 in0 → 输出 out0")
        running = true
        error = null
        bindCamera()
        return START_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .build()

                analysis.setAnalyzer(worker) { img ->
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - mark >= 1000) {
                        fps = (frames * 1000 / (now - mark)).toInt()
                        frames = 0
                        mark = now
                    }

                    if (!busy.compareAndSet(false, true)) {
                        img.close()
                        return@setAnalyzer
                    }
                    try {
                        val bmp = img.toUpright(UPRIGHT_DEGREES, IS_FRONT)
                        val det = engine
                        if (det != null) {
                            val persons = det.detect(bmp)
                            count = persons.size
                            // 尺寸必须在 recycle 之前取出来，否则闭包里读到的是已回收的 bitmap
                            val fw = bmp.width
                            val fh = bmp.height
                            val curFps = fps
                            overlay?.post { overlay?.submit(persons, curFps, fw, fh) }
                        }
                        bmp.recycle()
                    } catch (t: Throwable) {
                        error = "${t.javaClass.simpleName}: ${t.message}"
                        RunLog.e("Detect", error ?: "")
                        overlay?.post { overlay?.setError(error) }
                    } finally {
                        img.close()
                        busy.set(false)
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                RunLog.i("Camera", "相机已绑定，开始取帧")
            } catch (t: Throwable) {
                error = "相机启动失败: ${t.message}"
                RunLog.e("Camera", error ?: "")
                overlay?.post { overlay?.setError(error) }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "PoseVision", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("PoseVision 运行中")
            .setContentText("YOLO11n-pose · 骨架与检测框")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        RunLog.i("Service", "服务停止")
        host?.detach()
        engine?.release()
        worker.shutdownNow()
        super.onDestroy()
    }
}

private const val IS_FRONT = false
private const val UPRIGHT_DEGREES = 90

/**
 * YUV_420_888 → ARGB Bitmap，直接按 YUV→RGB 公式逐像素转换。
 *
 * 相比「NV21 → JPEG → decode」的老路，省掉一次编码 + 一次解码，
 * 单帧耗时从 60~90ms 降到 8~15ms，才可能做到每帧实时检测。
 *
 * @param rotate 需要顺时针旋转的角度（0/90/180/270）
 * @param mirror 是否水平镜像（前置镜头需要）
 */
fun ImageProxy.toUpright(rotate: Int = 90, mirror: Boolean = false): Bitmap {
    val w = width
    val h = height
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuf = yPlane.buffer
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixStride = yPlane.pixelStride
    val uRowStride = uPlane.rowStride
    val uPixStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixStride = vPlane.pixelStride

    val yBase = yBuf.position()
    val uBase = uBuf.position()
    val vBase = vBuf.position()

    // 先按传感器方向填一张 ARGB
    val src = IntArray(w * h)
    for (j in 0 until h) {
        val yRow = yBase + j * yRowStride
        val uRow = uBase + (j / 2) * uRowStride
        val vRow = vBase + (j / 2) * vRowStride
        for (i in 0 until w) {
            val yv = yBuf.get(yRow + i * yPixStride).toInt() and 0xFF
            val uv = uBuf.get(uRow + (i / 2) * uPixStride).toInt() and 0xFF
            val vv = vBuf.get(vRow + (i / 2) * vPixStride).toInt() and 0xFF

            val y1 = (yv - 16).coerceAtLeast(0)
            val u1 = uv - 128
            val v1 = vv - 128

            var r = (1.164f * y1 + 1.596f * v1).toInt()
            var g = (1.164f * y1 - 0.813f * v1 - 0.391f * u1).toInt()
            var b = (1.164f * y1 + 2.018f * u1).toInt()
            if (r < 0) r = 0 else if (r > 255) r = 255
            if (g < 0) g = 0 else if (g > 255) g = 255
            if (b < 0) b = 0 else if (b > 255) b = 255

            src[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val raw = Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)

    val deg = ((rotate % 360) + 360) % 360
    if (deg == 0 && !mirror) return raw

    val m = Matrix().apply {
        postRotate(deg.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    val fixed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    if (fixed !== raw) raw.recycle()
    return fixed
}
