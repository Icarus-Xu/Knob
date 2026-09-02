package cn.icarus.knob

import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import cn.icarus.knob.ble.BleManager
import cn.icarus.knob.databinding.ActivityKnobBinding
import cn.icarus.knob.host.KnobHostImpl
import cn.icarus.knob.plugin.PluginLoader
import cn.icarus.knob.util.CrashHandler
import cn.icarus.knob.util.LogSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 壳的主界面：左边按钮 + 日志，右边留白（待插件加载独立页面）。
 * 所有系统信息、权限、插件状态都直接打进日志，不单独占 UI。
 * 车控逻辑已全部移入插件。
 */
class KnobActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKnobBinding

    private val REQUEST_AC_COMMON_PERM = 2001
    private val REQUEST_BLUETOOTH_PERM = 2002
    private val REQUEST_SETTING_COMMON_PERM = 2003

    // 文件选择器：选择 plugin.dex（SAF，无需存储权限）
    private val pickPluginLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                LogSink.append("⚠️ 未选择插件文件")
                return@registerForActivityResult
            }
            importPlugin(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKnobBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 屏幕常亮：靠旋钮操作，不是触屏，不加这个的话没有触摸事件会
        // 正常走到系统的自动息屏/锁屏。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 安装全局崩溃捕获器（崩溃时导出 crash_*.txt 到 Downloads）
        CrashHandler.install()

        // 绑定日志窗口
        LogSink.bind(binding.tvLog)
        LogSink.section("Knob 启动")

        // 所有信息直接打进日志
        logSystemInfo()
        logPermissionStatus()
        logPluginStatus()

        binding.btnPickPlugin.setOnClickListener {
            pickPluginLauncher.launch(arrayOf("*/*"))
        }
        binding.btnClearLog.setOnClickListener { LogSink.clear() }
        binding.btnSaveLog.setOnClickListener { saveLog() }

        // 权限申请不在启动时自动弹——每个权限单独一个按键，用户手动点了
        // 才请求。之前试过在 onResume() 里自动发起，在某些设备上 onResume()
        // 会被系统层原因高频反复触发，无条件重复请求权限直接死循环；
        // 干脆全部改成手动触发，既不用猜什么时机安全，也不会有意外弹窗。
        binding.btnReqAcCommon.setOnClickListener { requestSinglePermission("android.permission.BYDAUTO_AC_COMMON", REQUEST_AC_COMMON_PERM) }
        binding.btnReqSettingCommon.setOnClickListener { requestSinglePermission("android.permission.BYDAUTO_SETTING_COMMON", REQUEST_SETTING_COMMON_PERM) }
        binding.btnReqBluetooth.setOnClickListener { requestBluetoothPermissionAndConnect() }
        refreshPermButtonStates()

        // 插件已在 Application.onCreate 同步加载完，这里把页面容器交给它
        bindPluginUi()
    }

    /** 若插件已加载，把右半边容器交给插件，让插件渲染页面 */
    private fun bindPluginUi() {
        PluginLoader.current?.onEvent("ui.bind", mapOf("container" to binding.pluginContainer))
    }

    /**
     * Activity 销毁时通知插件解绑容器。
     * 插件实例是进程级单例（PluginLoader.current），活得比 Activity 久，
     * 不解绑会让插件一直持有已销毁的 View 树。注意这里不能 unload 插件。
     */
    override fun onDestroy() {
        PluginLoader.current?.onEvent("ui.unbind", emptyMap())
        super.onDestroy()
    }

    /**
     * 所有按键先交给插件：蓝牙 HID 旋钮/按键、车机物理键、系统返回键统一走 onEvent("key")。
     *
     * 用 dispatchKeyEvent 而不是 onKeyDown/onKeyUp：它在 View 树分发之前拿到事件，
     * 不会被获得焦点的按钮抢走 DPAD/ENTER 等键 —— 旋钮上报的正是这类键。
     * 插件不消费则交回系统默认处理（返回键的默认行为就是关闭页面）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val consumed = PluginLoader.current?.onEvent("key", mapOf(
            "code" to event.keyCode,
            "action" to event.action
        )) ?: false
        return consumed || super.dispatchKeyEvent(event)
    }

    /** 把选中的 dex 拷贝到私有目录并加载 */
    private fun importPlugin(uri: Uri) {
        LogSink.section("导入插件")
        try {
            val ok = PluginLoader.importDex(this, uri)
            if (ok) {
                PluginLoader.current?.init(applicationContext, KnobHostImpl)
                LogSink.append("✅ 插件导入并加载成功")
                Toast.makeText(this, "插件已导入并加载", Toast.LENGTH_SHORT).show()
                // 把页面容器交给插件，让插件开始渲染页面
                bindPluginUi()
                // 示例：壳主动推数据给插件（state / bluetooth / hardware / config）
                pushShellData()
            } else {
                LogSink.append("❌ 插件导入/加载失败，请重新选择有效的 plugin.dex")
                Toast.makeText(this, "插件加载失败，请重新选择", Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            LogSink.append("❌ 导入异常: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    /**
     * 插件刚导入/加载完，把设备信息推给它。
     * 蓝牙连接状态不在这里推假数据——真实的连接状态由 BleManager 在
     * GATT 连上/断开时直接推给插件（见 BleManager.notifyPluginBluetoothState）。
     */
    private fun pushShellData() {
        val p = PluginLoader.current ?: return
        p.onPush("state", mapOf(
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        ))
    }

    // ==================== 信息直接打进日志 ====================

    private fun logSystemInfo() {
        LogSink.section("系统信息")
        LogSink.append("厂商: ${Build.MANUFACTURER}")
        LogSink.append("型号: ${Build.MODEL}")
        LogSink.append("品牌: ${Build.BRAND}")
        LogSink.append("设备: ${Build.DEVICE}")
        LogSink.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        LogSink.append("指纹: ${Build.FINGERPRINT}")
    }

    private fun logPermissionStatus() {
        val perms = listOf(
            "android.permission.BYDAUTO_AC_COMMON",
            "android.permission.BYDAUTO_AC_GET",
            "android.permission.BYDAUTO_AC_SET",
            "android.permission.BYDAUTO_SETTING_COMMON",
            "android.permission.BYDAUTO_SETTING_GET",
            "android.permission.BYDAUTO_SETTING_SET"
        )
        val pm = packageManager
        LogSink.section("BYDAUTO 权限状态")
        perms.forEach { perm ->
            val granted = try {
                pm.checkPermission(perm, packageName) == PackageManager.PERMISSION_GRANTED
            } catch (e: Throwable) { false }
            val short = perm.removePrefix("android.permission.")
            LogSink.append(if (granted) "✅ $short 已授权" else "❌ $short 未授权")
        }
    }

    private fun logPluginStatus() {
        val p = PluginLoader.current
        LogSink.section("插件状态")
        if (p != null) {
            LogSink.append("✅ 插件已加载")
        } else {
            LogSink.append("⚠️ 插件未加载，请点「选择插件」导入 plugin.dex")
        }
    }

    // ==================== 保存日志 ====================

    private fun saveLog() {
        val content = binding.tvLog.text.toString().trim()
        if (content.isEmpty()) {
            Toast.makeText(this, "日志为空，无内容可保存", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date())
            val fileName = "log_$timestamp.txt"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Toast.makeText(this, "保存失败：无法创建文件", Toast.LENGTH_SHORT).show()
                    return
                }
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    ?: run {
                        Toast.makeText(this, "保存失败：无法写入", Toast.LENGTH_SHORT).show()
                        return
                    }
                Toast.makeText(this, "已保存到 Downloads/$fileName", Toast.LENGTH_LONG).show()
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.exists()) downloads.mkdirs()
                val file = File(downloads, fileName)
                file.writeText(content)
                Toast.makeText(this, "已保存到 ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== BYDAUTO 运行时权限申请 ====================

    /** 申请单个 dangerous 级运行时权限，按钮点击时调用。 */
    private fun requestSinglePermission(perm: String, requestCode: Int) {
        val shortName = perm.removePrefix("android.permission.")
        LogSink.section("申请权限：$shortName")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            LogSink.append("⚠️ 当前系统无需运行时申请（API < 23）")
            return
        }
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            LogSink.append("✅ 已经是授权状态")
            refreshPermButtonStates()
            return
        }
        requestPermissions(arrayOf(perm), requestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_AC_COMMON_PERM -> {
                LogSink.append(if (granted) "✅ BYDAUTO_AC_COMMON 已授权" else "❌ BYDAUTO_AC_COMMON 未授权")
            }
            REQUEST_SETTING_COMMON_PERM -> {
                LogSink.append(if (granted) "✅ BYDAUTO_SETTING_COMMON 已授权" else "❌ BYDAUTO_SETTING_COMMON 未授权")
            }
            REQUEST_BLUETOOTH_PERM -> {
                LogSink.append(if (granted) "✅ BLUETOOTH_CONNECT 已授权" else "❌ BLUETOOTH_CONNECT 未授权，BLE 写入功能不可用")
                if (granted) BleManager.init(applicationContext)
            }
        }
        refreshPermButtonStates()
    }

    // ==================== 权限按钮的红/绿状态 ====================

    /**
     * 按当前实际权限状态刷新三个权限按钮的颜色：没授权=红色可点，已
     * 授权=绿色不可点（授权之后没有"撤销"这个操作，不可点直接表达
     * "已经完成，不用再管了"）。跟上面「选择插件」那排按钮同一套圆角
     * 色块+图标+文字风格。
     */
    private fun refreshPermButtonStates() {
        val acGranted = checkSelfPermission("android.permission.BYDAUTO_AC_COMMON") == PackageManager.PERMISSION_GRANTED
        updatePermButtonState(binding.btnReqAcCommon, binding.tvReqAcCommonLabel, acGranted)

        val settingGranted = checkSelfPermission("android.permission.BYDAUTO_SETTING_COMMON") == PackageManager.PERMISSION_GRANTED
        updatePermButtonState(binding.btnReqSettingCommon, binding.tvReqSettingCommonLabel, settingGranted)

        val bleGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        updatePermButtonState(binding.btnReqBluetooth, binding.tvReqBluetoothLabel, bleGranted)
    }

    private fun updatePermButtonState(container: LinearLayout, label: TextView, granted: Boolean) {
        container.setBackgroundResource(if (granted) R.drawable.bg_btn_green else R.drawable.bg_btn_red)
        label.setTextColor(Color.parseColor(if (granted) "#2E7D32" else "#C62828"))
        container.isClickable = !granted
        container.isFocusable = !granted
        container.foreground = if (granted) null else selectableItemForeground()
    }

    /** 每次都取一份新的 ?attr/selectableItemBackground drawable，不同 View 不能共用同一个实例（内部状态会互相打架）。 */
    private fun selectableItemForeground(): Drawable? {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val drawable = ta.getDrawable(0)
        ta.recycle()
        return drawable
    }

    // ==================== 蓝牙权限 + GATT 连接 ====================

    /**
     * BLUETOOTH_CONNECT 是 API 31+ 才有的运行时权限，更早的系统靠
     * manifest 里 maxSdkVersion=30 的旧版 BLUETOOTH/BLUETOOTH_ADMIN
     * （安装时自动授予，不用申请）覆盖，直接连。「申请蓝牙权限」按钮触发。
     */
    private fun requestBluetoothPermissionAndConnect() {
        LogSink.section("蓝牙权限 / GATT 连接")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            LogSink.append("当前系统无需运行时申请蓝牙权限（API < 31）")
            BleManager.init(applicationContext)
            return
        }
        val granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            LogSink.append("✅ BLUETOOTH_CONNECT 已授权")
            BleManager.init(applicationContext)
            refreshPermButtonStates()
        } else {
            LogSink.append("请求 BLUETOOTH_CONNECT 权限")
            requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH_PERM)
        }
    }
}
