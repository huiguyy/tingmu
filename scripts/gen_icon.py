#!/usr/bin/env python3
"""生成听幕 launcher 图标（PIL 绘制， 无外部素材）。

设计：蓝底圆角方块 + 白色对话气泡 + 气泡内三条蓝色"字幕行" + 右下角徽章字（快/双）。
  - legacy: mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png (+ ic_launcher_round.png 同款)
  - 自适应(API 26+): mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_foreground.png(432)
                     + drawable/ic_launcher_background.xml(纯色)

用法: python3 gen_icon.py <fast|bilingual|base> <app/src/main/res>
  fast→徽章"快"  bilingual→徽章"双"  base→无徽章
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

BLUE = (10, 132, 255, 255)      # 与 App 主按钮同色 #0A84FF
WHITE = (255, 255, 255, 255)
FONT = "/System/Library/Fonts/STHeiti Light.ttc"

BADGE = {"fast": "快", "bilingual": "双", "base": None}


def draw_logo(size: int, badge: str | None, rounded_bg: bool, monochrome: bool = False) -> Image.Image:
    S = size
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    line_fill = WHITE if monochrome else BLUE
    bubble_fill = WHITE
    badge_text_fill = WHITE if monochrome else BLUE
    if not monochrome:
        r = int(S * 0.18)
        if rounded_bg:
            d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=BLUE)
        else:
            d.rectangle([0, 0, S - 1, S - 1], fill=BLUE)  # 自适应背景层全幅纯色
    # 气泡（白色圆角矩形 + 尾巴），整体居中偏上
    bw, bh = int(S * 0.56), int(S * 0.36)
    bx, by = (S - bw) // 2, int(S * 0.24)
    d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=int(S * 0.07), fill=bubble_fill)
    tail = int(S * 0.05)
    d.polygon([(bx + int(S * 0.10), by + bh - 2),
               (bx + int(S * 0.10) + tail * 2, by + bh - 2),
               (bx + int(S * 0.10) + tail, by + bh + int(S * 0.09))], fill=bubble_fill)
    # 气泡内三条"字幕行"
    for i, wfrac in enumerate((0.66, 0.50, 0.32)):
        lw = int((bw - 2 * int(S * 0.07)) * wfrac)
        lh = max(3, int(S * 0.035))
        ly = by + int(bh * (0.22 + i * 0.28))
        lx = bx + int(S * 0.07)
        d.rounded_rectangle([lx, ly, lx + lw, ly + lh], radius=lh // 2, fill=line_fill)
    # 徽章：右下角圆 + 字（monochrome 下整体为白色剪影）
    if badge:
        br = int(S * 0.17)
        cx, cy = int(S * 0.66), int(S * 0.62)
        d.ellipse([cx - br, cy - br, cx + br, cy + br], fill=bubble_fill,
                  outline=(0, 0, 0, 0) if monochrome else BLUE, width=max(2, S // 128))
        f = ImageFont.truetype(FONT, int(br * 1.15))
        tb = d.textbbox((0, 0), badge, font=f)
        d.text((cx - (tb[2] - tb[0]) / 2 - tb[0], cy - (tb[3] - tb[1]) / 2 - tb[1]), badge, font=f, fill=badge_text_fill)
    return img


def main():
    variant, res = sys.argv[1], sys.argv[2]
    badge = BADGE.get(variant)
    os.makedirs(res, exist_ok=True)
    # legacy 各密度（整块圆角蓝底）
    for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = os.path.join(res, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        logo = draw_logo(px * 4, badge, rounded_bg=True).resize((px, px), Image.LANCZOS)
        logo.save(os.path.join(d, "ic_launcher.png"))
        logo.save(os.path.join(d, "ic_launcher_round.png"))
    # 自适应 icon
    ad = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(ad, exist_ok=True)
    fg = draw_logo(432, badge, rounded_bg=False)  # 透明底， 内容在安全区内
    fg.save(os.path.join(ad, "ic_launcher_foreground.png"))
    # monochrome 层：Android 13+ 主题图标用，白色剪影（气泡+徽章），避免被压成纯色块
    mono = draw_logo(432, badge, rounded_bg=False, monochrome=True)
    mono.save(os.path.join(ad, "ic_launcher_monochrome.png"))
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
                '<solid android:color="#0A84FF"/></shape>\n')
    print(f"icons generated for variant={variant} badge={badge} -> {res}")


if __name__ == "__main__":
    main()
