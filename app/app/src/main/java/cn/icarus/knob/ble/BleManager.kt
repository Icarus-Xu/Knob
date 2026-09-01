package cn.icarus.knob.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import cn.icarus.knob.plugin.PluginLoader
import cn.icarus.knob.util.LogSink
import java.util.UUID

/**
 * phone <-> ESP32 knob 的唯一 BLE 通道。
 *
 * 不走标准 BLE HID 键盘 profile：旋钮以前伪装成 HID 键盘，靠手机侧无障碍
 * 服务在系统层拦截按键，但车机的定制系统没有暴露标准无障碍设置入口
 * （跳转直接崩 ActivityNotFoundException），这条路走不通。现在旋钮的
 * 每个动作都通过下面这个自定义特征值以 NOTIFY 的方式直接通知给手机，
 * 收到后直接转发给插件，不再经过系统按键分发这一层。
 *
 * 对应固件那边 firmware/src/ble.cpp 里的自定义服务：
 *   service UUID:              29afa70d-2312-4f04-9b36-fe9298284756
 *   按键事件特征值（NOTIFY）：   182f303d-ea98-4ee4-839e-5599aaf3f29d
 *     payload: 一串字段，每个 4 字节 = fieldId(1) + type(1，目前只有
 *     0=int16) + value(2，小端)。fieldId 用 FIELD_CODE/FIELD_REPEAT
 *     这两个数字常量代替字段名文本（跟固件 ble.h 的 KEY_FIELD_CODE/
 *     KEY_FIELD_REPEAT 数值对齐），比 DisplayData 那种"keyLen+key名"的
 *     编码省字节——这条通道每次转旋钮都要发，值得省。解码本身仍然是
 *     顺序无关、字段可扩展的（新增字段不会破坏旧字段的解析），只是这里
 *     解出来之后还是要按 FIELD_CODE/FIELD_REPEAT 取值传给
 *     onEvent("key", {code, action, repeat})——那个接口本身的字段名是
 *     插件契约的一部分（KnobPlugin.kt 里文档化的固定形状），不是这次
 *     要通用化的目标，壳在"编解码 BLE 字节"这一层已经不用认字段名了。
 *   显示同步特征值（WRITE）：    5f774e59-5079-4e69-8e5d-de0ee1220d3f
 *     payload: 通用键值对编码，见 write(Map) / encodeDisplayData() 的
 *     注释——这里（跟固件 ble.cpp 对应）完全不理解字段含义，插件传什么
 *     Map 就原样编码成字节流搬过去，固件那边自己认哪些 key。以后加/改
 *     显示字段只需要同时改插件和固件，不用碰这一层。payload 可能超过
 *     默认 ATT MTU（23 字节）能装下的量，连上后会先请求更大 MTU 再发现
 *     服务，见 gattCallback 里的 requestMtu()。
 */
object BleManager {
    private const val TAG = "BleManager"

    // 跟 firmware/src/ble.cpp 的 build_device_name() 命名约定对上。
    private const val DEVICE_NAME_PREFIX = "Knob-"
    private val SERVICE_UUID = UUID.fromString("29afa70d-2312-4f04-9b36-fe9298284756")
    private val KEY_EVENT_CHARACTERISTIC_UUID = UUID.fromString("182f303d-ea98-4ee4-839e-5599aaf3f29d")
    private val DISPLAY_CHARACTERISTIC_UUID = UUID.fromString("5f774e59-5079-4e69-8e5d-de0ee1220d3f")
    // 标准 BLE 描述符 UUID：Client Characteristic Configuration，订阅 NOTIFY 靠写这个。
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // 按键事件字段 ID，跟 firmware/src/ble.h 的 KEY_FIELD_CODE/KEY_FIELD_REPEAT 数值对齐。
    private const val FIELD_CODE = 1
    private const val FIELD_REPEAT = 2

    private var gatt: BluetoothGatt? = null
    private var displayCharacteristic: BluetoothGattCharacteristic? = null
    private var keyEventCharacteristic: BluetoothGattCharacteristic? = null

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

    /** GATT 是不是已经连上（能不能马上用还得看两个特征值是否都发现了，这里只看连接本身）。 */
    fun isConnected(): Boolean = gatt != null

    private const val RECONNECT_POLL_INTERVAL_MS = 5_000L
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollingStarted = false

