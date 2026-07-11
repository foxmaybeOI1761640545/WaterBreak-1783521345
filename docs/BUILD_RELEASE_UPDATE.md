# 构建、签名、发布与应用内更新说明

## 当前版本基线

- 源码版本：`1.0.6`
- Android `versionCode`：`7`
- 下一次发布：`1.0.7`
- 下一次 Android `versionCode`：`8`

工作流会扫描仓库已有 Tag 中所有形如 `v1.0.N` 的版本号，以 `v1.0.6` 为最低基线，跨分支计算下一个全局版本。`main`、`master`、`work` 和内部 PR 共用同一序列。

## 为什么没有改成只在 `v*` Tag 时发布

常见的生产发布规则是：

```yaml
on:
  workflow_dispatch:
  push:
    tags:
      - "v*"
```

它的意义是只有维护者主动推送版本 Tag，或者在 Actions 页面手动运行时，才会使用正式签名并创建 Release。优点是发布可控、不会因普通代码提交消耗版本号、也减少内部 PR 接触正式签名 Secret 的机会。

本项目明确要求保留“每次 push 和内部 PR 都创建 Release”，因此实际实现仍监听：

```yaml
push:
  branches: [main, master, work]
pull_request:
  types: [opened, synchronize, reopened]
workflow_dispatch:
```

发布类型为：

- `main` / `master` push：正式 Release；
- `work` push：prerelease；
- 同仓库内部 PR：prerelease；
- 手动运行：默认 prerelease，可在运行表单取消勾选以发布正式版；
- 外部 Fork PR：跳过签名发布，因为 GitHub 不向 Fork PR 提供仓库 Secret。

这保留了原有自动发布习惯，同时避免测试版被稳定渠道误识别为最新正式版本。

## 固定签名参数

工作流只接受以下 GitHub Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

任一项缺失都会立即失败：

```bash
if [[ -z "${ANDROID_KEYSTORE_BASE64:-}" ]]; then
  echo "Missing required GitHub Actions secret: ANDROID_KEYSTORE_BASE64"
  exit 1
fi
```

项目不再：

- 自动生成 fallback keystore；
- 从 Actions Cache 恢复签名私钥；
- 使用默认密码继续发布。

`release-secrets/` 中提供了一套新生成的固定签名材料和 GitHub Secrets 填写说明。该目录被 `.gitignore` 忽略，只用于本次安全交付。

### 旧签名兼容性

Android 覆盖安装要求新旧 APK 的签名兼容。如果 v1.0.6 已经由旧 fallback 私钥签名，而旧私钥无法取得，新固定私钥签名的 v1.0.7 不能覆盖它。这种情况下只需在签名迁移时卸载旧版一次，之后持续使用同一固定 JKS 即可正常应用内更新。

若已有可用的正式 JKS，应把已有 JKS 转为 Base64 并配置到上述 Secrets，不应使用新生成的 JKS。

## 并发锁和全局版本

工作流使用仓库级并发锁：

```yaml
concurrency:
  group: android-release-${{ github.repository }}
  queue: max
```

多个 push 或 PR 同时触发时会进入等待队列，而不是用较新的等待任务替换较旧任务。每个任务获得执行机会后重新拉取 Tag，再计算下一个版本，因此不会同时生成相同的 `1.0.7`。

## Gradle 配置预检与签名指纹

`UPDATE_REPOSITORY` 通过 `defaultConfig.buildConfigField` 写入 Android `BuildConfig`。发布工作流会在恢复签名材料前运行 `gradle :app:tasks --all :app:compileDebugKotlin`，尽早发现 Gradle DSL 配置错误和原生 Kotlin 插件类型错误。

固定 JKS 恢复后，工作流会导出签名证书并强制核对 SHA-256 指纹。证书与本次交付的固定签名不一致时立即失败，避免误用其他 JKS 后发布无法覆盖安装的 APK。

## 源码版本一致性

仓库当前的下列文件已经统一为 `1.0.6`：

- `package.json`
- `package-lock.json`
- `android/app/build.gradle`

每次工作流发布时会把新版本写入这些文件，创建一个只由发布 Tag 指向的版本提交，再发布 GitHub Release。这样从 Release Tag 下载源码时，源码版本、APK 版本和 Release 版本一致；不会额外把版本提交推回开发分支，也不会触发发布循环。

## GitHub Release 资源

每个 Release 包含：

- `waterbreak.apk`：供应用内更新使用的固定文件名；
- `waterbreak-v1.0.N.apk`：方便人工辨识和留档；
- `update.json`：版本号、下载地址、大小、SHA-256、渠道和更新说明。

稳定版应用调用 GitHub 的 latest release 接口，只读取非 prerelease。测试版渠道会读取 Release 列表，并允许选择 prerelease。

仓库需要是公开仓库，或者需要另行实现不会泄露访问令牌的私有更新服务；APK 中不能安全保存 GitHub 私有仓库 Token。

## 应用内更新流程

1. 启动后延迟检查，自动检查间隔为 12 小时；
2. 稳定渠道只读取正式 Release，测试渠道包含 prerelease；
3. 发现更高 `versionCode` 后显示更新弹窗；
4. 用户点击一次“下载并安装”；
5. Android `DownloadManager` 下载到应用专属目录；
6. 校验 APK 的 SHA-256、包名、`versionCode` 和签名证书；
7. 首次更新时引导允许“安装未知应用”；
8. 自动打开 Android 系统安装确认页。

普通侧载应用不能绕过系统确认进行静默安装。此方案消除的是打开网页、寻找 Release、手动下载和从文件管理器打开 APK 的步骤。

## 更新渠道

应用设置页可切换：

- 稳定版：只接收 `main/master` 正式 Release；
- 测试版：允许接收 `work` 和内部 PR 产生的 prerelease。

切换渠道后会立即重新检查更新。
