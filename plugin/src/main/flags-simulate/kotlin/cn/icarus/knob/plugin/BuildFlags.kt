package cn.icarus.knob.plugin

// 这份文件只在 "simulate" source set 里参与编译，产出 plugin-simulate.dex
// （见 build.gradle.kts）——不碰真实车控接口，先不签名时验证按键/GATT/
// 插件路由这条完整链路用。另一份变体在 src/main/flags-real/kotlin 下，
// 两份文件同名同路径、只有这一个值不一样，一次 Gradle 构建各自编译出
// plugin.dex / plugin-simulate.dex，不用手改值来回切换重新编译。
internal const val SIMULATE_CAR_CONTROL = true
