package com.poseguard.x

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Surface
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * App 内独立相机页：
 *  - 用 CameraX PreviewView 显示自己的实时画面（不是系统相机）
 *  - 分析帧跑 YOLO11n-pose，把框和骨架画在一个透明叠层上
 *  - 叠层与预览共用 FILL_CENTER 的裁切规则，所以坐标天然对齐
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetBoxView
    private lateinit var tvHud: TextView
    private lateinit var btnClose: ImageButton

    private val worker = Executors.newSingleThreadExecutor()
    private var engine: PoseEngine? = null
    private val providerRef = arrayOfNulls<ProcessCameraProvider>(1)

    @Volatile private var busy = false
    private var usingFront = false
    private var frames = 0
    private var mark = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        tvHud = findViewById(R.id.tvHud)
        btnClose = findViewById(R.id.btnClose)

        btnClose.setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnFlip).setOnClickListener {
            usingFront = !usingFront
            RunLog.i("Camera", "切换镜头: " + if (usingFront) "前置" else "后置")
            startCamera()
        }

        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 200)
        } else {
            boot()
        }

        // 扫描线动画，让界面有「在跑」的观感
        startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) boot()
            else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun boot() {
        val e = PoseEngine(this)
        if (!e.init()) {
            RunLog.e("Engine", e.error() ?: "初始化失败")
            tvHud.text = e.error() ?: "模型加载失败"
            return
        }
        engine = e
        RunLog.i("Engine", "YOLO11n-pose 就绪")
        startCamera()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                providerRef[0] = provider

                val preview = Preview.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()

                analysis.setAnalyzer(worker) { img ->
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - mark >= 1000) {
                        val f = (frames * 1000 / (now - mark)).toInt()
                        frames = 0
                        mark = now
                        overlay.setFps(f)
                    }

                    if (busy) {
                        img.close()
                        return@setAnalyzer
                    }
                    busy = true
                    try {
                        val det = engine ?: return@setAnalyzer
                        val bmp = img.toUpright(usingFront)
                        val persons = det.detect(bmp)
                        val w = bmp.width
                        val h = bmp.height
                        bmp.recycle()
                        runOnUiThread {
                            overlay.setData(persons, w, h)
                            tvHud.text = "人数 ${persons.size}   FPS ${overlay.fps}"
                        }
                    } catch (t: Throwable) {
                        RunLog.e("Detect", "${t.javaClass.simpleName} ${t.message}")
                    } finally {
                        img.close()
                        busy = false
                    }
                }

                val selector = if (usingFront)
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                RunLog.i("Camera", "预览与推理已绑定")
            } catch (t: Throwable) {
                RunLog.e("Camera", "启动失败: ${t.message}")
                tvHud.text = "相机启动失败"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private var scanAnim: ValueAnimator? = null

    private fun startScan() {
        scanAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { overlay.scanProgress = it.animatedValue as Float }
            start()
        }
    }

    override fun onDestroy() {
        scanAnim?.cancel()
        providerRef[0]?.unbindAll()
        engine?.release()
        worker.shutdownNow()
        super.onDestroy()
    }
}

/**
 * 叠层：检测框 + 骨架 + 扫描线 + 角标。
 * 把分析帧尺寸映射到 View 尺寸，用与 FILL_CENTER 相同的 cover 规则。
 */
class DetBoxView(ctx: android.content.Context) : View(ctx) {

    @Volatile private var persons: List<Person> = emptyList()
    @Volatile private var srcW = 0
    @Volatile private var srcH = 0
    @Volatile var fps = 0
        private set
    @Volatile var scanProgress = 0f

    private var animT = 0L

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.SQUARE
    }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xCCFFFFFF.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = Color.BLACK
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0x6600E5FF
    }

    fun setData(list: List<Person>, w: Int, h: Int) {
        persons = list
        srcW = w
        srcH = h
        invalidate()
    }

    fun setFps(f: Int) {
        fps = f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return

        // 扫描线
        val y = scanProgress * vh
        canvas.drawLine(0f, y, vw, y, scanPaint)
        canvas.drawLine(0f, y - 14f, vw, y - 14f, scanPaint)

        if (srcW <= 0 || srcH <= 0) {
            invalidate()
            return
        }

        // FILL_CENTER：取 cover 缩放，并把画面居中
        val scale = max(vw / srcW, vh / srcH)
        val dx = (vw - srcW * scale) / 2f
        val dy = (vh - srcH * scale) / 2f

        persons.forEach { p ->
            val color = Kps.COLORS[p.id % Kps.COLORS.size]

            val l = p.box[0] * scale + dx
            val t = p.box[1] * scale + dy
            val r = p.box[2] * scale + dx
            val b = p.box[3] * scale + dy
            val rect = RectF(l, t, r, b)

            // 细框
            boxPaint.color = color
            boxPaint.alpha = 150
            canvas.drawRect(rect, boxPaint)

            // 四角
            cornerPaint.color = color
            drawCorners(canvas, rect)

            // 骨架
            bonePaint.color = color
            for (bone in Kps.BONES) {
                val a = bone[0]
                val c = bone[1]
                if (!p.ok(a) || !p.ok(c)) continue
                val pa = p.kps[a]
                val pc = p.kps[c]
                canvas.drawLine(
                    pa.x * scale + dx, pa.y * scale + dy,
                    pc.x * scale + dx, pc.y * scale + dy,
                    bonePaint
                )
            }

            // 关节点：外白圈 + 内彩色点
            for (i in p.kps.indices) {
                if (!p.ok(i)) continue
                val k = p.kps[i]
                val cx = k.x * scale + dx
                val cy = k.y * scale + dy
                canvas.drawCircle(cx, cy, 7f, ringPaint)
                jointPaint.color = color
                canvas.drawCircle(cx, cy, 4.5f, jointPaint)
            }

            // 标签：彩色底 + 黑字，对比最强
            val text = "#${p.id} ${(p.score * 100).toInt()}%"
            val tw = textPaint.measureText(text)
            val lx = l
            val ly = max(t - 40f, 0f)
            labelPaint.color = color
            canvas.drawRoundRect(lx, ly, lx + tw + 16f, ly + 34f, 7f, 7f, labelPaint)
            canvas.drawText(text, lx + 8f, ly + 25f, textPaint)
        }

        invalidate()
    }

    private fun drawCorners(canvas: Canvas, r: RectF) {
        val len = max(min(r.width(), r.height()) * 0.2f, 22f)
        canvas.drawLine(r.left, r.top, r.left + len, r.top, cornerPaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + len, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right - len, r.top, cornerPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, cornerPaint)
        canvas.drawLine(r.left, r.bottom, r.left, r.bottom - len, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right - len, r.bottom, cornerPaint)
        canvas.drawLine(r.right, r.bottom, r.right, r.bottom - len, cornerPaint)
    }
}
