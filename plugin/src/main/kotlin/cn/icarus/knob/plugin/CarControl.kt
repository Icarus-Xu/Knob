package cn.icarus.knob.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.IBYDAutoDevice
import android.hardware.bydauto.ac.BYDAutoAcDevice
import android.hardware.bydauto.setting.BYDAutoSettingDevice
import cn.icarus.knob.api.KnobHost
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method

/**
 * 车控引擎（从壳的 BydHal 迁移到插件）。
 * 日志通过 logger 回调发给壳显示。
 *
 * 座椅通风/空调均为车机厂商公开 HAL 接口（签名版可调用）。
 * 座椅: 1=主驾, 2=副驾, 3=后排左, 4=后排右
 * 通风档位: OFF=1, LOW=2, HIGH=3
 */
class CarControl(private val logger: KnobHost) {

    // ---- 座椅常量 ----
    companion object {
        const val DRIVER_SEAT = 1
        const val PASSENGER_SEAT = 2
        const val SEAT_VENTILATING_OFF = 1
        const val SEAT_VENTILATING_LOW = 2
        const val SEAT_VENTILATING_HIGH = 3

        // ---- 空调常量（官方 BYDAutoAcDevice）----
        const val AC_TEMPERATURE_MAIN = 1
        const val AC_TEMPERATURE_DEPUTY = 2
        const val AC_TEMPERATURE_REAR = 3
        const val AC_TEMPERATURE_MAIN_DEPUTY = 0
        const val AC_TEMPERATURE_UNIT_OC = 1
        const val AC_TEMP_CELSIUS_MIN = 17
        const val AC_TEMP_CELSIUS_MAX = 33

        const val AC_CTRL_SOURCE_UI_KEY = 0

        const val AC_COMMAND_SUCCESS = 0

        const val AC_TEMPCTRL_SEPARATE_OFF = 0
        const val AC_TEMPCTRL_SEPARATE_ON = 1
    }

    private var settingDevice: BYDAutoSettingDevice? = null
    private var acDevice: BYDAutoAcDevice? = null

    fun log(msg: String) = logger.log(msg)

    private fun enableDevice(context: Context, device: IBYDAutoDevice, logName: String) {
        try {
            val mgrClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
            val mgrGetInstance = mgrClass.getMethod("getInstance", Context::class.java)
            val mgr = mgrGetInstance.invoke(null, context) ?: run {
                log("⚠️ BYDAutoDeviceManager.getInstance 返回 null")
                return
            }
            try {
                mgrClass.getMethod("enableDevice", IBYDAutoDevice::class.java)
                    .invoke(mgr, device)
                log("✅ 已启用 $logName")
            } catch (e: Throwable) {
                try {
                    mgrClass.getMethod("enableDevice", IBYDAutoDevice::class.java, IntArray::class.java)
                        .invoke(mgr, device, intArrayOf())
                    log("✅ 已启用 $logName (带 feature 重载)")
                } catch (e2: Throwable) {
                    log("⚠️ 启用 $logName 失败: ${e2.message}")
                }
            }
        } catch (e: Throwable) {
            log("⚠️ enableDevice($logName) 异常: ${e.message}")
        }
    }

    private fun findMethod(device: Any, name: String, vararg params: Class<*>): Method {
        return try {
            device.javaClass.getMethod(name, *params)
        } catch (e: NoSuchMethodException) {
            device.javaClass.getDeclaredMethod(name, *params).also { it.isAccessible = true }
        }
    }

