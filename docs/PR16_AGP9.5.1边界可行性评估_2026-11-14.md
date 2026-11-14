# PR #16（AGP 8.6.1 → 9.5.1）边界可行性评估

- 评估日期：2026-11-14
- 评估对象：Dependabot 提出的 PR #16（仅改动 `gradle/libs.versions.toml` 中 `agp = "8.6.1"` → `"9.5.1"`）
- 评估红线：**Kotlin / AGP / BOM 不动**（用户明确授权方向「评估 PR #16（AGP）」，先评估、不盲合）
- 评估方法：隔离本地探针（临时分支）+ 文档核验，未 push、未动 main

## 一、结论（先讲结论）

**在当前「Kotlin / AGP / BOM 不动」红线下，PR #16 不可合入。**

Dependabot 的 PR #16 仅翻 `agp` 一行，但 AGP 9.5.1 的硬性要求与项目现状及红线**直接冲突**，且冲突在**实际构建中已被探针实锤**——不是推测。即便放开 AGP 红线走「协调升级线」，也**必然触碰 Kotlin 红线**（AGP 9.5.1 强制 Kotlin ≥ 2.2.10），无法绕开。

建议：**暂不合入 PR #16**；若确需升级，须单独立项「协调升级线」并显式授权放开 Kotlin 红线，覆盖 Gradle / Build Tools / Kotlin / KSP / Hilt / Compose compiler 的连锁升级与全门禁复跑。

## 二、实测证据（隔离探针，已清理）

探针流程：
1. 从 `main`（`f99b5e6`）切出临时分支 `probe/agp-9.5.1`（零 commit，仅工作树改动）。
2. `gradle/libs.versions.toml`：`agp = "8.6.1"` → `"9.5.1"`（复刻 PR #16 唯一改动）。
3. 跑 `./gradlew help` 捕获第一道墙。
4. 为越过第一道墙，探针内临时把 `gradle-wrapper.properties` 的 `distributionUrl` 由 `gradle-8.14.6-bin.zip` 升到 `gradle-9.6.0-bin.zip`，再跑 `./gradlew help` 捕获第二道墙。
5. `git checkout -f main` 丢弃改动 + `git branch -D probe/agp-9.5.1` 删分支。main 保持 `f99b5e6` 干净，无 push。

### 墙 1 —— Gradle 版本（AGP 自带版本检查，第一阻断）

```
FAILURE: Build failed with an exception.
What went wrong:
An exception occurred applying plugin request [id: 'com.android.application', version: '9.5.1']
> Failed to apply plugin 'com.android.internal.version-check'.
   > Minimum supported Gradle version is 9.6.0. Current version is 8.14.6.
     Try updating the 'distributionUrl' property in .../gradle-wrapper.properties to 'gradle-9.6.0-bin.zip'.
```

**含义**：AGP 9.5.1 强制 Gradle ≥ 9.6.0。当前 wrapper 为 8.14.6，连配置阶段都进不去。

### 墙 2 —— Kotlin 插件冲突（红线命中点，第二阻断）

升至 Gradle 9.6.0 越过墙 1 后，AGP 9.5.1 自身成功 apply，但项目显式 apply 的 `org.jetbrains.kotlin.android`（引脚 2.0.21）当场失败：

```
FAILURE: Build failed with an exception.
What went wrong:
An exception occurred applying plugin request [id: 'org.jetbrains.kotlin.android', version: '2.0.21']
> Failed to apply plugin 'org.jetbrains.kotlin.android'.
   > Cannot add extension with name 'kotlin', as there is an extension already registered with that name.
```

**含义（实锤）**：AGP 9.x 内置捆绑 Kotlin（已注册 `kotlin` extension），再 apply 独立的 `org.jetbrains.kotlin.android` 即冲突。这正是「Kotlin 不动」红线被击穿的**现场证据**——
- 要么删掉显式的 Kotlin Gradle Plugin，让 AGP 用其捆绑的 Kotlin **2.2.10**；
- 要么把独立 KGP 对齐到 **2.2.10+**；

无论哪条路，项目引脚的 Kotlin 2.0.21 都必须移动。红线被破。

> 注：墙 2 已足以定论，Build Tools 36.0.0 等后续检查（见下表）在配置阶段更靠后，未触发即已阻断，但同样构成缺口。

## 三、要求 vs 现状 对照表

