// Knob 固件。
// 屏幕显示"当前正在编辑的一路数值"（标题/数值/取值范围），手机每次
// 进页面/切页面时推一份新的过来当起点；转动旋钮只改本地这份副本、
// 立刻重绘，不再每转一档就等手机一次——避免了转动和屏幕更新之间的
// 蓝牙往返延迟。单击（Tab）切页面，长按（Enter）才把编辑到的最终值
// 带给手机去真正应用（对应插件那边只有 Enter 会调用车控接口，DPAD
// 调整期间不会触发大量真实车控调用）。
//
// 屏幕同时还要同步配对/连接状态（未连接、正在配对、刚配对成功、
// 断开重连），不是一直都在显示手机推来的数据——见下面的 ScreenState。
// 方案见 docs/旋钮-最小硬件调试方案.md、docs/蓝牙控制配件-方案.md。

#include <Arduino.h>
#include "config.h"
#include "display.h"
#include "encoder.h"
#include "ble.h"

// 中文字体（font_zh_18.c/font_zh_36.c，lvgl 官方字体转换器生成，只嵌入
// 了固件实际用到的汉字）——数字/括号/省略号这些没嵌入的 ASCII 字符
// 通过 fallback 挂到 lv_font_montserrat_18/36 上，见两个 .c 文件里的
// .fallback 字段。
LV_FONT_DECLARE(font_zh_18);
LV_FONT_DECLARE(font_zh_36);

// 后面需要更新的 LVGL 控件都存成全局变量，这样 build_ui()
// （只创建一次）和 redraw()（数值变化时更新）才都能访问到。
static lv_obj_t *s_label_name;
static lv_obj_t *s_label_value;
static lv_obj_t *s_arc;

// 屏幕当前处在哪个阶段。除了"显示手机推来的数据"这个主状态，还要
// 覆盖配对码/配对成功提示/未连接这几种状态，所以单独做一个简单状态机，
// 不是直接把这些揉进 s_title/s_value 里、靠字符串规则区分。
enum ScreenState {
    SCREEN_DISCONNECTED, // 未连接：显示配对码（正在配对时）/ 设备名+"未配对"（从没配对过）/ "等待连接"（配过、暂时断连）
    SCREEN_PAIRED_TOAST, // 刚配对/连接成功，显示提示 1 秒
    SCREEN_NORMAL,       // 显示手机同步过来的数据
};
// 除了字符缓冲区（下面单独说明），这个状态机相关的变量都要标
// volatile：NimBLE 的连接/配对回调（on_passkey/on_paired/on_disconnected/
// on_display_update）跑在 NimBLE 自己的后台任务线程上，不是 loop() 那个
// 线程，不标 volatile 的话 loop() 读到的可能是编译器缓存的旧值，看起来
// 就像"状态明明该变了、屏幕却没反应"。
static volatile ScreenState s_screen_state = SCREEN_DISCONNECTED;

// LVGL 不是线程安全的（官方文档明确说明，多线程/多任务并发调用 lv_ 函数
// 是未定义行为）——之前 on_paired() 这些回调直接在里面调 redraw()，
// 而 redraw() 最终会调 lv_label_set_text/lv_arc_set_value 等 LVGL 函数，
// 这些回调又是在 NimBLE 后台任务线程上跑的，跟 loop() 里的
// lv_timer_handler() 并发访问 LVGL 内部状态，会导致重绘偶尔"悄悄失效"
// （表现为：串口日志显示配对/收到数据都成功了，但屏幕没跟着变）。
// 现在改成回调只置这个标志位，真正的 redraw() 调用挪到 loop() 里做——
// 跟 lv_timer_handler() 在同一个线程，就没有这个问题了。
static volatile bool s_needs_redraw = false;

constexpr uint32_t PAIRED_TOAST_MS = 1000;
static volatile uint32_t s_toast_until_ms = 0;

// 正在配对时，手机和固件两边应该显示同一个 6 位确认码；没有正在配对
// 时是 -1。已配对过的设备重连不会再走一遍配对流程，也就不会再有这个码
// （见 ble_on_passkey 的注释）。
static volatile int32_t s_pending_passkey = -1;

