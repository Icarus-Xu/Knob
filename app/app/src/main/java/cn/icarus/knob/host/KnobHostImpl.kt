package cn.icarus.knob.host

import cn.icarus.knob.api.KnobHost
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
     * 当前为骨架：接入 BLE 后，把 data 序列化并通过 GATT Write 发到 knob。
     */
    override fun pushToKnob(data: Map<String, Any>) {
        val text = data.map { "${it.key}=${it.value}" }.joinToString(", ")
        LogSink.append("[插件→knob] pushToKnob: $text")
        // TODO: 接入 BLE 后，调用 BleManager.writeToKnob(data)
    }
}
