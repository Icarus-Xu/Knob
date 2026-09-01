package cn.icarus.knob.api

import android.content.Context

/**
 * 壳 ↔ 插件 的通信契约。
 * 插件 DEX 实现此接口，壳通过接口调用插件逻辑。
 * 必须同时存在于壳和插件工程（包名/类名/方法签名完全一致）。
 *
 * 设计原则：按【功能域】分方法，而不是每个功能一个方法。
 * 这样新增功能只需在对应域内加一条命令路由，接口本身保持稳定，无需改接口、无需重新签名。
 *
 * 四个功能域：
 *  - onCommand：控制域（写操作，下发命令改变车况/状态）
 *  - onQuery  ：查询域（读操作，读取状态/信息）
 *  - onEvent  ：事件域（外部事件，如蓝牙按键；UI 容器的绑定/解绑也走这里）
 *  - onPush   ：推送域（壳→插件，壳在任意时刻主动推数据给插件，不依赖某次调用）
 */
interface KnobPlugin {

    /**
     * 插件初始化
     * @param context 壳的 context
     * @param host 壳宿主回调（插件反向调壳用）
     */
    fun init(context: Context, host: KnobHost)

    /**
     * 控制域：下发命令（写操作）。
     * @param command 命令名（如 "seat.ventilating"、"ac.temperature"、"init.all"）
     * @param params 参数（如 {"area":1, "value":22}）
     * @return 统一结果 Map；失败/未知命令返回 null
     *   成功: {"success":true, "code":0, "value":null, "message":"ok"}
     *   失败: {"success":false, "code":<错误码/null>, "value":null, "message":"说明"}
     */
    fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>?

    /**
     * 查询域：读状态/信息（不改变任何状态）。
     * @param query 查询名（如 "perm.check"、"selfTest"、"seat.ventilating"、"ac.temperature"）
     * @param params 参数（如 {"area":1}）
     * @return 统一结果 Map；失败/未知查询返回 null
     *   成功: {"success":true, "code":0, "value":<查询结果>, "message":"ok"}
     *   失败: {"success":false, "code":<错误码/null>, "value":null, "message":"说明"}
     */
    fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>?

    /**
     * 事件域：外部事件。
     * @param event 事件名，约定：
     *   "key"           params: {code: Int, action: Int}
     *                   壳转发的按键：蓝牙旋钮/按键（自定义 BLE GATT 通知，见 BleManager）、
     *                   车机物理键、系统返回键都走这里。
     *                   code = KeyEvent.KEYCODE_*，action = KeyEvent.ACTION_DOWN / ACTION_UP。
     *                   消费某个键时**按下与抬起要成对返回 true**，只吃一半会让系统按键状态错乱。
     *   "ui.bind"       params: {container: ViewGroup} 壳把页面容器交给插件，插件开始渲染并自管页面栈
     *   "ui.unbind"     params: {}                     Activity 销毁，插件必须断开对该容器/View 树的持有
     * @param params 事件参数
     * @return 是否消费了该事件
     *
     * 注意：Activity 会反复创建销毁（配置变化、被系统回收等），
     * 插件收到 "ui.unbind" 必须清干净 View 的 parent 引用，否则会泄漏已销毁的 Activity，
     * 且下次 "ui.bind" 复用旧 View 时会抛 "already has a parent"。
     */
    fun onEvent(event: String, params: Map<String, Any>): Boolean

    /**
     * 推送域：壳→插件的通用数据通道。
     * 壳在任意时刻主动把数据推给插件（状态、蓝牙连接、硬件事件流、配置等），
     * 不依赖某次命令调用。新增数据类型只需在插件内加一条 when(type) 分支，接口不变。
     * @param type 数据类型（如 "state"、"bluetooth"、"hardware"、"config"）
     * @param data 数据内容（key-value）
     */
    fun onPush(type: String, data: Map<String, Any>)

    /** 插件销毁 */
    fun onDestroy()
}
