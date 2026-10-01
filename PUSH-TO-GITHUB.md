# 把 MagnetRush 推到 GitHub

## 为什么需要你配合一下

我这边的命令行环境有两个硬限制（都实测过）：

* **HTTPS / TLS 完全被挡** —— 不止 GitHub，连 baidu、gitee 的 TLS 握手都失败。
  所以没法用「用户名 + Token」那条路，`gh` CLI 也装不上（它走 HTTPS）。
* **SSH 是通的**，但**本机没有任何 SSH 私钥**，而且沙箱不允许我往 `~/.ssh` 写东西。
  所以我用 C# 在工作区里生成了一对专用密钥（已通过 `ssh-keygen` 校验）。

结论：**路已经铺好了，只差把公钥登记到你的 GitHub 账号** —— 这一步只能你本人做。

---

## 第 1 步：把公钥加到 GitHub

公钥文件：`.ghkeys\magnetrush_rsa.pub`

内容就是这一行（也可直接复制文件内容）：

```
ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC6M9cgRx81t3g1biNMD7hbRjqIY5RpJJcpYeeLaTQugIObjMryeySXKqMFLxQObUHSmPt3RwVYv0HsjoZfue3FnuxQEz0qckBcNaOLvsDih701XSmVsrhQI9crpA5VNaujoePjMHodad2bJK2h+YLdpKiaZ8JfEfve4lZEvI3XOKgpF6FJIYS2rEClZ3t8scP/td6ZT7yxRXT8yvSVE3/ogRPVhQfoAQPRm0B/iMsykcoJx+aeV6RJci72EII66SmyQEG0F54N+gvNpe9UqX83t51SKUMR9PdrPtVtMnlyjeZF8RzARQjSCoXErAPjKJJdxIxT5tIlVVIys5vYeYMx magnetrush-github
```

在 GitHub 上：**Settings → SSH and GPG keys → New SSH key**
（直达：<https://github.com/settings/ssh/new>）

* **Title**：随便写，比如 `magnetrush-laptop`
* **Key type**：`Authentication Key`
* **Key**：粘贴上面那一整行（`ssh-rsa` 开头，`magnetrush-github` 结尾，**一行不能断**）

点 **Add SSH key**。

> 想用命令行登记也行：
> ```powershell
> gh auth login          # 或者
> Get-Content .ghkeys\magnetrush_rsa.pub | Set-Clipboard
> ```

---

## 第 2 步：在 GitHub 上建一个空仓库

<https://github.com/new>

* **Repository name**：`MagnetRush`（或任何你喜欢的名字）
* **Public / Private 都行** —— 私有仓库的 Actions 一样能编译，只是每月有分钟数限制
* **不要**勾选 "Add a README file"、**不要**加 .gitignore、**不要**加 license
  （仓库必须是空的，否则推送会冲突）

建好后记下仓库地址，形如：

```
git@github.com:你的用户名/MagnetRush.git
```

---

## 第 3 步：一条命令推送

```powershell
cd C:\Users\abc12\Desktop\dfy\MagnetRush
.\tools\push-github.ps1 -Repo "git@github.com:你的用户名/MagnetRush.git"
```

脚本会自己：用工作区里的私钥配置 SSH → 测试认证 → 设置 remote → 推送。

---

## 第 4 步：等 APK 自动编译好

推送成功后会**自动触发** `.github/workflows/build-apk.yml`。等 3~6 分钟，然后：

1. 打开仓库的 **Actions** 页面
2. 点进最新那次 **Build APK** 运行
3. 页面底部 **Artifacts** 里下载 `MagnetRush-APK-arm64-v8a`
4. 解压得到 `MagnetRush-debug-arm64-v8a.apk` —— **这个包可以直接装到手机**

---

## 备选：不用我的密钥

如果你本机已经配好了 GitHub 凭据（比如 Git Credential Manager、
或者 `~/.ssh/id_ed25519` 已在 GitHub 上登记过），那**完全不需要上面的密钥**，
在你自己的终端里三条命令就够：

```powershell
cd C:\Users\abc12\Desktop\dfy\MagnetRush
git remote add origin git@github.com:你的用户名/MagnetRush.git
git push -u origin main
```

用 HTTPS 也一样（你的机器没有被封 TLS）：

```powershell
git remote add origin https://github.com/你的用户名/MagnetRush.git
git push -u origin main
```

---

## 安全提醒

* `.ghkeys\` 已经写进 `.gitignore`，**不会**被提交进仓库。
* 这把私钥没有设口令（为了方便脚本非交互推送）。如果你介意，
  登记完、推送完可以直接把 `.ghkeys\` 整个删掉，之后再重新生成一把带口令的。
* 私钥只用于这一次推送，权限范围就是你 GitHub 账号的 SSH 权限。
  不放心的话，用完去 GitHub 上把这个 key 删掉即可（Settings → SSH and GPG keys）。
