# MagnetRush

一个跑在手机上的 BitTorrent 下载器，内核用 **libtorrent**（通过 `org.libtorrent4j` 的 JNI 绑定），
支持磁力链接、`.torrent` 文件、DHT / PEX / LSD、uTP、协议加密、断点续传、后台前台服务下载。

> **先说清楚一件事**：这个仓库里是**完整的 Android 工程源码**，但**没有预编译好的 APK**。
> 原因很简单 —— 我生成这份代码的环境里没有 Android SDK，也没有外网出口，
> 无法执行 `aapt2`（编译资源）和 `d8`（编译 dex）这两步，APK 物理上生成不出来。
> 所以下面给你两条**都能真的产出 APK** 的路，第一条不需要你装任何开发环境。

---

## 一、拿到 APK（推荐路径：云编译，不用装环境）

### 方案 A：GitHub Actions 自动编译

1. 在 GitHub 上新建一个空仓库（公开/私有都行）。
2. 把这个目录推上去：

   ```bash
   cd MagnetRush
   git init
   git add .
   git commit -m "MagnetRush: android bt downloader"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```

3. 推上去之后，GitHub 会自动跑 `.github/workflows/build-apk.yml`。
   也可以去仓库的 **Actions** 页面点 **Build APK → Run workflow** 手动触发。
4. 等大约 3~6 分钟，进这次运行的页面，在页面底部 **Artifacts** 里下载
   `MagnetRush-APK-arm64-v8a`，解压出来就是 `MagnetRush-debug-arm64-v8a.apk`。
5. 把这个 APK 传到手机上安装（安装时系统会提示"未知来源"，允许即可）。

工作流做了什么：

| 步骤 | 说明 |
| --- | --- |
| `setup-android` | 自动装好 Android SDK（platform 34 + build-tools） |
| `gradle/actions/setup-gradle` | 自动准备好 Gradle 8.7 |
| `assembleDebug` | 出**可以直接装**的调试签名包 |
| `assembleRelease` | 同时尝试出 release 包（未签名，`continue-on-error`） |
| `upload-artifact` | 把 APK 作为构建产物上传，保留 90 天 |

想同时要 32 位 ARM 的包，在手动触发时把 `abis` 填成 `arm64-v8a,armeabi-v7a` 即可。

### 方案 B：本地编译

需要三样东西：**JDK 17**、**Android SDK**、**Gradle 8.7+**。

Windows 上直接跑：

```powershell
cd MagnetRush
.\tools\build.ps1
```

脚本会自己找 JDK / SDK / Gradle，缺哪样就告诉你确切的安装命令，不会甩你一脸 Gradle 报错。
常用参数：

```powershell
.\tools\build.ps1 -Abis "arm64-v8a,armeabi-v7a"   # 多架构
.\tools\build.ps1 -Variant Release -Clean          # release + 先清理
```

手工命令等价于：

