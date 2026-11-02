#!/usr/bin/env python3
"""生成听幕 launcher 图标 v7（PIL 程序化绘制，无外部素材）。

v7：恢复自适应图标（根治"变形"——启动器对非自适应 PNG 会强行拉伸适配形状；
自适应由系统固定尺寸合成，只裁切不变形），且不再有黑圈：
  - background 层 = 品牌蓝 #0A6CFF（就是气泡的颜色，蓝形状本身=字幕泡）
  - foreground 层 = 透明底 + 白色两条粗杠 + 黄色徽章（快/双），全部在中央安全区内
  - legacy PNG（API<26 及安装界面）= 整块蓝色圆角气泡 + 尾巴 + 白杠 + 黄徽章
  - 预览图模拟启动器的圆形裁切，所见即所得

用法: python3 gen_icon.py <fast|bilingual|base> <app/src/main/res>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

BLUE = (10, 108, 255, 255)      # #0A6CFF 气泡/背景层
NAVY = (17, 24, 39, 255)        # 徽章字色
YELLOW = (255, 214, 10, 255)    # 徽章
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)
FONT = "/System/Library/Fonts/STHeiti Light.ttc"
BADGE = {"fast": "快", "bilingual": "双", "base": None}


def draw_bars_badge(d, S, badge, scaled=True):
    """白色两条杠 + 黄徽章。scaled=True 时按安全区(中央 66%)缩进（自适应前景用）。"""
    m = 0.17 if scaled else 0.10          # 内容边距
    inner = 1 - 2 * m
    for i, wfrac in enumerate((0.68, 0.44)):
        lw = int(S * inner * 0.62 * wfrac) + int(S * inner * 0.38 * wfrac)
        lw = int(S * inner * wfrac)
        lh = max(3, int(S * 0.09))
        ly = int(S * (m + inner * (0.24 + i * 0.42)))
        lx = int(S * (m + inner * 0.08))
        d.rounded_rectangle([lx, ly, lx + lw, ly + lh], radius=lh // 2, fill=WHITE)
    if badge:
        br = int(S * 0.13)
        cx, cy = int(S * 0.67), int(S * 0.65)
        d.ellipse([cx - br, cy - br, cx + br, cy + br], fill=YELLOW)
        bf = ImageFont.truetype(FONT, int(br * 1.25))
        bb = d.textbbox((0, 0), badge, font=bf)
        d.text((cx - (bb[2] - bb[0]) / 2 - bb[0], cy - (bb[3] - bb[1]) / 2 - bb[1]),
               badge, font=bf, fill=NAVY)


def draw_logo(size, badge, mode):
    """mode: legacy=整块蓝色圆角气泡(带尾巴)+内容; fg=透明底+安全区内内容(自适应前景)"""
    S = size
    img = Image.new("RGBA", (S, S), CLEAR)
    d = ImageDraw.Draw(img)
    if mode == "legacy":
        d.rounded_rectangle([0, 0, S - 1, int(S * 0.86)], radius=int(S * 0.16), fill=BLUE)
        tail = int(S * 0.06)
        d.polygon([(int(S * 0.14), int(S * 0.84)), (int(S * 0.14) + tail * 2, int(S * 0.84)),
                   (int(S * 0.14) + tail, int(S * 0.97))], fill=BLUE)
        draw_bars_badge(d, S, badge, scaled=False)
    else:
        draw_bars_badge(d, S, badge, scaled=True)
    return img


def mask_circle(img):
    """模拟启动器圆形裁切（预览用）"""
    S = img.size[0]
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).ellipse([0, 0, S - 1, S - 1], fill=255)
    out = Image.new("RGBA", (S, S), CLEAR)
    out.paste(img, (0, 0), m)
    return out


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
    with open(os.path.join(dd, "ic_launcher_background.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android">'
                '<solid android:color="#0A6CFF"/></shape>\n')
    print(f"icons v7 generated: variant={variant} badge={badge} -> {res}")


if __name__ == "__main__":
    main()
