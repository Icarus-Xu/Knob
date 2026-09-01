#include "ble.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <esp_mac.h>
#include <string.h>

// 自定义服务下两个特征值：
//   kKeyEventCharUUID   NOTIFY  knob -> 手机，旋钮动作事件
//   kDisplayCharUUID    WRITE   手机 -> knob，同步显示数据（通用键值对）给屏幕
static const char *kServiceUUID = "29afa70d-2312-4f04-9b36-fe9298284756";
static const char *kKeyEventCharUUID = "182f303d-ea98-4ee4-839e-5599aaf3f29d";
static const char *kDisplayCharUUID = "5f774e59-5079-4e69-8e5d-de0ee1220d3f";

static NimBLECharacteristic *s_key_event_char = nullptr;
static void (*s_display_update_cb)(const DisplayData &data) = nullptr;

// 复用同一块缓冲区解析每次收到的显示数据，不用堆分配。
static DisplayData s_display_data;

static volatile bool s_connected = false;
static volatile bool s_authenticated = false;

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

// 通用键值对解码：壳（BleManager）编码时完全不理解字段含义，格式是
// 一连串 entry，每条：
//   byte  keyLen
//   bytes key (ASCII, keyLen 字节)
//   byte  type  0=int16 / 1=string / 2=bool
//   值本身：int16 是 2 字节小端；string 是 1 字节长度 + UTF-8 内容
//           （超过 MAX_STR_LEN 会被壳裁掉）；bool 是 1 字节 0/1
// 遇到不认识的 type 就没法知道占几个字节，直接停止解析——后面的 entry
// 宁可丢掉也不能瞎猜着继续读，会把后面的数据全解析错。
static void parse_display_data(const uint8_t *buf, size_t len, DisplayData &out) {
    out.count = 0;
    size_t pos = 0;
    while (pos < len && out.count < DisplayData::MAX_ENTRIES) {
        if (pos + 2 > len) break; // 至少要有 keyLen(1) + type(1)
        uint8_t keyLen = buf[pos++];
        if (pos + keyLen > len) break;
        DisplayData::Entry &e = out.entries[out.count];
        size_t copyKeyLen = keyLen > DisplayData::MAX_KEY_LEN ? DisplayData::MAX_KEY_LEN : keyLen;
        memcpy(e.key, buf + pos, copyKeyLen);
        e.key[copyKeyLen] = '\0';
        pos += keyLen;

        if (pos + 1 > len) break;
        e.type = buf[pos++];

        if (e.type == 0) { // int16
            if (pos + 2 > len) break;
            e.intValue = (int16_t)(buf[pos] | (buf[pos + 1] << 8)); // 小端
            pos += 2;
        } else if (e.type == 1) { // string
            if (pos + 1 > len) break;
            uint8_t strLen = buf[pos++];
            if (pos + strLen > len) break;
            size_t copyStrLen = strLen > DisplayData::MAX_STR_LEN ? DisplayData::MAX_STR_LEN : strLen;
            memcpy(e.strValue, buf + pos, copyStrLen);
            e.strValue[copyStrLen] = '\0';
            pos += strLen;
        } else if (e.type == 2) { // bool
            if (pos + 1 > len) break;
            e.boolValue = buf[pos++] != 0;
        } else {
            break; // 未知类型，无法安全跳过，停止解析
        }
        out.count++;
    }
}

int16_t DisplayData::getInt(const char *key, int16_t defaultValue) const {
    for (int i = 0; i < count; i++) {
        if (entries[i].type == 0 && strcmp(entries[i].key, key) == 0) return entries[i].intValue;
    }
    return defaultValue;
}

const char *DisplayData::getString(const char *key, const char *defaultValue) const {
    for (int i = 0; i < count; i++) {
        if (entries[i].type == 1 && strcmp(entries[i].key, key) == 0) return entries[i].strValue;
    }
    return defaultValue;
}

bool DisplayData::getBool(const char *key, bool defaultValue) const {
    for (int i = 0; i < count; i++) {
        if (entries[i].type == 2 && strcmp(entries[i].key, key) == 0) return entries[i].boolValue;
    }
    return defaultValue;
}

// 手机往 kDisplayCharUUID 写数据时触发：解析成通用键值对，转发给
// main.cpp 通过 ble_on_display_update() 注册的回调。
class DisplayUpdateCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *characteristic, NimBLEConnInfo &connInfo) override {
        NimBLEAttValue value = characteristic->getValue();
        parse_display_data(value.data(), value.length(), s_display_data);

        Serial.printf("[BLE] display update: %d fields\n", s_display_data.count);
        for (int i = 0; i < s_display_data.count; i++) {
            const DisplayData::Entry &e = s_display_data.entries[i];
            if (e.type == 0) {
                Serial.printf("  %s=%d\n", e.key, e.intValue);
            } else if (e.type == 1) {
                Serial.printf("  %s=\"%s\"\n", e.key, e.strValue);
            } else {
                Serial.printf("  %s=%s\n", e.key, e.boolValue ? "true" : "false");
            }
        }

        if (s_display_update_cb != nullptr) {
            s_display_update_cb(s_display_data);
        }
    }
};
static DisplayUpdateCallbacks s_display_update_callbacks;

