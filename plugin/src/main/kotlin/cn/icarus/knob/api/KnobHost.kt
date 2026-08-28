package cn.icarus.knob.api

/**
 * 壳宿主回调。壳实现并传给插件，插件通过它反向调用壳。
 * 必须同时存在于壳和插件工程（包名/签名一致）。
 */
interface KnobHost {
    /** 插件打日志，转发给壳显示 */
    fun log(message: String)

    /**
     * 插件请求壳推送数据到 knob（屏显/指令）。
     * 壳收到后负责通过 BLE 等通道下发。
     * @param data 数据内容（如 {"temp":22, "mode":"auto"}）
     */
    fun pushToKnob(data: Map<String, Any>)
}
