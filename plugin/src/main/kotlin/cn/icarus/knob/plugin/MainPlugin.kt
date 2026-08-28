package cn.icarus.knob.plugin

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import cn.icarus.knob.api.KnobHost
import cn.icarus.knob.api.KnobPlugin
import cn.icarus.knob.api.Ui

/**
 * 插件入口。实现 KnobPlugin，持有车控引擎 + 自检引擎。
 * 车控/业务逻辑全部在本插件内，壳只负责加载和 UI。
 *
 * 按功能域路由：
 *  - onCommand：控制域（写操作）
 *  - onQuery  ：查询域（读操作）
 *  - onEvent  ：事件域（外部事件）
 *  - onPush   ：推送域（壳→插件主动推数据）
 * 新增功能只需在对应域内加一条 when 分支，接口无需改动。
 */
class MainPlugin : KnobPlugin {

    private val tag = "KnobPlugin"

    private var carControl: CarControl? = null
    private var selfTest: PluginSelfTest? = null
    private var appContext: Context? = null

    // ---- 页面栈（壳提供容器，插件自管多页面跳转）----
    private var pageContainer: ViewGroup? = null
    private val pageStack = ArrayDeque<View>()

    override fun init(context: Context, host: KnobHost) {
        Log.i(tag, "MainPlugin.init 调用")
        appContext = context
        // 初始化车控引擎（日志转发给壳）
        carControl = CarControl(host)
        selfTest = PluginSelfTest(carControl!!, host)
        host.log("✅ 插件初始化完成（车控引擎已就绪）")
    }

    /** 统一结果 Map：success 是否成功 / code 返回码 / value 结果 / message 说明。null 的 code/value 不放入。 */
    private fun resultMap(success: Boolean, code: Any?, value: Any? = null, message: String = ""): Map<String, Any> {
        val m = mutableMapOf<String, Any>("success" to success, "message" to message)
        if (code != null) m["code"] = code
        if (value != null) m["value"] = value
        return m
    }

    /** 把返回码 Int? 包装成统一结果 Map（command 用） */
    private fun codeResult(code: Int?, okMsg: String, failMsg: String): Map<String, Any> =
        if (code != null) resultMap(true, code, message = okMsg)
        else resultMap(false, null, message = failMsg)

