package com.poseguard.x

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：内存环形缓冲 + 通知界面刷新。
 * 界面上的 LogView 和日志页共用这一份。
 */
object RunLog {

    private const val MAX = 400

    private val buf = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((List<String>) -> Unit)? = null

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())}  $level/$tag  $msg"
        synchronized(buf) {
            if (buf.size >= MAX) buf.removeFirst()
            buf.addLast(line)
        }
        main.post { listener?.invoke(snapshot()) }
    }

    fun snapshot(): List<String> = synchronized(buf) { buf.toList() }

    fun clear() {
        synchronized(buf) { buf.clear() }
        main.post { listener?.invoke(emptyList()) }
    }
}
