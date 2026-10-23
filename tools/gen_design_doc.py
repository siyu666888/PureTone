# -*- coding: utf-8 -*-
"""
PureTone 各页面及功能设计详情 —— Word 文档生成脚本
依据 app/src/main/java/com/puretone/player 下源码整理，输出简体中文 .docx。
运行：python gen_design_doc.py
"""
import os
from docx import Document
from docx.shared import Pt, RGBColor, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

# ---- 品牌色（与 Color.kt 一致） ----
BRAND_HEX = "5E6AD2"
BRAND = RGBColor(0x5E, 0x6A, 0xD2)
DARK = RGBColor(0x23, 0x29, 0x65)
GREY = RGBColor(0x47, 0x46, 0x4F)

DOC_PATH = os.path.join(os.path.dirname(__file__), "..", "dist", "PureTone_设计详情_v1.1.4.docx")

def set_cell_bg(cell, hexcolor):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), hexcolor)
    tcPr.append(shd)

def set_repeat_header(row):
    trPr = row._tr.get_or_add_trPr()
    th = OxmlElement('w:tblHeader')
    th.set(qn('w:val'), 'true')
    trPr.append(th)

def style_base(doc):
    # 默认字体（中文）
    style = doc.styles['Normal']
    style.font.name = '微软雅黑'
    style.font.size = Pt(10.5)
    style.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    # 标题颜色
    for name, color, size in [
        ('Heading 1', BRAND, 17),
        ('Heading 2', DARK, 13.5),
        ('Heading 3', DARK, 11.5),
    ]:
        st = doc.styles[name]
        st.font.color.rgb = color
        st.font.size = Pt(size)
        st.font.name = '微软雅黑'
        st.element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

def add_para(doc, text, size=10.5, bold=False, color=None, after=6, before=0, italic=False):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(after)
    p.paragraph_format.space_before = Pt(before)
    r = p.add_run(text)
    r.font.size = Pt(size)
    r.bold = bold
    r.italic = italic
    if color:
        r.font.color.rgb = color
    r.font.name = '微软雅黑'
    r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    return p

def add_bullets(doc, items, size=10.5):
    for it in items:
        p = doc.add_paragraph(style='List Bullet')
        p.paragraph_format.space_after = Pt(2)
        r = p.add_run(it)
        r.font.size = Pt(size)
        r.font.name = '微软雅黑'
        r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')

def add_kv_table(doc, rows, col0="项目", col1="说明", widths=(1.5, 5.0)):
    table = doc.add_table(rows=1, cols=2)
    table.style = 'Light Grid Accent 1'
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = table.rows[0].cells
    hdr[0].text = col0
    hdr[1].text = col1
    for c in hdr:
        set_cell_bg(c, BRAND_HEX)
        for p in c.paragraphs:
            for r in p.runs:
                r.font.bold = True
                r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
                r.font.size = Pt(10)
                r.font.name = '微软雅黑'
                r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    set_repeat_header(table.rows[0])
    for k, v in rows:
        cells = table.add_row().cells
        cells[0].text = k
        cells[1].text = v
        for ci, c in enumerate(cells):
            for p in c.paragraphs:
                for r in p.runs:
                    r.font.size = Pt(9.5)
                    r.font.name = '微软雅黑'
                    r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
                    if ci == 0:
                        r.font.bold = True
                        r.font.color.rgb = DARK
    table.columns[0].width = Inches(widths[0])
    table.columns[1].width = Inches(widths[1])
    doc.add_paragraph().paragraph_format.space_after = Pt(4)
    return table

def add_feature_table(doc, headers, data, widths=None):
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = 'Light Grid Accent 1'
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr = table.rows[0].cells
    for i, h in enumerate(headers):
        hdr[i].text = h
        set_cell_bg(hdr[i], BRAND_HEX)
        for p in hdr[i].paragraphs:
            for r in p.runs:
                r.font.bold = True
                r.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
                r.font.size = Pt(9.5)
                r.font.name = '微软雅黑'
                r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    set_repeat_header(table.rows[0])
    for row in data:
        cells = table.add_row().cells
        for i, val in enumerate(row):
            cells[i].text = val
            for p in cells[i].paragraphs:
                for r in p.runs:
                    r.font.size = Pt(9.5)
                    r.font.name = '微软雅黑'
                    r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    if widths:
        for i, w in enumerate(widths):
            table.columns[i].width = Inches(w)
    doc.add_paragraph().paragraph_format.space_after = Pt(4)
    return table

