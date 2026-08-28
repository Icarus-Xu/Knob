#pragma once

#include <stdint.h>

// 把 EC11 的 A/B 引脚配置成中断，按键引脚配置成输入。
// 在 setup() 里调用一次。
void encoder_init();

// 返回自上次调用以来旋钮转过的档位数
// （正数=顺时针，负数=逆时针），读完自动清零。
// 每次 loop() 调用一次。
int16_t encoder_get_diff();

// 做按键消抖和长按计时。每次 loop() 都要调用——
// 下面两个取事件的函数只有靠它才能被正确更新。
void encoder_poll_button();

// 每发生一次"短按"（按下又很快松开）就返回一次 true。
// 取到之后标志位会被清掉，所以每次点击只会被上报一次。
bool encoder_take_click();

// 和 encoder_take_click() 类似，但对应按住超过
// ENC_LONG_PRESS_MS 的长按。
bool encoder_take_long_press();
