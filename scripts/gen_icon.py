#!/usr/bin/env python3
"""生成听幕 launcher 图标 v9（PIL 程序化绘制，无外部素材）。

v9 = 回到用户认可的 v1.2 纯 PNG 方案（无自适应 icon，避免"光圈/蓝方块"等图层渲染差异）：
  - 单一 PNG 图标：透明底，蓝泡几乎铺满画布（左右 3%~97%、上下 8%~84% + 尾巴），
    白杠加粗，右下黄徽章（快/双）——启动器无论缩放/裁切，看到的都是这个泡。
  - 不生成 mipmap-anydpi-v26 / drawable 背景层；manifest 引用 @mipmap/ic_launcher 走 PNG。

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
BADGE = {"fast": "快", "bilingual": "双", "dialect": "方", "screen": "屏", "hall": "厅", "base": None}


def draw_icon(size, badge):
    S = size
    img = Image.new("RGBA", (S, S), CLEAR)
    d = ImageDraw.Draw(img)
    # 气泡几乎铺满画布，减少任何缩放带来的视觉变形
    x0, y0, x1, y1 = int(S * 0.03), int(S * 0.08), int(S * 0.97), int(S * 0.84)
    d.rounded_rectangle([x0, y0, x1, y1], radius=int(S * 0.14), fill=BLUE)
    tail = int(S * 0.10)
    ty = y1 - 2
    d.polygon([(x0 + int(S * 0.12), ty), (x0 + int(S * 0.12) + tail * 2, ty),
               (x0 + int(S * 0.12) + tail, ty + int(S * 0.13))], fill=BLUE)
    # 两条粗白杠
    bw, bh = x1 - x0, y1 - y0
    for i, wfrac in enumerate((0.72, 0.48)):
        lh = max(4, int(S * 0.10))
        ly = y0 + int(bh * (0.26 + i * 0.34))
        lx = x0 + int(bw * 0.09)
        lw = int((bw - 2 * int(bw * 0.09)) * wfrac)
        d.rounded_rectangle([lx, ly, lx + lw, ly + lh], radius=lh // 2, fill=WHITE)
    # 右下黄徽章
    if badge:
        br = int(S * 0.15)
        cx, cy = int(S * 0.80), int(S * 0.80)
        d.ellipse([cx - br, cy - br, cx + br, cy + br], fill=YELLOW)
        bf = ImageFont.truetype(FONT, int(br * 1.3))
        bb = d.textbbox((0, 0), badge, font=bf)
        d.text((cx - (bb[2] - bb[0]) / 2 - bb[0], cy - (bb[3] - bb[1]) / 2 - bb[1]),
               badge, font=bf, fill=NAVY)
    return img


def main():
    variant, res = sys.argv[1], sys.argv[2]
    badge = BADGE.get(variant)
    os.makedirs(res, exist_ok=True)
    # 清掉可能残留的自适应资源，确保只走 PNG
    for stale in ("mipmap-anydpi-v26",):
        p = os.path.join(res, stale)
        if os.path.isdir(p):
            for f in os.listdir(p):
                os.remove(os.path.join(p, f))
            os.rmdir(p)
    bg = os.path.join(res, "drawable", "ic_launcher_background.xml")
    if os.path.exists(bg):
        os.remove(bg)
    for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = os.path.join(res, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        icon = draw_icon(px * 4, badge).resize((px, px), Image.LANCZOS)
        icon.save(os.path.join(d, "ic_launcher.png"))
        icon.save(os.path.join(d, "ic_launcher_round.png"))
    print(f"icons v9 generated (plain PNG only): variant={variant} badge={badge} -> {res}")


if __name__ == "__main__":
    main()