    private fun exceptionDetail(e: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 5) {
            sb.append("cause[$depth] ${current.javaClass.simpleName}: ${current.message}\n")
            val sw = StringWriter()
            current.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString().split("\n").take(6).joinToString("\n"))
            sb.append("\n")
            current = current.cause
            depth++
        }
        return sb.toString().trim()
    }

    // ==================== 座椅通风 ====================

    fun initSettingDevice(context: Context): Boolean {
        return try {
            settingDevice = BYDAutoSettingDevice.getInstance(context)
            if (settingDevice == null) {
                log("❌ BYDAutoSettingDevice.getInstance 返回 null")
                return false
            }
            log("✅ 获取 BYDAutoSettingDevice 实例成功")
            enableDevice(context, settingDevice!!, "Setting 设备")
            try {
                val r = settingDevice!!.hasFeature("DriverSeatVentilating")
                log("主驾座椅通风 feature = $r (1=支持)")
            } catch (e: Throwable) {
                log("⚠️ hasFeature 查询失败: ${e.message}")
            }
            true
        } catch (e: Throwable) {
            log("❌ 初始化 Setting 设备失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    fun setDriverSeatVentilating(context: Context, state: Int): Int? {
        if (settingDevice == null && !initSettingDevice(context)) return null
        return try {
            val method = findMethod(settingDevice!!, "setSeatVentilatingState", Int::class.java, Int::class.java)
            val result = method.invoke(settingDevice, DRIVER_SEAT, state) as? Int
            log("▶ 主驾座椅通风 → 档位=$state，返回码=$result (0=成功)")
            result
        } catch (e: Throwable) {
            log("❌ setSeatVentilatingState 调用失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun getDriverSeatVentilating(context: Context): Int? {
        if (settingDevice == null && !initSettingDevice(context)) return null
        return try {
            val method = findMethod(settingDevice!!, "getSeatVentilatingState", Int::class.java)
            val result = method.invoke(settingDevice, DRIVER_SEAT) as? Int
            log("◀ 主驾座椅通风状态 = $result (1=关,2=低,3=高)")
            result
        } catch (e: Throwable) {
            log("⚠️ 读取座椅通风状态失败: ${e.message}")
            null
        }
    }

    // ==================== 空调 ====================

    fun initAcDevice(context: Context): Boolean {
        return try {
            acDevice = BYDAutoAcDevice.getInstance(context)
            if (acDevice == null) {
                log("❌ BYDAutoAcDevice.getInstance 返回 null")
                return false
            }
            log("✅ 获取 BYDAutoAcDevice 实例成功")
            enableDevice(context, acDevice!!, "AC 设备")
            true
        } catch (e: Throwable) {
            log("❌ 初始化 AC 设备失败: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    fun setAcTemperature(context: Context, area: Int, value: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = acDevice!!.setAcTemperature(area, value, AC_CTRL_SOURCE_UI_KEY, AC_TEMPERATURE_UNIT_OC)
            log("▶ 空调(${areaLabel(area)}) → 温度=${value}°C，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ setAcTemperature 调用失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcTemperatureControlMode(context: Context, separate: Boolean): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        val mode = if (separate) AC_TEMPCTRL_SEPARATE_ON else AC_TEMPCTRL_SEPARATE_OFF
        return try {
            val result = acDevice!!.setAcTemperatureControlMode(AC_CTRL_SOURCE_UI_KEY, mode)
            log("▶ 分控=${if (separate) "开启" else "关闭"}，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ setAcTemperatureControlMode 调用失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcPower(context: Context, on: Boolean): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = if (on) acDevice!!.start(AC_CTRL_SOURCE_UI_KEY)
                          else acDevice!!.stop(AC_CTRL_SOURCE_UI_KEY)
            log("▶ 空调${if (on) "开启" else "关闭"}，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 开关空调失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcWindLevel(context: Context, level: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = acDevice!!.setAcWindLevel(AC_CTRL_SOURCE_UI_KEY, level)
            log("▶ 空调风量=$level，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 设置风量失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcWindMode(context: Context, mode: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val result = acDevice!!.setAcWindMode(AC_CTRL_SOURCE_UI_KEY, mode)
            log("▶ 空调风向=$mode，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 设置风向失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun setAcCycleMode(context: Context, inner: Boolean): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val mode = if (inner) 1 else 0
            val result = acDevice!!.setAcCycleMode(AC_CTRL_SOURCE_UI_KEY, mode)
            log("▶ 空调循环=${if (inner) "内循环" else "外循环"}，返回码=$result")
            result
        } catch (e: Throwable) {
            log("❌ 设置循环失败:\n${exceptionDetail(e)}")
            null
        }
    }

    fun getAcTemperature(context: Context, area: Int): Int? {
        if (acDevice == null && !initAcDevice(context)) return null
        return try {
            val method = findMethod(acDevice!!, "getTemprature", Int::class.java)
            val result = method.invoke(acDevice, area) as? Int
            log("◀ 读取空调(${areaLabel(area)})温度 = $result")
            result
        } catch (e: Throwable) {
            log("⚠️ 读取空调温度失败: ${e.message}")
            null
        }
    }

    private fun areaLabel(area: Int): String = when (area) {
        AC_TEMPERATURE_MAIN_DEPUTY -> "主副联动"
        AC_TEMPERATURE_MAIN -> "主驾"
        AC_TEMPERATURE_DEPUTY -> "副驾"
        AC_TEMPERATURE_REAR -> "后排"
        else -> "区域$area"
    }

    fun checkBydPermissions(context: Context) {
        val perms = listOf(
            "android.permission.BYDAUTO_SETTING_GET",
            "android.permission.BYDAUTO_SETTING_SET",
            "android.permission.BYDAUTO_SETTING_COMMON",
            "android.permission.BYDAUTO_AC_GET",
            "android.permission.BYDAUTO_AC_SET",
            "android.permission.BYDAUTO_AC_COMMON"
        )
        val pm = context.packageManager
        perms.forEach { perm ->
            val granted = try {
                pm.checkPermission(perm, context.packageName) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) { false }
            log(if (granted) "✅ $perm 已授权" else "❌ $perm 未授权")
        }
    }
}
