// Knob 最小硬件调试固件。
// 目标：屏幕显示 UI + 旋转旋钮调数值 + 按压切换功能 + 长按"确认"——
// 全部在本地模拟，暂不连车机、不做蓝牙。方案见
// docs/旋钮-最小硬件调试方案.md。

#include <Arduino.h>
#include "config.h"
#include "display.h"
#include "encoder.h"

// 旋钮能控制的一个"功能"。这个 demo 不会真的去控制任何车上的
// 硬件——转动旋钮只是在改内存里的 value 变量、然后重绘屏幕，
// 这样可以先验证"输入 -> UI"这条链路完全没问题，之后再接真实的
// 车控逻辑。
struct FuncDef {
    const char *name;
    float min_value;
    float max_value;
    float step;      // 每转一档 value 变化多少
    const char *unit;
    float value;      // 当前值，随着转动旋钮被修改
};

static FuncDef s_functions[] = {
    {"温度", 16.0f, 30.0f, 0.5f, "C", 22.5f},
    {"风量", 0.0f, 7.0f, 1.0f, "档", 3.0f},
    {"座椅加热", 0.0f, 3.0f, 1.0f, "档", 0.0f},
};
static const int FUNC_COUNT = sizeof(s_functions) / sizeof(s_functions[0]);
static int s_current_func = 0; // 当前正在显示/调节的是 s_functions 里的哪一个

// 后面需要更新的 LVGL 控件都存成全局变量，这样 build_ui()
// （只创建一次）和 refresh_ui()（每次值变化都要更新）才都能访问到。
static lv_obj_t *s_label_name;
static lv_obj_t *s_label_value;
static lv_obj_t *s_arc;

// 把当前功能的名称/数值刷新到已经建好的控件上。
// 这个函数不会创建新控件——第一次必须先调用 build_ui()。
static void refresh_ui() {
    const FuncDef &f = s_functions[s_current_func];

    lv_label_set_text(s_label_name, f.name);

    char buf[32];
    if (f.step < 1.0f) {
        snprintf(buf, sizeof(buf), "%.1f %s", f.value, f.unit);
    } else {
        snprintf(buf, sizeof(buf), "%.0f %s", f.value, f.unit);
    }
    lv_label_set_text(s_label_value, buf);

    // 把当前值映射成 0-100 的百分比显示在圆弧上，这样不管实际的
    // min/max/单位是什么，圆弧看起来都是"进度走到哪了"的直观感觉。
    int pct = (int)((f.value - f.min_value) / (f.max_value - f.min_value) * 100.0f);
    lv_arc_set_value(s_arc, pct);
    lv_obj_set_style_arc_color(s_arc, lv_palette_main(LV_PALETTE_BLUE), LV_PART_INDICATOR);
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
    lv_obj_center(s_arc);

    s_label_name = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_name, &lv_font_montserrat_14, 0);
    lv_obj_set_style_text_color(s_label_name, lv_color_white(), 0);
    lv_obj_align(s_label_name, LV_ALIGN_CENTER, 0, -30);

    s_label_value = lv_label_create(scr);
    lv_obj_set_style_text_font(s_label_value, &lv_font_montserrat_26, 0);
    lv_obj_set_style_text_color(s_label_value, lv_color_white(), 0);
    lv_obj_align(s_label_value, LV_ALIGN_CENTER, 0, 5);

    refresh_ui(); // 先刷一次初始值，不然标签一开始是空的
}

void setup() {
    Serial.begin(115200);
    Serial.println("[Knob] boot");

    display_init();
    encoder_init();
    build_ui();

    Serial.println("[Knob] ready: rotate = adjust, click = switch function, long-press = confirm");
}

void loop() {
    // encoder_poll_button() 每次 loop() 都要跑，哪怕这次循环没有
    // 别的事情发生——它是唯一负责计算"按了多久"的地方。
    encoder_poll_button();

    int16_t diff = encoder_get_diff();
    if (diff != 0) {
        FuncDef &f = s_functions[s_current_func];
        f.value += diff * f.step;
        // 限幅，保证数值不会跑出合法范围。
        if (f.value < f.min_value) f.value = f.min_value;
        if (f.value > f.max_value) f.value = f.max_value;
        refresh_ui();
        Serial.printf("[ENC] %s = %.1f\n", f.name, f.value);
    }

    if (encoder_take_click()) {
        s_current_func = (s_current_func + 1) % FUNC_COUNT; // 转到最后一个之后回到第一个
        refresh_ui();
        Serial.printf("[BTN] switch -> %s\n", s_functions[s_current_func].name);
    }

    if (encoder_take_long_press()) {
        // 这个 demo 还没接车机/蓝牙，长按并没有真正的东西可以"确认"——
        // 这里先打日志，并把圆弧闪一下绿色，让你能直观看到长按被
        // 正确识别到了。
        Serial.printf("[BTN] confirm %s = %.1f\n", s_functions[s_current_func].name,
                       s_functions[s_current_func].value);
        lv_obj_set_style_arc_color(s_arc, lv_palette_main(LV_PALETTE_GREEN), LV_PART_INDICATOR);
    }

    display_task_handler(); // 让 LVGL 把刚才的改动画出来
    delay(5);
}
