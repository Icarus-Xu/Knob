#include "ble.h"

#include <Arduino.h>
#include <HijelHID_BLEKeyboard.h>
#include <esp_mac.h>

static HijelHID_BLEKeyboard *s_keyboard = nullptr;

// 多块板子刷同一份固件时，蓝牙名字不能都叫一样——名字只是给人看的、
// 蓝牙协议本身认设备靠地址和密钥，不会真的连错，但人在手机配对列表里
// 认名字，一堆同名设备很容易手滑点错。这里在名字后面拼上芯片蓝牙 MAC
// 地址的后 2 字节，让每块板子广播出来的名字都不一样。
static String build_device_name() {
    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_BT); // 出厂烧录的蓝牙 MAC，不依赖 BLE 栈已初始化
    char suffix[5];
    snprintf(suffix, sizeof(suffix), "%02X%02X", mac[4], mac[5]);
    return String("Knob-") + suffix;
}

static void on_pairing_complete(bool success) {
    Serial.println(success ? "[BLE] pairing succeeded" : "[BLE] pairing failed");
}

void ble_init() {
    String device_name = build_device_name();
    Serial.printf("[BLE] advertising as BLE keyboard \"%s\"...\n", device_name.c_str());

    s_keyboard = new HijelHID_BLEKeyboard(device_name.c_str(), "Knob", 100);
    s_keyboard->setLogLevel(HIDLogLevel::Normal); // 库自带的调试日志，直接打到 Serial
    // Passkey = 数字比对配对：库会在配对时把 6 位数打到串口，手机弹窗上
    // 应该显示同一个数字，人工确认一致后在手机上点确认即可，不需要输入。
    s_keyboard->setSecurityMode(HIDSecurity::Passkey);
    s_keyboard->onPairingComplete(on_pairing_complete);
    s_keyboard->begin();
}

bool ble_is_paired() {
    return s_keyboard != nullptr && s_keyboard->isPaired();
}

void ble_tap(uint8_t keycode) {
    if (s_keyboard != nullptr) {
        s_keyboard->tap(keycode);
    }
}
