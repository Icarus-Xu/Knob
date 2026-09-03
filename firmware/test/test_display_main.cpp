// 独立屏幕测试固件：只验证 GC9A01 本身能不能点亮、方向/颜色对不对。
// 不依赖 LVGL、不依赖编码器——接线只按 docs/旋钮-最小硬件调试方案.md
// 第 2.1 节接屏幕那几根线即可。
// 编译烧录：pio run -e test_display -t upload -t monitor

#include <Arduino.h>
#include <Arduino_GFX_Library.h>
#include "config.h"

static Arduino_GC9A01 *screen;

void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("[TestDisplay] boot");

    Arduino_DataBus *bus = new Arduino_HWSPI(TFT_DC, TFT_CS, TFT_SCLK, TFT_MOSI, TFT_MISO);
    screen = new Arduino_GC9A01(bus, TFT_RST, /*rotation=*/0, /*IPS=*/true);
    screen->begin();

    pinMode(TFT_BLK, OUTPUT);
    digitalWrite(TFT_BLK, HIGH); // 打开背光

    Serial.println("[TestDisplay] screen init done, cycling colors");
}

// 依次填充几种纯色再画一行字：颜色对了 = SPI 接线和驱动没问题，
// 文字方向正常（不是倒的/镜像的）= rotation 参数跟屏幕的物理装配方向一致。
static const uint16_t COLORS[] = {RGB565_RED, RGB565_GREEN, RGB565_BLUE, RGB565_WHITE, RGB565_BLACK};
static const int COLOR_COUNT = sizeof(COLORS) / sizeof(COLORS[0]);
static int s_color_idx = 0;

void loop() {
    uint16_t bg = COLORS[s_color_idx];
    screen->fillScreen(bg);
    screen->setCursor(40, 110);
    screen->setTextColor(bg == RGB565_BLACK ? RGB565_WHITE : RGB565_BLACK);
    screen->setTextSize(2);
    screen->print("HELLO");
    Serial.printf("[TestDisplay] fill color #%d\n", s_color_idx);

    s_color_idx = (s_color_idx + 1) % COLOR_COUNT;
    delay(1000);
}
