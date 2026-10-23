"""从展示图裁出发光螺旋徽标 → 不透明合成 PureTone 图标前景。

思路：徽标本身是「深色背景上的发光螺旋」，用亮度抠图会削弱光晕。
改为：采样裁剪图边缘的深色作为统一底色，把徽标居中贴到同色画布上；
自适应图标的 background 与启动屏底色都用同一颜色，方块边缘即无缝。

输出：
  app/src/main/res/drawable-nodpi/ic_launcher_foreground.png
  build/preview_dark.png / build/preview_brand.png
  build/edge_color.txt（采样到的底色，供 colors.xml 使用）
"""
import os
from PIL import Image

SRC = r"C:\Users\sidey\.workbuddy\clipboard-images\clipboard-2026-10-20T23-25-15-499Z-6ef55b99.jpg"
ROOT = r"D:\PureTone"
OUT_RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
OUT_BUILD = os.path.join(ROOT, "build")
os.makedirs(OUT_RES, exist_ok=True)
os.makedirs(OUT_BUILD, exist_ok=True)

CROP = (452, 230, 1084, 862)   # 632x632，已人工确认无文字/边框
CANVAS = 512
SCALE = 0.72                   # 裁剪图占画布比例（其内部已含徽标留白）

src = Image.open(SRC).convert("RGB")
crop = src.crop(CROP)

# 采样边缘 12px 的平均色作为底色
w, h = crop.size
px = crop.load()
acc, n = [0, 0, 0], 0
for y in range(h):
    for x in range(w):
        if x < 12 or x >= w - 12 or y < 12 or y >= h - 12:
            r, g, b = px[x, y]
            acc[0] += r; acc[1] += g; acc[2] += b; n += 1
edge = tuple(round(c / n) for c in acc)

side = int(CANVAS * SCALE)
emblem = crop.resize((side, side), Image.LANCZOS)

# 径向羽化：仅淡出裁剪图最外 10%（那里是纯暗边，徽标在 r≈0.86 以内），
# 使方块边缘与同色背景平滑融合，消除接缝。
import numpy as np
arr = np.asarray(emblem).astype(np.float32)
yy, xx = np.mgrid[0:side, 0:side]
c = (side - 1) / 2.0
r = np.sqrt((xx - c) ** 2 + (yy - c) ** 2) / (side / 2.0)
t = np.clip((1.0 - r) / 0.10, 0.0, 1.0)      # r>0.90 开始淡出
alpha = (t * t * (3 - 2 * t) * 255).astype(np.uint8)
emblem = Image.fromarray(np.dstack([arr.astype(np.uint8), alpha]), "RGBA")

fg = Image.new("RGB", (CANVAS, CANVAS), edge)
off = (CANVAS - side) // 2
fg.paste(emblem, (off, off), emblem)
fg.save(os.path.join(OUT_RES, "ic_launcher_foreground.png"))

for name, bg in (("preview_dark.png", edge), ("preview_brand.png", (0x5E, 0x6A, 0xD2))):
    canvas = Image.new("RGB", (CANVAS, CANVAS), bg)
    canvas.paste(fg, (0, 0))
    canvas.save(os.path.join(OUT_BUILD, name))

hex_edge = "#%02X%02X%02X" % edge
with open(os.path.join(OUT_BUILD, "edge_color.txt"), "w") as f:
    f.write(hex_edge)

print("edge color:", edge, hex_edge)
print("foreground ->", os.path.join(OUT_RES, "ic_launcher_foreground.png"))
