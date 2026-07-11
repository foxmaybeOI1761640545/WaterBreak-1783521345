# 本次修改交付说明

## 已完成

- 源码版本统一为 `1.0.6` / `versionCode 7`，下一次全局版本为 `1.0.7` / `versionCode 8`。
- 发布工作流增加仓库级并发锁，跨 `main/master/work/内部 PR` 统一递增版本。
- 保留每次 push 和内部 PR 发布：`main/master` 为正式 Release，`work` 与内部 PR 为 prerelease。
- 删除 fallback keystore、Actions Cache 密钥和默认密码逻辑；缺少固定签名 Secret 时直接失败。
- 生成一套新的固定 JKS 和 GitHub Secrets 导入材料，位于 `release-secrets/`。
- 每个 Release 同时发布 `waterbreak.apk`、版本化 APK 和 `update.json`。
- 增加原生 Capacitor 应用更新插件：自动检查、稳定/测试渠道、DownloadManager 下载、进度、SHA-256/包名/versionCode/签名校验、未知来源授权和系统安装页。
- 设置页增加应用更新卡片；启动后每 12 小时自动检查并显示更新弹窗。
- Release Tag 指向写入真实版本号的源码提交，Tag 源码、APK 与 Release 版本保持一致。

## 已验证

- `npm ci`
- `npm run build`
- `npx cap sync android`
- `package.json` / `package-lock.json` / Gradle 版本一致性检查
- GitHub Actions YAML 语法解析
- 新 JKS 的 alias、密码可用性和证书 SHA-256 指纹

## 当前环境未执行

当前执行环境没有 Android SDK 和 Gradle，因此未在本地运行 `gradle assembleRelease`。项目仍按原要求由 GitHub Actions 安装 Gradle 8.11.1 和 Android SDK 完成最终 APK 构建。

## 使用前必须做

1. 阅读 `release-secrets/README.md`。
2. 将四项固定签名值写入 GitHub Actions Secrets。
3. 安全备份 JKS 和密码，确认备份后不要把 `release-secrets/` 提交到 Git。
4. 若 v1.0.6 使用旧随机 fallback 签名，首次切换新签名可能需要卸载旧版。
