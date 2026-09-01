package cn.icarus.knob

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.icarus.knob.util.LogSink

/**
 * 开机自启：以前无障碍服务绑定顺带把进程拉起来，现在改用监听开机广播。
 * BOOT_COMPLETED 是标准 action；QUICKBOOT_POWERON 是部分定制系统
 * （国产 ROM/车机常见的"快速开机"路径）用的非标准 action，不一定会发
 * 标准 BOOT_COMPLETED——参考同类第三方车控 App（dotix）两个都注册了，
 * 这里照抄，覆盖车机实际走的是哪条路径。
 *
 * onReceive() 本身不需要做什么：系统投递广播前必须先把本进程和
 * Application 起来（KnobApp.onCreate() 会先跑完，BleManager 的重连轮询
 * 和插件加载那时已经生效），这里只是记一条日志，确认到底是哪个广播把
 * 进程拉起来的，方便以后排查车机走的是哪条开机路径。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LogSink.append("[开机自启] 收到广播 action=${intent.action}，进程已拉起")
    }
}
