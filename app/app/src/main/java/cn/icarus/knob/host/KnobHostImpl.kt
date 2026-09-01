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
     * 插件请求壳发送数据到 knob（屏显/指令）。壳在这里完全不理解字段
     * 含义——原样把 Map 交给 BleManager 编码转发，插件传什么 key/值就
     * 发什么，字段的意义由插件和固件两边自己约定。以后插件要加/改显示
     * 字段，这一层不用跟着改。
     */
    override fun pushToKnob(data: Map<String, Any>) {
        val text = data.map { "${it.key}=${it.value}" }.joinToString(", ")
        val sent = BleManager.write(data)
        LogSink.append("[插件→knob] pushToKnob: $text -> ${if (sent) "已发送" else "发送失败（GATT 未就绪）"}")
    }
}