// 这次连接有没有真的走过配对码确认——onConfirmPassKey 触发过才是真配对，
// 已配对设备直接用保存的密钥重连不会走这一步。on_paired() 用这个字段
// 区分该显示"配对成功"还是"连接成功"，on_disconnected() 里清零，
// 保证每次连接周期都是从头判断，不会用上一轮的结果。
static volatile bool s_paired_via_confirm = false;

// 当前正在编辑的这一路数值——手机每次同步（进页面/切页面/长按确认后
// 的回执）会覆盖这几个字段；转动旋钮只改 s_value 这一个字段，不碰
// title/min/max，也不会发消息给手机。s_title 这个字符缓冲区不标
// volatile——strncpy 系操作跟 volatile 指针配合很别扭，这里的一致性靠
// s_screen_state/s_display_pending 的读写顺序保证（BLE 回调写完 title
// 才会翻转对应的标志位，loop() 先看到标志位翻转才会去读 title），够用，
// 不需要上更重的同步机制。
static char s_title[DisplayData::MAX_STR_LEN + 1] = "";
static volatile int16_t s_value = 0;
static volatile int16_t s_min = 0;
static volatile int16_t s_max = 0;
static volatile bool s_have_display_data = false; // 有没有收到过手机推来的真实数据

// 配对成功提示还没消失之前，手机如果推了新数据，先缓存在这里，等提示
// 消失后再应用——不能提示还没显示完就被数据覆盖掉。多次收到就直接
// 覆盖，只保留最新的一份。
static volatile bool s_display_pending = false;
static char s_pending_title[DisplayData::MAX_STR_LEN + 1] = "";
static volatile int16_t s_pending_value = 0;
static volatile int16_t s_pending_min = 0;
static volatile int16_t s_pending_max = 0;

// 浅蓝 -> 深蓝 -> 橙色 -> 红色，四段色标均匀分布在 [min,max] 上，两个
// 页面共用同一套渐变：
//   空调温度（min>=0，比如 17~33）：低温浅蓝、高温红，中间自然经过
//   深蓝到橙色的过渡。
//   座椅（min<0，比如 -2~2，0=关闭居中）：通风那一侧落在浅蓝/深蓝这
//   两段，加热那一侧落在橙/红这两段，"关闭"正好卡在深蓝到橙色的过渡
//   区——但那时候指示条长度是 0（见 redraw 的对称模式），这段颜色
//   根本不会被画出来，不影响观感。
static lv_color_t gradient_color(int16_t value, int16_t vmin, int16_t vmax) {
    static const lv_color_t stops[4] = {
        LV_COLOR_MAKE(0x64, 0xB5, 0xF6), // 浅蓝
        LV_COLOR_MAKE(0x0D, 0x47, 0xA1), // 深蓝
        LV_COLOR_MAKE(0xFF, 0x98, 0x00), // 橙色
        LV_COLOR_MAKE(0xD5, 0x00, 0x00), // 红色
    };
    if (vmax <= vmin) return stops[0];
    float t = (float)(value - vmin) / (float)(vmax - vmin); // 0..1
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    float seg = t * 3.0f; // 落在四段里的哪一段：0..3
    int idx = (int)seg;
    if (idx > 2) idx = 2;
    float frac = seg - idx;
    // lv_color_mix(c1, c2, mix)：mix=0 时是纯 c2，mix=255 时是纯 c1。
    // frac=0 时要纯 stops[idx]，frac=1 时要纯 stops[idx+1]。
    return lv_color_mix(stops[idx + 1], stops[idx], (uint8_t)(frac * 255));
}

// 三个屏幕状态各自的重绘逻辑，统一由 redraw() 按 s_screen_state 分发。
// 数值圆弧只有在真正显示手机数据时才有意义，其它状态一律隐藏，屏幕
// 中间只剩两行文字。

