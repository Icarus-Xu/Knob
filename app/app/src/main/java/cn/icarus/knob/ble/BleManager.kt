package cn.icarus.knob.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import cn.icarus.knob.util.LogSink
import java.util.UUID

/**
 * phone → ESP32 knob 的 BLE 写入通道。
 *
 * 跟 knob 广播的标准 HID 键盘连接是完全独立的两条通道，共存不冲突：
 * HID 那条是系统蓝牙栈管的输入设备连接，这条是 App 自己发起的 GATT
 * client 连接（同一个物理蓝牙链路上可以叠加多个 profile）。
 *
 * 对应固件那边 firmware/src/ble.cpp 里新增的自定义特征值：
 *   service UUID:        29afa70d-2312-4f04-9b36-fe9298284756
 *   characteristic UUID: 5f774e59-5079-4e69-8e5d-de0ee1220d3f
 *   payload: 3 字节，byte0=module(0/1)，byte1..2=小端 int16 value
 */
object BleManager {
    private const val TAG = "BleManager"

    // 跟 firmware/src/ble.cpp 的 build_device_name() 命名约定对上。
    private const val DEVICE_NAME_PREFIX = "Knob-"
    private val SERVICE_UUID = UUID.fromString("29afa70d-2312-4f04-9b36-fe9298284756")
    private val CHARACTERISTIC_UUID = UUID.fromString("5f774e59-5079-4e69-8e5d-de0ee1220d3f")

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    // 这个项目一直没有 adb（见 docs/验证结论.md），日志得打进 App 里那个
    // 滚动窗口才看得见——Log.x() 只是留个习惯，真正调试靠这个。
    private fun logBoth(msg: String, isWarning: Boolean = false) {
        if (isWarning) Log.w(TAG, msg) else Log.i(TAG, msg)
        LogSink.append("[BleManager] $msg")
    }

    /**
     * BLUETOOTH_CONNECT（API 31+）是运行时权限，没拿到就调用
     * adapter.bondedDevices/connectGatt 这些接口会直接抛 SecurityException
     * 崩掉整个 App——这里做成公开方法，所有会碰蓝牙 API 的入口（init()、
     * 以及以后可能新增的调用点）都先过一遍这个检查，不指望调用方每次都
     * 记得自己判断。
     */
    fun hasBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true // API < 31 不需要运行时申请
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 找已配对的 knob，发起 GATT 连接。调用前需要已经拿到
     * BLUETOOTH_CONNECT 权限（API 31+）——没拿到会直接打日志退出，不会崩。
     *
     * 已经连上/正在连接时重复调用直接跳过——不然会叠加出好几条独立的
     * GATT 连接。如果之前因为"当时还没配对"而没找到设备，可以放心
     * 再调一次重试（这种情况下 gatt 还是 null）。
     */
    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        if (!hasBluetoothPermission(context)) {
            logBoth("init() 中止：还没拿到 BLUETOOTH_CONNECT 权限", isWarning = true)
            return
        }
        if (gatt != null) {
            logBoth("init() 跳过：已经连接/正在连接中")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val knob = adapter?.bondedDevices?.firstOrNull { it.name?.startsWith(DEVICE_NAME_PREFIX) == true }
        if (knob == null) {
            logBoth("未找到已配对的 Knob 设备（名字以 \"$DEVICE_NAME_PREFIX\" 开头），先去蓝牙设置配对，配对后可以重新触发一次 init()", isWarning = true)
            return
        }
        logBoth("找到已配对设备 ${knob.name}，发起 GATT 连接")
        gatt = knob.connectGatt(context, /* autoConnect = */ true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logBoth("GATT 已连接，开始发现服务")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logBoth("GATT 断开（status=$status），autoConnect 会在设备重新出现时自动重连", isWarning = true)
                    characteristic = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val char = g.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (char == null) {
                logBoth("没找到自定义特征值（service/characteristic UUID 对不上，固件版本可能太旧）", isWarning = true)
                return
            }
            characteristic = char
            logBoth("自定义特征值已就绪，可以调用 write() 了")
        }
    }

    /**
     * 把 module/value 编码成 3 字节写给 knob。
     * @return 是否成功发起写入请求——GATT 写入本身是异步的，这里只代表
     *   "请求发出去了"，不代表对方已经收到（固件那边收到后会在串口打日志，
     *   要确认送达以那个为准）。
     */
    @SuppressLint("MissingPermission")
    fun write(module: Int, value: Int): Boolean {
        val g = gatt ?: run {
            logBoth("write() 失败：GATT 还没连上", isWarning = true)
            return false
        }
        val char = characteristic ?: run {
            logBoth("write() 失败：特征值还没发现（服务发现可能还没完成）", isWarning = true)
            return false
        }
        val payload = byteArrayOf(
            module.toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
    }
}
