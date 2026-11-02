# 智能歌单图标（矢量，非描摹）

> 日期：2026-11-02｜用途：替代此前评估不通过的三个描摹 SVG（心形/循环箭头/沙漏+十字）
> 交付：`res/drawable/ic_smart_playlist.xml` + `res/drawable/ic_smart_playlist_foreground.xml` + `tools/ic_smart_playlist.svg`

## 1. 设计语义（双含义，明确表达「智能歌单」）

| 视觉元素 | 含义 |
|---|---|
| 左侧**竖播放条** + 三条**曲目横线**（底部较短） | 「播放列表 / 歌单」——单色下清晰可读，不与背景混淆 |
| 右上**大四角星火花** + 右下**小四角星火花** | 「智能 / 自动生成」——AI 自动聚合歌单的语义信号 |

摒弃了之前描摹图标的缺陷：无实心背景、非描摹碎片、语义直接对应「智能 + 歌单」。

## 2. 交付文件清单

| 文件 | 类型 | 规范 | 说明 |
|---|---|---|---|
| `app/src/main/res/drawable/ic_smart_playlist.xml` | VectorDrawable | Material 功能图标 | 24×24 视图，单色占位黑（`#FF000000`，可被 `tint` 覆盖），可用于导航/列表/分区标题 |
| `app/src/main/res/drawable/ic_smart_playlist_foreground.xml` | VectorDrawable | 自适应图标前景层 | 108×108 视图，品牌紫 `#FF5E6AD2`，内容落在 66dp 安全区圆内 |
| `tools/ic_smart_playlist.svg` | 标准 SVG | 跨平台源 | 24×24，`fill="currentColor"`，便于预览/转译 |

生成脚本：`tools/gen_smart_playlist_icon.py`（纯几何构造 + 本机栅格化预览，无外部依赖）。

## 3. 规范符合度核对

- **图像格式**：矢量（SVG / VectorDrawable），纯几何路径，**非位图描摹**。✅
- **分辨率与比例**：正方形。功能图标 24×24（Material 标准网格）；自适应前景层 108×108（Android 自适应图标标准画布）。✅
- **视觉风格 / 主题契合 / 辨识度**：
  - 单色面性，复用 PureTone 品牌紫体系；`SmartPlaylistBrandIcon` 用 `tint = MaterialTheme.colorScheme.primary`，深浅三套主题自动适配，与既有 `SmartPlaylistGlyph`（沙漏/爱心/环形箭头）风格一致。✅
  - 同时传达「歌单」与「智能」两层含义，辨识度高、缩小后剪影清晰（已渲染 24dp 与 108dp 预览核对）。✅
- **自适应图标规范**：前景层内容缩放后居中，实测像素包围盒 **(58,68)–(179,175)**（240px 预览），安全区白圆为 中心 (120,120)、半径 73.3（即 x46.7–193.3, y46.7–193.3）；图形完全在圆内、四周留白充分（左 11px/右 14px/上 21px/下 18px），居中良好。✅

## 4. 已落地 / 编译验证

- `SmartPlaylistIcons.kt` 新增 `SmartPlaylistBrandIcon(modifier)` Composable：`Icon(painterResource(R.drawable.ic_smart_playlist), tint=primary, contentDescription=智能歌单)`。
- `strings.xml` 新增 `pt_smart_playlist_000 = 智能歌单`。
- `assembleDebug` **BUILD SUCCESSFUL**（资源 aapt2 编译、R 引用、Kotlin 编译均通过）。

## 5. 接入示例（如需把图标放到浏览页「智能歌单」分区标题）

`SmartPlaylistsRow` 调用处（BrowseScreen.kt ~404）可包一个标题行：

```kotlin
// 在 SmartPlaylistsRow(...) 上方
Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    SmartPlaylistBrandIcon(modifier = Modifier.size(22.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.pt_smart_playlist_000), style = MaterialTheme.typography.titleMedium)
}
SmartPlaylistsRow(recentlyAdded, topSongs, recentlyPlayed, onPlay)
```

> 注：当前三个子卡（最近添加/我的最爱/最近播放）仍使用既有 `SmartPlaylistGlyph`，新图标代表「智能歌单」整体特性，二者不冲突。UI 实际摆放位置请按需确认。

## 6. 与之前三个 SVG 的对比结论（为何替换）

此前评估的 1/2/3.svg 均为 VTracer 描摹产物：实心背景、缩小发糊、语义分别为收藏/循环/医疗计时，均不适合。**本图标为纯几何矢量，无上述缺陷。**