static void redraw_disconnected() {
    lv_obj_add_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    if (s_pending_passkey >= 0) {
        lv_label_set_text(s_label_name, "配对码");
        char buf[8];
        snprintf(buf, sizeof(buf), "%06u", (unsigned)s_pending_passkey);
        lv_label_set_text(s_label_value, buf);
    } else if (!ble_has_any_bond()) {
        // 从没跟任何设备配对成功过（NimBLE 自己 flash 里的配对记录数为
        // 0，本地就能查，不用猜）——这种情况才是真的需要用户去手机蓝牙
        // 设置里配对，显示广播名字（放大号 s_label_value）方便用户找，
        // 同时配好几个 knob 时尤其有用：只有真正没配过的那个会显示
        // 名字，已经配好的不会。
        lv_label_set_text(s_label_name, "未配对");
        lv_label_set_text(s_label_value, ble_get_device_name());
    } else {
        // 已经跟某个设备配对过，现在只是暂时没连上（杀 App、暂时超出
        // 范围……）——配对信息（LTK）早就还在，不需要用户做任何事，
        // 等它自动重连就行，不需要再显示名字（不是要用户去找它配对）。
        // 这种情况下"等待连接"就是唯一要传达的信息，放大号 s_label_value。
        lv_label_set_text(s_label_name, "");
        lv_label_set_text(s_label_value, "等待连接");
    }
}

static void redraw_paired_toast() {
    lv_obj_add_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    // 真的走过配对码确认才是"配对成功"；已配对设备用保存的密钥重新
    // 连接（比如手机 App 被杀后台又重启）没有这一步，应该说"连接成功"，
    // 不然会让人误以为每次连接都要重新配对。放大号的 s_label_value。
    lv_label_set_text(s_label_name, "");
    lv_label_set_text(s_label_value, s_paired_via_confirm ? "配对成功" : "连接成功");
}

static void redraw_normal() {
    if (!s_have_display_data) {
        // 已经连上了，但手机还没推过任何数据（比如刚配对完，提示消失
        // 时手机还没来得及同步）——不能显示上一次残留的 title/value，
        // 那可能是断线之前的旧值，容易让人误以为是当前状态。
        lv_obj_add_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
        lv_label_set_text(s_label_name, "已连接");
        lv_label_set_text(s_label_value, "等待数据...");
        return;
    }

    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    lv_label_set_text(s_label_name, s_title);

    char buf[16];
    snprintf(buf, sizeof(buf), "%d", s_value);
    lv_label_set_text(s_label_value, buf);

    if (s_max > s_min) {
        lv_arc_set_range(s_arc, s_min, s_max);
        // min<0（比如座椅 -2~2，0=关闭居中）用 LVGL 内置的对称模式：
        // 指示条从取值范围的中点（这里正好是 0）往两侧长，负值往一侧、
        // 正值往另一侧——不用自己算角度，LVGL 按 range 中点自动处理。
        // 其它情况（比如空调温度 17~33，没有"居中关闭"这个概念）还是
        // 普通模式，从一端往另一端长。
        lv_arc_set_mode(s_arc, s_min < 0 ? LV_ARC_MODE_SYMMETRICAL : LV_ARC_MODE_NORMAL);
        lv_arc_set_value(s_arc, s_value);
    }
    lv_obj_set_style_arc_color(s_arc, gradient_color(s_value, s_min, s_max), LV_PART_INDICATOR);
}

static void redraw() {
    switch (s_screen_state) {
        case SCREEN_DISCONNECTED: redraw_disconnected(); break;
        case SCREEN_PAIRED_TOAST: redraw_paired_toast(); break;
        case SCREEN_NORMAL: redraw_normal(); break;
    }
}

// 正在配对，手机弹窗和这里应该显示同一个 6 位数——见 ble.h 里
// ble_on_passkey 的注释，只有真正走一遍新配对流程才会触发。
// 这几个回调都跑在 NimBLE 后台任务线程上，只允许改状态变量，不能直接
// 调 redraw()（LVGL 不是线程安全的，见 s_needs_redraw 的注释）——置个
// 标志位，交给 loop() 在自己的线程上真正重绘。
static void on_passkey(uint32_t passkey) {
    s_pending_passkey = (int32_t)passkey;
    s_paired_via_confirm = true; // 这个连接周期真的走了配对码确认
    s_screen_state = SCREEN_DISCONNECTED;
    s_needs_redraw = true;
}

