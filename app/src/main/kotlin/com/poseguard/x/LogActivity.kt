package com.poseguard.x

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView

    private val listener: (List<String>) -> Unit = { lines ->
        runOnUiThread { render(lines) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tv = findViewById(R.id.tvFull)
        scroll = findViewById(R.id.scroll)

        findViewById<Button>(R.id.btnClear).setOnClickListener { RunLog.clear() }

        render(RunLog.snapshot())
    }

    override fun onResume() {
        super.onResume()
        RunLog.listener = listener
        render(RunLog.snapshot())
    }

    override fun onPause() {
        super.onPause()
        if (RunLog.listener === listener) RunLog.listener = null
    }

    private fun render(lines: List<String>) {
        tv.text = if (lines.isEmpty()) "暂无日志" else lines.joinToString("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
