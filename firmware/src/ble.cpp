#include "ble.h"

#include <Arduino.h>
#include <HijelHID_BLEKeyboard.h>
#include <NimBLEDevice.h>
#include <esp_mac.h>

static HijelHID_BLEKeyboard *s_keyboard = nullptr;
static void (*s_display_update_cb)(uint8_t module, int16_t value) = nullptr;

// 自定义可写特征值：手机把"当前模块+数值"写进来，同步旋钮屏幕显示。
// 跟 HID 那套标准特征值挂在同一个 GATT server 上（不是另开一条连接），
// UUID 随手生成，不跟任何标准服务冲突。
static const char *kDisplayServiceUUID = "29afa70d-2312-4f04-9b36-fe9298284756";
static const char *kDisplayCharUUID = "5f774e59-5079-4e69-8e5d-de0ee1220d3f";

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

// 手机往 kDisplayCharUUID 写数据时触发：解析出 module/value，转发给
// main.cpp 通过 ble_on_display_update() 注册的回调。
class DisplayUpdateCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *characteristic, NimBLEConnInfo &connInfo) override {
        NimBLEAttValue value = characteristic->getValue();
        if (value.length() < 3) {
            Serial.printf("[BLE] display update: payload too short (%u bytes)\n", value.length());
            return;
        }
        const uint8_t *data = value.data();
        uint8_t module = data[0];
        int16_t v = (int16_t)(data[1] | (data[2] << 8)); // 小端
        Serial.printf("[BLE] display update: module=%u value=%d\n", module, v);
        if (s_display_update_cb != nullptr) {
            s_display_update_cb(module, v);
        }
    }
};
static DisplayUpdateCallbacks s_display_update_callbacks;

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

    // 在 HijelHID_BLEKeyboard 已经建好的同一个 GATT server 上加一个自定义
    // 可写特征值。NimBLEDevice::getServer() 拿到的是它内部创建的那个单例
    // server（NimBLEDevice::createServer() 已存在就直接返回现有指针），
    // 不会另开一条连接。
    NimBLEServer *server = NimBLEDevice::getServer();
    NimBLEService *displayService = server->createService(kDisplayServiceUUID);
    NimBLECharacteristic *displayChar =
        displayService->createCharacteristic(kDisplayCharUUID, NIMBLE_PROPERTY::WRITE);
    displayChar->setCallbacks(&s_display_update_callbacks);

    // begin() 内部已经调过一次 server->start()（那时候只有 HID 服务）。
    // NimBLEServer::start() 只有在"服务列表变了 && 没有客户端连接"时才会
    // 真的重新注册 GATT 表，现在多加了一个服务，必须再调一次才会生效
    // ——这一步会顺带停掉广播（重置 GATT 表的副作用），所以后面要手动
    // 把广播重新开起来，不然设备会从手机的蓝牙列表里消失。
    server->start();
    NimBLEDevice::getAdvertising()->start();
}

bool ble_is_paired() {
    return s_keyboard != nullptr && s_keyboard->isPaired();
}

void ble_tap(uint8_t keycode) {
    if (s_keyboard != nullptr) {
        s_keyboard->tap(keycode);
    }
}

void ble_on_display_update(void (*callback)(uint8_t module, int16_t value)) {
    s_display_update_cb = callback;
}
