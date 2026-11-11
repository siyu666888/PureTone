# PureTone 代码审查报告（第三轮）

- **审查基线**：v1.9.5（versionCode 57）工作树
- **本轮重点**：R2（`CODE_REVIEW_2026-10-24_R2.md`）之后**新增**的 DLNA 投屏模块（`playback/cast/` 约 30 个文件）、AMLL 歌词渠道、网络/安全配置、构建脚本
- **方法**：全文件通读关键路径（HTTP 投送服务器、SSDP/SOAP、DIDL 构造、URL 拼接、XML 解析、JSON 解析、Manifest/NSC/proguard）；PlaybackManager 等 R2 已覆盖且大部分已修的模块不重复展开
- **结论概览**：P1 ×3、P2 ×5、P3 ×6；另附 R2 遗留未修项清单与总体评价

> 说明：本轮为审查报告，未修改任何代码。所有行号均指当前工作树。

> ⚠️ **【事实订正 · 2026-11-10】** 下文 §「R2 报告遗留未修项」的清单及「本轮确认仍在工作树中」判断**有误**：R2 标注未修的 33 条（P2×8 + P3×25）早在 2026-10-29 的四个 commit 中全部修复，`dist/v1.7.19_遗留审查项对账表_2026-11-01.md` 已逐条实证「遗留债务为 0」。本轮撰写时未读代码、未查该对账表，照抄了 R2 的「2026-10-25 时点快照」。详见订正后的 §R2 段与 `dist/v1.9.6_审查报告R2清单订正_复核说明_2026-11-10.md`。**本轮自行发现的 P1×3 / P2×5 / P3×6 结论不受影响**（均为 11-09 对新增 DLNA / AMLL 模块的独立审查）。

---

## 总体评价

项目工程素质显著高于一般个人作品：分层清晰（data/domain/ui/playback 解耦）、KDoc 详尽（每个非显然决策都写了"为什么这样写"而非"这是什么"）、单测覆盖纯逻辑函数、detekt/lint 双门禁 + baseline 冻结存量、R2 发现的 P0/P1 基本闭环。Cast 模块的取证日志设计（每一步 SOAP 的状态码与 fault 都落盘）尤其出色。

以下问题按严重程度排列。

---

## P1 — 应尽快修复

### P1-1 CastHttpServer 对非法 Range 头错误返回 206

**位置**：`playback/cast/CastHttpServer.kt:207-210, 229`

**问题**：`serve()` 在 `parseRange` 返回 `RangeSpec.Full` 时（即 Range 头缺失、不以 `bytes=` 开头、或解析失败），仍把**原始 rangeHeader** 传给 `sendFile`；而 `sendFile` 里 `partial = rangeHeader != null`，于是：

- 请求带了一个无法识别的 `Range: garbage` → `parseRange` 返回 `Full` → `sendFile` 认为 `partial=true` → 返回 **206 Partial Content + `Content-Range: bytes 0-{size-1}/{size}`**，但 body 是整文件。

协议层不一致：客户端收到 206 会按分段理解，而 Content-Range 声明的就是整文件，部分严格渲染器可能误判。

**修复**：`serve()` 的 `Full` 分支传 `null` 作为 rangeHeader：
```kotlin
RangeSpec.Full -> {
    markFetchIfActive(token)
    sendFile(socket, method, entry, 0L, (size - 1).coerceAtLeast(0L), null)
}
```

---

### P1-2 AmllLyricsProvider 未过滤 JSON `null` → 字面量 `"null"`（R2 P1-1 的同类问题在新渠道复现）

**位置**：`data/metadata/AmllLyricsProvider.kt:133, 143`

**问题**：R2 P1-1 已修过 LRCLIB 的 `JSONObject.optString` 对显式 JSON `null` 返回 `"null"` 字面量的坑（见 `LrcLibLyricsProvider.kt:123-124` 的 `optLyrics` 扩展）。但本轮新增的 AMLL 渠道没有套用同一防护：

