package cn.icarus.knob.plugin

import android.content.Context
import cn.icarus.knob.api.KnobHost

/**
 * 一键全量自检（从壳的 SelfTest 迁移到插件）。
 * 接收 CarControl 实例执行车控，日志通过 logger 回调。
 */
class PluginSelfTest(
    private val control: CarControl,
    private val logger: KnobHost
) {

    fun runAll(context: Context) {
        val sb = StringBuilder()
        fun out(msg: String) {
            sb.append(msg).append("\n")
            logger.log(msg)
        }

        out("🚀 一键全量自检开始")
        var pass = 0
        var fail = 0

        fun run(name: String, action: () -> String) {
            try {
                val result = action()
                out("[自检] ✅ $name : 返回码=$result")
                pass++
            } catch (e: Throwable) {
                out("[自检] ❌ $name : ${e.javaClass.simpleName} - ${e.message}")
                fail++
            }
            try { Thread.sleep(300) } catch (ignored: InterruptedException) {}
        }

        // 权限
        run("权限检查") { control.checkBydPermissions(context); "见上方" }
        // 初始化
        run("初始化 Setting 设备") { if (!control.initSettingDevice(context)) throw RuntimeException("失败"); "ok" }
        run("初始化 AC 设备") { if (!control.initAcDevice(context)) throw RuntimeException("失败"); "ok" }

        // 座椅通风
        run("座椅通风-低档") { control.setDriverSeatVentilating(context, CarControl.SEAT_VENTILATING_LOW)?.toString() ?: throw RuntimeException("null") }
        run("座椅通风-高档") { control.setDriverSeatVentilating(context, CarControl.SEAT_VENTILATING_HIGH)?.toString() ?: throw RuntimeException("null") }
        run("座椅通风-关闭") { control.setDriverSeatVentilating(context, CarControl.SEAT_VENTILATING_OFF)?.toString() ?: throw RuntimeException("null") }
        run("读座椅通风状态") { control.getDriverSeatVentilating(context)?.toString() ?: throw RuntimeException("null") }

        // 空调温度
        val temps = listOf(CarControl.AC_TEMP_CELSIUS_MIN, 22, CarControl.AC_TEMP_CELSIUS_MAX)
        temps.forEach { t ->
            run("空调主驾 ${t}°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN, t)?.toString() ?: throw RuntimeException("null") }
        }
        run("空调副驾 22°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_DEPUTY, 22)?.toString() ?: throw RuntimeException("null") }
        run("空调后排 22°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_REAR, 22)?.toString() ?: throw RuntimeException("null") }
        run("空调主副联动 22°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN_DEPUTY, 22)?.toString() ?: throw RuntimeException("null") }
        listOf(
            CarControl.AC_TEMPERATURE_MAIN to "主驾",
            CarControl.AC_TEMPERATURE_DEPUTY to "副驾",
            CarControl.AC_TEMPERATURE_REAR to "后排"
        ).forEach { (area, label) ->
            run("读空调$label 温度") { control.getAcTemperature(context, area)?.toString() ?: throw RuntimeException("null") }
        }

        // 分控
        run("分控-开启") { control.setAcTemperatureControlMode(context, true)?.toString() ?: throw RuntimeException("null") }
        run("分控-关闭") { control.setAcTemperatureControlMode(context, false)?.toString() ?: throw RuntimeException("null") }

        // 空调更多功能
        run("空调开关-开启") { control.setAcPower(context, true)?.toString() ?: throw RuntimeException("null") }
        run("空调风量-3档") { control.setAcWindLevel(context, 3)?.toString() ?: throw RuntimeException("null") }
        run("空调风量-5档") { control.setAcWindLevel(context, 5)?.toString() ?: throw RuntimeException("null") }
        run("空调风向-吹脸(1)") { control.setAcWindMode(context, 1)?.toString() ?: throw RuntimeException("null") }
        run("空调风向-吹脚(3)") { control.setAcWindMode(context, 3)?.toString() ?: throw RuntimeException("null") }
        run("空调循环-内循环") { control.setAcCycleMode(context, true)?.toString() ?: throw RuntimeException("null") }
        run("空调循环-外循环") { control.setAcCycleMode(context, false)?.toString() ?: throw RuntimeException("null") }
        run("空调开关-关闭") { control.setAcPower(context, false)?.toString() ?: throw RuntimeException("null") }

        // 异常温度边界
        run("异常温度-16°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN, 16)?.toString() ?: throw RuntimeException("null") }
        run("异常温度-34°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN, 34)?.toString() ?: throw RuntimeException("null") }
        run("异常温度--5°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN, -5)?.toString() ?: throw RuntimeException("null") }
        run("异常温度-99°C") { control.setAcTemperature(context, CarControl.AC_TEMPERATURE_MAIN, 99)?.toString() ?: throw RuntimeException("null") }

        out("🏁 自检完成：通过 $pass 项，失败 $fail 项")
        _lastResult = sb.toString()
    }

    private var _lastResult: String = ""

    fun lastResult(): String = _lastResult
}
