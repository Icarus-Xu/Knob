// Knob 固件。
// 屏幕显示"当前正在编辑的一路数值"（标题/数值/取值范围），手机每次
// 进页面/切页面时推一份新的过来当起点；转动旋钮只改本地这份副本、
// 立刻重绘，不再每转一档就等手机一次——避免了转动和屏幕更新之间的
// 蓝牙往返延迟。单击（Tab）切页面，长按（Enter）才把编辑到的最终值
// 带给手机去真正应用（对应插件那边只有 Enter 会调用车控接口，DPAD
// 调整期间不会触发大量真实车控调用）。
// 方案见 docs/旋钮-最小硬件调试方案.md、docs/蓝牙控制配件-方案.md。

#include <Arduino.h>
#include "config.h"
#include "display.h"
#include "encoder.h"
#include "ble.h"

// 后面需要更新的 LVGL 控件都存成全局变量，这样 build_ui()
// （只创建一次）和 redraw()（数值变化时更新）才都能访问到。
static lv_obj_t *s_label_name;
static lv_obj_t *s_label_value;
static lv_obj_t *s_arc;

// 当前正在编辑的这一路数值——手机每次同步（进页面/切页面/长按确认后
// 的回执）会覆盖这几个字段；转动旋钮只改 s_value 这一个字段，不碰
// title/min/max，也不会发消息给手机。
static char s_title[DisplayData::MAX_STR_LEN + 1] = "";
static int16_t s_value = 0;
static int16_t s_min = 0;
static int16_t s_max = 0;

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

// 用 s_title/s_value/s_min/s_max 当前的值重绘屏幕——转旋钮（本地改
// s_value）和收到手机同步（改全部四个字段）之后都调这个，两条路径
// 最终画出来的东西是一样的。
static void redraw() {
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

// 手机同步了新的显示数据时调用（见 ble_on_display_update）——进页面、
// 切页面（Tab）、长按确认后手机的回执，都会走到这里，覆盖本地这份
// 编辑副本。这里认的 "title"/"value"/"min"/"max" 几个 key 是跟插件那边
// 约定好的，壳不参与这部分——以后要加新字段/新形状的页面，只需要同时
// 改插件发什么 key、这里认哪些 key，不用碰壳。data 指向的缓冲区只在
// 本次回调期间有效，getString() 拿到的指针也是，用完这次回调就失效，
// 所以这里立刻复制进 s_title，不能只存指针。
static void on_display_update(const DisplayData &data) {
    strncpy(s_title, data.getString("title"), sizeof(s_title) - 1);
    s_title[sizeof(s_title) - 1] = '\0';
    s_value = data.getInt("value");
    s_min = data.getInt("min");
    s_max = data.getInt("max");
    redraw();
}

// 创建所有控件，只在 setup() 里调用一次。
static void build_ui() {
    lv_obj_t *scr = lv_scr_act(); // 这个 demo 只有这一个页面
    lv_obj_set_style_bg_color(scr, lv_color_black(), 0);

    // 屏幕边缘的一圈圆弧，用来表示当前值在整个范围里的比例。
    s_arc = lv_arc_create(scr);
    lv_obj_set_size(s_arc, 220, 220);
    lv_arc_set_rotation(s_arc, 135);   // 起始角度，让缺口留在下方
    lv_arc_set_bg_angles(s_arc, 0, 270); // 留 90 度缺口，而不是画一整圈
    lv_arc_set_range(s_arc, 0, 100);
    lv_obj_remove_style(s_arc, NULL, LV_PART_KNOB); // 隐藏默认的可拖拽手柄，这里只是展示用，不需要能拖
    lv_obj_clear_flag(s_arc, LV_OBJ_FLAG_CLICKABLE); // 禁止点击/触摸拖动它
    lv_obj_set_style_arc_color(s_arc, lv_palette_main(LV_PALETTE_BLUE), LV_PART_INDICATOR);
    lv_obj_center(s_arc);

    s_label_name = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_name, &lv_font_montserrat_14, 0);
    lv_obj_set_style_text_color(s_label_name, lv_color_white(), 0);
    lv_obj_align(s_label_name, LV_ALIGN_CENTER, 0, -30);
    lv_label_set_text(s_label_name, "等待连接...");

    s_label_value = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_value, &lv_font_montserrat_26, 0);
    lv_obj_set_style_text_color(s_label_value, lv_color_white(), 0);
    lv_obj_align(s_label_value, LV_ALIGN_CENTER, 0, 5);
}

void setup() {
    Serial.begin(115200);
    Serial.println("[Knob] boot");

    display_init();
    encoder_init();
    build_ui();
    ble_on_display_update(on_display_update);
    ble_init();

    Serial.println("[Knob] ready: rotate = local edit, click = switch page, long-press = confirm");
}

void loop() {
    // encoder_poll_button() 每次 loop() 都要跑，哪怕这次循环没有
    // 别的事情发生——它是唯一负责计算"按了多久"的地方。
    encoder_poll_button();

    int16_t diff = encoder_get_diff();
    if (diff != 0) {
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

    if (encoder_take_click() && ble_is_paired()) {
        Serial.println("[BTN] click -> TAB");
        ble_send_key(KEYCODE_TAB);
    }

    if (encoder_take_long_press() && ble_is_paired()) {
        // 长按 = 确认：把本地编辑到的最终值带给手机，插件收到后才会
        // 真正调用车控接口——转动过程中不会触发任何车控调用，只有这
        // 一下会。
        Serial.printf("[BTN] long-press -> ENTER, value=%d\n", s_value);
        ble_send_key_with_value(KEYCODE_ENTER, s_value);
    }

    display_task_handler(); // 让 LVGL 把刚才的改动画出来
    delay(5);
}