// 加密连接建立成功——不管是刚配对完还是用已保存的密钥重新连接，都会
// 触发这个。切到"配对成功/连接成功"提示（哪个文案见 s_paired_via_confirm），
// 1 秒后 loop() 里会自动收尾。
static void on_paired() {
    s_pending_passkey = -1; // 配对完成了，不用再显示配对码
    s_screen_state = SCREEN_PAIRED_TOAST;
    s_toast_until_ms = millis() + PAIRED_TOAST_MS;
    s_needs_redraw = true;
}

// 断开连接——不管之前是已经用了一会儿还是配对到一半就断了，都切回
// "未连接"。之前显示的数据可能已经过期（比如手机端车况变了），清掉
// s_have_display_data，避免重连、提示消失后一时半会儿没收到新数据，
// 却还在显示断线前的旧值。s_paired_via_confirm 也一起清零，下一个
// 连接周期重新判断是配对还是重连。
static void on_disconnected() {
    s_pending_passkey = -1;
    s_paired_via_confirm = false;
    s_screen_state = SCREEN_DISCONNECTED;
    s_have_display_data = false;
    s_display_pending = false;
    s_needs_redraw = true;
}

// 手机同步了新的显示数据时调用（见 ble_on_display_update）——进页面、
// 切页面（Tab）、长按确认后手机的回执，都会走到这里。这里认的
// "title"/"value"/"min"/"max" 几个 key 是跟插件那边约定好的，壳不参与
// 这部分——以后要加新字段/新形状的页面，只需要同时改插件发什么 key、
// 这里认哪些 key，不用碰壳。data 指向的缓冲区只在本次回调期间有效，
// getString() 拿到的指针也是，用完这次回调就失效，所以这里立刻复制，
// 不能只存指针。
static void on_display_update(const DisplayData &data) {
    const char *title = data.getString("title");
    int16_t value = data.getInt("value");
    int16_t min = data.getInt("min");
    int16_t max = data.getInt("max");

    if (s_screen_state == SCREEN_PAIRED_TOAST) {
        // "配对成功"提示还没显示完，先缓存，等 loop() 里提示到时收尾时
        // 再应用——不能提示还没看清就被数据覆盖掉。
        strncpy(s_pending_title, title, sizeof(s_pending_title) - 1);
        s_pending_title[sizeof(s_pending_title) - 1] = '\0';
        s_pending_value = value;
        s_pending_min = min;
        s_pending_max = max;
        s_display_pending = true;
        return;
    }

    strncpy(s_title, title, sizeof(s_title) - 1);
    s_title[sizeof(s_title) - 1] = '\0';
    s_value = value;
    s_min = min;
    s_max = max;
    s_have_display_data = true;
    s_screen_state = SCREEN_NORMAL;
    // 这个回调（跟上面几个一样）跑在 NimBLE 后台任务线程上，真正的
    // redraw() 调用得留给 loop()，不能在这里直接调，见 s_needs_redraw
    // 的注释。
    s_needs_redraw = true;
}

// 创建所有控件，只在 setup() 里调用一次——具体显示什么文字交给 redraw()，
// 这里不写死初始文案。
static void build_ui() {
    lv_obj_t *scr = lv_screen_active(); // 这个 demo 只有这一个页面
    lv_obj_set_style_bg_color(scr, lv_color_black(), 0);

    // 屏幕边缘的一圈圆弧，用来表示当前值在整个范围里的比例。只在
    // SCREEN_NORMAL 且已经收到过数据时显示，其它状态由 redraw() 隐藏。
    s_arc = lv_arc_create(scr);
    lv_obj_set_size(s_arc, 220, 220);
    lv_arc_set_rotation(s_arc, 135);   // 起始角度，让缺口留在下方
    lv_arc_set_bg_angles(s_arc, 0, 270); // 留 90 度缺口，而不是画一整圈
    lv_arc_set_range(s_arc, 0, 100);
    lv_obj_remove_style(s_arc, NULL, LV_PART_KNOB); // 隐藏默认的可拖拽手柄，这里只是展示用，不需要能拖
    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_CLICKABLE); // 禁止点击/触摸拖动它
    lv_obj_set_style_arc_color(s_arc, lv_palette_main(LV_PALETTE_BLUE), LV_PART_INDICATOR);
    lv_obj_center(s_arc);

    // 字号比之前大一档（18/36），方便辨识——两个 offset 也跟着调过，
    // 保持两行文字上下留白均衡、不会挤在一起或超出圆屏中间的安全区。
    s_label_name = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_name, &font_zh_18, 0);
    lv_obj_set_style_text_color(s_label_name, lv_color_white(), 0);
    lv_obj_align(s_label_name, LV_ALIGN_CENTER, 0, -34);

    s_label_value = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_value, &font_zh_36, 0);
    lv_obj_set_style_text_color(s_label_value, lv_color_white(), 0);
    lv_obj_align(s_label_value, LV_ALIGN_CENTER, 0, 8);
}