```kotlin
// :133
val musicName = names?.optString(0) ?: item.optString("musicName")
// :143
return root.optJSONObject("data")?.optString("lyrics")?.takeIf { it.isNotBlank() }
```

- `:133`：若 `musicName` 字段为 JSON null，`optString("musicName")` 返回 `"null"`，存入 candidates map；虽然后续 `LyricsTitleMatch.isAcceptable("null", title)` 会过滤，但属于脏数据混入匹配管道。
- `:143`：若 `data.lyrics` 为 JSON null，`optString("lyrics")` 返回 `"null"`，`takeIf { isNotBlank() }` 通过（"null" 非空），返回 `"null"` 字符串。当前靠 `TtmlToLrc.convert("null")` 返回 null 兜底，未造成实际错误，但这是同一个坑没修干净——一旦 TTML 转换器对非 XML 输入行为变化（例如宽松匹配到某条 `<p>`），就会把 "null" 写进 .lrc。

**修复**：与 LrcLib 对齐，抽一个公共扩展：
```kotlin
fun JSONObject.optNonNullString(name: String): String? =
    optString(name).takeIf { it.isNotBlank() && it != "null" }
```
（或用 `obj.isNull("lyrics")` 先判空）。建议把这个扩展放到 `LyricsProvider.kt` 伴生对象里，三个 Provider 共用。

---

### P1-3 SoapClient 响应体无大小上限，恶意 LAN 设备可致 OOM

**位置**：`playback/cast/SoapClient.kt:79-85`

**问题**：
```kotlin
BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
```
`readText()` 一次性把整个响应读入内存。SOAP/SCPD 响应正常只有几百字节，但局域网内一台被攻陷或恶意的 DLNA 设备可以在收到 GET/POST 后无限发送数据（或返回巨大的设备描述 XML），导致 App OOM 崩溃。`BODY_LIMIT=300` 只在 `snippet()` 截断日志展示用，不限制实际读取量。

**修复**：加一个上限读取（如 256KB，足够容纳任何合法 SCPD/描述）：
```kotlin
private const val MAX_BODY_BYTES = 256 * 1024
// readBody 中：
val buf = ByteArray(MAX_BODY_BYTES)
var n = 0
while (n < buf.size) {
    val r = stream.read(buf, n, buf.size - n)
    if (r < 0) break
    n += r
}
String(buf, 0, n, Charsets.UTF_8)
```

---

## P2 — 建议修复

### P2-1 CastHttpServer 监听绑 0.0.0.0，且 entries map 只增不减

**位置**：`playback/cast/CastHttpServer.kt:97, 121, 52`

两个相关问题：

1. **绑定 `ServerSocket(0)` 默认绑 `0.0.0.0`**：注释说"对外公布的地址才是关键"，但实际上 socket 接受来自**所有网络接口**的连接。手机开热点时，连接热点的其他设备；或装了 VPN 时 VPN 对端，都能连到这个随机端口。虽然 token 鉴权保护了文件不被读，但更稳妥的做法是直接绑到 Wi-Fi 网卡 IP（即 `urlFor` 传入的 `host`），从网络层拒绝其他接口。
2. **`entries[token] = Entry(...)` 旧 token 永不清理**：连续投屏播放时每首歌登记一个新 token，旧 token 一直留在 map 里直到 `stop()`。单条 entry 很小，但长时间投屏（如车载场景连播几小时）会累积几十上百条，且旧 token 永久有效——任何嗅探到旧 URL 的设备都能一直拉旧文件。

**修复**：
- `start()` 时绑定到指定 Wi-Fi IP（由调用方传入），而非 `0.0.0.0`；
- `urlFor()` 登记新 token 时顺带清理超过 30 分钟未访问的旧 entry。

