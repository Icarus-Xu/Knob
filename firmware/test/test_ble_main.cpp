// 独立 BLE 测试固件：验证 ESP32-S3 能不能被手机/车机配对连接，配对
// 加密建立后按键事件通知和显示同步写入两条自定义特征值能不能正常工作。
// 不依赖屏幕、不依赖编码器。
// 编译烧录：pio run -e test_ble -t upload -t monitor
//
// 用法：烧录后用 nRF Connect 连上 "Knob-XXXX"（后 4 位是该板子蓝牙 MAC
// 的后 2 字节，每块板子不一样），配对方式是 Passkey（数字比对）：ESP32
// 和手机会各自显示一个 6 位数，串口和手机弹窗上应该是同一个数字，确认
// 一致后在手机上点确认即可，不需要输入。
//
// 验证按键事件通知（29afa70d-... 服务下 182f303d-... 特征值）：在
// nRF Connect 里对这个特征值点"订阅"（Enable Notifications），连上后
// 每隔 2 秒会交替通知一次左/右方向键，应该能在 nRF Connect 里看到收到
// 的 2 字节数据交替变化（21,0 / 22,0，小端 int16 的 KEYCODE_DPAD_LEFT/
// RIGHT），串口也会打印发送日志。
//
// 顺带验证 ble_on_display_update()：找到同一服务下的可写特征值
// （5f774e59-...），写 9 字节 "05 76 61 6C 75 65 00 16 00"——这是通用
// 键值对编码里的一条 entry：keyLen=5, key="value"(76 61 6C 75 65),
// type=0(int16), 值=0x0016=22（小端）。串口应该打出
// "[BLE] display update: 1 fields" + "  value=22"，然后
// "[TestBLE] display update: 1 fields, value=22"。
// 完整格式（多个 entry、string/bool 类型）见 ble.h 里 DisplayData 的注释。

#include <Arduino.h>
#include "ble.h"

static void on_display_update(const DisplayData &data) {
    Serial.printf("[TestBLE] display update: %d fields, value=%d\n",
                  data.count, data.getInt("value"));
}

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("[TestBLE] boot");

    ble_on_display_update(on_display_update);
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
            Serial.println("[TestBLE] send DPAD_LEFT");
            ble_send_key(KEYCODE_DPAD_LEFT);
        } else {
            Serial.println("[TestBLE] send DPAD_RIGHT");
            ble_send_key(KEYCODE_DPAD_RIGHT);
        }
        send_left = !send_left;
    }

    delay(20);
}
