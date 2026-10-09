# 短剧TV (DuanjuTV)

面向 **Google TV / Android TV** 的短剧观看应用，Jetpack Compose 实现，遥控器全焦点操作。

- 包名 `com.duanju.tv` · minSdk 23 · targetSdk 36
- 双启动入口：`LAUNCHER`（手机/盒子）+ `LEANBACK_LAUNCHER`（TV 桌面）
- 仅申请 `INTERNET` / `ACCESS_NETWORK_STATE` 两项权限

## 功能

| 模块 | 说明 |
| --- | --- |
| 首页 | 自动轮播（获焦即暂停）、为你推荐、我的收藏、最近更新、完结好剧、换一批 |
| 分类 | 标签自动发现 + 6 列海报墙 + 触底增量加载 |
| 搜索 | 全网聚合多源并发搜索、热词与历史词、回车即搜（无需键盘） |
| 详情 | 沉浸式头图、多线路切换并持久化、每 50 集分页、整剧缓存（可取消） |
| 播放 | HLS/直连自动识别、失效自动切线路、倍速 0.75–2.0、跳片头片尾、自动下一集、进度续播 |
| 追剧库 | 历史进度与收藏，显示「看到第几集 · 百分比」 |
| 下载 | 离线缓存管理、占用统计、一键清空 |
| 设置 | 数据源、UI 缩放 50–150%、播放偏好 |

## 数据来源

公开 macCMS 聚合接口（`api.php/provide/vod/`）：`ac=list` 发现分类，`ac=detail` 取详情与播放地址。
部分源的播放地址是分享页形式，需带浏览器 UA + Referer 二次解析出 `index.m3u8?sign=...`；
签名与请求头绑定，因此**每集单独构建 ExoPlayer 实例**。

数据源清单维护在 `CmsApi.kt` 的内置列表，也可在设置页切换；接口约定变更时优先改 `SourceSpec`。

排行接口在多数源上不可用，热度由本地 `HotRanker` 估算：
`0.55 × 时间衰减 + 0.30 × 体量 + 0.15 × 完结加成`。

## 技术栈

Compose BOM 2024.11.00 · Navigation Compose 2.9.3 · Media3 1.11.1（ExoPlayer / HLS / OkHttp DataSource / DownloadManager）· Coil 3.0.0 · OkHttp 4.12.0 · kotlinx-serialization。

未使用 tv-material，焦点态由自研 `Modifier.tvFocusable`（缩放 + 阴影 + 描边环）实现。
依赖注入为手写 `AppGraph` + `CompositionLocal`，未引入 Hilt。

## 构建

需要 JDK 17+（实测 21）与 Gradle 8.13（AGP 8.13.2），仓库已自带 Wrapper。

```bash
cp local.properties.example local.properties   # 填入你的 sdk.dir
```

签名凭据不入库，二选一：

```properties
# keystore.properties（已 gitignore）
storeFile=keystore/release.jks
storePassword=<口令>
keyAlias=<别名>
keyPassword=<口令>
```

或通过环境变量 `DJ_storeFile` / `DJ_storePassword` / `DJ_keyAlias` / `DJ_keyPassword`。
未配置时 `assembleRelease` 产出未签名 APK。

```bash
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/DuanjuTV-release-<version>.apk
./gradlew :app:testDebugUnitTest  # 26 个测试：数据层 / 排序 / 真机网络
```

## 测试

- `DataLayerTest`（18）：macCMS 编码解析、剧集排序、分享页还原、进度持久化原子性
- `HotRankerTest`（6）：本地热度打分与排序稳定性
- `LiveNetworkTest`（2）：打真实服务器，验证内置源能拿到可播 m3u8、聚合搜索有结果

## 已知限制

- 第三方 CMS 源可用性随时间变化，某线路失效时播放器自动切换其余线路。
- 海报 CDN 常拒绝非浏览器 UA，已在 Coil 全局补 `User-Agent`。
- 本项目仅供技术研究与个人学习使用，请遵守源站条款与当地法规。