> ✅ **【状态订正 · 2026-11-11】** 两项**均已落地**，改动仍在工作树未提交：
> - 第 2 项（entries 清理）：2026-11-10 由 `CastEntryRegistry` 实现 30 分钟沉寂淘汰；`224721` 真机首次触发 `http-token-prune removed=2 remaining=1`，零 404，见 `dist/v1.9.6_VERIFY-0833_224721判读_P2-1TTL真机验收与R7第三次实证_2026-11-11.md`。
> - 第 1 项（绑 Wi-Fi IP）：2026-11-11 完成。新增纯策略 `CastServerBindPolicy`，`CastHttpServer.start(bindHost)` 优先绑定该网卡、**任何判不准或绑定失败都回退 `0.0.0.0` 旧行为**，诊断行追加 `bind=… reason=…`；新增 15 例单测，全量门禁 **897 用例 / 96 类 / 0 失败**、lint 0 / detekt 0 / 行数 194 文件全过。见 `dist/v1.9.6_P2-1_CastHttpServer绑定WiFi_IP_复核说明_2026-11-11.md`。
> **仍未闭环**：绑定分支是否走通、热点/VPN 拒绝效果均需真机 + 新 VERIFY 包（当前 `VERIFY-0833` 不含本轮改动）；同一会话内 Wi-Fi 换 IP 只落 `http-server host_changed bound=… urlHost=…` 诊断、不重建 server——重建会换端口并让 `lastOwnCast` 存的旧 URL 与电视旧连接一并失效。

---

### P2-2 NSC 全局放行明文，应改为"公网显式拒绝 + LAN 默认允许"

**位置**：`app/src/main/res/xml/network_security_config.xml:38-44`

**问题**：注释已充分论证 DLNA 必须明文，但当前 `base-config cleartextTrafficPermitted="true"` 是**全局**放行——意味着代码里任何公网 URL 误写成 `http://` 都不会被 NSC 阻断。公网端点目前有三个：`itunes.apple.com`、`lrclib.net`、`api.amll.dev`，全部 HTTPS，但靠人工审计而非配置强制。

**修复**：对公网域名显式拒绝明文，把"全局允许"收窄为"默认允许但公网白名单拒绝"：
```xml
<base-config cleartextTrafficPermitted="true">
    <trust-anchors><certificates src="system" /></trust-anchors>
</base-config>
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">itunes.apple.com</domain>
    <domain includeSubdomains="true">lrclib.net</domain>
    <domain includeSubdomains="true">api.amll.dev</domain>
    <domain includeSubdomains="true">amll.dev</domain>
</domain-config>
```
这样未来误写 `http://lrclib.net` 会被系统直接阻断，而非静默发出去。

---

### P2-3 SSDP LOCATION 未校验应答源，存在 SSRF 面

**位置**：`playback/cast/SsdpDiscovery.kt:242`、`SoapClient.kt:56`、`CastProbe.kt:76`

**问题**：SSDP 应答的 `LOCATION` 头来自局域网任何设备，App 直接把它当作 `SoapClient.get(url)` 的目标 URL。恶意设备可以在 SSDP 应答里返回 `LOCATION: http://127.0.0.1:8080/` 或 `http://192.168.1.1/admin`，诱导手机向非预期目标发请求（SSRF）。`ControlUrls.join` 还会接受 controlURL 里的绝对 URL 并直接使用（`ControlUrls.kt:76`）。

LAN 场景下攻击者需先在局域网伪装设备，且 App 是用户主动点选投屏才触发，风险有限。但按协议，M-SEARCH 的应答 IP 应当与 LOCATION 的 host 一致（UPnP 设备在自己的 IP 上提供描述），可以 cheaply 校验。

**修复**：`onDatagram` 解析出 LOCATION 后，校验 `InetAddress.getByName(URI(location).host) == packet.address`；不一致则丢弃。`ControlUrls.join` 里绝对 URL 也校验 host 与 locationUrl 的 host 同网段。

