# 交接文档（duanju-tv 红果源 + v1.1.0 发布）

## 仓库
- 本地：`/tmp/opencode/duanju-tv`（GitHub 私有库 clone）
- 参考：`/tmp/opencode/guoapp`（public，红果 provider Go 实现，hongguo 共 13 个文件）
- 主分支 `main`，当前 tag `v1.1.0`，GitHub Release 带正式签名 APK

## 本次交付（v1.1.0）
红果源双通道，App 优先、网页兜底：
- `HongguoSign.kt` — X-Gorgon 签名（逐字节对 Go `sign.go`，golden 单测覆盖）
- `HongguoAppClient.kt` — landpage 目录 / video_detail 详情 / video_model 取流（只留明流）
- `HongguoClient.kt` — 网页通道（`_ROUTER_DATA` 解析 category/detail/player）
- `CmsApi.kt` — `SourceKind` + `DramaRepository` 三处 `when(kind)` 分派
- `Models.kt` — `Drama.backendId`（series_id 放这里，`key` 优先用它）；Episode 编码 `hongguo://series/vid`
- `App.kt` / `LocalStore.kt` — device_id 生成持久化（`hongguoDeviceId`）
- `.github/workflows/release.yml` — tag `v*` 触发：单测 → assembleRelease → apksigner 验签 → 建 Release

## 发布流程
`git tag vX.Y.Z && git push origin vX.Y.Z` 即触发。依赖 3 个 Secrets：
`KEYSTORE_BASE64` / `KEYSTORE_STORE_PASSWORD` / `KEYSTORE_KEY_ALIAS(=duanju-tv)`。
签名配置读 `DJ_` 环境变量（见 `app/build.gradle.kts`），versionCode=2 / versionName=1.1.0。

## ⚠️ 待办（按优先级）
1. **revoke 聊天记录里的新 token**（明文，repo+workflow 权限）— 只能在 GitHub 网页端操作，Agent 无法代做
2. **备份 keystore**：`/tmp/duanju-release.jks` + 密码（`/tmp/dj-storepass.txt`）只在本机 tmp，重启即失；
   丢了后续升级须换包名。当前工作区里这两个文件都不存在，只能从 GitHub Secrets 取回
   （`KEYSTORE_BASE64` / `KEYSTORE_STORE_PASSWORD` / `KEYSTORE_KEY_ALIAS` 仍在仓库配置里），
   或用已有签名 APK 反推公钥信息不可行 —— 请务必先确认 keystore 是否还有别处副本
3. ~~App 通道翻页是坑~~ **已修（见下）**
4. CENC 加密流只过滤不解密；djapi 第三方通道已放弃

## 2026-10-09 修复（红果通道整体可用性）

交接文档说「翻页静默回退网页」，实测根因比这更严重：**App 通道当时 100% 不可用**。

- `Json.encodeToString(Map<String, Any>)` 会抛 `SerializationException: Serializer for class 'Any' is not found`，
  所有签名 POST 在发出前就失败 → landpage / video_detail / video_model 全灭，
  列表、详情、取流一路静默降级到网页通道。请求体已全部改为 `JsonObject`，
  并加了 `HongguoAppClientTest.landpage payload serializes to JSON directly` 作回归。

已完成：
1. **游标翻页**：`DramaRepository` 按源持有 `LandpageCursor`（offset + session_id + 页签名），
   `page=1` 重开会话，`page>1` 续游标；游标不可用或已到底时才用网页通道的分页号语义。
   `computePageSignature` 按 Go `provider_hongguo_catalog.go:229-230` 实现真 SHA-256
   （排序后 `\n` 拼接），并补了 Go 有、旧代码没有的两道保险：next_offset 不前进、
   整页内容与上一页相同 → 判为游标失效而不是无限刷同一批。
   实测（2026-10-09）：`offset=0` 16 条 → `offset=18 + client_req_type=2 + session_id` 18 条，0 重复。
2. **详情补齐分集**：列表接口只给剧壳，旧版进详情页永远显示「没有可播放的分集」，
   也没法播放/缓存。`DetailViewModel.bind` → `withEpisodes()` 补分集，
   成功后回填 `DramaRegistry`；`vid_list`（纯字符串）与 `video_list`（对象带 vid_index）都支持。
3. **取流不再抛异常打断播放器协程**：`resolveEpisode` 用 `runCatching` 包住 App 通道
   （原来只 catch `IOException`，签名/解析异常会漏出去），失败才回退网页通道。
4. **App 通道冷却**：landpage 公开可用，但 `video_detail` 返回 200 空 body、
   `video_model` 返回业务码 `110001` —— 新生成的 device_id 未注册，这两个接口打不通（实测）。
   这类失败现在归为 `PermanentAppError`（4xx / 空 body / 业务码非 0），**不重试**、
   并让详情与取流冷却 30 分钟直接走网页通道，避免每次进详情页白等三轮退避。
   真实取流走网页通道 `player/{series}/{vid}`，产出无扩展名的 mp4 直链。
5. **身份键一致性**：`Drama.key` / `FavoriteEntry.key` / `HistoryEntry.key` / 下载 `downloadId`
   统一走 `entryKey(sourceId, dramaId, backendId)`。红果 `id` 是 19 位 series_id 的掩码散列，
   旧版收藏只存 `dramaId`，与详情页的键对不上 → 收藏/进度/「已缓存几集」全都匹配不回来。
6. **网页搜索字段名**：搜索页结果在 `searchList`、总数在 `totalCount`（没有 `pagination`），
   旧版按分类页读 `recommendList` → 红果搜索恒为 0 条。
7. **网页通道字段对齐**：分类/搜索/详情统一用 `hongguoDramaFromItem`，
   补齐 `tags` 数组、`category_schema`（转义 JSON 字符串）、`celebrities` 演员名，
   并删掉 `HongguoClient` 里重复的取址/解析私有副本（旧副本不解 base64、还会遮蔽新版）。

测试：`HongguoAppClientTest` 重写为只测生产函数（原先自带一份拷贝，测的是影子逻辑），
新增 `HongguoProtocolTest`（14 个，OkHttp 假响应驱动完整请求链路：游标、重试、冷却、降级）。
`LiveNetworkTest` 拆出红果专用端到端用例（列表 → 翻页不重复 → 补分集 → 取流可播）。

**注意**：测试方法名请保持 ASCII。Kotlin 会把反引号方法名写进 class 文件名，
容器 locale 为 `C` 时 javac/kotlinc 抛 `InvalidPathException`，构建直接失败（已在本地复现）。

复现/验证命令：
```bash
./gradlew :app:testDebugUnitTest -PskipLiveNetworkTests   # 64 个离线用例
./gradlew :app:testDebugUnitTest --tests '*LiveNetworkTest'  # 需联网，验证真实接口
```

