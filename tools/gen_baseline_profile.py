#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成手写 Baseline Profile（HRF 格式）。

为什么手写：生成 Baseline Profile 的标准做法是 Macrobenchmark 真机/Gradle Managed
Device 实测，本机无可用模拟器（无 hypervisor），故采用折中方案——从 APK 的 dex
导出**真实类名清单**，据此写出覆盖「冷启动 + 首屏 Composition」路径的类级规则。

用法：
  1) 先构建 debug APK：./gradlew assembleDebug
  2) 解压 classes*.dex 并用 dexdump 导出类名：
       for d in classes*.dex; do dexdump -l plain "$d"; done | grep -oE "'L[^']+;'" > classes_raw.txt
  3) python tools/gen_baseline_profile.py classes_raw.txt app/src/main/baselineProfiles/baseline-prof.txt

脚本保证：只输出**确实存在于 dex 中**的类，避免 profgen 报 "Cannot find class"。
"""
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# 框架侧候选：冷启动/首屏真实会执行的关键类。
# 只写类级规则（不写方法签名——签名极易随依赖升级失效）。
# 不存在的会被自动剔除并在 stderr 报告，便于维护时核对。
# ---------------------------------------------------------------------------
CANDIDATES = [
    # --- Kotlin / 协程 ---
    "kotlin/jvm/internal/Intrinsics",
    "kotlin/collections/CollectionsKt",
    "kotlin/text/StringsKt",
    "kotlin/ranges/RangesKt",
    "kotlinx/coroutines/Dispatchers",
    "kotlinx/coroutines/BuildersKt",
    "kotlinx/coroutines/CoroutineScopeKt",
    "kotlinx/coroutines/flow/FlowKt",
    "kotlinx/coroutines/flow/StateFlowImpl",
    "kotlinx/coroutines/flow/SharedFlowImpl",
    "kotlinx/coroutines/flow/internal/ChannelFlow",
    "kotlinx/coroutines/channels/BufferedChannel",
    "kotlinx/coroutines/scheduling/DefaultScheduler",
    "kotlinx/coroutines/internal/ThreadContextKt",

    # --- AndroidX 基础 / 生命周期 ---
    "androidx/core/app/CoreComponentFactory",
    "androidx/core/os/TraceKt",
    "androidx/activity/ComponentActivity",
    "androidx/activity/compose/ComponentActivityKt",
    "androidx/lifecycle/ViewModel",
    "androidx/lifecycle/ViewModelKt",
    "androidx/lifecycle/viewmodel/compose/ViewModelKt",
    "androidx/lifecycle/runtime/compose/FlowExtKt",
    "androidx/lifecycle/DefaultLifecycleObserver",
    "androidx/savedstate/SavedStateRegistry",

    # --- Compose 运行时（冷启动首屏的骨架） ---
    "androidx/compose/runtime/Composer",
    "androidx/compose/runtime/ComposerKt",
    "androidx/compose/runtime/Recomposer",
    "androidx/compose/runtime/SlotTable",
    "androidx/compose/runtime/SlotReader",
    "androidx/compose/runtime/SlotWriter",
    "androidx/compose/runtime/CompositionKt",
    "androidx/compose/runtime/ComposablesKt",
    "androidx/compose/runtime/Updater",
    "androidx/compose/runtime/changelist/ChangeList",
    "androidx/compose/runtime/snapshots/Snapshot",
    "androidx/compose/runtime/snapshots/SnapshotKt",
    "androidx/compose/runtime/snapshots/SnapshotStateKt",
    "androidx/compose/runtime/collection/MutableVector",
    "androidx/compose/runtime/internal/ComposableLambdaKt",
    "androidx/compose/runtime/internal/StabilityInferred",

    # --- Compose UI / Foundation（列表与网格是首屏重头） ---
    "androidx/compose/ui/platform/AndroidComposeView",
    "androidx/compose/ui/platform/ComposeView",
    "androidx/compose/ui/platform/AndroidCompositionLocals_androidKt",
    "androidx/compose/ui/node/LayoutNode",
    "androidx/compose/ui/node/NodeChain",
    "androidx/compose/ui/layout/LayoutKt",
    "androidx/compose/ui/draw/DrawModifierKt",
    "androidx/compose/ui/graphics/ColorKt",
    "androidx/compose/ui/graphics/vector/VectorKt",
    "androidx/compose/foundation/lazy/LazyListKt",
    "androidx/compose/foundation/lazy/grid/LazyGridKt",
    "androidx/compose/foundation/lazy/layout/LazyLayoutKt",
    "androidx/compose/foundation/BackgroundKt",
    "androidx/compose/foundation/ClickableKt",
    "androidx/compose/foundation/ScrollKt",
    "androidx/compose/foundation/text/TextDelegate",

    # --- Material3 ---
    "androidx/compose/material3/TextKt",
    "androidx/compose/material3/SurfaceKt",
    "androidx/compose/material3/MaterialTheme",
    "androidx/compose/material3/ColorSchemeKt",
    "androidx/compose/material3/ScaffoldKt",
    "androidx/compose/material3/TopAppBarKt",
    "androidx/compose/material3/NavigationBarKt",
    "androidx/compose/material3/CardKt",
    "androidx/compose/material3/ButtonKt",
    "androidx/compose/material3/TabKt",
    "androidx/compose/material3/tokens/ColorSchemeKeyTokens",
    "androidx/compose/material/ripple/Ripple",

    # --- Navigation ---
    "androidx/navigation/compose/NavHostKt",
    "androidx/navigation/NavController",
    "androidx/navigation/compose/ComposeNavigator",

    # --- Room / SQLite（冷启动开库 + 首屏 Flow） ---
    "androidx/room/Room",
    "androidx/room/RoomDatabase",
    "androidx/room/RoomDatabaseKt",
    "androidx/room/RoomOpenHelper",
    "androidx/room/EntityInsertionAdapter",
    "androidx/room/EntityDeletionOrUpdateAdapter",
    "androidx/room/InvalidationTracker",
    "androidx/room/RoomSQLiteQuery",
    "androidx/room/SharedSQLiteStatement",
    "androidx/room/util/DBUtil",
    "androidx/room/util/TableInfo",
    "androidx/sqlite/db/framework/FrameworkSQLiteOpenHelper",
    "androidx/sqlite/db/framework/FrameworkSQLiteOpenHelperFactory",
    "androidx/sqlite/db/framework/FrameworkSQLiteDatabase",

    # --- DataStore（冷启动读设置） ---
    "androidx/datastore/core/DataStore",
    "androidx/datastore/core/SingleProcessDataStore",
    "androidx/datastore/preferences/core/Preferences",
    "androidx/datastore/preferences/core/PreferencesKt",
    "androidx/datastore/preferences/PreferencesDataStoreFileKt",

    # --- Media3 / ExoPlayer（播放链路） ---
    "androidx/media3/common/MediaItem",
    "androidx/media3/common/Player",
    "androidx/media3/exoplayer/ExoPlayerImpl",
    "androidx/media3/exoplayer/ExoPlayer$Builder",
    "androidx/media3/exoplayer/DefaultRenderersFactory",
    "androidx/media3/exoplayer/source/DefaultMediaSourceFactory",
    "androidx/media3/exoplayer/trackselection/DefaultTrackSelector",
    "androidx/media3/extractor/DefaultExtractorsFactory",
    "androidx/media3/session/MediaSession",
    "androidx/media3/session/MediaSessionService",

    # --- Coil（封面加载，首屏网格墙） ---
    "coil/ImageLoader",
    "coil/ImageLoader$Builder",
    "coil/compose/AsyncImageKt",
    "coil/request/ImageRequest",
    "coil/request/ImageRequest$Builder",
    "coil/request/ImageResult",
    "coil/memory/MemoryCache",
    "coil/disk/DiskCache",
    "coil/decode/DecodeUtils",
    "coil/util/UtilsKt",
    "coil/fetch/ContentUriFetcher",
    "coil/fetch/FileUriFetcher",

    # --- Hilt / Dagger（注入是冷启动必经路径） ---
    "dagger/hilt/internal/Preconditions",
    "dagger/internal/DoubleCheck",
    "dagger/internal/Factory",
    "dagger/internal/Provider",
    "dagger/hilt/android/EntryPointAccessors",
    "dagger/hilt/android/internal/managers/ActivityComponentManager",
    "dagger/hilt/android/qualifiers/ApplicationContext",
]

HEADER = """# 手写 Baseline Profile 规则（HRF 格式）——PureTone v1.5.0
#
# 为什么是手写：生成 Baseline Profile 的标准做法是 Macrobenchmark 真机 / Gradle
# Managed Device 实测，本机无可用模拟器（无 hypervisor），故采用折中方案：
# 用 ART 可读的 HRF 规则覆盖「冷启动 + 首屏 Composition」路径。AGP 会把本文件
# 编译进 APK 的 assets/dexopt/baseline.prof，安装时随包下发，dex2oat 直接预编译
# 这些类/方法，减少 JIT 解释执行与后台编译开销。
#
# 生成方式（脚本化，避免手写出错）：tools/gen_baseline_profile.py
#   1) 先 dexdump 导出 debug APK 全部 dex 的类名清单；
#   2) 本文件的每条规则都在该清单中**确实存在**（脚本自动求交并剔除不存在项）。
#
# 维护约定：
#   1. 只写「类级规则」（L全限定名;），不写方法签名——方法签名极易随依赖升级失效；
#   2. 新增类必须确认存在（重跑脚本即可，勿手抄）；
#   3. TODO：具备真机/模拟器条件后改用 Macrobenchmark 实测生成，本文件作兜底；
#   4. 启用 R8 后（release）AGP 会按 mapping 自动重写这些规则为混淆后名字，
#      因此这里写的是**源码名**，不要手抄 mapping.txt。
#
# ---------------------------------------------------------------------------
# 一、应用自身（冷启动即执行的代码，含 Hilt/Room 生成的类）
# ---------------------------------------------------------------------------
"""


def main():
    raw_path, out_path = Path(sys.argv[1]), Path(sys.argv[2])
    names = set()
    for line in raw_path.read_text(encoding="utf-8", errors="replace").splitlines():
        # 兼容两种导出格式：`Lcom/foo/Bar;` 与 `Lcom/foo/Bar`（后者是 tr -d "';"
        # 把结尾分号一并删掉的结果）
        line = line.strip().strip("'")
        if line.startswith("L"):
            names.add(line[1:].rstrip(";"))

    # 应用自身：排除 $ 命名的内部/lambda 类（数量大且随实现细节剧烈变动）
    app = sorted(n for n in names if n.startswith("com/puretone/player/") and "$" not in n)

    hit, miss = [], []
    for c in CANDIDATES:
        (hit if c in names else miss).append(c)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [HEADER]
    lines += [f"L{c};" for c in app]
    lines.append("")
    lines.append("# ---------------------------------------------------------------------------")
    lines.append("# 二、框架侧：冷启动与首屏必经的关键类（不存在于 dex 的候选已被脚本剔除）")
    lines.append("# ---------------------------------------------------------------------------")
    lines += [f"L{c};" for c in hit]
    out_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"应用类规则: {len(app)} 条")
    print(f"框架类规则: {len(hit)} 条（候选 {len(CANDIDATES)}，未命中 {len(miss)}）")
    print(f"写入: {out_path}")
    if miss:
        print("\n未命中（dex 中不存在，已剔除）:")
        for m in miss:
            print("  -", m)


if __name__ == "__main__":
    main()