    /**
     * 控制域：所有写操作/命令。
     * 命令约定：
     *   "init.all"          初始化 Setting + AC 设备
     *   "seat.ventilating"  params: {state: Int(1=关,2=低,3=高)}
     *   "ac.temperature"    params: {area: Int(0-3), value: Int(17-33)}
     *   "ac.power"          params: {on: Boolean}
     *   "ac.windlevel"      params: {level: Int(0-7)}
     *   "ac.windmode"       params: {mode: Int}
     *   "ac.cycle"          params: {inner: Boolean}
     *   "ac.separate"       params: {separate: Boolean}
     */
    override fun onCommand(command: String, params: Map<String, Any>): Map<String, Any>? {
        val ctrl = carControl ?: return null
        val context = appContext ?: return null
        return try {
            when (command) {
                "init.all" -> {
                    ctrl.log("📦 初始化设备（Setting + AC）")
                    val s = ctrl.initSettingDevice(context)
                    val a = ctrl.initAcDevice(context)
                    if (s && a) resultMap(true, 0, message = "初始化成功")
                    else resultMap(false, null, message = "初始化失败")
                }
                "seat.ventilating" -> {
                    val state = (params["state"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 state")
                    codeResult(ctrl.setDriverSeatVentilating(context, state), "座椅通风执行完成", "座椅通风执行失败")
                }
                "ac.temperature" -> {
                    val area = (params["area"] as? Number)?.toInt() ?: CarControl.AC_TEMPERATURE_MAIN
                    val value = (params["value"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 value")
                    codeResult(ctrl.setAcTemperature(context, area, value), "设置温度执行完成", "设置温度失败")
                }
                "ac.power" ->
                    codeResult(ctrl.setAcPower(context, (params["on"] as? Boolean) ?: false), "空调开关执行完成", "空调开关失败")
                "ac.windlevel" -> {
                    val level = (params["level"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 level")
                    codeResult(ctrl.setAcWindLevel(context, level), "设置风量执行完成", "设置风量失败")
                }
                "ac.windmode" -> {
                    val mode = (params["mode"] as? Number)?.toInt()
                        ?: return resultMap(false, null, message = "缺少 mode")
                    codeResult(ctrl.setAcWindMode(context, mode), "设置风向执行完成", "设置风向失败")
                }
                "ac.cycle" ->
                    codeResult(ctrl.setAcCycleMode(context, (params["inner"] as? Boolean) ?: false), "设置循环执行完成", "设置循环失败")
                "ac.separate" ->
                    codeResult(ctrl.setAcTemperatureControlMode(context, (params["separate"] as? Boolean) ?: false), "设置分控执行完成", "设置分控失败")
                else -> {
                    ctrl.log("⚠️ 未知命令: $command")
                    null
                }
            }
        } catch (e: Throwable) {
            ctrl.log("❌ onCommand($command) 异常: ${e.message}")
            resultMap(false, null, message = "异常: ${e.message}")
        }
    }

    /**
     * 查询域：所有读操作。
     * 查询约定：
     *   "perm.check"        检查 BYDAUTO 权限
     *   "selfTest"          一键全量自检（value=结果文本）
     *   "seat.ventilating"  读取主驾座椅通风状态（value=Int）
     *   "ac.temperature"    params: {area: Int} → 读取空调温度（value=Int）
     */
    override fun onQuery(query: String, params: Map<String, Any>): Map<String, Any>? {
        val ctrl = carControl ?: return null
        val context = appContext ?: return null
        return try {
            when (query) {
                "perm.check" -> {
                    ctrl.log("📋 检查 BYDAUTO 权限")
                    ctrl.checkBydPermissions(context)
                    resultMap(true, 0, message = "权限检查完成，详见日志")
                }
                "selfTest" -> {
                    selfTest?.runAll(context)
                    resultMap(true, 0, value = selfTest?.lastResult() ?: "自检未初始化", message = "自检完成")
                }
                "seat.ventilating" -> {
                    val v = ctrl.getDriverSeatVentilating(context)
                    if (v != null) resultMap(true, 0, value = v, message = "读取成功")
                    else resultMap(false, null, message = "读取失败")
                }
                "ac.temperature" -> {
                    val area = (params["area"] as? Number)?.toInt() ?: CarControl.AC_TEMPERATURE_MAIN
                    val v = ctrl.getAcTemperature(context, area)
                    if (v != null) resultMap(true, 0, value = v, message = "读取成功")
                    else resultMap(false, null, message = "读取失败")
                }
                else -> {
                    ctrl.log("⚠️ 未知查询: $query")
                    null
                }
            }
        } catch (e: Throwable) {
            ctrl.log("❌ onQuery($query) 异常: ${e.message}")
            resultMap(false, null, message = "异常: ${e.message}")
        }
    }

    /**
     * 事件域：外部事件。
     * 事件约定：
     *   "key"           params: {code: Int, action: Int}  壳转发的所有按键（含系统返回键）
     *   "ui.bind"       params: {container: ViewGroup}   壳交出页面容器，开始渲染
     *   "ui.unbind"     Activity 销毁，断开容器与 View 树的持有
     */
    override fun onEvent(event: String, params: Map<String, Any>): Boolean {
        return try {
            when (event) {
                "ui.bind" -> {
                    val container = params["container"] as? ViewGroup ?: return false
                    bindUi(container)
                    true
                }
                "ui.unbind" -> {
                    unbindUi()
                    true
                }
                "key" -> {
                    val code = (params["code"] as? Number)?.toInt() ?: return false
                    val action = (params["action"] as? Number)?.toInt() ?: return false
                    handleKey(code, action)
                }
                else -> false
            }
        } catch (e: Throwable) {
            Log.e(tag, "onEvent($event) 异常: ${e.message}")
            false
        }
    }

    /**
     * 推送域：壳在任意时刻主动推数据给插件。
     * type 约定（新增类型只需加一条 when 分支）：
     *   "bluetooth"  data: {connected: Boolean, name: String?, mac: String?}  蓝牙连接状态
     *   "state"      data: {model: String, android: String, ...}              壳/设备状态
     *   "hardware"   data: {event: String, ...}                               硬件事件流（旋钮/按键原始值）
     *   "config"     data: {key: Any, ...}                                    配置项
     */
    override fun onPush(type: String, data: Map<String, Any>) {
        val ctrl = carControl
        try {
            when (type) {
                "bluetooth" -> {
                    val connected = data["connected"] as? Boolean ?: false
                    ctrl?.log("📡 蓝牙状态: ${if (connected) "已连接" else "断开"} " +
                        "name=${data["name"]} mac=${data["mac"]}")
                    Log.i(tag, "onPush bluetooth connected=$connected")
                }
                "state" -> {
                    ctrl?.log("ℹ️ 壳状态: ${data.map { "${it.key}=${it.value}" }.joinToString(", ")}")
                    Log.i(tag, "onPush state done")
                }
                "hardware" -> {
                    ctrl?.log("🎛 硬件事件: $data")
                    Log.i(tag, "onPush hardware: $data")
                }
                "config" -> {
                    ctrl?.log("⚙️ 配置更新: $data")
                    Log.i(tag, "onPush config done")
                }
                else -> {
                    Log.i(tag, "onPush 未知类型: $type, data=$data")
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "onPush($type) 异常: ${e.message}")
        }
    }

    /**
     * 按键处理，返回是否消费。
     *
     * 返回键：页面栈里还有上一页时由插件吃掉（抬起时出栈），栈底则交还给壳去关闭 Activity。
     * 按下和抬起都要消费，只吃一半会让系统的按键状态错乱。
     */
    private fun handleKey(code: Int, action: Int): Boolean {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (pageStack.size <= 1) return false
            if (action == KeyEvent.ACTION_UP) popPage()
            return true
        }
        Log.i(tag, "onEvent key code=$code action=$action")
        // TODO: 在这里按 keyCode 映射到具体车控命令（路线图阶段 5）
        return false
    }

    // ==================== 页面栈（纯代码构建 View，避免资源 ID 冲突） ====================

    /** 壳交出页面容器，插件开始渲染（自管页面栈） */
    private fun bindUi(container: ViewGroup) {
        pageContainer = container
        Log.i(tag, "ui.bind: 页面容器已就绪")
        // 首次进入显示首页；已有页面栈（Activity 重建）则恢复到离开时那一页
        if (pageStack.isEmpty()) {
            showPage("home")
        } else {
            renderTop()
        }
    }

    /**
     * Activity 销毁，断开对容器和 View 树的持有。
     * removeAllViews 会清掉栈顶 View 的 parent —— 既避免插件（常驻进程）泄漏已销毁的 Activity，
     * 也让这些 View 下次 ui.bind 时能被重新 addView。pageStack 保留，以恢复页面位置。
     */
    private fun unbindUi() {
        pageContainer?.removeAllViews()
        pageContainer = null
        Log.i(tag, "ui.unbind: 页面容器已解绑")
    }

    /** 压入新页面并显示 */
    private fun pushPage(view: View) {
        pageStack.addLast(view)
        renderTop()
    }

    /** 弹出栈顶页面（返回上一页） */
    private fun popPage() {
        if (pageStack.size > 1) {
            pageStack.removeLast()
            renderTop()
        }
    }

    /** 把栈顶页面显示到容器 */
    private fun renderTop() {
        val container = pageContainer ?: return
        val top = pageStack.lastOrNull() ?: return
        container.removeAllViews()
        val lp = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.addView(top, lp)
    }

    /** 页面注册表：pageId → 构建函数。新增页面只需在此加一行，并写对应的构建函数。 */
    private val pages: Map<String, (Map<String, Any>) -> View> = mapOf(
        "home" to { _ -> buildHomePage() },
        "car" to { _ -> buildCarPage() },
        "seat" to { _ -> buildSeatPage() },
    )

    /** 按 pageId 显示页面（查注册表，构建后压栈显示）。返回是否成功。 */
    private fun showPage(pageId: String, params: Map<String, Any> = emptyMap()): Boolean {
        val builder = pages[pageId] ?: run {
            Log.w(tag, "未知页面: $pageId")
            return false
        }
        pushPage(builder(params))
        return true
    }

    // ---- 页面构建（用 Ui 纯代码构建，避免资源 ID 冲突）----

    /** 首页：跳转到空调页 / 座椅页 */
    private fun buildHomePage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(Ui.title(ctx, "🎛 插件首页"))
            addView(Ui.hint(ctx, "三个示例页面互相跳转\n页面栈由插件自管，back 键可返回"))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "❄️ 去空调页") { showPage("car") })
            addView(Ui.btn(ctx, "🪑 去座椅页") { showPage("seat") })
        }
    }

    /** 空调页：跳转到座椅页 / 返回首页 */
    private fun buildCarPage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            addView(Ui.title(ctx, "❄️ 空调页"))
            addView(Ui.label(ctx, "这里是空调控制示例页面"))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "🪑 去座椅页") { showPage("seat") })
            addView(Ui.btn(ctx, "← 返回首页") { popPage() })
        }
    }

    /** 座椅页：跳转到首页 / 返回空调页 */
    private fun buildSeatPage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            addView(Ui.title(ctx, "🪑 座椅页"))
            addView(Ui.label(ctx, "这里是座椅控制示例页面"))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "🎛 回首页") { showPage("home") })
            addView(Ui.btn(ctx, "❄️ 去空调页") { showPage("car") })
        }
    }

    override fun onDestroy() {
        Log.i(tag, "MainPlugin.onDestroy")
        pageStack.clear()
    }
}
