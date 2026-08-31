#pragma once

#include <stdint.h>

// 初始化 BLE HID 键盘：建好 HijelHID_BLEKeyboard、配置配对方式（数字
// 比对）、开始广播。在 setup() 里调用一次。
void ble_init();

// 当前是不是已经跟主机配对并且认证完成——发按键之前应该先检查这个，
// 没配对的时候乱发没有意义。
bool ble_is_paired();

// 按一下某个键（按下又立刻松开）。keycode 是 BLEHIDKeys.h 里的 KEY_*
// 常量。没配对时调用不会有任何效果。
void ble_tap(uint8_t keycode);