def hr(doc):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(2)
    pPr = p._p.get_or_add_pPr()
    pbdr = OxmlElement('w:pBdr')
    bottom = OxmlElement('w:bottom')
    bottom.set(qn('w:val'), 'single')
    bottom.set(qn('w:sz'), '6')
    bottom.set(qn('w:space'), '1')
    bottom.set(qn('w:color'), BRAND_HEX)
    pbdr.append(bottom)
    pPr.append(pbdr)

def build():
    doc = Document()
    style_base(doc)

    # ===== 封面 =====
    for _ in range(3):
        doc.add_paragraph()
    t = doc.add_paragraph()
    t.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = t.add_run("PureTone 纯净之音")
    r.font.size = Pt(30); r.bold = True; r.font.color.rgb = BRAND
    r.font.name = '微软雅黑'; r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    sub = doc.add_paragraph()
    sub.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = sub.add_run("各页面及功能设计详情")
    r.font.size = Pt(18); r.font.color.rgb = DARK
    r.font.name = '微软雅黑'; r._element.rPr.rFonts.set(qn('w:eastAsia'), '微软雅黑')
    doc.add_paragraph()
    v = doc.add_paragraph(); v.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = v.add_run("Android 原生音乐播放器 · 设计规格说明书")
    r.font.size = Pt(12); r.font.color.rgb = GREY
    meta = doc.add_paragraph(); meta.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = meta.add_run("版本 v1.1.4  ·  生成于 2026-10-22  ·  技术栈 Jetpack Compose + Material 3")
    r.font.size = Pt(10); r.font.color.rgb = GREY
    doc.add_page_break()

    # ===== 一、概述 =====
    doc.add_heading("一、产品概述", level=1)
    add_para(doc,
        "PureTone（纯净之音）是一款面向 Android 平台的本地音乐播放器，采用 Jetpack Compose 全量声明式 UI，"
        "主打“纯净、沉浸、克制的视觉与交互”。应用以 Material 3 设计语言为基底，叠加品牌蓝紫色板与自定义动效，"
        "覆盖本地曲库扫描、播放控制、歌词、音效、听歌统计等完整体验。本说明书依据工程源码逐页梳理布局、交互与功能设计。")
    add_kv_table(doc, [
        ("应用名称", "PureTone · 纯净之音"),
        ("目标平台", "Android（minSdk 26 / targetSdk 34 / compileSdk 34）"),
        ("技术栈", "Kotlin + Jetpack Compose + Material 3 + Hilt + Room + ExoPlayer + MediaStore"),
        ("构建环境", "AGP 8.2.2 · JDK 17 · Gradle 8.7"),
        ("当前版本", "v1.1.4（versionCode 10）"),
        ("导航结构", "底部三标签（音乐 / 我的 / 设置）+ 全屏播放页 + 均衡器页 + 听歌统计页（子页）"),
    ], widths=(1.5, 5.2))

    # ===== 二、整体架构 =====
    doc.add_heading("二、整体架构与导航", level=1)
    add_para(doc, "应用根（PureToneApp）采用 Scaffold 布局：底部导航栏（NavigationBar）+ 全局迷你播放条（MiniPlayer）+ 导航宿主（NavHost）。"
                  "播放页、均衡器页、听歌统计页为从底部导航或功能入口进入的子页面（带返回按钮的 TopAppBar）。")
    add_feature_table(doc,
        ["页面", "路由常量", "入口", "呈现形式"],
        [
            ["音乐", "Routes.BROWSE", "底部导航默认页", "Tab 页（含 TopAppBar）"],
            ["我的", "Routes.ME", "底部导航", "Tab 页"],
            ["设置", "Routes.SETTINGS", "底部导航", "Tab 页"],
            ["播放页", "Routes.PLAYER", "迷你播放条 / 点击歌曲", "全屏覆盖页"],
            ["均衡器", "Routes.EQ", "播放页 / 设置页入口", "子页（带返回）"],
            ["听歌统计", "Routes.STATS", "我的页入口", "子页（带返回）"],
        ],
        widths=(1.3, 1.5, 2.2, 1.7))
    add_para(doc, "约束：当停留在播放页（onPlayer）时，底部导航栏与迷你播放条整体隐藏，保证全屏沉浸；其余页面迷你播放条常驻于底部导航之上，"
                  "点击任意处或播放/下一首按钮进行交互。", color=GREY, size=9.5)

    # ===== 三、设计规范 =====
    doc.add_heading("三、视觉与交互设计规范", level=1)

    doc.add_heading("3.1 配色体系", level=2)
    add_para(doc, "品牌静态色板为全局默认；播放页封面取色默认开启；系统壁纸动态取色默认关闭（Android 12+ 可手动开启）。"
                  "提供浅色 / 深色 / 纯黑（AMOLED）三套配色方案。")
    add_feature_table(doc,
        ["角色", "色值 / 说明"],
        [
            ["品牌主色 Primary", "#5E6AD2（纯净蓝紫），应用于强调、激活态、主按钮"],
            ["主色浅阶 / 深阶", "#8A93E4 / #3A449E"],
            ["主色容器", "#E4E3FA（浅色下容器底色）"],
            ["辅助色 Tertiary", "#7A56C6（图表第二色相，如时段分布柱）"],
            ["深空底（深色）", "#121218，深色方案 background / surface"],
            ["纯黑（AMOLED）", "background/surface = #000000，其余沿用深色阶，OLED 省电"],
        ],
        widths=(2.0, 4.7))

    doc.add_heading("3.2 排版规范", level=2)
    add_para(doc, "设置页统一节奏：分类标题比子项大一级，分组头行高 63dp（titleLarge 22sp），子项行高 55dp（bodyLarge 16sp），"
                  "全页同一节奏、内容铺满可滚动。通用文字层级遵循 Material 3 Typography。")
    add_kv_table(doc, [
        ("分组标题", "titleLarge · 22sp · 行高 63dp · 主色"),
        ("子项 / 列表主体", "bodyLarge · 16sp · 行高 55dp"),
        ("歌曲标题（列表行）", "titleMedium，单行省略"),
        ("副信息（艺术家·专辑）", "bodyMedium · onSurfaceVariant"),
        ("页面大标题（如“音乐”）", "headlineSmall"),
    ], widths=(2.2, 4.5))

    doc.add_heading("3.3 形状与圆角", level=2)
    add_para(doc, "全局采用 Material 3 形状令牌（PureToneShapes）。列表封面为 8dp 圆角矩形；播放页封面为 24dp 大圆角方形并支持 3D 翻转；"
                  "封面缩略图、按钮多用圆形（CircleShape）。")

    doc.add_heading("3.4 动效语言", level=2)
    add_bullets(doc, [
        "切歌：上滑 / 下滑触发 AnimatedContent 视差过渡（新封面自方向滑入、旧封面滑出 + 缩放淡入淡出），与手势方向一致；",
        "封面翻转：单击在 550ms tween 内绕 Y 轴 3D 翻转（rotationY 0↔180），展示背面歌词；",
        "按压缩放：主播放键与次级按钮采用中等回弹（MediumBouncy）弹簧缩放，松手回弹；",
        "状态切换：播放/暂停图标、激活态着色均带淡入淡出 + 轻微缩放过渡；",
        "进度条：自定义控件，拖动时滑块上方弹出时间气泡，GPU 加速。",
    ])

    doc.add_heading("3.5 图标系统", level=2)
    add_para(doc, "播放控制图标（ControlIcons）为自绘矢量（ImageVector.Builder + PathBuilder），统一线形语言："
                  "上一首/下一首为双尖角 « / » 线形（描边 1.9dp 圆头圆角）；播放为实心圆角三角（描边同色柔化尖角）；暂停为两条圆头竖条（描边 2.8dp）。"
                  "描边用 Color.Black 占位，实际由 Icon(tint=…) 统一着色，天然支持封面取色 / 主题色。辅助图标（均衡器、循环、随机、队列）采用 Material Outlined 细线风格。")

    # ===== 四、页面与功能设计详情 =====
    doc.add_heading("四、页面与功能设计详情", level=1)

    # 4.1 权限引导页
    doc.add_heading("4.1 权限引导页（PermissionGate）", level=2)
    add_para(doc, "首装或未授权时，应用先渲染授权引导页，而非功能页，确保“先授权后使用”。")
    add_kv_table(doc, [
        ("触发条件", "缺少必需权限：媒体读取 + 通知"),
        ("必需权限", "API33+：READ_MEDIA_AUDIO、POST_NOTIFICATIONS；低版本：READ_EXTERNAL_STORAGE"),
        ("界面", "居中标题“需要音乐访问权限”+ 说明文案 + “授予权限”按钮 + “前往系统设置”按钮"),
        ("授权后", "required 权限全部授予即通过门控，渲染正常内容（曲库自动扫描建库）"),
    ], widths=(1.8, 4.9))
    add_para(doc, "注意：API33+ 必须同时授予通知权限，否则前台播放服务无法展示通知、可能导致播放崩溃。", color=GREY, size=9.5)

    # 4.2 音乐页
    doc.add_heading("4.2 音乐页（BrowseScreen / 路由 BROWSE）", level=2)
    add_para(doc, "本地曲库主浏览页。顶部 TopAppBar 标题“音乐”，右侧动作区含四项工具；内容区顶部为“播放全部”入口，"
                  "其后为搜索框（按需展开）与歌曲列表。")
    doc.add_heading("布局结构", level=3)
    add_bullets(doc, [
        "TopAppBar：标题“音乐” + 动作按钮 [扫描] [补全封面] [排序] [搜索]；",
        "内容区顶部 Row：FilledTonalButton“播放全部”（无数量文案，曲库非空才可点）；",
        "扫描 / 封面补全进行中：动作图标替换为进度圈，并显示“正在扫描本地音乐… / 正在补全封面 n/m”；",
        "搜索开启后：出现 OutlinedTextField（搜索歌曲/艺术家/专辑，右侧清空按钮）；",
        "列表区：LazyColumn 渲染 SongRow，曲库为空时展示 EmptyState 空态文案。",
    ])
    doc.add_heading("功能与交互", level=3)
    add_feature_table(doc,
        ["功能", "说明"],
        [
            ["曲库扫描", "点击刷新图标触发；曲库为空且本次进程未扫描过时自动扫描一次（授权后首次进入即建库）。扫描器自动跳过录音/铃声/提示音等非音乐文件。"],
            ["补全封面", "一键联网补全本页缺失封面，进度实时展示（正在补全封面 n/m）。"],
            ["排序", "下拉菜单：按标题 / 添加时间 / 歌手 / 专辑 / 时长 / 播放次数，当前项右侧显示勾选。"],
            ["搜索", "切换显示搜索框；实时过滤歌曲/艺术家/专辑，结果为空展示“未找到匹配的音乐”。"],
            ["播放全部", "点击从列表第一首开始顺序播放整库（FilledTonalButton）。"],
            ["单曲播放", "点击列表任意行从该首开始播放其所在列表。"],
        ],
        widths=(1.6, 5.1))
    doc.add_heading("歌曲列表行（SongRow 组件）", level=3)
    add_kv_table(doc, [
        ("左", "48dp 圆角封面（无封面显示音符图标）"),
        ("中", "歌名（titleMedium）+ 副行“艺术家 · 专辑”（单行省略）"),
        ("右", "已收藏显示主色心形；APE 格式显示“APE”标签；可注入额外尾部操作（如队列移除）"),
    ], widths=(1.2, 5.5))

    # 4.3 迷你播放条
    doc.add_heading("4.3 迷你播放条（MiniPlayer · 全局常驻）", level=2)
    add_para(doc, "在音乐/我的/设置三页底部导航之上常驻显示（当前有歌曲且不在播放页时）。是“随处可见、随处可控”的核心入口。")
    add_feature_table(doc,
        ["元素 / 交互", "说明"],
        [
            ["封面缩略图", "48dp 圆角图，无封面显示音符"],
            ["主信息", "歌名（titleMedium）；副行默认艺术家，长按切换为“剩余时长 / 总时长”（淡入淡出过渡）"],
            ["播放/暂停", "次级控制按钮，图标随状态切换"],
            ["下一首", "次级控制按钮，跳下一首"],
            ["底部细进度线", "3dp 主色进度条，直观感知播放进度"],
            ["点击整条", "打开全屏播放页"],
        ],
        widths=(1.8, 4.9))

    # 4.4 我的页
    doc.add_heading("4.4 我的页（MeScreen / 路由 ME）", level=2)
    add_para(doc, "个人音乐聚合页，垂直滚动，包含听歌数据入口、最近播放、我喜欢、歌单。")
    add_feature_table(doc,
        ["区块", "说明"],
        [
            ["听歌数据入口", "卡片点击进入听歌统计页（Routes.STATS），图标用品牌主色。"],
            ["最近播放", "取最近 8 首，SongRow 列表，点击从该项开始播放。"],
            ["我喜欢", "收藏歌曲集合，标题带数量；点击播放整组收藏。"],
            ["歌单", "横向 LazyRow 展示各歌单卡片（名称 + 曲目数 + 播放/删除按钮），空则显示“暂无歌单”。"],
        ],
        widths=(1.6, 5.1))

    # 4.5 设置页
    doc.add_heading("4.5 设置页（SettingsScreen / 路由 SETTINGS）", level=2)
    add_para(doc, "采用分组卡片（SettingsGroup）布局，分类头行高 63dp、子项行高 55dp，全页统一节奏、可滚动。")
    add_feature_table(doc,
        ["分组", "子项 / 控件", "功能"],
        [
            ["外观", "主题分段控件（系统/浅色/深色/纯黑）", "整行分段轨道，选中态 primary/onPrimary 动画切换"],
            ["外观", "启用系统动态取色（实验）", "Switch，默认关"],
            ["外观", "播放页封面取色", "Switch，默认开（播放页局部光晕）"],
            ["播放", "无缝播放（gapless）", "Switch，开启后曲目间无间隙"],
            ["播放", "启动时续播上次歌曲", "Switch，启动时恢复上次进度"],
            ["播放", "倍速", "分段控件 0.5x / 1x / 1.25x / 1.5x / 2x"],
            ["播放", "定时", "分段控件 关 / 15m / 30m / 45m / 60m（睡眠定时）"],
            ["音效", "均衡器", "单行可点击入口，跳转均衡器页（开关在页内控制）"],
            ["底部", "品牌 + 版本号", "居中“PureTone · 纯净之音”与“v<版本名>”"],
        ],
        widths=(1.0, 2.6, 3.1))

    # 4.6 播放页
    doc.add_heading("4.6 播放页（PlayerScreen / 路由 PLAYER）", level=2)
    add_para(doc, "全屏沉浸式播放页，是所有播放交互的核心。封面以“旋转 CD”呈现，并承载丰富手势与浮层。")
    doc.add_heading("布局结构（自上而下）", level=3)
    add_bullets(doc, [
        "TopAppBar：标题固定“正在播放” + 返回按钮（右上角功能按钮已下沉至内容区）；",
        "封面区（占宽 0.82）：旋转 CD + 单击翻转歌词 / 上滑下滑动切换歌 / 双击播放暂停 / 长按歌曲信息；",
        "标题 + 艺术家；",
        "APE 转码提示（转码中进度圈）/ 错误提示；",
        "进度条上方一行：左=均衡器入口，右=收藏按钮；",
        "自定义进度条（带时间气泡）+ 起止时间；",
        "底部四核心按钮：左=播放模式，中=上一首/播放暂停/下一首（严格对称），右=播放队列。",
    ])
    doc.add_heading("旋转 CD 封面", level=3)
    add_para(doc, "播放时 CD 以 14 秒/圈匀速持续旋转（LinearEasing 无限 animateTo）；暂停即停在当前角度（不归零）；"
                  "切歌不重置角度（Animatable 实例跨 entry 保持）。视觉由外到内：深色盘体（#161616，0.96）+ 内圈白色 10% 细高光（0.93）+ 圆形封面（0.86）+ 中心孔（页面底色 0.16）+ 中心芯（深色 0.05）。")
    doc.add_heading("封面手势（SwipeableCover）", level=3)
    add_feature_table(doc,
        ["手势", "动作"],
        [
            ["单击", "3D 翻转切换至背面歌词（再点翻回）"],
            ["双击", "播放 / 暂停"],
            ["长按（500ms）", "唤起歌曲信息浮层"],
            ["上滑", "切下一首（新封面自下而上滑入，带阻尼 tanh）"],
            ["下滑", "切上一首（新封面自上而下滑入）"],
            ["松手过阈值(90px)", "容器位移以 380ms 补间归位，与过渡衔接避免瞬移"],
        ],
        widths=(2.0, 4.7))
    doc.add_heading("背面歌词（LyricsFace）", level=3)
    add_bullets(doc, [
        "自动加载同目录 .lrc 歌词（当前歌曲变化时 LaunchedEffect 触发）；",
        "字号可在 12–30sp 间 ±2 调节（缩/放按钮）；",
        "当前播放行高亮为主色，并随播放位置自动滚动（animateScrollToItem）；",
        "无歌词时提示“可将同名 .lrc 放在歌曲同一目录”。",
    ])
    doc.add_heading("播放队列浮层（底部半模态）", level=3)
    add_bullets(doc, [
        "展示队列全部曲目（含数量），点击任意首跳至该曲播放；",
        "非当前曲右侧显示移除按钮（关闭图标）可移出队列；",
        "队列为空显示“队列为空”。",
    ])
    doc.add_heading("播放控制按钮（底部）", level=3)
    add_feature_table(doc,
        ["按钮", "样式 / 状态"],
        [
            ["播放模式", "OFF（灰）/ ALL 列表循环 / ONE 单曲循环 / SHUFFLE 随机，四态循环切换；激活态主色 + 底部小圆点指示"],
            ["上一首 / 下一首", "自绘线形双尖角 « / »（ControlIcons），按压缩放反馈"],
            ["播放 / 暂停", "84dp 圆形渐变主按钮（线性渐变 + 阴影），弹簧按压缩放；图标 AnimatedContent 切换"],
            ["均衡器", "Outlined.Equalizer，跳转均衡器页"],
            ["收藏", "心形，点亮态主色填充，切换弹跳过渡"],
            ["播放队列", "AutoMirrored.Outlined.QueueMusic，唤起队列浮层"],
        ],
        widths=(1.6, 5.1))

    # 4.7 听歌统计页
    doc.add_heading("4.7 听歌统计页（StatsScreen / 路由 STATS）", level=2)
    add_para(doc, "听歌数据看板，垂直滚动，含总览指标与多张图表卡片（柱状图使用 Compose Canvas GPU 加速）。")
    add_feature_table(doc,
        ["区块", "内容"],
        [
            ["总览卡片", "累计时长、总播放次数、收听歌曲数、活跃天数、日均时长、最勤时段（6 张指标卡 2×3）"],
            ["每日听歌", "近 14 天柱状图（主色），峰值柱高亮，底部标注起止日期"],
            ["时段分布", "0–23 时 24 柱（辅助色 tertiary），高亮最勤时段"],
            ["最爱歌手", "Top 列表（名称 + 播放次数）"],
            ["最爱歌曲", "Top 列表（标题 + 艺术家 · 次数）"],
            ["趣味标签", "如“最爱歌手 / 深夜听众 / 日常听众 / 近 30 天活跃天数”"],
            ["空态", "无数据时提示“去播放几首歌后再来看看吧”"],
        ],
        widths=(1.6, 5.1))

    # 4.8 均衡器页
    doc.add_heading("4.8 均衡器页（EqScreen / 路由 EQ）", level=2)
    add_para(doc, "音效调节页，顶部开关控制整体启用；下方预设 + 多频段增益 + 重低音/环绕声。")
    add_feature_table(doc,
        ["区块", "说明"],
        [
            ["启用开关", "TopAppBar 右侧 Switch，关闭后所有频段/效果滑块禁用"],
            ["预设", "Chip 标签（如 默认/流行/摇滚/古典 等），点击一键套用预设曲线"],
            ["频段", "按中心频率（如 60/230/910/3k/14k）逐段滑块，显示当前 dB，范围 MIN_LEVEL_MB..MAX_LEVEL_MB"],
            ["重低音 / 环绕声", "两个 LabeledSlider（百分比 0–100，步进 1%），受启用开关约束"],
            ["提示", "底部注明“音效效果需设备支持，部分机型可能无变化”"],
        ],
        widths=(1.6, 5.1))

    # 4.9 歌曲信息浮层
    doc.add_heading("4.9 歌曲信息浮层（SongInfoSheet）", level=2)
    add_para(doc, "长按播放页封面唤起，底部半模态。支持查看/编辑本地信息与联网匹配元数据。")
    add_feature_table(doc,
        ["区块", "说明"],
        [
            ["头部", "标题“歌曲信息”+ 关闭按钮；封面缩略 + 只读摘要（歌名/艺术家·时长/文件路径）"],
            ["编辑字段", "歌名 / 歌手 / 专辑 三个 OutlinedTextField + “保存修改”按钮（写入 Room，列表实时刷新）"],
            ["联网匹配", "搜索关键词输入 + 搜索按钮；显示来源（iTunes 等）+ 候选列表，可“使用”套用"],
            ["一键操作", "“自动填充”（按歌手+歌名一键套用首个候选，含封面）/ “下载歌词”（从歌词源抓取存至 App 歌词目录，播放页自动读取）"],
            ["结果列表", "候选最多 8 条，逐条“使用”；可清空结果"],
        ],
        widths=(1.6, 5.1))
    add_para(doc, "应用匹配成功后即时刷新播放页当前歌曲（refreshCurrentSong），并弹出短暂提示。", color=GREY, size=9.5)

    # ===== 五、播放引擎与数据层（功能支撑） =====
    doc.add_heading("五、播放引擎与数据层（功能支撑）", level=1)
    doc.add_heading("5.1 播放引擎", level=2)
    add_feature_table(doc,
        ["能力", "说明"],
        [
            ["解码", "ExoPlayer；APE 无损通过 ApeTranscoder 软解转码（播放页显示“APE 转码中…”进度）"],
            ["无缝播放", "gapless 开关，曲目间无间隙衔接"],
            ["倍速", "0.5x–2x（设置页分段选择）"],
            ["睡眠定时", "15/30/45/60 分钟定时停止"],
            ["续播", "启动时恢复上次歌曲与进度"],
            ["循环/随机", "OFF / 列表循环 / 单曲循环 / 随机，四态"],
            ["收藏", "单首收藏，全局“我喜欢”聚合"],
        ],
        widths=(1.6, 5.1))
    doc.add_heading("5.2 数据层", level=2)
    add_bullets(doc, [
        "扫描：MediaStoreScanner 扫描本地音频，自动过滤非音乐类型；授权后首次自动建库。",
        "持久化：Room 存储 SongEntity（歌曲）、PlaylistEntity/PlaylistSongEntity（歌单）、PlayHistoryEntity（播放历史）、Stats 统计。",
        "元数据：MetadataViewModel 联网匹配（iTunes 等）补全歌曲信息；LRCLIB 等歌词源下载 .lrc。",
        "封面：backfillMissingCovers 一键联网补全缺失封面；本地优先读取专辑封面。",
        "歌词：播放页自动加载同目录 .lrc，或经歌曲信息浮层联网下载至 App 歌词目录。",
        "歌单：支持创建/删除，我的页横向卡片展示并可一键播放。",
        "统计：记录播放行为与收听时长，驱动听歌统计页看板。",
    ])

    # ===== 六、结论 =====
    doc.add_heading("六、设计要点小结", level=1)
    add_bullets(doc, [
        "统一的 Material 3 + 品牌蓝紫语言，三套主题与封面取色兼顾沉浸与个性化；",
        "底部三标签 + 常驻迷你播放条，保证“随处可控”；全屏播放页专注沉浸体验；",
        "丰富而克制的手势（翻转歌词、视差切歌、双击暂停）与弹簧/AnimatedContent 动效；",
        "自绘线形控制图标 + Outlined 辅助图标，统一着色支持取色；",
        "扫描、元数据联网匹配、歌词下载、APE 软解、gapless、统计看板构成完整本地播放闭环。",
    ])
    hr(doc)
    add_para(doc, "本说明书基于 PureTone 工程源码（v1.1.4）整理，供设计评审、开发对齐与后续迭代参考。",
            size=9.5, color=GREY)

    os.makedirs(os.path.dirname(DOC_PATH), exist_ok=True)
    doc.save(DOC_PATH)
    print("已生成:", os.path.abspath(DOC_PATH))

if __name__ == "__main__":
    build()
