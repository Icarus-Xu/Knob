#include "encoder.h"
#include "config.h"
#include <Arduino.h>

// --- 旋转方向：正交解码 ---
//
// EC11 内部有 A、B 两路开关，机械结构上错开了一点，所以转动旋钮时
// 它们不会同时触发。靠它们"谁先变"就能判断方向：
//   顺时针：  A、B 按 00 -> 01 -> 11 -> 10 -> 00 ... 的顺序变化
//   逆时针：  顺序正好反过来
//
// QUAD_TABLE 的下标是"上一次的 2 位状态 + 这一次的 2 位状态"拼成的
// 4 位数（一共 16 种组合），查出来的值就是这一次跳变对应的 -1/0/+1。
// 这是很成熟、被广泛验证过的"整步"解码表——比单纯看某一个引脚的
// 边沿去猜方向要抗干扰得多。
static const int8_t QUAD_TABLE[16] = {
    0, -1, 1, 0,
    1, 0, 0, -1,
    -1, 0, 0, 1,
    0, 1, -1, 0,
};

static volatile uint8_t s_quad_state = 0;   // 最近一次 A/B 读数，用作查表下标
static volatile int32_t s_enc_accum = 0;    // 原始跳变计数；4 次跳变 = 转过 1 个物理档位

// 这个函数会在 A 或 B 每次电平变化时触发，运行在中断优先级——
// 要写得尽量短，绝对不能在里面调用 Serial.print 这类耗时的操作。
void IRAM_ATTR encoder_isr() {
    uint8_t a = digitalRead(ENC_PIN_A);
    uint8_t b = digitalRead(ENC_PIN_B);
    s_quad_state = ((s_quad_state << 2) | (a << 1) | b) & 0x0F;
    s_enc_accum += QUAD_TABLE[s_quad_state];
}

void encoder_init() {
    pinMode(ENC_PIN_A, INPUT_PULLUP);
    pinMode(ENC_PIN_B, INPUT_PULLUP);
    pinMode(ENC_PIN_SW, INPUT_PULLUP);
    // CHANGE = 上升沿、下降沿都触发，正好是上面查表算法需要的。
    attachInterrupt(digitalPinToInterrupt(ENC_PIN_A), encoder_isr, CHANGE);
    attachInterrupt(digitalPinToInterrupt(ENC_PIN_B), encoder_isr, CHANGE);
}

int16_t encoder_get_diff() {
    // 常见的 EC11 转一档（一次"咔哒"手感）对应 4 次正交跳变。
    // 如果你手上这颗转起来感觉要转两下才动一格，说明它一档只有
    // 2 次跳变——把下面的 /4 改成 /2 即可。
    static int32_t last_reported = 0;
    int32_t accum = s_enc_accum; // volatile 读取；函数执行过程中 ISR 可能改它，没关系
    int16_t steps = (int16_t)((accum - last_reported) / 4);
    if (steps != 0) {
        last_reported += (int32_t)steps * 4;
    }
    return steps;
}

// --- 按键：消抖 + 短按/长按判定 ---
//
// 机械开关按下/松开的瞬间会有几毫秒的"抖动"，如果直接读电平会
// 产生很多多余的跳变。这里的做法很简单：20ms 之内的状态变化一律
// 忽略（消抖），再靠 millis() 时间戳区分"短按"和"长按"。

static bool s_btn_pressed = false;     // 消抖之后的当前按键状态
static uint32_t s_press_start_ms = 0;  // 这次按下是什么时候开始的
static uint32_t s_last_change_ms = 0;  // 用于消抖计时
static bool s_long_fired = false;      // 这次按下是否已经触发过长按事件
static bool s_click_flag = false;      // "发生了一次短按"的一次性标志位
static bool s_long_flag = false;       // "发生了一次长按"的一次性标志位

void encoder_poll_button() {
    bool pressed = (digitalRead(ENC_PIN_SW) == LOW); // 接线是按下时低电平（上拉）
    uint32_t now = millis();

    if (pressed != s_btn_pressed && (now - s_last_change_ms) > 20) { // 20ms 消抖窗口
        s_last_change_ms = now;
        s_btn_pressed = pressed;
        if (pressed) {
            s_press_start_ms = now;
            s_long_fired = false;
        } else if (!s_long_fired) {
            // 在触发长按之前就松开了，而且这次按下还没报过长按
            // —— 那就算一次短按。
            s_click_flag = true;
        }
    }

    // 还按着，而且已经超过长按阈值？
    if (s_btn_pressed && !s_long_fired && (now - s_press_start_ms) >= ENC_LONG_PRESS_MS) {
        s_long_fired = true;
        s_long_flag = true;
    }
}

bool encoder_take_click() {
    if (s_click_flag) {
        s_click_flag = false;
        return true;
    }
    return false;
}

bool encoder_take_long_press() {
    if (s_long_flag) {
        s_long_flag = false;
        return true;
    }
    return false;
}
