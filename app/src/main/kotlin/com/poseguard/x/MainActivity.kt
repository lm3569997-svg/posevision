package com.poseguard.x

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val logListener: (List<String>) -> Unit = { lines ->
        runOnUiThread {
            val tail = if (lines.size > 14) lines.takeLast(14) else lines
            tvLog.text = if (tail.isEmpty()) "等待启动…" else tail.joinToString("\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnPerm).setOnClickListener {
            requestPerms()
            RunLog.i("Perm", "已发起权限申请")
        }

        findViewById<Button>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startService()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CameraService::class.java))
            RunLog.i("Main", "请求停止服务")
            tvStatus.postDelayed({ refresh() }, 400)
        }

        refresh()
    }

    private fun startService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            RunLog.w("Main", "悬浮窗未授权，跳转设置")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            RunLog.w("Main", "相机未授权，先申请权限")
            requestPerms()
            return
        }
        RunLog.i("Main", "启动前台服务")
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        tvStatus.postDelayed({ refresh() }, 1500)
    }

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onResume() {
        super.onResume()
        RunLog.listener = logListener
        val tail = RunLog.snapshot().takeLast(14)
        tvLog.text = if (tail.isEmpty()) "等待启动…" else tail.joinToString("\n")
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (RunLog.listener === logListener) RunLog.listener = null
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        val sb = StringBuilder()
        sb.append("状态  ").append(if (CameraService.running) "● 运行中" else "○ 已停止").append('\n')
        sb.append("悬浮窗 ").append(if (ov) "✓ 已授权" else "✗ 未授权").append('\n')
        sb.append("相机  ").append(if (cam) "✓ 已授权" else "✗ 未授权").append('\n')
        sb.append("FPS   ").append(CameraService.fps).append('\n')
        sb.append("目标  ").append(CameraService.count)
        CameraService.error?.let { sb.append("\n错误  ").append(it) }
        tvStatus.text = sb.toString()
    }
}
