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
1. **revoke 聊天记录里的新 token**（明文，repo+workflow 权限）
2. **备份 keystore**：`/tmp/duanju-release.jks` + 密码（`/tmp/dj-storepass.txt`）只在本机 tmp，重启即失；丢了后续升级须换包名
3. App 通道翻页是坑：page>1 传裸数字 cursor 无效，landpage 只供首页，翻页静默回退网页——下次修
4. CENC 加密流只过滤不解密；djapi 第三方通道已放弃
