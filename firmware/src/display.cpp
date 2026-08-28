#include "display.h"
#include "config.h"

// LVGL 从来不会直接往屏幕上画，它先画到这块内存缓冲区里，
// 画完一块区域后再交给下面的 disp_flush_cb() —— 整个工程里
// 只有这个函数真正跟硬件打交道。40 行缓冲在动画流畅度和内存
// 占用（240*40*2 字节）之间是个还不错的折中。
static lv_disp_draw_buf_t draw_buf;
static lv_color_t buf1[CONFIG_SCREEN_HOR_RES * 40];
static lv_disp_drv_t disp_drv;
static SCREEN_CLASS *screen;

// LVGL 每画完一块矩形区域的像素就会调这个回调。area 是这块矩形的
// 位置和大小，color_p 是像素数据。我们只是把它转发给 Arduino_GFX
// 驱动去真正显示，再告诉 LVGL"画完了，这块缓冲你可以回收了"。
static void disp_flush_cb(lv_disp_drv_t *disp, const lv_area_t *area, lv_color_t *color_p) {
    uint32_t w = area->x2 - area->x1 + 1;
    uint32_t h = area->y2 - area->y1 + 1;
    screen->draw16bitBeRGBBitmap(area->x1, area->y1, (uint16_t *)&color_p->full, w, h);
    lv_disp_flush_ready(disp);
}

void display_init() {
    // 1) 先把物理屏幕通过 SPI 点亮。
    Arduino_DataBus *bus = new Arduino_HWSPI(TFT_DC, TFT_CS, TFT_SCLK, TFT_MOSI, TFT_MISO);
    screen = new SCREEN_CLASS(bus, TFT_RST, /*rotation=*/2, /*IPS=*/true);
    screen->begin();
    screen->fillScreen(BLACK);

    pinMode(TFT_BLK, OUTPUT);
    digitalWrite(TFT_BLK, HIGH); // 打开背光

    // 2) 初始化 LVGL，并告诉它怎么找到刚才点亮的这块屏幕。
    lv_init();
    lv_disp_draw_buf_init(&draw_buf, buf1, NULL, CONFIG_SCREEN_HOR_RES * 40);

    lv_disp_drv_init(&disp_drv);
    disp_drv.hor_res = CONFIG_SCREEN_HOR_RES;
    disp_drv.ver_res = CONFIG_SCREEN_VER_RES;
    disp_drv.flush_cb = disp_flush_cb;
    disp_drv.draw_buf = &draw_buf;
    lv_disp_drv_register(&disp_drv);
}

void display_task_handler() {
    // 让 LVGL 处理定时器/动画，并把变化的像素刷到屏幕上。
    lv_timer_handler();
}
