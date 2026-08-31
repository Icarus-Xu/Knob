// 独立 BLE HID 测试固件：只验证 ESP32-S3 能不能被手机/车机识别成蓝牙键盘、
// 配对连接后按键事件能不能真的送达。不依赖屏幕、不依赖编码器。
// 编译烧录：pio run -e test_ble -t upload -t monitor
//
// 用法：烧录后在手机/车机的蓝牙设置里找到 "KnobPanel-XXXX"（后 4 位是
// 该板子蓝牙 MAC 的后 2 字节，每块板子不一样）并配对连接，配对方式是
// Passkey（数字比对）：ESP32 和手机会各自显示一个 6 位数，串口和手机
// 弹窗上应该是同一个数字，确认一致后在手机上点确认即可，不需要输入。
// 连上之后每隔 2 秒会交替发送一次左/右方向键——找一个能看到光标或者
// 输入框的地方（比如手机记事本 App、电脑文本编辑器），确认真的收到了
// 按键。

#include <Arduino.h>
#include "ble.h"
#include <HijelHID_BLEKeyboard.h> // KEY_LEFT / KEY_RIGHT 常量

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("[TestBLE] boot");

    ble_init();
}

void loop() {
    static bool was_paired = false;
    bool paired = ble_is_paired();
    if (paired != was_paired) {
        was_paired = paired;
        Serial.println(paired ? "[TestBLE] paired" : "[TestBLE] not paired");
    }

    static uint32_t last_send_ms = 0;
    static bool send_left = true;
    if (paired && millis() - last_send_ms >= 2000) {
        last_send_ms = millis();
        if (send_left) {
            Serial.println("[TestBLE] tap LEFT");
            ble_tap(KEY_LEFT);
        } else {
            Serial.println("[TestBLE] tap RIGHT");
            ble_tap(KEY_RIGHT);
        }
        send_left = !send_left;
    }

    delay(20);
}