// 连接/配对生命周期。ESP32 这边没有屏幕能显示数字比对的确认弹窗（严格说
// 有屏幕，但没接进配对流程里），配对时自动确认自己这一侧——真正的人工
// 核对靠人去比串口打印的 6 位数和手机弹窗上的是不是一样，跟接 HID 键盘
// 那版行为一致，没有削弱安全性，只是没有强制要求人去核对。
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *server, NimBLEConnInfo &connInfo) override {
        s_connected = true;
        Serial.println("[BLE] host connected");

        // 主动请求短连接间隔 + 短 supervision timeout——默认值可能有
        // 十几二十秒，knob 断电重启后手机要等这个超时才会发现连接死了，
        // 之后才轮到重连逻辑，等待时间主要耗在这里，不是轮询间隔。
        // 参数单位：interval 是 1.25ms 单位（12=15ms，24=30ms），
        // latency=0（每个连接事件都应答），timeout 是 10ms 单位
        // （300=3000ms=3秒）——留了远超协议要求下限（2×30ms=60ms）的
        // 余量，不会因为正常的信号波动就被当成掉线。
        server->updateConnParams(connInfo.getConnHandle(), 12, 24, 0, 300);
    }

    void onDisconnect(NimBLEServer *server, NimBLEConnInfo &connInfo, int reason) override {
        s_connected = false;
        s_authenticated = false;
        Serial.printf("[BLE] host disconnected (reason=%d), restarting advertising\n", reason);
        NimBLEDevice::startAdvertising();
    }

    void onAuthenticationComplete(NimBLEConnInfo &connInfo) override {
        s_authenticated = connInfo.isEncrypted();
        Serial.println(s_authenticated ? "[BLE] pairing succeeded" : "[BLE] pairing failed");
    }

    void onConfirmPassKey(NimBLEConnInfo &connInfo, uint32_t pass_key) override {
        Serial.printf("[BLE] passkey: %06u (should match what the phone shows)\n", pass_key);
        NimBLEDevice::injectConfirmPasskey(connInfo, true);
    }
};
static ServerCallbacks s_server_callbacks;

void ble_init() {
    String device_name = build_device_name();
    Serial.printf("[BLE] initializing as \"%s\"...\n", device_name.c_str());

    NimBLEDevice::init(device_name.c_str());

    // Arduino-ESP32 3.x + NimBLE-Arduino 2.5.1 上 init() 之后 NimBLE host
    // 任务需要一点时间才真正就绪，太早配置安全参数/建 server 会失败。
    uint32_t t = millis();
    while (!NimBLEDevice::isInitialized()) {
        delay(10);
        if (millis() - t > 5000) {
            Serial.println("[BLE] ERROR: NimBLE failed to initialize after 5s");
            return;
        }
    }

    // Passkey（数字比对）配对：MITM + Secure Connections，DisplayYesNo
    // 触发数字比对而不是要求手机输入数字。
    NimBLEDevice::setSecurityAuth(BLE_SM_PAIR_AUTHREQ_BOND |
                                   BLE_SM_PAIR_AUTHREQ_MITM |
                                   BLE_SM_PAIR_AUTHREQ_SC);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_YESNO);
    NimBLEDevice::setSecurityInitKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);
    NimBLEDevice::setSecurityRespKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);

    NimBLEServer *server = NimBLEDevice::createServer();
    server->setCallbacks(&s_server_callbacks);

    NimBLEService *service = server->createService(kServiceUUID);

    s_key_event_char = service->createCharacteristic(kKeyEventCharUUID, NIMBLE_PROPERTY::NOTIFY);

    NimBLECharacteristic *displayChar =
        service->createCharacteristic(kDisplayCharUUID, NIMBLE_PROPERTY::WRITE);
    displayChar->setCallbacks(&s_display_update_callbacks);

    server->start();

    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(kServiceUUID);
    NimBLEAdvertisementData scanResponse;
    scanResponse.setName(device_name.c_str());
    adv->setScanResponseData(scanResponse);
    NimBLEDevice::startAdvertising();

    Serial.printf("[BLE] advertising as \"%s\"\n", device_name.c_str());
}

bool ble_is_paired() {
    return s_authenticated;
}

// 把一个 int16 字段编码成 "fieldId(1) + type(1)=0 + value(2,小端)"，写进
// buf，返回写入的字节数——跟 DisplayData 用的是同一套 type 约定（0=int16），
// 只是用数字 ID 代替字段名文本，按键事件这条通道字节量更小。
static size_t encode_int16_field(uint8_t *buf, uint8_t fieldId, int16_t value) {
    buf[0] = fieldId;
    buf[1] = 0; // type = int16
    buf[2] = (uint8_t)(value & 0xFF);
    buf[3] = (uint8_t)((value >> 8) & 0xFF);
    return 4;
}

void ble_send_key(int16_t keycode, uint8_t repeat) {
    if (!ble_is_paired() || s_key_event_char == nullptr) {
        return;
    }
    uint8_t payload[8]; // 两个字段，各 4 字节
    size_t pos = 0;
    pos += encode_int16_field(payload + pos, KEY_FIELD_CODE, keycode);
    pos += encode_int16_field(payload + pos, KEY_FIELD_REPEAT, (int16_t)repeat);
    s_key_event_char->notify(payload, pos);
}

void ble_on_display_update(void (*callback)(const DisplayData &data)) {
    s_display_update_cb = callback;
}