> ✅ **【状态订正 · 2026-11-11】** 上述**第一处**（`SsdpDiscovery.onDatagram`）已修：新增纯策略 `SsdpSourcePolicy`，在收包循环里校验 LOCATION host 与应答源，`REJECT` 丢弃、`UNVERIFIED`（域名 LOCATION / 源地址未知 / IPv6 与 IPv4 混用）放行并落 `ownssdp-source` 诊断行。刻意**不采用**报告建议的 `InetAddress.getByName(host)`：它会触发 DNS，既可能阻塞收包循环，也可能被攻击者用域名解析结果绕过比较；改为纯字符串规范化比较。新增 14 例单测，全量门禁 **882 用例 / 95 类 / 0 失败**、lint 0 / detekt 0 / 行数 193 文件全过，改动**仍在工作树未提交**。详见 `dist/v1.9.6_P2-3_SSDP应答源校验_复核说明_2026-11-11.md`。
> ✅ **【状态订正 · 2026-11-11 第二轮】** `ControlUrls.join` 的绝对 URL 亦已补：新增纯策略 `ControlUrlHostPolicy`（三态口径与 `SsdpSourcePolicy` 一致），`join` 加**可选**诊断回调——生产侧传 `CastNetworkLog::log`，纯函数与 JVM 单测不传，避免 `android.util.Log` stub 让测试挂掉。同 host 零改动；跨设备 IP、纯数字主机、带 userInfo / 缺 host 的畸形 URL 一律拒绝并落 `control-url reject …`；域名 controlURL、IPv6/IPv4 混用、描述 URL 解析不出 host 则放行留痕 `control-url unverified …`。相对写法分支一字未改。既有 `ControlUrlsTest` / `CastProbeTest` 各 1 例「跨网段绝对地址原样返回」用例已同步订正为同 host，并补拒绝用例。详见 `dist/v1.9.6_P2-3_控制侧绝对URL目标校验_复核说明_2026-11-11.md`。
> ✅ **【状态订正 · 2026-11-11 第三轮】** `SoapClient` 全局目标限制亦已补：新增纯策略 `SoapTargetPolicy`，在唯一的网络出口 `request()` **打开连接之前**校验目标 URL。非 http(s) / 缺 host / 带 userInfo / 环回 / 纯数字与前导零主机 / 多播广播 / 非法端口 / 控制字符一律返回 `Result(-1, "blocked-target…")` 且不发包；合法单播 IPv4 零行为变化，域名与 IPv6 为 `UNVERIFIED` 放行留痕。诊断沿用「生产侧可选回调」：`CastProbe.inspect` / `DlnaTransport.endpoints` 两处描述 GET 注入 `CastNetworkLog::log`，纯函数与 JVM 单测不传，避免 `android.util.Log` stub。新增策略单测与 `SoapClientTest` 拒绝路径用例（证明拒绝发生在 `openConnection()` 之前、零网络访问）。详见 `dist/v1.9.6_P2-3_SoapClient目标校验_复核说明_2026-11-11.md`。
> ✅ **【状态订正 · 2026-11-11 第四轮】** 三处判定的**诊断覆盖**已收口：新建 `CastTargetDiagnostics`（默认 noop sink + 按诊断行全文去重、64 条上限），由 `DlnaCastManager.init()` 注入 `CastNetworkLog::log`，`SoapClient` 全部 15 个生产调用点统一接 `CastTargetDiagnostics::emit`。1Hz 轮询路径的 `UNVERIFIED` 只落一次、不会刷屏；JVM 单测仍零 Android 依赖。第三轮复核说明里「其余调用点不单独出 `soap-target …` 行」的边界至此消除。详见 `dist/v1.9.6_P2-3_目标校验诊断全链路覆盖_复核说明_2026-11-11.md`。
> ✅ **【状态订正 · 2026-11-11 第五轮】** 库通道兜底入口亦已收口：新增纯策略 `LibraryDeviceTargetPolicy`，在 `CastDeviceDiscovery.discover()` 把库返回的 `Device.id` 转成 `CastDevice` **之前**先做目标校验——合法单播 IPv4 LOCATION URL 放行，非 http(s) / 缺 host / 带 userInfo / 纯数字与前导零主机 / 环回 / 多播广播 / 非法端口 / 控制字符一律拒绝并落 `lib-device reject …`（`CastTargetDiagnostics::emit`，默认 noop 避开 JVM 单测的 Log stub）；非 URL 形状的 id（UDN / `uuid:…` / 裸 IP，库常见）走 `UNVERIFIED` 放行留痕，不误杀。被拒设备仍入 `lastDevices` 保 UI 不变，但 `select()` 前被过滤，真正的投送目标永不落到被拒 URL。**零网络访问、零连接行为改动**。详见 `dist/v1.9.6_P2-3_库通道设备入口校验_复核说明_2026-11-11.md`。
> **仍未闭环（仅剩）**：三处 `UNVERIFIED` 兼容窗口（域名 LOCATION / 域名 controlURL / 域名或 IPv6 的 SOAP 目标）是否要从「放行留痕」收紧为「拒绝」，需真机采集多品牌设备描述后定；本轮只让它们在日志里可见，判定口径未动。库通道兜底路径本身已覆盖。