    // 本来想用 ACTION_ACL_CONNECTED 广播做事件驱动重连，实测这台车机的
    // 蓝牙栈重连 knob 时压根不发这个广播（系统蓝牙设置界面显示已连接，
    // 但广播接收器完全没收到）——跟之前遇到的双重配对问题一样，这台车机
    // 的蓝牙栈很多地方不按标准 AOSP 行为来，不值得继续在广播上找解法。
    // 改成定时轮询：每 15 秒检查一下有没有连上，没连上就重试 init()，
    // 不依赖任何厂商可能不发的系统广播，也不依赖 Activity 在不在前台。
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isConnected()) init(appContext)
            pollHandler.postDelayed(this, RECONNECT_POLL_INTERVAL_MS)
        }
    }

    private lateinit var appContext: Context

    /**
     * 启动重连轮询，进程生命周期内只需要调一次（在 KnobApp.onCreate() 里调用）。
     * 第一次立刻查一次（不等 15 秒），后续才按间隔轮询——进程刚启动时
     * 大概率之前已经配对过，没必要让用户干等一整个轮询周期才连上。
     */
    fun startReconnectPolling(context: Context) {
        if (pollingStarted) return
        pollingStarted = true
        appContext = context.applicationContext
        pollHandler.post(pollRunnable)
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
        logBoth("找到已配对设备 ${knob.name}（bondState=${knob.bondState}），发起 GATT 连接")
        // autoConnect=false（直连）而不是 true：设备这时候已经在系统蓝牙里
        // 配对/连接着了，不是"以后随时可能出现"的场景。之前用 true 观察到
        // 会触发一次独立于系统 HID 连接的全新配对（弹出跟系统配对时不一样
        // 的 6 位数），怀疑是 autoConnect 走的后台自动重连路径在某些机型/
        // 车机蓝牙栈上没有正确复用已有的连接和绑定信息。
        gatt = knob.connectGatt(context, /* autoConnect = */ false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 顺手提示系统这条连接要低延迟（配合固件那边主动请求的
                    // 短连接间隔），不是这次卡顿的关键，但不吃亏。
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // 先协商更大的 MTU 再发现服务——默认 ATT MTU 只有 23 字节，
                    // 装不下带中文标题的显示同步 payload。onMtuChanged() 里
                    // 才真正调 discoverServices()，不管协商成不成功都会继续
                    // （失败了就退回默认 MTU，标题超长时截断，不阻塞连接）。
                    logBoth("GATT 已连接，请求更大 MTU")
                    g.requestMtu(185)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // autoConnect=false 下断开不会自动重连，得关掉这个 GATT
                    // 对象、清空状态，下次调用 init() 才会重新连接。
                    logBoth("GATT 断开（status=$status），已停止", isWarning = true)
                    g.close()
                    gatt = null
                    displayCharacteristic = null
                    keyEventCharacteristic = null
                    notifyPluginBluetoothState(connected = false)
                    // 断线的这一刻就立刻重试，不等下一次轮询（最多可能再等
                    // 15 秒）——knob 断电重启的话，手机检测到断线时它大概率
                    // 已经在重新广播了，直接连大概率能一次成功；连不上的话
                    // 轮询还是会作为兜底继续重试。
                    init(appContext)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            logBoth("MTU 协商结果=$mtu (status=$status)，开始发现服务")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                logBoth("没找到自定义服务（UUID 对不上，固件版本可能太旧）", isWarning = true)
                return
            }

            displayCharacteristic = service.getCharacteristic(DISPLAY_CHARACTERISTIC_UUID)
            logBoth(if (displayCharacteristic != null) "显示同步特征值已就绪" else "没找到显示同步特征值", isWarning = displayCharacteristic == null)

            val keyChar = service.getCharacteristic(KEY_EVENT_CHARACTERISTIC_UUID)
            // GATT 同一时刻只能有一个写操作在飞，两个写操作前后脚发起
            // 后面那个会被直接拒绝——如果 CCCD 订阅写真的发出去了
            // （waitingForCccd=true），"通知插件已连接"（会触发插件的
            // syncToKnob() 写显示特征值）就得等它在 onDescriptorWrite()
            // 里真正完成之后再做，不能在这里就地通知，会跟这个写操作撞车。
            val waitingForCccd = if (keyChar == null) {
                logBoth("没找到按键事件特征值", isWarning = true)
                false
            } else {
                keyEventCharacteristic = keyChar
                subscribeKeyEvents(g, keyChar)
            }
            if (!waitingForCccd && displayCharacteristic != null) {
                notifyPluginBluetoothState(connected = true, device = g.device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            logBoth(
                if (status == BluetoothGatt.GATT_SUCCESS) "按键事件通知订阅完成" else "按键事件通知订阅失败 status=$status",
                isWarning = status != BluetoothGatt.GATT_SUCCESS
            )
            // CCCD 这个写操作真正完成了，这时候再通知插件已连接（触发
            // syncToKnob() 写显示特征值）才不会跟它撞车。
            if (displayCharacteristic != null) {
                notifyPluginBluetoothState(connected = true, device = g.device)
            }
        }

        // API 33+ 路径。
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == KEY_EVENT_CHARACTERISTIC_UUID) handleKeyEventNotification(value)
        }

        // API < 33 路径——33+ 时上面那个新回调已经处理过，这里直接跳过，
        // 不然同一个通知会被处理两遍。
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == KEY_EVENT_CHARACTERISTIC_UUID) handleKeyEventNotification(characteristic.value)
        }
    }

    /**
     * 订阅按键事件特征值：本地开启通知回调 + 写 CCCD 描述符告诉外设
     * "开始推送"，两步都要做，缺一个都收不到通知。
     * @return CCCD 写请求是不是真的发出去了——true 的话调用方要等
     *   onDescriptorWrite() 回调才算这个操作完成；false 的话（没找到
     *   特征值/描述符，或者写请求本身就同步失败）不会有回调，调用方
     *   不能傻等。
     */
    @SuppressLint("MissingPermission")
    private fun subscribeKeyEvents(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(characteristic, true)) {
            logBoth("setCharacteristicNotification 失败，按键事件收不到", isWarning = true)
            return false
        }
        val cccd = characteristic.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            logBoth("按键事件特征值没有 CCCD 描述符，收不到通知", isWarning = true)
            return false
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        logBoth(if (ok) "已发起按键事件通知订阅" else "订阅按键事件通知失败", isWarning = !ok)
        return ok
    }

    // BluetoothGattCallback 的回调（包括 onCharacteristicChanged）跑在蓝牙
    // Binder 线程上，不是主线程——插件的按键处理会碰 View（切页面时
    // removeAllViews/addView），必须先切回主线程再转发，不然直接抛
    // CalledFromWrongThreadException，还会被 onEvent() 的 try/catch 吞掉，
    // 表现成"插件未处理"+页面被清空但没画上新内容（残留空白）。
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 通用解码：一串 "fieldId(1) + type(1) + value" 字段，目前只认
     * type=0（int16），遇到不认识的类型没法知道占几个字节，直接停止解析
     * ——后面的字段宁可丢掉也不能瞎猜着继续读。顺序无关、缺字段/多字段
     * 都不影响已解出来的部分，跟固件那边 parse_display_data() 是同一套
     * 思路，只是 key 是数字 ID 不是字符串。
     */
    private fun decodeIntFields(value: ByteArray): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        var pos = 0
        while (pos + 4 <= value.size) {
            val fieldId = value[pos].toInt() and 0xFF
            val type = value[pos + 1].toInt() and 0xFF
            if (type != 0) break
            val low = value[pos + 2].toInt() and 0xFF
            val high = value[pos + 3].toInt() and 0xFF
            result[fieldId] = (high shl 8) or low
            pos += 4
        }
        return result
    }

    /**
     * 收到一次按键通知：解出 FIELD_CODE/FIELD_REPEAT 两个字段。按下+抬起
     * 都转发给插件（跟以前无障碍服务转发系统按键时的语义一致，只吃一半
     * 会让插件内部按键状态错乱），插件不处理时这里没有壳兜底可吃——不再
     * 走系统按键分发，没有"焦点被移动/按钮被点击"这类风险了。
     */
    private fun handleKeyEventNotification(value: ByteArray) {
        val fields = decodeIntFields(value)
        val code = fields[FIELD_CODE] ?: run {
            logBoth("按键通知缺 code 字段（${value.size} 字节）", isWarning = true)
            return
        }
        val repeat = fields[FIELD_REPEAT] ?: 1
        logBoth("收到按键事件 code=$code repeat=$repeat")
        mainHandler.post { dispatchKeyToPlugin(code, repeat) }
    }

    private fun dispatchKeyToPlugin(code: Int, repeat: Int) {
        PluginLoader.current?.onEvent("key", mapOf("code" to code, "action" to KeyEvent.ACTION_DOWN, "repeat" to repeat))
        val consumed = PluginLoader.current?.onEvent(
            "key",
            mapOf("code" to code, "action" to KeyEvent.ACTION_UP, "repeat" to repeat)
        ) ?: false
        logBoth(if (consumed) "  → 插件已处理" else "  → 插件未处理")
    }

    /**
     * 通知插件蓝牙连接状态变化（复用 KnobPlugin.onPush("bluetooth", ...)
     * 已经约定好的字段）。跟按键通知一样切回主线程再转发——onPush 目前
     * 的实现不碰 View，但谁也不能保证以后不会加，统一走这条路更保险，
     * 不用每加一个新回调入口都重新想一遍线程安全。
     */
    @SuppressLint("MissingPermission")
    private fun notifyPluginBluetoothState(connected: Boolean, device: BluetoothDevice? = null) {
        val data = if (connected && device != null) {
            mapOf("connected" to true, "name" to (device.name ?: ""), "mac" to device.address)
        } else {
            mapOf("connected" to connected)
        }
        mainHandler.post { PluginLoader.current?.onPush("bluetooth", data) }
    }

    /**
     * 把插件给的通用 Map 编码成字节流写给 knob——这里完全不理解字段
     * 含义，只认值的 Kotlin 类型（Int/String/Boolean），不认 key 名。
     * @return 是否成功发起写入请求——GATT 写入本身是异步的，这里只代表
     *   "请求发出去了"，不代表对方已经收到（固件那边收到后会在串口打日志，
     *   要确认送达以那个为准）。
     */
    @SuppressLint("MissingPermission")
    fun write(data: Map<String, Any>): Boolean {
        val g = gatt ?: run {
            logBoth("write() 失败：GATT 还没连上", isWarning = true)
            return false
        }
        val char = displayCharacteristic ?: run {
            logBoth("write() 失败：显示同步特征值还没发现（服务发现可能还没完成）", isWarning = true)
            return false
        }
        val payload = encodeDisplayData(data)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }
        // gatt/characteristic 都在，写请求本身却被拒绝——通常是 Android
        // 同一时刻只能有一个 GATT 写操作在飞，跟另一个还没完成的写操作
        // 撞车了（比如连接刚建立时的 CCCD 订阅写）。之前这个分支完全没
        // 日志，排查的时候只能看到 KnobHostImpl 那句笼统的"GATT 未就绪"，
        // 补一条能看出具体是这个原因。
        if (!ok) {
            logBoth("write() 失败：writeCharacteristic 请求被拒绝（可能跟另一个 GATT 操作撞车了）", isWarning = true)
        }
        return ok
    }

    /**
     * 通用键值对编码——这一层不理解字段含义，只按值的 Kotlin 类型编码，
     * 固件那边（firmware/src/ble.cpp 的 parse_display_data）按同样的格式
     * 解码。每条 entry：
     *   keyLen(1 字节) + key(ASCII, keyLen 字节)
     *   + type(1 字节: 0=int16 / 1=string / 2=bool)
     *   + 值本身：int16 是 2 字节小端；string 是 1 字节长度（裁到最多
     *     60 字节）+ UTF-8 内容；bool 是 1 字节 0/1
     * 不支持的值类型直接跳过并打警告日志，不中断其余字段的编码。
     */
    private fun encodeDisplayData(data: Map<String, Any>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((key, value) in data) {
            val keyBytes = key.toByteArray(Charsets.US_ASCII).let { if (it.size > 255) it.copyOf(255) else it }
            when (value) {
                is Int -> {
                    out.write(keyBytes.size)
                    out.write(keyBytes)
                    out.write(0)
                    out.write(value and 0xFF)
                    out.write((value shr 8) and 0xFF)
                }
                is String -> {
                    val strBytes = value.toByteArray(Charsets.UTF_8).let { if (it.size > 60) it.copyOf(60) else it }
                    out.write(keyBytes.size)
                    out.write(keyBytes)
                    out.write(1)
                    out.write(strBytes.size)
                    out.write(strBytes)
                }
                is Boolean -> {
                    out.write(keyBytes.size)
                    out.write(keyBytes)
                    out.write(2)
                    out.write(if (value) 1 else 0)
                }
                else -> logBoth("encodeDisplayData 跳过不支持的字段类型：$key=$value (${value::class.simpleName})", isWarning = true)
            }
        }
        return out.toByteArray()
    }
}
