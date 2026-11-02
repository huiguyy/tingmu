#!/usr/bin/env python3
"""生成听幕 launcher 图标 v2（PIL 程序化绘制，无外部素材）。

设计 v2：蓝底(#0A84FF) + 中央白色粗体「听幕」二字 + 右下角徽章圆圈（快/双）。
理由：小尺寸下单个粗字比细线条图形辨识度高、对比度高（听障友好）。
  - legacy: mipmap-{mdpi..xxxhdpi}/ic_launcher.png (+round) 蓝色圆角方块底
  - 自适应(API26+): foreground = 透明底 + 白字 + 徽章；background = 纯色；monochrome = 白色剪影

用法: python3 gen_icon.py <fast|bilingual|base> <app/src/main/res>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

BLUE = (10, 108, 255, 255)  # #0A6CFF：白字对比度 4.6:1（听障友好），接近 App 主按钮色
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)
FONT = "/System/Library/Fonts/STHeiti Light.ttc"  # Mac 自带中易体，可渲染汉字
BADGE = {"fast": "快", "bilingual": "双", "base": None}


def fit_font(draw, text, max_w, start_size):
    size = start_size
    while size > 8:
        f = ImageFont.truetype(FONT, size)
        b = draw.textbbox((0, 0), text, font=f, stroke_width=max(1, size // 14))
        if b[2] - b[0] <= max_w:
            return f, b
        size -= 2
    return ImageFont.truetype(FONT, 8), draw.textbbox((0, 0), text, font=ImageFont.truetype(FONT, 8))


def draw_content(d, S, badge, mode):
    """v4 反色设计（与 v1/v3 蓝底白标完全不同，一眼能认出是新 logo)：
    蓝色字幕气泡（圆角矩形+尾巴）+ 泡内两条粗白杠 + 右下徽章圆圈（快/双）。
    mode: fg=彩色(蓝泡白杠蓝徽章白字); mono=白色剪影; legacy 下由调用方先铺白底"""
    bubble_fill = WHITE if mode == "mono" else BLUE
    bar_fill = CLEAR if mode == "mono" else WHITE
    # 蓝色气泡
    bx, by = int(S * 0.20), int(S * 0.24)
    bw, bh = int(S * 0.60), int(S * 0.40)
    d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=int(S * 0.07), fill=bubble_fill)
    tail = int(S * 0.045)
    d.polygon([(bx + int(S * 0.12), by + bh - 2),
               (bx + int(S * 0.12) + tail * 2, by + bh - 2),
               (bx + int(S * 0.12) + tail, by + bh + int(S * 0.10))], fill=bubble_fill)
    # 泡内两条粗白杠
    for i, wfrac in enumerate((0.68, 0.44)):
        lh = int(S * 0.10)
        ly = by + int(bh * (0.24 + i * 0.40))
        lx = bx + int(S * 0.08)
        lw = int((bw - 2 * int(S * 0.08)) * wfrac)
        d.rounded_rectangle([lx, ly, lx + lw, ly + lh], radius=lh // 2, fill=bar_fill)
    if badge:
        br = int(S * 0.14)
        cx, cy = int(S * 0.68), int(S * 0.66)  # 保持在自适应安全区(中央 66%)内
        badge_fill = WHITE if mode == "mono" else BLUE
        d.ellipse([cx - br, cy - br, cx + br, cy + br], fill=badge_fill)
        bf = ImageFont.truetype(FONT, int(br * 1.25))
        bb = d.textbbox((0, 0), badge, font=bf)
        char_fill = CLEAR if mode == "mono" else WHITE
        d.text((cx - (bb[2] - bb[0]) / 2 - bb[0], cy - (bb[3] - bb[1]) / 2 - bb[1]),
               badge, font=bf, fill=char_fill)


def draw_logo(size, badge, mode):
    """mode: legacy=蓝底圆角方块+内容; fg=透明+内容(彩色徽章); mono=透明+白色剪影"""
    S = size
    img = Image.new("RGBA", (S, S), CLEAR)
    d = ImageDraw.Draw(img)
    if mode == "legacy":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.18), fill=WHITE)  # v4 白底
    draw_content(d, S, badge, mode)
    return img


def main():
    variant, res = sys.argv[1], sys.argv[2]
    badge = BADGE.get(variant)
    os.makedirs(res, exist_ok=True)
    for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = os.path.join(res, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        logo = draw_logo(px * 4, badge, "legacy").resize((px, px), Image.LANCZOS)
        logo.save(os.path.join(d, "ic_launcher.png"))
        logo.save(os.path.join(d, "ic_launcher_round.png"))
    ad = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(ad, exist_ok=True)
    draw_logo(432, badge, "fg").save(os.path.join(ad, "ic_launcher_foreground.png"))
    draw_logo(432, badge, "mono").save(os.path.join(ad, "ic_launcher_monochrome.png"))
    for n in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(ad, f"{n}.xml"), "w") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                    '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
                    '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
                    '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n'
                    '</adaptive-icon>\n')
    dd = os.path.join(res, "drawable")
    os.makedirs(dd, exist_ok=True)
    with open(os.path.join(dd, "ic_launcher_background.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android">'
                '<solid android:color="#FFFFFF"/></shape>\n')  # v4 白底
    print(f"icons v2 generated: variant={variant} badge={badge} -> {res}")


if __name__ == "__main__":
    main()
