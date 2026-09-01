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

    companion object {
        // 先不签名，验证按键/GATT/插件路由这条完整链路时不碰真实车控接口
        // ——BYDAUTO_*_GET/SET 没有签名拿不到，调了也只会拿到失败/异常，
        // 干扰链路验证。等签名装上真车机测试时把这个改成 false 就切回
        // 真实调用，其余代码不用动。
        private const val SIMULATE_CAR_CONTROL = true

        private const val PREFS_NAME = "knob_plugin_state"
        private const val KEY_PAGE = "page"
        private const val KEY_AC_SEPARATE = "ac_separate"
        private const val KEY_AC_MAIN_DEPUTY = "ac_main_deputy"
        private const val KEY_AC_MAIN = "ac_main"
        private const val KEY_AC_DEPUTY = "ac_deputy"
        private const val KEY_SEAT_LEVEL = "seat_level"

        // 座椅 5 档：-2/-1=通风2/1档，0=关闭，1/2=加热1/2档。负数=通风、
        // 正数=加热，这样触屏 ±/knob 屏幕的"居中对称"圆弧
        // （见 firmware/src/main.cpp 的 LV_ARC_MODE_SYMMETRICAL）都能直接
        // 用这一个有符号数表示，不用另外维护"档位+方向"两个字段。
        // CarControl 里还没有座椅加热的真实 HAL 接口，这几个档位目前只在
        // 插件本地模拟，没有对应的真实车控常量可以复用。
        private const val SEAT_LEVEL_MIN = -2
        private const val SEAT_LEVEL_MAX = 2
    }

    /**
     * 车辆状态：插件内唯一数据源，触屏页面和旋钮同步都读写这一份，不再有
     * 各自独立的状态。SIMULATE_CAR_CONTROL=true 时纯本地值；=false 时
     * 由 loadAcState() 从 CarControl 真实读取覆盖（座椅暂时没有对应的真实
     * 读取接口，始终是本地值）。
     */
    private data class VehicleState(
        var acSeparate: Boolean = false,
        var acMainDeputyTemp: Int = 22,
        var acMainTemp: Int = 22,
        var acDeputyTemp: Int = 22,
        var seatLevel: Int = 0,
    )

    private val tag = "KnobPlugin"

    private var carControl: CarControl? = null
    private var selfTest: PluginSelfTest? = null
    private var appContext: Context? = null
    private var host: KnobHost? = null

    private var pageContainer: ViewGroup? = null
    private val vehicleState = VehicleState()

    // 当前显示的页面 id，持久化 + 同步给 knob 屏幕。knobCyclePages 是旋钮
    // 单击（Tab）能循环切到的页面，首页不算在内——旋钮场景下没有"回菜单"
    // 的需求，首页只是触屏进入时的一个入口，靠页面里的按钮跳转。
    private var currentPageId: String = "car"
    private val knobCyclePages = listOf("car", "seat")

    override fun init(context: Context, host: KnobHost) {
        Log.i(tag, "MainPlugin.init 调用")
        appContext = context
        this.host = host
        carControl = CarControl(host)
        selfTest = PluginSelfTest(carControl!!, host)
        host.log("✅ 插件初始化完成（车控引擎已就绪）")
        restoreState()
        syncToKnob()
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
                    // value：只有旋钮长按（确认）才会带上，是旋钮本地编辑
                    // 到的最终值——旋钮转动时不再逐档通知手机，只有长按
                    // 才把结果发过来。
                    val value = (params["value"] as? Number)?.toInt()
                    handleKey(code, action, value)
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
                    // 刚连上（含断线重连）时主动推一次当前状态给屏幕——不然
                    // 插件 init() 那次同步大概率因为当时 GATT 还没连上而白白
                    // 失败，用户不主动操作的话屏幕会一直停在出厂默认画面。
                    if (connected) syncToKnob()
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
     * 旋钮映射：Tab=切换页面（车控页/座椅页循环），Enter=确认（对应触屏
     * 页面上的 OK）。旋钮转动本身完全在固件本地处理、不再上报给手机——
     * 长按 Enter 时才把旋钮本地编辑到的最终值（value）一起带过来，直接
     * 拿去应用，不需要手机在转动过程中跟着增量调整。系统返回键不再有
     * 特殊处理——页面之间是平级切换，没有"返回上一页"这个概念了，交给
     * 壳/系统默认处理。按下和抬起都要消费，只吃一半会让系统按键状态
     * 错乱。
     */
    private fun handleKey(code: Int, action: Int, value: Int?): Boolean {
        when (code) {
            KeyEvent.KEYCODE_TAB -> {
                if (action == KeyEvent.ACTION_UP) {
                    val idx = knobCyclePages.indexOf(currentPageId)
                    val next = knobCyclePages[if (idx == -1) 0 else (idx + 1) % knobCyclePages.size]
                    host?.log("🔀 Tab -> $next")
                    switchToPage(next)
                }
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (action == KeyEvent.ACTION_UP) applyPrimaryValue(value)
                return true
            }
        }
        Log.i(tag, "onEvent key code=$code action=$action（未处理）")
        return false
    }

    /**
     * Enter：应用当前页面的主要数值，等价于触屏上点那一行的 OK。
     * @param knobValue 旋钮本地编辑到的最终值（长按时固件带过来的），
     *   写入 vehicleState 后再提交——旋钮转动期间手机完全不知道中间值，
     *   只在这一下才知道最终结果。knobValue 为 null（比如系统按键触发
     *   的 Enter，没有旋钮本地状态）时直接沿用 vehicleState 里已有的值。
     */
    private fun applyPrimaryValue(knobValue: Int?) {
        when (currentPageId) {
            "car" -> {
                if (vehicleState.acSeparate) {
                    if (knobValue != null) {
                        vehicleState.acMainTemp = knobValue.coerceIn(CarControl.AC_TEMP_CELSIUS_MIN, CarControl.AC_TEMP_CELSIUS_MAX)
                    }
                    persistState()
                    commitAcTemperature(CarControl.AC_TEMPERATURE_MAIN, vehicleState.acMainTemp)
                } else {
                    if (knobValue != null) {
                        vehicleState.acMainDeputyTemp = knobValue.coerceIn(CarControl.AC_TEMP_CELSIUS_MIN, CarControl.AC_TEMP_CELSIUS_MAX)
                    }
                    persistState()
                    commitAcTemperature(CarControl.AC_TEMPERATURE_MAIN_DEPUTY, vehicleState.acMainDeputyTemp)
                }
                refreshCurrentPage()
            }
            "seat" -> {
                if (knobValue != null) {
                    vehicleState.seatLevel = knobValue.coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX)
                }
                persistState()
                commitSeatLevel(vehicleState.seatLevel)
                refreshCurrentPage()
            }
            else -> Unit
        }
    }

    /** 座椅档位 -> 人能看懂的文字，UI 标签和日志都用这个，保证显示一致。 */
    private fun seatLevelLabel(level: Int): String = when {
        level <= -2 -> "通风2档"
        level == -1 -> "通风1档"
        level == 0 -> "关闭"
        level == 1 -> "加热1档"
        else -> "加热2档"
    }

    /** 座椅 OK 键：真正下发座椅通风/加热。CarControl 还没有真实的座椅加热接口，先只做模拟。 */
    private fun commitSeatLevel(level: Int) {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）座椅 -> ${seatLevelLabel(level)}")
            return
        }
        host?.log("⚠️ 座椅真实车控接口还没实现（CarControl 缺座椅加热的 HAL 封装），只做了本地模拟：${seatLevelLabel(level)}")
    }

    /**
     * 把当前页面的"主要数值"同步给旋钮屏幕（走 host.pushToKnob -> BLE -> ESP32），
     * 作为固件本地编辑的起点。knob 自己不知道页面/模块的概念，只管按
     * 拿到的 title/value/min/max 原样显示；此后转动旋钮只改固件本地的
     * 副本，不会再触发这个同步，直到长按确认把最终值带回来、或者切页面/
     * 重新进页面时再推一次新的起点（空调页分控开时是主驾温度，关时是
     * 联动温度；座椅页是 seatLevel）。min<0（座椅）时固件会画成从中间
     * 向两侧扩展的圆弧，见 firmware/src/main.cpp。
     */
    private fun syncToKnob() {
        val (title, value, min, max) = when (currentPageId) {
            "car" -> if (vehicleState.acSeparate) {
                DisplayInfo("空调温度(主驾)", vehicleState.acMainTemp, CarControl.AC_TEMP_CELSIUS_MIN, CarControl.AC_TEMP_CELSIUS_MAX)
            } else {
                DisplayInfo("空调温度", vehicleState.acMainDeputyTemp, CarControl.AC_TEMP_CELSIUS_MIN, CarControl.AC_TEMP_CELSIUS_MAX)
            }
            "seat" -> DisplayInfo(seatLevelLabel(vehicleState.seatLevel), vehicleState.seatLevel, SEAT_LEVEL_MIN, SEAT_LEVEL_MAX)
            else -> DisplayInfo("首页", 0, 0, 0)
        }
        host?.pushToKnob(mapOf("title" to title, "value" to value, "min" to min, "max" to max))
    }

    private data class DisplayInfo(val title: String, val value: Int, val min: Int, val max: Int)

    // ==================== 状态持久化（进程重启后恢复页面 + 车辆状态） ====================

    private fun persistState() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PAGE, currentPageId)
            .putBoolean(KEY_AC_SEPARATE, vehicleState.acSeparate)
            .putInt(KEY_AC_MAIN_DEPUTY, vehicleState.acMainDeputyTemp)
            .putInt(KEY_AC_MAIN, vehicleState.acMainTemp)
            .putInt(KEY_AC_DEPUTY, vehicleState.acDeputyTemp)
            .putInt(KEY_SEAT_LEVEL, vehicleState.seatLevel)
            .apply()
    }

    private fun restoreState() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentPageId = prefs.getString(KEY_PAGE, "car") ?: "car"
        vehicleState.acSeparate = prefs.getBoolean(KEY_AC_SEPARATE, false)
        vehicleState.acMainDeputyTemp = prefs.getInt(KEY_AC_MAIN_DEPUTY, 22)
        vehicleState.acMainTemp = prefs.getInt(KEY_AC_MAIN, 22)
        vehicleState.acDeputyTemp = prefs.getInt(KEY_AC_DEPUTY, 22)
        vehicleState.seatLevel = prefs.getInt(KEY_SEAT_LEVEL, 0)
    }

    // ==================== 页面渲染（纯代码构建 View，避免资源 ID 冲突） ====================

    /** 页面注册表：pageId → 构建函数。新增页面只需在此加一行，并写对应的构建函数。 */
    private val pages: Map<String, () -> View> = mapOf(
        "home" to { buildHomePage() },
        "car" to { buildCarPage() },
        "seat" to { buildSeatPage() },
    )

    /** 壳交出页面容器，插件开始渲染当前页面（Activity 重建也会重新调用，直接按当前状态重绘）。 */
    private fun bindUi(container: ViewGroup) {
        pageContainer = container
        Log.i(tag, "ui.bind: 页面容器已就绪，当前页面=$currentPageId")
        if (currentPageId == "car") loadAcState()
        refreshCurrentPage()
    }

    /** Activity 销毁，断开容器持有——插件是进程级单例，不能强持有已销毁的 Activity。 */
    private fun unbindUi() {
        pageContainer?.removeAllViews()
        pageContainer = null
        Log.i(tag, "ui.unbind: 页面容器已解绑")
    }

    /**
     * 真正切换到另一个页面（跟同页内数值调整的 refreshCurrentPage 区分开）：
     * 更新 currentPageId、持久化、如果切到空调页顺带刷新一次真实车况，
     * 最后交给 refreshCurrentPage() 重绘并同步给 knob。不管是旋钮 Tab
     * 触发的还是页面内点按钮触发的，都走这一个函数，保证两条路径下
     * knob 收到的通知是一致的。
     */
    private fun switchToPage(pageId: String) {
        if (!pages.containsKey(pageId)) {
            Log.w(tag, "未知页面: $pageId")
            return
        }
        currentPageId = pageId
        if (pageId == "car") loadAcState()
        persistState()
        refreshCurrentPage()
    }

    /** 用当前状态重绘 currentPageId 对应的页面，并同步给 knob——数值调整（不切页）时用这个。 */
    private fun refreshCurrentPage() {
        val container = pageContainer ?: return
        val builder = pages[currentPageId] ?: return
        container.removeAllViews()
        container.addView(builder(), ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        syncToKnob()
    }

    /** 首页：跳转到空调页 / 座椅页（纯菜单，不接旋钮 Tab 循环） */
    private fun buildHomePage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(Ui.title(ctx, "🎛 插件首页"))
            addView(Ui.hint(ctx, "旋钮单击在空调页/座椅页之间循环切换\n首页只能触屏进入"))
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "❄️ 去空调页") { switchToPage("car") })
            addView(Ui.btn(ctx, "🪑 去座椅页") { switchToPage("seat") })
        }
    }

    /** 从车况刷新分控开关 + 两个区域的默认温度（进空调页时调，页内调整不调）。 */
    private fun loadAcState() {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）跳过读取车况，沿用当前本地值")
            return
        }
        val ctrl = carControl ?: return
        val context = appContext ?: return
        vehicleState.acSeparate = ctrl.getAcTemperatureControlMode(context) == CarControl.AC_TEMPCTRL_SEPARATE_ON
        ctrl.getAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN_DEPUTY)?.let { vehicleState.acMainDeputyTemp = it }
        ctrl.getAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN)?.let { vehicleState.acMainTemp = it }
        ctrl.getAcTemperature(context, CarControl.AC_TEMPERATURE_DEPUTY)?.let { vehicleState.acDeputyTemp = it }
        persistState()
    }

    /** 分控关：一组联动温度框；分控开：主驾/副驾各一组。 */
    private fun buildCarPage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            addView(Ui.title(ctx, "❄️ 空调温度"))
            addView(Ui.space(ctx, 0, 8))
            addView(Ui.btn(ctx, if (vehicleState.acSeparate) "分控：开（点击关闭）" else "分控：关（点击开启）") {
                toggleAcSeparate()
            })
            addView(Ui.space(ctx, 0, 12))
            if (!vehicleState.acSeparate) {
                addView(buildTempRow(
                    label = "联动温度",
                    value = vehicleState.acMainDeputyTemp,
                    onChange = { vehicleState.acMainDeputyTemp = it; persistState(); refreshCurrentPage() },
                    onOk = { commitAcTemperature(CarControl.AC_TEMPERATURE_MAIN_DEPUTY, vehicleState.acMainDeputyTemp) }
                ))
            } else {
                addView(buildTempRow(
                    label = "主驾温度",
                    value = vehicleState.acMainTemp,
                    onChange = { vehicleState.acMainTemp = it; persistState(); refreshCurrentPage() },
                    onOk = { commitAcTemperature(CarControl.AC_TEMPERATURE_MAIN, vehicleState.acMainTemp) }
                ))
                addView(Ui.space(ctx, 0, 8))
                addView(buildTempRow(
                    label = "副驾温度",
                    value = vehicleState.acDeputyTemp,
                    onChange = { vehicleState.acDeputyTemp = it; persistState(); refreshCurrentPage() },
                    onOk = { commitAcTemperature(CarControl.AC_TEMPERATURE_DEPUTY, vehicleState.acDeputyTemp) }
                ))
            }
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "🪑 去座椅页") { switchToPage("seat") })
            addView(Ui.btn(ctx, "← 返回首页") { switchToPage("home") })
        }
    }

    /** 一组"标签 － 数值 ＋ OK"。－/＋ 只改本地待提交值，OK 才真正下发。 */
    private fun buildTempRow(label: String, value: Int, onChange: (Int) -> Unit, onOk: () -> Unit): View {
        val ctx = appContext!!
        return Ui.card(ctx).apply {
            addView(Ui.label(ctx, label))
            addView(Ui.hStack(ctx).apply {
                addView(Ui.btn(ctx, "－") {
                    onChange((value - 1).coerceIn(CarControl.AC_TEMP_CELSIUS_MIN, CarControl.AC_TEMP_CELSIUS_MAX))
                })
                addView(Ui.text(ctx, "${value}°C", size = 18f, bold = true))
                addView(Ui.btn(ctx, "＋") {
                    onChange((value + 1).coerceIn(CarControl.AC_TEMP_CELSIUS_MIN, CarControl.AC_TEMP_CELSIUS_MAX))
                })
                addView(Ui.btn(ctx, "OK") { onOk() })
            })
        }
    }

    /** 切分控：调用 setAcTemperatureControlMode，返回码非 null 就认为成功，直接翻本地状态。 */
    private fun toggleAcSeparate() {
        if (SIMULATE_CAR_CONTROL) {
            vehicleState.acSeparate = !vehicleState.acSeparate
            host?.log("🧪（模拟）setAcTemperatureControlMode separate=${vehicleState.acSeparate}")
            persistState()
            refreshCurrentPage()
            return
        }
        val ctrl = carControl ?: return
        val context = appContext ?: return
        val result = ctrl.setAcTemperatureControlMode(context, !vehicleState.acSeparate)
        if (result != null) {
            vehicleState.acSeparate = !vehicleState.acSeparate
            persistState()
            refreshCurrentPage()
        }
    }

    /** OK 键：真正下发 setAcTemperature，source 固定 UI_KEY（CarControl 内部已带）。 */
    private fun commitAcTemperature(type: Int, value: Int) {
        if (SIMULATE_CAR_CONTROL) {
            host?.log("🧪（模拟）setAcTemperature type=$type value=$value")
            return
        }
        val ctrl = carControl ?: return
        val context = appContext ?: return
        ctrl.setAcTemperature(context, type, value)
    }

    /** 座椅页：跳转到首页 / 空调页（占位页面，还没接真实座椅通风状态）。 */
    private fun buildSeatPage(): View {
        val ctx = appContext!!
        return Ui.vStack(ctx).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            addView(Ui.title(ctx, "🪑 座椅"))
            addView(Ui.space(ctx, 0, 12))
            addView(buildSeatRow())
            addView(Ui.space(ctx, 0, 12))
            addView(Ui.btn(ctx, "❄️ 去空调页") { switchToPage("car") })
            addView(Ui.btn(ctx, "← 返回首页") { switchToPage("home") })
        }
    }

    /**
     * 座椅的"标签 － 档位 ＋ OK"，跟 buildTempRow 不共用是因为这里显示的
     * 是"通风2档"这种文字档位，不是"数值+单位"，格式完全不同。
     * －/＋ 只改本地待提交档位，OK 才真正下发。
     */
    private fun buildSeatRow(): View {
        val ctx = appContext!!
        val level = vehicleState.seatLevel
        return Ui.card(ctx).apply {
            addView(Ui.label(ctx, "座椅"))
            addView(Ui.hStack(ctx).apply {
                addView(Ui.btn(ctx, "－") {
                    vehicleState.seatLevel = (level - 1).coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX)
                    persistState()
                    refreshCurrentPage()
                })
                addView(Ui.text(ctx, seatLevelLabel(level), size = 18f, bold = true))
                addView(Ui.btn(ctx, "＋") {
                    vehicleState.seatLevel = (level + 1).coerceIn(SEAT_LEVEL_MIN, SEAT_LEVEL_MAX)
                    persistState()
                    refreshCurrentPage()
                })
                addView(Ui.btn(ctx, "OK") { commitSeatLevel(vehicleState.seatLevel) })
            })
        }
    }

    override fun onDestroy() {
        Log.i(tag, "MainPlugin.onDestroy")
    }
}
