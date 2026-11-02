#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 PureTone「智能歌单」矢量图标（非描摹，纯几何构造）。

产出：
  1) app/src/main/res/drawable/ic_smart_playlist.xml          -> Material 功能图标 (24x24, 单色占位黑, 可 tint)
  2) app/src/main/res/drawable/ic_smart_playlist_foreground.xml -> 自适应图标前景层 (108x108, 品牌紫, 内容落在 66dp safe zone)
  3) tools/ic_smart_playlist.svg                               -> 标准 SVG 源 (24x24, currentColor)
  4) 预览 PNG (24 与 108) 到 %LOCALAPPDATA%/Temp，便于人工核对形状

设计：歌单面板(圆角矩形) + 两条曲目横线 = 播放列表；右上 + 右下一大一小四角星火花 = 智能/自动生成。
全部使用 M/L/Q/Z 绝对坐标，便于自适应缩放与本机栅格化预览（不依赖外部渲染器）。
"""
import os, re, math, struct, zlib, shutil

# ---------- 1. 24 坐标系下的几何（绝对坐标，单色）----------

# 歌单面板：圆角矩形 (x=3,y=7.5,w=13,h=13,r=3)
def rounded_rect_path(x, y, w, h, r):
    x2, y2 = x + w, y + h
    return (f"M{x+r:.1f},{y:.1f} L{x2-r:.1f},{y:.1f} "
            f"Q{x2:.1f},{y:.1f} {x2:.1f},{y+r:.1f} "
            f"L{x2:.1f},{y2-r:.1f} Q{x2:.1f},{y2:.1f} {x2-r:.1f},{y2:.1f} "
            f"L{x+r:.1f},{y2:.1f} Q{x:.1f},{y2:.1f} {x:.1f},{y2-r:.1f} "
            f"L{x:.1f},{y+r:.1f} Q{x:.1f},{y:.1f} {x+r:.1f},{y:.1f} Z")

# 播放列表：左侧竖播放条 + 三条曲目横线（均为独立实色条，背景透明故清晰可读）
vbar  = rounded_rect_path(3, 4, 2, 16, 1)
line1 = rounded_rect_path(7, 6.5, 10, 1.8, 0.9)
line2 = rounded_rect_path(7, 11, 10, 1.8, 0.9)
line3 = rounded_rect_path(7, 15.5, 7, 1.8, 0.9)

def four_point_star(cx, cy, R, rin):
    # 外顶点：上/右/下/左；内凹点：45°方向
    out = [(cx, cy-R), (cx+R, cy), (cx, cy+R), (cx-R, cy)]
    inn = []
    for ang in (45, 135, 225, 315):
        a = math.radians(ang)
        inn.append((cx + rin*math.cos(a), cy - rin*math.sin(a)))
    seq = [out[0], inn[0], out[1], inn[1], out[2], inn[2], out[3], inn[3]]
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in seq) + " Z"
    return d

spark_big = four_point_star(20, 5, 2.6, 1.0)        # 主火花（右上，智能/自动）
spark_small = four_point_star(20.5, 12, 1.5, 0.55)  # 次火花（右下）

paths_24 = [vbar, line1, line2, line3, spark_big, spark_small]

# ---------- 2. 坐标变换：24 -> 108（居中，落在 66dp safe zone）----------
def transform_path(d24, k, ox, oy):
    # 提取命令与数字：命令字母 + 后续成对浮点数（绝对坐标点）
    out = []
    i = 0
    toks = re.findall(r'[MLQZmlqz]|[-+]?\d*\.?\d+', d24)
    # 用更稳的方式：逐字符扫描
    cmds = re.findall(r'[MLQZ]|-?\d+\.\d+|-?\d+', d24)
    res = []
    j = 0
    while j < len(cmds):
        t = cmds[j]
        if t in 'MLQZ':
            res.append(t)
            j += 1
        else:
            x = float(t); y = float(cmds[j+1])
            nx = x * k + ox
            ny = y * k + oy
            # 保留一位小数精度足够
            res.append(f"{nx:.2f},{ny:.2f} ")
            j += 2
    return "".join(res)

# 24 内容包围盒
def bbox(paths):
    nums = []
    for d in paths:
        for m in re.finditer(r'[-+]?\d*\.?\d+', d):
            nums.append(float(m.group()))
    # 奇数个（命令）忽略；取所有数字对的最小值/最大值近似
    xs, ys = [], []
    for d in paths:
        vals = [float(x) for x in re.findall(r'[-+]?\d*\.?\d+', d)]
        for idx in range(0, len(vals)-1, 2):
            xs.append(vals[idx]); ys.append(vals[idx+1])
    return min(xs), min(ys), max(xs), max(ys)

minx, miny, maxx, maxy = bbox(paths_24)
cx24 = (minx + maxx) / 2
cy24 = (miny + maxy) / 2
content_half = max((maxx - minx) / 2, (maxy - miny) / 2)
SAFE_R = 28.0  # 留 5dp 余量，确保内容明确落在 66dp 安全区内（108 画布）
k = SAFE_R / content_half
VIEW = 108
ox = VIEW / 2 - cx24 * k
oy = VIEW / 2 - cy24 * k

paths_108 = [transform_path(d, k, ox, oy) for d in paths_24]

# ---------- 3. 写出文件----------
ROOT = r"D:/PureTone"
drawable = os.path.join(ROOT, "app/src/main/res/drawable")
tools = os.path.join(ROOT, "tools")
os.makedirs(drawable, exist_ok=True)

# (a) 功能图标 24x24
xml24 = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="24dp"\n'
    '    android:height="24dp"\n'
    '    android:viewportWidth="24"\n'
    '    android:viewportHeight="24">\n'
)
for d in paths_24:
    xml24 += f'    <path android:fillColor="#FF000000" android:pathData="{d}"/>\n'
xml24 += '</vector>\n'
with open(os.path.join(drawable, "ic_smart_playlist.xml"), "w", encoding="utf-8") as f:
    f.write(xml24)

# (b) 自适应前景层 108x108（品牌紫）
xml108 = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:width="108dp"\n'
    '    android:height="108dp"\n'
    '    android:viewportWidth="108"\n'
    '    android:viewportHeight="108">\n'
)
for d in paths_108:
    xml108 += f'    <path android:fillColor="#FF5E6AD2" android:pathData="{d}"/>\n'
xml108 += '</vector>\n'
with open(os.path.join(drawable, "ic_smart_playlist_foreground.xml"), "w", encoding="utf-8") as f:
    f.write(xml108)

# (c) 标准 SVG 源（24x24, currentColor）
svg = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24">\n'
    '  <g fill="currentColor">\n'
)
for d in paths_24:
    svg += f'    <path d="{d}"/>\n'
svg += '  </g>\n</svg>\n'
with open(os.path.join(tools, "ic_smart_playlist.svg"), "w", encoding="utf-8") as f:
    f.write(svg)

print("wrote ic_smart_playlist.xml / ic_smart_playlist_foreground.xml / ic_smart_playlist.svg")
print("24 bbox:", (minx, miny, maxx, maxy), "center24:", (cx24, cy24))
print("108 scale k=%.3f offset=(%.2f,%.2f)" % (k, ox, oy))
print("108 bbox:", (
    minx*k+ox, miny*k+oy, maxx*k+ox, maxy*k+oy))

# ---------- 4. 本机栅格化预览（支持 M/L/Q/Z）----------
def parse_path_flat(d):
    cmds = re.findall(r'[MLQZ]|-?\d+\.\d+|-?\d+', d)
    subs = []; cur = []; cx = cy = 0.0; sx = sy = 0.0
    def flush():
        nonlocal cur
        if len(cur) >= 3: subs.append(cur)
        cur = []
    j = 0
    while j < len(cmds):
        t = cmds[j]
        if t in 'MLQZ':
            if t == 'M':
                x=float(cmds[j+1]); y=float(cmds[j+2]); cx,cy=x,y; sx,sy=x,y; cur=[(x,y)]; j+=3
            elif t == 'L':
                x=float(cmds[j+1]); y=float(cmds[j+2]); cx,cy=x,y; cur.append((x,y)); j+=3
            elif t == 'Q':
                x1=float(cmds[j+1]); y1=float(cmds[j+2]); x=float(cmds[j+3]); y=float(cmds[j+4])
                steps=18
                for s in range(1,steps+1):
                    u=s/steps; mt=1-u
                    bx=mt*mt*cx+2*mt*u*x1+u*u*x; by=mt*mt*cy+2*mt*u*y1+u*u*y
                    cur.append((bx,by))
                cx,cy=x,y; j+=5
            elif t == 'Z':
                if cur: cur.append((sx,sy))
                flush(); j+=1
        else:
            j+=1
    flush()
    return subs

def fill_polys(img,W,H,subs,color):
    r=int(color[1:3],16); g=int(color[3:5],16); b=int(color[5:7],16)
    edges=[]; miny=H; maxy=0
    for poly in subs:
        n=len(poly)
        for kk in range(n):
            x0,y0=poly[kk]; x1,y1=poly[(kk+1)%n]
            if y0==y1: continue
            if y0>y1: x0,y0,x1,y1=x1,y1,x0,y0
            edges.append((x0,y0,x1,y1)); miny=min(miny,y0); maxy=max(maxy,y1)
    miny=max(0,math.floor(miny)); maxy=min(H-1,math.ceil(maxy))
    for yy in range(miny,maxy+1):
        yc=yy+0.5; xs=[]
        for (x0,y0,x1,y1) in edges:
            if y0<=yc<y1:
                tt=(yc-y0)/(y1-y0); xs.append(x0+tt*(x1-x0))
        xs.sort()
        for kk in range(0,len(xs)-1,2):
            xa=max(0,min(W-1,math.ceil(xs[kk]))); xb=max(0,min(W-1,math.floor(xs[kk+1])))
            for xx in range(xa,xb+1):
                idx=(yy*W+xx)*3; img[idx]=r; img[idx+1]=g; img[idx+2]=b

def render_preview(paths, size, color, out, viewbox=24.0, safe_zone=None):
    img=bytearray(size*size*3)
    scale=size/viewbox
    for d in paths:
        polys=parse_path_flat(d)
        polys=[[(x*scale,y*scale) for (x,y) in sub] for sub in polys]
        fill_polys(img,size,size,polys,color)
    if safe_zone:
        scx,scy,sr=safe_zone
        ccx=scx*scale; ccy=scy*scale; rr=sr*scale
        cr=cg=cb=255  # 白色安全区圆，与紫色图形/黑底形成对比，便于核对落位
        for a in range(0,360):
            aa=math.radians(a)
            px=int(round(ccx+rr*math.cos(aa))); py=int(round(ccy+rr*math.sin(aa)))
            if 0<=px<size and 0<=py<size:
                idx=(py*size+px)*3; img[idx]=cr;img[idx+1]=cg;img[idx+2]=cb
    raw=bytearray()
    for yy in range(size):
        raw.append(0); raw.extend(img[yy*size*3:(yy+1)*size*3])
    comp=zlib.compress(bytes(raw),9)
    def chunk(typ,data):
        c=struct.pack('>I',len(data))+typ+data
        return c+struct.pack('>I',(zlib.crc32(typ+data)&0xffffffff))
    png=b'\x89PNG\r\n\x1a\n'
    png+=chunk(b'IHDR',struct.pack('>IIBBBBB',size,size,8,2,0,0,0))
    png+=chunk(b'IDAT',comp); png+=chunk(b'IEND',b'')
    open(out,'wb').write(png)
    # 客观检测：紫色像素包围盒（不依赖视觉模型）
    minx=miny=10**9; maxx=maxy=-1
    for yy in range(size):
        for xx in range(size):
            idx=(yy*size+xx)*3
            r0,g0,b0=img[idx],img[idx+1],img[idx+2]
            if not (r0==0 and g0==0 and b0==0) and not (r0==255 and g0==255 and b0==255):  # 非黑非白 = 图形
                if xx<minx:minx=xx
                if xx>maxx:maxx=xx
                if yy<miny:miny=yy
                if yy>maxy:maxy=yy
    if minx<10**9:
        print(f"  [{out.split('/')[-1]}] graphic px bbox=({minx},{miny})-({maxx},{maxy}) "
              f"circle=({int(54*scale)},{int(54*scale)}) r={33*scale:.1f}")

tmp = os.path.join(os.environ.get('LOCALAPPDATA','/tmp'), 'Temp')
os.makedirs(tmp, exist_ok=True)
render_preview(paths_24, 240, '#5E6AD2', os.path.join(tmp, 'smart_playlist_24_preview.png'), viewbox=24.0)
render_preview(paths_108, 240, '#5E6AD2', os.path.join(tmp, 'smart_playlist_108_preview.png'), viewbox=108.0, safe_zone=(54, 54, 33))
print("previews ->", os.path.join(tmp, 'smart_playlist_24_preview.png'))
