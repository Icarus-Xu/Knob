package cn.icarus.knob.plugin

// 这份文件只在 "main" source set 里参与编译，产出 plugin.dex（见
// build.gradle.kts）——真实车控，不模拟。另一份变体在
// src/main/flags-simulate/kotlin 下，两份文件同名同路径、只有这一个值
// 不一样，一次 Gradle 构建各自编译出 plugin.dex / plugin-simulate.dex，
// 不用手改值来回切换重新编译。
internal const val SIMULATE_CAR_CONTROL = false