---

### P2-4 CastHttpServer 日志计数不随换曲重置，新歌取流记录丢失

**位置**：`playback/cast/CastHttpServer.kt:60, 286-290`

**问题**：`loggedRequests` 上限 8，只在 `stop()` 时重置。连续投屏播放时，前 8 次取流（可能是前两首歌）记满后，后续所有歌的取流都不再落盘到诊断日志。虽然 `lastFetchMs` 不受影响（用于判定投屏是否建立），但诊断报告里看不到"第 5 首歌电视到底来没来拉流"。

**修复**：在 `urlFor()`（换新 token = 换新曲）时重置 `loggedRequests = 0`。

---

### P2-5 签名 keystore 密码弱且硬编码在 local.properties

**位置**：`local.properties:5,7`

**问题**：`storePassword=puretone2024` / `keyPassword=puretone2024`。虽然 `local.properties` 已 gitignore、`.jks` 也已忽略（`build.gradle.kts:36-45` 的注释和 `gitignore` 都对），但密码本身是弱口令（单词 + 年份）。自分发应用的签名 keystore 一旦随设备备份/误分享泄露，攻击者可伪造任意更新包。

**修复**：换一个 16 位以上随机密码的新 keystore（或至少把现有密码改为强随机串），密码存 `local.properties` 即可（不入库），但不要用 `puretone2024` 这类可猜测值。

---

## P3 — 代码规范 / 可维护性

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| P3-1 | `data/metadata/AmllLyricsProvider.kt:165-181` vs `LrcLibLyricsProvider.kt:146-183` | 第三份重复的 HTTP 样板（connectTimeout/readTimeout/UA/use/disconnect/非 2xx）。R2 可维护性建议第 1 条已提过抽公共 `HttpUtil`，AMLL 是新增拷贝 | 抽一个 `object HttpUtil { fun get(url, limit): String? }`，三个 Provider 共用 |
| P3-2 | `playback/cast/DidlBuilder.kt:30-33` | `mimeType` 直接拼入 `protocolInfo` 字符串，未校验是否含 `;`/`"`/空格 | 加一行校验 `require(mimeType.matches(Regex("[\\w.+-]+/[\\w.+-]+")))` 或在 KDoc 约束调用方 |
| P3-3 | `data/metadata/MultiChannelLyricsProvider.kt:60-73` | `lastHitChannel`/`lastAttemptedChannels` 是 @Volatile var，但"清空→逐个 add→赋值"非原子。当前 UI 串行调用安全，未来并发下载会竞态 | 用 `AtomicReference<Snapshot>` 一次性替换，或注释明确"仅主线程调用" |
| P3-4 | `playback/cast/CastProbe.kt:237-246` | 正则解析 `<service>` 块，假设设备描述不嵌套 service。DLNA 格式固定故风险低，但正则解析 XML 本质脆弱 | 可接受现状；若未来出问题再换 `XmlPullParser`（Android 内置，无额外依赖） |
| P3-5 | `data/metadata/TtmlToLrc.kt:109-116` | `plainText` 反转义实体只处理了 5 种命名实体，未处理数字字符引用 `&#NN;`/`&#xNN;`。AMLL 词库若投稿含此类实体（罕见），会原样保留 `&#39;` 到歌词文本 | 补一个 `&#(\d+);` 正则替换为对应字符 |
| P3-6 | `playback/cast/CastHttpServer.kt:81-83` | `Executors.newFixedThreadPool(3)` 线程池无优雅关闭。`stop()` 后 worker 线程是 daemon，进程退出时才结束，可接受；但 `running=false` 后已 accept 的连接会被强行 close | 可接受（daemon 线程），无需改 |

