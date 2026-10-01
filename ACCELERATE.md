# 国内网络下的提速指南

先说最重要的一条，能省掉你一大半精力：

## 一、真正吃带宽的事，云编译已经帮你绕开了

| 环节 | 谁来下载 | 大小 | 要不要你操心 |
| --- | --- | --- | --- |
| Android SDK + build-tools | GitHub Actions runner（机房里） | ~700 MB | ❌ 不用，你本机一字节都不下 |
| Gradle 8.7 + AGP | GitHub Actions runner | ~200 MB | ❌ 不用 |
| androidx / libtorrent4j 依赖 | GitHub Actions runner | ~80 MB | ❌ 不用 |
| **你的仓库代码** | **你本机** | **239 KB** | ✅ 只有这一项 |

GitHub 的 runner 是**机房内网直连** `dl.google.com` 和 `repo1.maven.org`，
通常几十秒拉完。所以"下不动 SDK"这件事在云编译路径下根本不会发生 ——
**不要为了编译去下 SDK**。

你本机要传的只有 239 KB（git 压缩后约 60~80 KB）。这个体积即使被限速到
50 KB/s 也就一两秒，**提速方案的收益极小**。

---

## 二、唯一值得做的：让 git 走 SSH 443 端口

国内运营商经常对 **22 端口**做 QoS 限速，或者在晚高峰丢包，表现为
`git push` 卡住、速度只有几十 KB/s 甚至超时。走 **443 端口**通常明显更稳，
而且 443 流量看起来像普通 HTTPS，不容易被针对性干扰。

### 操作（两步）

**1. 合并 SSH 配置**

把 `tools/ssh-config-github-443` 的内容追加到：

```
C:\Users\<你的用户名>\.ssh\config
```

（`~/.ssh` 目录不存在就先建一个。如果里面已经有 `Host github.com` 段落，
把它改成文件里给的那段，**不要**留下两段同名的）

内容就这几行：

```
Host github.com
    HostName ssh.github.com
    Port 443
    User git
    ServerAliveInterval 30
    ServerAliveCountMax 6
```

**2. 验证**

```powershell
ssh -T git@github.com
```

看到 `Hi <你的用户名>! You've successfully authenticated...` 就成了。
**注意**：命令里写的还是 `github.com`，是配置在背后把它转到了 `ssh.github.com:443`，
所以你不需要改任何 git 地址。

### 顺手做的两项调优

```powershell
# 大仓库推送时避免 "RPC failed; HTTP 411"（走 SSH 的话其实用不上，但配上无害）
git config --global http.postBuffer 524288000

# 让 git 压缩更用力一些，慢网络下总传输量更小
git config --global core.compression 9
```

---

## 三、如果你还是想在本机编译

那才需要处理依赖下载。`settings.gradle` 里我已经把阿里云镜像**写成注释放在那了**，
把注释打开、把官方 `google()` / `mavenCentral()` 注释掉即可：

```groovy
repositories {
    maven { url 'https://maven.aliyun.com/repository/google' }
    maven { url 'https://maven.aliyun.com/repository/public' }
    google()
    mavenCentral()
}
```

（同时保留官方源做兜底，阿里云没有的构件会回落到官方源。）

Android SDK 本体的下载，用清华或中科大的镜像也行，但**不推荐**，
因为 700 MB 下完你还得配 `ANDROID_HOME`、接受 license，比直接云编译麻烦得多。

---

## 四、几个常见误区的澄清

**❌ "把 remote 换成 ghproxy 就能加速推送"**
不行。`ghproxy` / `ghfast.top` 这类是**只读代理**，只能加速 clone 和下载，
**不能 push**。把它们设成 remote 会导致推送失败。

用法是"一旦需要克隆/下载时才临时加前缀"：

```bash
# 只用于 clone（不能用于 push）
git clone https://ghproxy.com/https://github.com/Wen0827-del/cllj.git
git clone https://ghfast.top/https://github.com/Wen0827-del/cllj.git
```

**❌ "改 hosts 文件给 github.com 指定 IP 就能加速"**
对**网页访问**和**克隆**有时候有用，但对 push 帮助有限，而且 GitHub 的
IP 会变，写死了过一阵反而打不开。真要写，只写 `github.com` 和
`objects.githubusercontent.com`，别写 `ssh.github.com`（443 端口本来就好用）。

**❌ "开全局代理但只挂 TCP"**
BT 客户端和 SSH 都可能因此出错。如果要用代理，优先选支持 UDP 中继的
TUN 模式，或者干脆把 `github.com` 走代理、BT 流量直连（分流规则）。

**✅ 下载 Actions 产物慢**
APK 产物在 Actions 页面下载走的是 `objects.githubusercontent.com`，
国内偶尔很慢。这时候可以用只读代理：

```
https://ghproxy.com/https://github.com/Wen0827-del/cllj/releases/download/<tag>/<file>.apk
```

（或者把仓库打 tag 触发 workflow 里的 release 逻辑，产物会挂到 Releases 页面）

---

## 五、一句话总结

**云编译路径下你唯一需要优化的就是那一次 `git push`，而它只有 239 KB。**
把 SSH 换成 443 端口是唯一性价比高的动作，其余都不用折腾。
