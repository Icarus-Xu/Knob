package cn.icarus.knob.util

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局日志器：因为没有 adb，日志直接显示在 App 内的滚动 TextView 窗口里。
 * - 线程安全：任意线程调用 append，回调到主线程刷新 UI
 * - 保留最近 MAX_LINES 行，避免内存无限增长
 *
 * 防泄漏：用 WeakReference 持有 TextView，避免单例在 Activity 销毁后仍强持有其 View，
 * 导致 Activity 及其 View 树无法被 GC（Activity 重建/退出时旧 View 会被自动回收）。
 */
object LogSink {
    private const val MAX_LINES = 500
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI 绑定：弱引用持有，Activity 销毁后不阻止回收
    private var tvRef: WeakReference<TextView> = WeakReference(null)
    private val lines = ArrayDeque<String>()

    fun bind(textView: TextView) {
        tvRef = WeakReference(textView)
        // 绑定后立刻回显已有日志
        val sb = StringBuilder()
        synchronized(lines) { lines.forEach { sb.append(it).append("\n") } }
        textView.text = sb.toString()
    }

    fun clear() {
        synchronized(lines) { lines.clear() }
        mainHandler.post { tvRef.get()?.text = "" }
    }

    /** 追加一行日志 */
    fun append(msg: String) {
        val line = time() + "  " + msg
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            val snapshot = ArrayList(lines)
            mainHandler.post {
                val textView = tvRef.get() ?: return@post
                textView.text = snapshot.joinToString("\n")
                // 滚动到底部：偏移量 = 文本总高 - 可视高。
                // 直接 scrollTo(layout.height) 会把整段文字顶出可视区，日志区看起来是空白的。
                textView.post {
                    val layout = textView.layout ?: return@post
                    val visible = textView.height - textView.paddingTop - textView.paddingBottom
                    val dy = layout.height - visible
                    textView.scrollTo(0, if (dy > 0) dy else 0)
                }
            }
        }
    }

    /** 分隔线 */
    fun section(title: String) {
        append("========== $title ==========")
    }

    private fun time(): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date())
    }
}