// 长按 BOOT 键触发清除配对期间，借用 s_arc 画一圈红色进度环（绕屏幕
// 一整圈，跟正常显示数值时那个留了缺口的弧不是一回事，这里 360 度
// 全画满）——percent 走到 100 正好是长按到点的那一刻。
static void draw_reset_progress(int percent) {
    lv_obj_remove_flag(s_arc, LV_OBJ_FLAG_HIDDEN);
    lv_arc_set_bg_angles(s_arc, 0, 360);
    lv_arc_set_rotation(s_arc, 270); // 从正上方开始，顺时针画满一圈
    lv_arc_set_mode(s_arc, LV_ARC_MODE_NORMAL);
    lv_arc_set_range(s_arc, 0, 100);
    lv_arc_set_value(s_arc, percent);
    lv_obj_set_style_arc_color(s_arc, lv_palette_main(LV_PALETTE_RED), LV_PART_INDICATOR);
}

// 开发板自带的 BOOT 键（GPIO0）：设备正常运行期间长按
// BOOT_BTN_CLEAR_BONDS_MS，清空 ESP32 自己保存的配对记录——主要是
// 给调试用，模拟"从没配对过"的状态（正常使用不会用到这个）。开机
// 瞬间不检测，避免跟"按住 BOOT 进下载模式"这个约定冲突，见 config.h
// 里 BOOT_BTN_PIN 的注释。
//
// 手感：按住前 BOOT_BTN_PROGRESS_START_MS 没有任何视觉反馈（避免误碰
// 一下就吓一跳）；过了这个点开始在屏幕上画红色进度环，绕完一圈正好是
// BOOT_BTN_CLEAR_BONDS_MS，到点即触发清除；进度画到一半松手就放弃，
// 画面恢复成松手前该显示的样子，不会真的清除。
static void check_boot_button_reset() {
    static uint32_t press_start_ms = 0;
    static bool triggered = false;
    static bool showing_progress = false;

    bool pressed = digitalRead(BOOT_BTN_PIN) == LOW;
    if (!pressed) {
        if (showing_progress) {
            // 进度环画到一半松手——放弃，把 s_arc 恢复成当前真实状态
            // 该显示的样子（redraw() 会按 s_screen_state 重新配置它）。
            showing_progress = false;
            redraw();
        }
        press_start_ms = 0;
        triggered = false;
        return;
    }
    if (press_start_ms == 0) {
        press_start_ms = millis();
        return;
    }
    if (triggered) {
        return; // 已经触发过一次，松开之前不重复触发
    }

    uint32_t held_ms = millis() - press_start_ms;
    if (held_ms >= BOOT_BTN_CLEAR_BONDS_MS) {
        triggered = true;
        showing_progress = false;
        Serial.println("[Knob] BOOT 长按触发：清除配对信息");
        ble_clear_all_bonds();
        // 立刻按"未连接"重绘一次——如果这时候本来就没连接，redraw()
        // 会重新查一次 ble_has_any_bond()（现在是 0 了），正确显示
        // "未配对"；如果这时候还连着，画面要等真正断连才会更新，这里
        // 不强行打断当前连接。
        s_screen_state = SCREEN_DISCONNECTED;
        redraw();
    } else if (held_ms >= BOOT_BTN_PROGRESS_START_MS) {
        showing_progress = true;
        uint32_t fill_elapsed = held_ms - BOOT_BTN_PROGRESS_START_MS;
        uint32_t fill_span = BOOT_BTN_CLEAR_BONDS_MS - BOOT_BTN_PROGRESS_START_MS;
        int percent = (int)((uint64_t)fill_elapsed * 100 / fill_span);
        draw_reset_progress(percent > 100 ? 100 : percent);
    }
}