```bash
# local.properties 里写 sdk.dir=/path/to/Android/Sdk
gradle :app:assembleDebug -Pabis=arm64-v8a
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 关于签名

* **debug 包**用 Android 默认调试签名，能直接装、能用，只是不适合上架。
* 要用自己的密钥签 release 包：

  ```bash
  gradle :app:assembleRelease \
    -PksPath=/abs/path/my.keystore -PksPass=你的库口令 \
    -PksAlias=myalias -PksKeyPass=你的密钥口令
  ```

### 国内网络下编译

Gradle 需要访问 `maven.google.com` 和 `repo1.maven.org`。如果慢或不通，
在工程根目录的 `settings.gradle` 里把 `google()` / `mavenCentral()` 换成镜像即可，例如：

```groovy
repositories {
    maven { url 'https://maven.aliyun.com/repository/google' }
    maven { url 'https://maven.aliyun.com/repository/public' }
}
```

---

## 二、这个应用能做什么

| 能力 | 实现方式 |
| --- | --- |
| 磁力链接下载 | `AddTorrentParams.parseMagnetUri()` + DHT 找节点 |
| 直接粘贴 40 位 info-hash | 自动补成 `magnet:?xt=urn:btih:...` |
| 从浏览器"打开磁力链接" | 清单里注册了 `magnet:` 的 VIEW intent-filter |
| 打开 / 分享 `.torrent` 文件 | 支持 `application/x-bittorrent` 的 VIEW 与 SEND |
| 边下边看 | 每个任务可开"顺序下载"（`SEQUENTIAL_DOWNLOAD`） |
| 只下部分文件 | 任务详情页里逐个文件开关（`prioritizeFiles`） |
| 后台 / 锁屏继续下 | 前台服务（`dataSync` 类型）+ `PARTIAL_WAKE_LOCK` + 组播锁 |
| 通知栏控制 | 常驻进度通知 + "全部暂停/继续"按钮；完成单独通知 |
| 断点续传 | 每 15 秒 + 退出时把 `resume_data` 写盘；重启后用磁链/种子重新挂载，libtorrent 校验已有分片，**已下好的字节不重下** |
| 开机自恢复 | `BootReceiver`，且只在确实有未完成任务时才拉起服务 |
| 仅 Wi-Fi 下载 | 监听网络变化，非 Wi-Fi 时暂停、回到 Wi-Fi 自动继续 |
| 限速 | 设置页可分别限制上/下行（默认都不限） |
| 深浅色主题 | Material3 `DayNight` |

---

## 三、**为什么它下载得快** —— 关键都在这几处

这不是"官方配置 + 一个漂亮的壳"。BT 客户端慢，绝大多数情况是下面几个原因之一，
工程里针对每一条都做了处理：

### 1. 连接数没被限死（最常见的原因）

`app/src/main/java/com/magnetrush/app/core/TorrentEngine.java` 里：

```java
sp.connectionsLimit(800);      // 默认值偏保守，手机上一堆 peer 连不进来
sp.maxPeerlistSize(8000);      // 已知节点池放大，避免好节点被过早淘汰
sp.activeDownloads(-1);        // -1 = 不限，所有任务同时跑，不进队列
sp.activeSeeds(-1);
sp.activeLimit(-1);
```

libtorrent 的默认 `active_downloads` 是 3，也就是**同时只下 3 个任务**，
而且连接上限相对保守。磁力链接冷启动阶段本来就缺 peer，再限连接数就更容易卡在
"0 节点" 状态。这里全部放开。

### 2. DHT / LSD / uTP / 加密都打开

```java
sp.setEnableDht(true);          // 无 Tracker 的磁力全靠它
sp.setEnableLsd(true);          // 局域网内互相发现，同一 WiFi 下提速明显
sp.setDhtBootstrapNodes(...);   // 5 个引导节点兜底，防 DNS 挂掉
sp.stopTrackerTimeout(3);       // 退出时别在 tracker 上白等
```

uTP、UPnP、NAT-PMP、协议加密（mixed 模式）用的是 libtorrent 默认值 —— 它们默认就是开的，
所以这里**没有**去重复设置，避免踩到跨版本不存在的配置项。

### 3. 上传**不**限速

```java
sp.downloadRateLimit(0);
sp.uploadRateLimit(0);
```

这条容易被误解：很多人为了"省流量"把上传压到很低，结果下载反而更慢。
BT 是**互惠协议** —— 你的上传配额决定了别人愿意给你多高的下载优先级，
上传被掐住的客户端会被对面主动降速（choke）。默认不限，是经过权衡的选择。

### 4. 磁盘 I/O 不成为瓶颈

```java
sp.maxQueuedDiskBytes(32 * 1024 * 1024);   // 写队列 32MB，防止慢存储反压下载
sp.sendBufferWatermark(3 * 1024 * 1024);
```

高速下载时如果磁盘写入跟不上，libtorrent 会停止从 peer 读数据（这就是"网速明明够、
速度却上不去"的典型症状）。加大磁盘队列和发送缓冲能明显改善。

### 5. 多 Tracker 兜底注入

添加任务时会自动补上 8 个公共 Tracker（`TorrentEngine.EXTRA_TRACKERS`），
UDP + HTTP + WebSocket 全都有。磁力链接里的 Tracker 往往早就挂了，
这一步能显著提高冷启动速度 —— 尤其在没有 IPv6、DHT 又被运营商干扰的网络里。

### 6. 界面开销不影响下载

* 引擎的所有 libtorrent 调用都投递到**一条单线程队列**（`commandQueue`）执行，
  不会出现多线程同时进 JNI 造成的锁竞争；
* 界面每秒只从广播里拿一份 JSON 快照，不在 UI 线程碰 libtorrent；
* 状态按需查询，不做 `QUERY_PIECES` 这类昂贵调用。

### 还能更快吗？

可以，但要看你更信谁。可选方向：

* **提高多任务并发**：把 `settings.maxConnections()` 从 800 调到 1500（设置页可改，
  重启应用生效）。代价是内存和耗电上升。
* **换更新的内核**：把 `app/build.gradle` 里的 `libtorrentVersion` 升到 2.1.0-39 之外的
  更新版本（先确认 Maven Central 上有对应 ABI 构件）。
* **降低 piece 校验开销**：torrent 本身 piece 越小，校验越频繁、CPU 越忙，
  这是种子制作者决定的，客户端改不了。

---

## 四、下载的文件存到哪了？

默认目录：

```
/storage/emulated/0/Android/data/com.magnetrush.app/files/MagnetRush/
```

**为什么选这里，而不是 `/sdcard/Download`：**

* Android 10+ 的分区存储下，写公共 `Download` 目录需要走 MediaStore，
  而 libtorrent 需要**真实的文件路径**来读写数据。用 App 专属目录可以完全绕开这个矛盾，
  而且**不需要申请任何存储权限**。
* 缺点也直说：卸载应用时这些文件会被一起删掉。下完想长期留着，
  请用文件管理器把它复制到普通目录。

路径在**设置页**可以直接看到（也支持长按复制）。

> 关于"自选目录"：目前点"选择目录"会提示不支持。原因是 Android 的文件选择器返回的是
> `content://` 形式的 SAF 树 URI，libtorrent 拿它没法读写。要真正支持，
> 需要在下载完成后多一步"从私有目录拷贝到 SAF 目录"的动作，这会让大文件多占一份空间和
> 一次完整 IO。工程里刻意没做这一步，避免为了一个边缘需求引入复杂度和风险。
> 如果你确实需要，可以在 `DownloadService.onTorrentFinished()` 里加这个拷贝。

