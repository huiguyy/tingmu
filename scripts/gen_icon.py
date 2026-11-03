#!/usr/bin/env python3
"""生成听幕 launcher 图标 v8（PIL 程序化绘制，无外部素材）。

v8 = 用户认可 v1.2 的"悬浮泡"外观 + 自适应机制（系统合成，只裁切永不拉伸）：
  - adaptive 背景层 = 全透明（泡外无任何底色）
  - adaptive 前景层 = 完整气泡造型（蓝泡+尾巴+白杠+黄徽章）全部画在中央安全区(66%)内
  - legacy PNG（API<26 / 安装界面）= 同款造型，泡稍大铺满画布
  - 预览 = 圆形裁切模拟启动器渲染

用法: python3 gen_icon.py <fast|bilingual|base> <app/src/main/res>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

BLUE = (10, 108, 255, 255)      # #0A6CFF 气泡
NAVY = (17, 24, 39, 255)        # 徽章字
YELLOW = (255, 214, 10, 255)    # 徽章
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)
FONT = "/System/Library/Fonts/STHeiti Light.ttc"
BADGE = {"fast": "快", "bilingual": "双", "base": None}


def draw_bubble(d, S, badge, x0, y0, x1, y1):
    """在给定矩形里画完整气泡造型：蓝泡+尾巴+白杠+黄徽章"""
    d.rounded_rectangle([x0, y0, x1, y1], radius=int((x1 - x0) * 0.16), fill=BLUE)
    tail = int((x1 - x0) * 0.10)
    ty = y1 - 2
    d.polygon([(x0 + int((x1 - x0) * 0.16), ty), (x0 + int((x1 - x0) * 0.16) + tail * 2, ty),
               (x0 + int((x1 - x0) * 0.16) + tail, ty + int((y1 - y0) * 0.30))], fill=BLUE)
    bw, bh = x1 - x0, y1 - y0
    for i, wfrac in enumerate((0.70, 0.46)):
        lh = max(3, int(bh * 0.17))
        ly = y0 + int(bh * (0.24 + i * 0.38))
        lx = x0 + int(bw * 0.12)
        lw = int((bw - 2 * int(bw * 0.12)) * wfrac)
        d.rounded_rectangle([lx, ly, lx + lw, ly + lh], radius=lh // 2, fill=WHITE)
    if badge:
        br = int(bh * 0.34)
        cx, cy = x1 - int(bw * 0.10), y1 - int(bh * 0.02)
        d.ellipse([cx - br, cy - br, cx + br, cy + br], fill=YELLOW)
        bf = ImageFont.truetype(FONT, int(br * 1.25))
        bb = d.textbbox((0, 0), badge, font=bf)
        d.text((cx - (bb[2] - bb[0]) / 2 - bb[0], cy - (bb[3] - bb[1]) / 2 - bb[1]),
               badge, font=bf, fill=NAVY)


def draw_logo(size, badge, mode):
    """mode: fg=自适应前景（整个造型收在中央 66% 安全区）; legacy=安装界面/老系统（泡铺 80% 画布）"""
    S = size
    img = Image.new("RGBA", (S, S), CLEAR)
    d = ImageDraw.Draw(img)
    if mode == "fg":
        # 安全区: 中央 66% → x/y 从 0.17S 到 0.83S
        draw_bubble(d, S, badge, int(S * 0.17), int(S * 0.22), int(S * 0.83), int(S * 0.72))
    else:
        draw_bubble(d, S, badge, int(S * 0.08), int(S * 0.12), int(S * 0.92), int(S * 0.78))
    return img


def main():
    variant, res = sys.argv[1], sys.argv[2]
    badge = BADGE.get(variant)
    os.makedirs(res, exist_ok=True)
    for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = os.path.join(res, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        draw_logo(px * 4, badge, "legacy").resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
    ad = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(ad, exist_ok=True)
    draw_logo(432, badge, "fg").save(os.path.join(ad, "ic_launcher_foreground.png"))
    with open(os.path.join(ad, "ic_launcher.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
                '</adaptive-icon>\n')
    dd = os.path.join(res, "drawable")
    os.makedirs(dd, exist_ok=True)
    # 背景层全透明 → 泡外无任何底色，启动器上呈悬浮泡
    with open(os.path.join(dd, "ic_launcher_background.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android">'
                '<solid android:color="#00000000"/></shape>\n')
    print(f"icons v8 generated (transparent bg adaptive): variant={variant} badge={badge} -> {res}")


if __name__ == "__main__":
    main()
