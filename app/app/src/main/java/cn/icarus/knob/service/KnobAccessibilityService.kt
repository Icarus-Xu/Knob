package cn.icarus.knob.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.icarus.knob.plugin.PluginLoader
import cn.icarus.knob.util.LogSink
import java.util.concurrent.atomic.AtomicInteger

/**
 * 无障碍探测服务：
 * 1. 记录最近发生的窗口/控件事件
 * 2. 提供"转储当前窗口控件树"的能力（供 KnobActivity 主动调用）
 * 3. 捕获系统按键事件（旋钮/按键映射成方向键时能在这里看到）
 */
class KnobAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: KnobAccessibilityService? = null
        val isRunning: Boolean get() = instance != null

        /** 记录最近若干条无障碍事件，供界面展示 */
        private val eventLog = ArrayDeque<String>().apply { }
        @Volatile
        private var keyCount = AtomicInteger(0)

        /** 获取当前窗口的控件树文本（线程安全，切回主线程执行） */
        fun dumpCurrentWindow(onResult: (String) -> Unit) {
            val svc = instance ?: run {
                onResult("（无障碍服务未运行，请先在系统设置中开启 Knob 的辅助功能权限）")
                return
            }
            Handler(Looper.getMainLooper()).post {
                val root = svc.rootInActiveWindow
                if (root == null) {
                    onResult("（当前无活动窗口可读取，请打开空调/车控界面后再试）")
                } else {
                    onResult(formatNodeTree(root, 0))
                }
            }
        }

        /**
         * 无障碍节点探测：把当前窗口的控件树转成文本。
         */
        fun formatNodeTree(node: AccessibilityNodeInfo, depth: Int): String {
            if (node == null) return ""
            val pad = "  ".repeat(depth)
            val id = node.viewIdResourceName ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val clickable = if (node.isClickable) "[可点击]" else ""
            val sb2 = StringBuilder()
            sb2.append("$pad• $cls id=$id $clickable")
            if (text.isNotEmpty()) sb2.append(" 文本=\"$text\"")
            if (desc.isNotEmpty()) sb2.append(" 描述=\"$desc\"")
            sb2.append("\n")
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                sb2.append(formatNodeTree(child, depth + 1))
            }
            return sb2.toString()
        }

        fun keyEventCount(): Int = keyCount.get()

        fun clearKeyCount() { keyCount.set(0) }

        fun takeRecentEvents(): List<String> {
            return synchronized(eventLog) { eventLog.toList() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val entry = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                "[窗口切换] ${event.packageName} / ${event.className?.toString()?.substringAfterLast('.')}"
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                "[点击] ${event.packageName} → ${event.text?.joinToString()}"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                "[内容变化] ${event.packageName} (type=${event.contentChangeTypes})"
            AccessibilityEvent.TYPE_VIEW_FOCUSED ->
                "[焦点] ${event.text?.joinToString()} cls=${event.className?.toString()?.substringAfterLast('.')}"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                "[通知] ${event.packageName}"
            else -> null
        }
        if (entry != null) {
            synchronized(eventLog) {
                eventLog.addLast(entry)
                while (eventLog.size > 60) eventLog.removeFirst()
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 记录系统按键（旋钮映射的方向键/媒体键会到这里）
        keyCount.incrementAndGet()
        val kc = event.keyCode
        val action = if (event.action == KeyEvent.ACTION_DOWN) "按下" else "抬起"
        synchronized(eventLog) {
            eventLog.addLast("[按键] code=$kc action=$action")
            while (eventLog.size > 60) eventLog.removeFirst()
        }
        LogSink.append("[无障碍-按键] code=$kc action=$action")

        // 先转发给插件（真正的车控逻辑在插件里，参见 KnobPlugin.onEvent
        // 的接口注释——"key" 事件本来就该走这条路）；插件没吃（没加载
        // 插件，或者插件不关心这个键）时壳自己兜底吃掉，保证旋钮的按键
        // 永远不会漏给当前车机界面（不吃的话方向键会移动焦点、确认键会
        // 点到聚焦的控件，是安全隐患）。同一个键按下/抬起要么都被插件
        // 消费要么都不消费，这个一致性由插件自己的实现保证。
        val consumedByPlugin = PluginLoader.current?.onEvent("key", mapOf(
            "code" to kc,
            "action" to event.action
        )) ?: false
        LogSink.append(if (consumedByPlugin) "  → 插件已处理" else "  → 插件未处理，壳兜底吃掉")
        return true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
