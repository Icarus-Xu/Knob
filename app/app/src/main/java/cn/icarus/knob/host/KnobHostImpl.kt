package cn.icarus.knob.host

import cn.icarus.knob.api.KnobHost
import cn.icarus.knob.ble.BleManager
import cn.icarus.knob.util.LogSink

/**
 * 壳实现 KnobHost：把插件发来的日志转发到 LogSink 显示，
 * 并把插件请求的 pushToKnob（屏显/指令）交给 BLE 通道下发。
 * 传给插件的 init(context, host)。
 */
object KnobHostImpl : KnobHost {

    override fun log(message: String) {
        LogSink.append("[插件] $message")
    }

    /**
     * 插件请求壳发送数据到 knob（屏显/指令）。
     * 目前只有 {"module": Int, "value": Int} 这一种用法（同步旋钮屏幕
     * 当前显示的车控模块+数值），对应 firmware/src/ble.cpp 里那个自定义
     * 可写特征值的 3 字节格式。
     */
    override fun pushToKnob(data: Map<String, Any>) {
        val text = data.map { "${it.key}=${it.value}" }.joinToString(", ")
        val module = (data["module"] as? Number)?.toInt()
        val value = (data["value"] as? Number)?.toInt()
        if (module == null || value == null) {
            LogSink.append("[插件→knob] pushToKnob 参数不对：$text（需要 module/value）")
            return
        }
        val sent = BleManager.write(module, value)
        LogSink.append("[插件→knob] pushToKnob: $text -> ${if (sent) "已发送" else "发送失败（GATT 未就绪）"}")
    }
}
