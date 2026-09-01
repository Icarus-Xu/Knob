#pragma once

#include <stdint.h>

// Android KeyEvent.KEYCODE_* 常量（不依赖 Android SDK，这几个数值是长期
// 稳定不变的 AOSP 框架常量，直接抄过来用）。DPAD 转动现在完全本地处理
// （见 main.cpp），不再通知手机，所以这里只剩 Tab（单击=切页面）和
// Enter（长按=确认，带上最终值）两个真正会发出去的键。
constexpr int16_t KEYCODE_TAB = 61;
constexpr int16_t KEYCODE_ENTER = 66;

// 初始化 BLE：建 GATT server、配置配对方式（数字比对）、加两个自定义
// 特征值（按键事件通知 + 显示同步可写），开始广播。在 setup() 里调用一次。
//
// 不走标准 BLE HID 键盘 profile——之前是 HID + 手机侧无障碍服务拦截系统
// 按键，但车机的定制系统没有暴露标准的无障碍设置入口（跳转会直接崩
// ActivityNotFoundException），这条路走不通。现在旋钮的每个动作都直接
// 通过自定义 GATT 特征值通知给手机，壳收到通知后直接转发给插件，不再
// 经过系统按键分发/无障碍服务这一层，也就不需要这个权限了。
void ble_init();

// 当前是不是已经跟主机配对并且加密连接建立完成——发事件之前应该先检查
// 这个，没配对时发了也没人收。
bool ble_is_paired();

// 按键事件通知的字段 ID——跟手机那边 BleManager.kt 里同名常量对齐（两边
// 各写一份、数值对齐，就跟 KEYCODE_* 这些常量的维护方式一样）。用数字
// ID 代替字段名文本，字节量比 DisplayData 那种"keyLen+key名"的编码小
// 得多，这条通道每次按键都要发，值得省这个字节。
constexpr uint8_t KEY_FIELD_CODE = 1;
constexpr uint8_t KEY_FIELD_VALUE = 2;

// 通知手机"发生了一次按键"，keycode 用上面 KEYCODE_* 常量。没配对时
// 调用不会有任何效果。
void ble_send_key(int16_t keycode);

// 跟 ble_send_key 一样，但额外带上一个数值——目前只有长按（确认）用，
// 把旋钮本地编辑到的最终值一起带给手机，不用手机在这之前每转一档就
// 跟着同步一遍。
void ble_send_key_with_value(int16_t keycode, int16_t value);

// 手机推来的显示数据，通用键值对——这一层（跟手机那边的插件对应）自己
// 按约定好的 key 名取值，壳（BleManager/KnobHostImpl）完全不理解这些
// key 的含义，只负责把 Map 编码成字节流搬过来，所以以后要加/改字段，
// 只需要同时改插件（发什么 key）和这里（认哪些 key），不用碰壳。
struct DisplayData {
    static const int MAX_ENTRIES = 8;
    static const int MAX_KEY_LEN = 15;
    static const int MAX_STR_LEN = 60;

    struct Entry {
        char key[MAX_KEY_LEN + 1];
        uint8_t type; // 0=int16, 1=string, 2=bool
        int16_t intValue;
        char strValue[MAX_STR_LEN + 1];
        bool boolValue;
    };

    Entry entries[MAX_ENTRIES];
    int count = 0;

    // 找不到对应 key（或类型不对）就返回 defaultValue，调用方不用先判断
    // 有没有这个字段。
    int16_t getInt(const char *key, int16_t defaultValue = 0) const;
    const char *getString(const char *key, const char *defaultValue = "") const;
    bool getBool(const char *key, bool defaultValue = false) const;
};

// 注册"手机同步了显示数据"回调：手机往自定义特征值写数据时触发。
// data 指向的是内部一块复用的缓冲区，只在回调期间有效，getString()
// 返回的指针也是——需要长期保留的话必须自己复制一份，不能只存指针。
void ble_on_display_update(void (*callback)(const DisplayData &data));