void setup() {
    Serial.begin(115200);
    Serial.println("[Knob] boot");

    pinMode(BOOT_BTN_PIN, INPUT_PULLUP);
    display_init();
    encoder_init();
    build_ui();
    ble_on_display_update(on_display_update);
    ble_on_passkey(on_passkey);
    ble_on_paired(on_paired);
    ble_on_disconnected(on_disconnected);
    // ble_init() 里才会算出广播名字（ble_get_device_name() 之后才有效），
    // 所以 redraw() 得放在它后面，不然刚开机那一下会显示空名字。
    ble_init();
    redraw(); // 刚开机、还没连上手机的初始画面

    Serial.println("[Knob] ready: rotate = local edit, click = switch page, long-press = confirm");
}

void loop() {
    // encoder_poll_button() 每次 loop() 都要跑，哪怕这次循环没有
    // 别的事情发生——它是唯一负责计算"按了多久"的地方。
    encoder_poll_button();
    check_boot_button_reset();

    // BLE 回调（跑在 NimBLE 后台任务线程上）置了这个标志位就说明状态
    // 变了、需要重绘——真正调 redraw()（LVGL 函数）只能在这个线程上做，
    // 见 s_needs_redraw 的注释。
    if (s_needs_redraw) {
        s_needs_redraw = false;
        redraw();
    }

    // "配对成功"提示到时间了：如果提示期间手机推过新数据，直接应用；
    // 没有的话就先显示"已连接，等待数据"，等 on_display_update() 来了
    // 再刷新（走上面 SCREEN_NORMAL 分支）。
    if (s_screen_state == SCREEN_PAIRED_TOAST && millis() >= s_toast_until_ms) {
        if (s_display_pending) {
            strncpy(s_title, s_pending_title, sizeof(s_title) - 1);
            s_title[sizeof(s_title) - 1] = '\0';
            s_value = s_pending_value;
            s_min = s_pending_min;
            s_max = s_pending_max;
            s_have_display_data = true;
            s_display_pending = false;
        }
        s_screen_state = SCREEN_NORMAL;
        redraw();
    }

    // 旋钮转动只在真正显示手机数据时才有意义（未连接/配对提示阶段
    // 没有可编辑的值，s_min/s_max 也可能是上一次连接时的残留）。
    int16_t diff = encoder_get_diff();
    if (diff != 0 && s_screen_state == SCREEN_NORMAL && s_have_display_data) {
        // 只改本地这份编辑副本、立刻重绘——不通知手机，转动过程中不会
        // 有蓝牙往返，也就没有转动和屏幕更新之间的延迟。s_min/s_max
        // 是手机上次同步时给的范围，本地直接夹住，不需要手机来回确认。
        s_value = (int16_t)(s_value + diff);
        if (s_max > s_min) {
            if (s_value < s_min) s_value = s_min;
            if (s_value > s_max) s_value = s_max;
        }
        redraw();
        Serial.printf("[ENC] local value=%d\n", s_value);
    }

    // Tab/Enter 同样只在正常显示阶段才有意义——配对提示这 1 秒钟不接受
    // 交互，避免提示还没看清就被打断。ble_is_paired() 已经在
    // ble_send_key* 内部检查过一次，这里的 s_screen_state 检查是额外
    // 的交互层面限制，不是安全检查。
    if (encoder_take_click() && s_screen_state == SCREEN_NORMAL) {
        Serial.println("[BTN] click -> TAB");
        ble_send_key(KEYCODE_TAB);
    }

    if (encoder_take_long_press() && s_screen_state == SCREEN_NORMAL && s_have_display_data) {
        // 长按 = 确认：把本地编辑到的最终值带给手机，插件收到后才会
        // 真正调用车控接口——转动过程中不会触发任何车控调用，只有这
        // 一下会。
        Serial.printf("[BTN] long-press -> ENTER, value=%d\n", s_value);
        ble_send_key_with_value(KEYCODE_ENTER, s_value);
    }

    display_task_handler(); // 让 LVGL 把刚才的改动画出来
    delay(5);
}