| 维度 | AGP 9.5.1 要求 | 项目现状 | 缺口 | 触碰红线 |
|---|---|---|---|---|
| Gradle | ≥ 9.6.0 | 8.14.6（wrapper） | 须升到 9.6.0 | 否（Gradle 非红线，但属连带升级） |
| JDK | 17 | 17（JDK17） | 满足 | — |
| Build Tools | 36.0.0 | 35.0.0 | 须升 | 否 |
| Max API level | 37 | compileSdk / targetSdk = 35 | 满足（在天花板下） | — |
| Kotlin | 内置 2.2.10，且禁止再 apply 独立 KGP | 引脚 2.0.21 + apply `kotlin.android` / `kotlin.compose` | 强制 ≥ 2.2.10 | **是（Kotlin 不动红线被击穿）** |
| KSP | 随 Kotlin；kapt 与内置 Kotlin 不兼容 | `ksp = "2.0.21-1.0.28"` 绑定 Kotlin 2.0.21 | 须随 Kotlin 升 | 间接触碰 |
| Hilt | KSP 耦合 | `hilt = "2.56.2"`（KSP 绑定 Kotlin 2.0.21） | 须随 KSP 升 | 间接触碰 |
| Compose BOM | 2025.02.00（Compose compiler 随 Kotlin 2.0.x） | `composeBom = "2025.02.00"` | 若 Kotlin 升到 2.2.x，Compose compiler 需重匹配 | 间接触碰（BOM 红线） |

## 四、风险定级

- **P0 阻断**：墙 1（Gradle）+ 墙 2（Kotlin）双阻断。PR #16 原样（仅改 `agp` 行）**无法配置、无法构建**，合入即导致 CI 26 步全红。
- **P1 连锁升级**：AGP↑ 强制 Gradle↑、Build Tools↑、Kotlin↑；Kotlin↑ 强制 KSP↑、Hilt↑、Compose compiler 重匹配。一个 dependabot 单行 PR 实际牵动 **6+ 版本坐标**，远超「仅改 agp 行」的表象。
- **P2 行为回归**：AGP 9 的 DSL 变更（`kotlinOptions {}` 废弃 → `kotlin { compilerOptions {} }`）、baseline profile 流程、lint / detekt 门禁在 9.x 下可能漂移；`warningsAsErrors` 下任何新告警即失败，需完整门禁（103 类 / 1015 例 JVM 单测 + lint/detekt baseline 0 + 三 ABI 出包硬校验）复跑。

## 五、建议

1. **维持现状、暂不合入 PR #16**：在「Kotlin / AGP / BOM 不动」红线下，PR #16 技术上不可构建，合入即破坏 CI。
2. **若确需升级 AGP 9.5.1**，须作为**独立「协调升级线」单独立项**，并**显式授权放开 Kotlin 红线**（AGP 9.5.1 强制 Kotlin ≥ 2.2.10，无法绕开）。授权范围应覆盖：
   - Gradle 8.14.6 → 9.6.0
   - Build Tools 35.0.0 → 36.0.0
   - Kotlin 2.0.21 → ≥ 2.2.10（并随之处理 `kotlin.android` / `kotlin.compose` apply 方式）
   - KSP 随 Kotlin 升
   - Hilt 随 KSP 升
   - Compose compiler 重匹配
   - 完整门禁（JVM 单测 103 类 / 1015 例 + lint / detekt baseline 0 + 三 ABI 出包硬校验：apksigner / LGPL / 16KB / DEX 串）复跑
3. **Dependabot 单行 PR 不适用大版本升级**：AGP 9.x 属破坏性变更，不应以「仅改 agp 行」的形式静默合入。建议对 AGP 配置 `ignore` 或改为手动协调升级流程，避免再次产生「看似安全、实则破坏构建」的 PR。
4. **回滚面提示**：本评估**未改动任何跟踪文件**（探针已清），main 仍 `f99b5e6`；后续若走协调升级线，回滚面将扩至 `gradle/libs.versions.toml` + `gradle/wrapper/gradle-wrapper.properties` + `app/build.gradle.kts`（Kotlin DSL 调整）。

## 六、附：本次探针事实快照

- 探针分支：`probe/agp-9.5.1`（已从 main `f99b5e6` 切出，零 commit）
- 改动文件（仅工作树，已丢弃）：`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`
- 清理后 main：`agp = "8.6.1"`、`distributionUrl=...gradle-8.14.6-bin.zip`、HEAD `f99b5e6`
- 远端：未 push；未触碰 main 内容
- 文档来源核验：Android 官方 `tools/revisions/gradle-plugin` 等多源确认 AGP 9.5.1 最低 Gradle 9.6.0、Build Tools 36.0.0、Max API 37、内置 Kotlin 2.2.10 且禁止再 apply 独立 KGP（与探针实锤一致）