---

## 五、架构与包体

```
app/src/main/java/com/magnetrush/app/
├── App.java                     Application，负责加载 libtorrent 的 .so
├── core/                        与 UI 完全解耦的引擎层
│   ├── TorrentEngine.java       libtorrent 会话单例（速度调优都在这里）
│   ├── TaskStatus.java          实时状态的值对象（不暴露 JNI 类型给 UI）
│   ├── TorrentTask.java         持久化实体
│   └── TaskStore.java           任务表 + resume data 落盘
├── service/
│   ├── DownloadService.java     前台服务：tick 循环、通知、断点、仅 Wi-Fi
│   ├── TorrentView.java         服务→界面的可序列化快照
│   ├── NotificationActionReceiver.java
│   └── BootReceiver.java
├── ui/
│   ├── MainActivity.java        列表 + 添加磁力链接（含剪贴板预填）
│   ├── TaskDetailActivity.java  进度详情 + 文件级选择
│   ├── SettingsActivity.java    限速 / 连接数 / 行为开关
│   ├── TaskAdapter.java
│   └── FileAdapter.java
└── util/
    ├── Settings.java            SharedPreferences 封装
    └── Fmt.java                 字节 / 速度 / 剩余时间格式化
```

**架构要点**：UI 层**从不直接调用 libtorrent**。所有操作通过 Intent 发给
`DownloadService`，状态通过广播拿回 JSON 快照。这样做的代价是多了一层序列化，
收益是 UI 永远不会因为 JNI 对象被释放而崩，也让"后台下载"和"界面展示"天然解耦。

**APK 体积**：默认只打包 `arm64-v8a`（2018 年之后绝大多数手机），
`libjlibtorrent.so` 解压后约 10MB，APK 大约 **12~16 MB**。
四个 ABI 全打包会涨到 ~50MB，所以默认没有这么做。

**包体里没有任何广告 SDK、统计 SDK、也不申请通讯录/位置/相机权限。**
权限清单只有 6 项，全部是联网与后台下载必需的。

---

## 六、常见问题

**Q：添加磁力后一直"正在获取元数据"，没有速度？**
说明一个 peer 都没连上。按顺序检查：

