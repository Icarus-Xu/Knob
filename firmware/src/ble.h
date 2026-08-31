#pragma once

#include <stdint.h>

// 初始化 BLE HID 键盘：建好 HijelHID_BLEKeyboard、配置配对方式（数字
// 比对）、开始广播，并且加一个自定义可写特征值，用来接收手机同步过来的
// "当前模块+数值"（见 ble_on_display_update）。在 setup() 里调用一次。
void ble_init();

// 当前是不是已经跟主机配对并且认证完成——发按键之前应该先检查这个，
// 没配对的时候乱发没有意义。
bool ble_is_paired();

// 按一下某个键（按下又立刻松开）。keycode 是 BLEHIDKeys.h 里的 KEY_*
// 常量。没配对时调用不会有任何效果。
void ble_tap(uint8_t keycode);

// 注册"手机同步了新的模块/数值"回调：手机往自定义特征值写 3 字节
// （byte0=module，byte1..2=小端 int16 value）时触发，用来驱动屏幕显示。
// module: 0=空调温度, 1=座椅通风。
void ble_on_display_update(void (*callback)(uint8_t module, int16_t value));
