#pragma once

#include <Arduino_GFX_Library.h>
#include <lvgl.h>

// 对应我们这块屏幕具体型号的驱动类。以后如果换了别的屏幕控制芯片，
// 改这一行就行。
typedef Arduino_GC9A01 SCREEN_CLASS;

// 初始化 SPI 总线、GC9A01 屏幕本身，以及 LVGL 的显示驱动。
// 在 setup() 里调用一次，且要在搭 UI 之前调用。
// 改自 X-Knob 的 port/display.cpp + lv_port_disp.cpp。
void display_init();

// LVGL 不会自己重绘，需要你定期"敲"它一下，它才会处理动画、
// 把变化的部分刷新到屏幕上。每次 loop() 都要调用这个函数。
void display_task_handler();

// 设置背光亮度，0~100（百分比），内部换算成 PWM 占空比。display_init()
// 里默认是 100（全亮），手机端插件根据光照传感器算出目标亮度后，通过
// 显示数据的 "brightness" 字段推过来，main.cpp 收到后调这个。
void display_set_brightness(uint8_t percent);