---

## R2 报告遗留未修项〔2026-11-10 订正：本节原判断不成立〕

> ✅ **订正结论：R2 标注「未修（后续迭代）」的 33 条（P2×8 + P3×25）全部已修，遗留债务为 0。** 本节原句「本轮确认仍在工作树中」是**误判**，勿再据此排期。

**为什么出错**：R2 报告末尾的「修复状态（2026-10-25，v1.5.0 / code 22 交付）」是**交付时点快照**；其后 2026-10-29 的四个 commit 把剩余债务清空，2026-11-01 的 `dist/v1.7.19_遗留审查项对账表_2026-11-01.md` 已逐条给出 `文件:行号` 判定与 commit 溯源。本报告 11-09 撰写时未读代码、未查该对账表，把快照当成了现状。

**三层证据（2026-11-10 复核，基线 `da6f631` / v1.9.6 code58）**

| 层 | 结论 |
|---|---|
| 既有对账表 | `dist/v1.7.19_遗留审查项对账表_2026-11-01.md`：**33/33 已修、0 未修**；另记 3 条「风险已变化但表述需更新」（P3-2 / P3-13 / P3-15），属描述过时而非欠账 |
| 修复 commit 链 | `33a8721`（P2-4 跨卷隔离 / Room v5→v6）、`2aef926`（P2-9/10/13/15/16）、`9571068`（P2-7/8）、`a3186e1`（P3 全 25 条，11 批次），均 2026-10-29；**四个都是当前 HEAD 的祖先，未被回退** |
| 当前代码抽查 | 12 个代表性修复符号全部在位：`volume_name`、`MIGRATION_5_6`、`onQueueStructureChanged`、`fingerprint`、`searchJob`、`coerceAtLeast(0.0)`、`renameTo`、`loadLyricsJob`、`rawPathUri`、`getFirstWindowIndex`、`key = { it.playlist.id }`、`@Synchronized` |

⚠️ **编号消歧（照单排期前必读）**：本报告与 R2 使用两套互不相关的编号。本报告的 **P2-4 = CastHttpServer 日志计数不随换曲重置**（`ac75cbf` 已修）、**P3-1 = 抽公共 `HttpUtil`**（2026-11-10 已修）；R2 的 **P2-4 = `songs.album_id` 跨卷撞号**、**P3-1 = `MeScreen` 嵌套滚动**。同名不同事，勿混。

<details>
<summary>原误判清单（2026-11-09 原文，保留作证据，勿再据此排期）</summary>

R2 报告末尾标注"未修（后续迭代）"的问题本轮确认仍在工作树中，按优先级列出：

- **P2-4**：`songs.album_id` 跨卷撞号（需加 `album_volume` 列 + v6 迁移）——多 SD 卡设备专辑详情串页。
- **P2-7**：`M3uCodec.parse` 丢弃时长为 0/负/小数的 EXTINF 标题，往返不自洽。
- **P2-8**：`PlaylistImportResolver` 不剥离 `file://`、不做 URL 解码，百分比编码条目静默跳过。
- **P2-9**：`LrcParser` 单遍解析，offset 标签出现在歌词行之后时时间轴错位。
- **P2-10**：`LyricsStore` 文件名无长度约束，长曲名导致保存静默失败。
- **P2-13**：队列增删操作不参与 `queueGeneration`，在途旧 setter 覆盖用户操作。
- **P2-15**：`ApeTranscoder` 缓存 key 仅用 songId，源文件被替换后命中旧 WAV。
- **P2-16**：`MetadataViewModel.search` 无请求取消，连点搜索旧结果可覆盖新结果。
- **P3 全部**：共 25 条，详见 R2 报告 P3 表格（Compose key 缺失、嵌套滚动、Write 非原子等）。