1. 下拉看列表顶部的 **DHT 节点数**（主界面底部汇总栏）。如果长时间是 0，
   说明 UDP 出不去 —— 换网络（有些公司/校园网封 UDP），或者确认没有开全局代理但代理不支持 UDP。
2. 这个种子可能真的没人做种了。换一个热门的磁力试试，先确认应用本身是通的。
3. 冷门种子请耐心等 1~5 分钟，DHT 找节点不是瞬时的。

**Q：速度比电脑上的 qBittorrent 慢很多？**
手机端天然吃亏：通常是 NAT 后面的"被动连接"，拿不到入站连接。
能改善的：连 Wi-Fi（别用移动数据）、路由器开 UPnP、避免用会限制 P2P 的网络。
另外手机存储的随机写性能确实不如 SSD，这也是真实瓶颈之一。

**Q：通知栏一直有一条常驻通知，能去掉吗？**
可以，设置页关掉"显示常驻进度通知"。但**服务仍然是前台服务**，
这是 Android 保证后台不被杀的机制，关掉的只是通知的可见性。

**Q：应用被杀掉后下载会断吗？**
`START_STICKY` + 前台服务 + 唤醒锁，正常情况不会被杀。
被系统强行回收后，重新打开应用会自动恢复。
恢复时会用磁链（或保存的种子字节）重新挂载任务，libtorrent 校验磁盘上已有的分片，
**已经下好的部分不会重下** —— 代价只是启动时多一次哈希校验（每 GB 大约几秒）。

> 关于 libtorrent 的 `resume_data`：工程里每 15 秒和退出时都会把它写盘
> （`filesDir/resume/<hash>.fastresume`），但**当前没有用它做快速恢复**。
> 原因是 `org.libtorrent4j` 2.1.0-39 只暴露了「写」resume data 的 Java API
> （`AddTorrentParams.writeResumeDataBuf`），没有暴露对应的「读」API
> （底层 `add_torrent_params` 没有 `read_resume_data`）。
> 与其自己解析这份 bencode 或反射调用不稳定的内部方法，不如走官方支持的等价路径 —— 
> 结果一样（不重下），可靠性更高。数据保留着，将来内核补上读入 API 就能直接启用秒级恢复。

**Q：提示"下载内核未能加载"？**
说明 APK 里没有你这个 CPU 架构的 `.so`。默认只打 `arm64-v8a`。
老的 32 位设备请用 `-Pabis=armeabi-v7a` 重新编译。
对话框里会显示你的实际架构，照着选。

---

## 七、法律提示（请务必读一下）

这是一个**通用 BitTorrent 客户端**，和 qBittorrent、Transmission、Deluge 属于同一类工具：

* 它**自身不提供、不索引、不搜索任何资源**。你输入什么磁力链接，它就下载什么，
  开发者无法控制也无从知晓。
* P2P 下载的特点是**你在下载的同时也在向其他人上传**。
  在很多国家和地区，未经授权传播受版权保护的作品是侵权行为，可能承担法律责任。
* 请只用于下载你有权获取的内容：Linux 发行版镜像、开源软件、你自己创作或已获授权的作品、
  以及明确允许自由分发的资源。
* 使用本软件所产生的全部后果由使用者自行承担。

技术本身是中性的 —— 同样的代码，用来分发 Debian ISO 和用来盗版电影在技术层面没有任何区别，
区别只在于使用者。请做前者。

---

## 八、第三方组件与许可

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| [libtorrent](https://www.libtorrent.org/) | BitTorrent 协议实现 | BSD-3-Clause |
| [libtorrent4j](https://github.com/aldenml/libtorrent4j) | libtorrent 的 Java/JNI 绑定 | MIT |
| AndroidX / Material Components | 界面 | Apache-2.0 |

`libtorrent4j` 是按 ABI 分构件发布的，工程里四个都声明了，靠 `abiFilters` 决定最终打哪个：

```groovy
implementation "org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39"
implementation "org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39"
implementation "org.libtorrent4j:libtorrent4j-android-x86:2.1.0-39"
implementation "org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39"
```

> 注意别和 `com.frostwire:jlibtorrent-android-*` 搞混 —— 那是另一个分支，
> 包名是 `com.frostwire.jlibtorrent`，Maven Central 上的版本停在 2018 年。
> 本工程用的是仍在维护的 `org.libtorrent4j`，内核是 libtorrent 2.0.x。
