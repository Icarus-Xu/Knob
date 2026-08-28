package cn.icarus.knob.api

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 插件页面通用 UI 构建工具（纯代码构建 View，避免资源 ID 冲突）。
 * 壳与插件共用一份（放 api 包，两工程内容保持一致）。
 *
 * 用法：
 *   Ui.vStack(ctx).apply {
 *       addView(Ui.title(ctx, "空调温度"))
 *       addView(Ui.text(ctx, "当前 22°C"))
 *       addView(Ui.btn(ctx, "＋ 升温") { onCommand("ac.temperature", ...) })
 *   }
 */
object Ui {

    // ===== 常用颜色 =====
    const val COLOR_PRIMARY = "#1A237E"
    const val COLOR_TEXT = "#1A1A1A"
    const val COLOR_GRAY = "#555555"
    const val COLOR_HINT = "#888888"
    const val COLOR_BG = "#FFFFFF"

    // ==================== 布局 ====================

    /** 垂直布局（默认内边距 16） */
    fun vStack(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

    /** 水平布局（垂直居中） */
    fun hStack(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    /** 圆角卡片（垂直布局，浅灰底） */
    fun card(context: Context, bg: String = "#F5F5F5"): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = roundedBg(bg)
        }

    // ==================== 文本 ====================

    /** 普通文本 */
    fun text(context: Context, s: String, size: Float = 14f, color: String = COLOR_TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    /** 标题（大号加粗主色） */
    fun title(context: Context, s: String, size: Float = 20f): TextView =
        text(context, s, size, COLOR_PRIMARY, bold = true)

    /** 副标题/说明（灰色小字） */
    fun label(context: Context, s: String, size: Float = 13f): TextView =
        text(context, s, size, COLOR_GRAY)

    /** 提示文字（浅灰小字） */
    fun hint(context: Context, s: String, size: Float = 12f): TextView =
        text(context, s, size, COLOR_HINT)

    // ==================== 按钮 ====================

    /** 按钮 */
    fun btn(context: Context, s: String, onClick: (View) -> Unit): Button =
        Button(context).apply {
            text = s
            setOnClickListener(onClick)
        }

    // ==================== 分隔线 ====================

    /** 水平分隔线 */
    fun divider(context: Context): View =
        View(context).apply {
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }

    // ==================== 间距 ====================

    /** 空占位（用于撑开间距） */
    fun space(context: Context, width: Int = 0, height: Int = 8): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
        }

    // ==================== 辅助 ====================

    /** 圆角背景 drawable */
    fun roundedBg(color: String, radius: Float = 12f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.parseColor(color))
        }
}