</details>

**R2「可维护性改进建议」5 条当前状态**（非缺陷项，独立于上述 33 条）：建议 1「抽公共 HTTP 客户端」已由本报告 P3-1 于 2026-11-10 完成（`HttpUtil.kt`）；建议 3「收敛 `ControlIconSizes`」仍未做〔**订正 · 2026-11-11**：建议 3 已落地，新增 `ui/components/ControlIconSizes.kt`，6 文件 39 处裸 `N.dp` 改为常量引用，数值序列与代码行多重集对照 HEAD 严格相等；建议 1 的响应体 `limit` 亦已补齐，见 `dist/v1.9.6_静态债务收口_HttpUtil上限与ControlIconSizes_复核说明_2026-11-11.md`〕；建议 2/4/5 维持对账表结论。

---

## 已专项核查、确认无问题的重点项

**安全**
- CastHttpServer 按 token 发文件不按路径，token 是 32 位十六进制随机 UUID，无路径穿越；`/file/<token>` 外一律 404。
- SoapClient.escape 转义顺序正确（先 `&` 后其他），DIDL-Lite 中 title/URL 均经转义。
- TtmlToLrc 用正则解析而非 DOM/SAX，无 XXE 风险。
- FileProvider 只暴露 `cache-diagnostics/` 和 `cache-posters/` 两个 cache-path，不暴露外部存储，grantUriPermissions 临时授权。
- SQL 全参数化（R2 已实证）；ffmpeg 走参数数组非 shell 拼接。
- Manifest：`allowBackup=false` + dataExtractionRules + backupRules 双保险；MediaSessionService exported 但有 onConnect 白名单；无 WebView；无导出深链。
- 崩溃日志写 filesDir，不包含敏感信息。

**数据层**
- Room 迁移 1→5 与导出 schema 一致（R2 已实证）；索引补齐。
- MediaStore 游标 use{} 关闭。

**DLNA 模块**
- ControlUrls.normalize 正确归约 `.`/`..`，无路径穿越。
- DlnaTransport 的音量量程换算（按设备 SCPD maximum 折算）处理了极光TV 类越界截断问题，逻辑严谨。
- SsdpDiscovery 显式绑定网卡、三轮重传、组播+广播并行，设计扎实。
- DlnaTransport/CastProbe 全程 try/catch 不抛到 UI，CancellationException 正确向上传播。

---

## 建议修复顺序

1. **P1-1**（CastHttpServer Range 响应码，改 1 行）
2. **P1-2**（AMLL `"null"` 过滤，抽公共扩展，改 ~5 行）
3. **P1-3**（SoapClient 响应体上限，改 ~10 行）
4. **P2-2**（NSC 公网显式拒绝明文，改 XML）
5. **P2-1**（HTTP server 绑 Wi-Fi IP + entries 清理）、**P2-4**（日志计数重置，改 1 行）→〔2026-11-11 订正〕三项均已落地：P2-4 由 `ac75cbf` 修、entries 清理 11-10 修并经真机验收、绑 Wi-Fi IP 11-11 修并过全量门禁；**仅剩真机绑定回归**，见 §P2-1 状态订正
6. **P2-3**（SSDP 应答源校验）→〔2026-11-11 订正〕第一处 `onDatagram`、第二处 `ControlUrls.join`、第三处 `SoapClient` 目标校验、第四轮全链路诊断覆盖、第五轮库通道入口校验均已修并过全量门禁；仅剩三处 `UNVERIFIED` 窗口是否收紧（需多品牌真机），见 §P2-3 状态订正；**P2-5**（换强 keystore 密码）按约定不动
7. P3 随日常维护；~~R2 遗留项按 R2 报告原优先级排期~~ →〔2026-11-10 订正〕R2 遗留 33 条已于 2026-10-29 全部修复，**无需排期**，见 `dist/v1.7.19_遗留审查项对账表_2026-11-01.md` 与上文订正后的 §R2
