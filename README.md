# 水息守护

使用 Vue 3、Vite、TypeScript、Capacitor Android 与 Kotlin Capacitor Plugin 开发的 Android 喝水及亮屏超时提醒应用。

## 主要功能

- 在设定时段内按随机间隔安排喝水提醒。
- 使用 `AlarmManager + BroadcastReceiver`，应用从最近任务划掉后仍可触发喝水提醒，不依赖常驻前台服务。
- 使用系统 Usage Events 恢复最近亮屏/息屏事件，并通过一次性闹钟检查连续亮屏时长。
- 前台使用原生提醒页；后台优先显示短时悬浮窗，失败时退回高优先级通知。
- 固定喝水、亮屏提醒通知渠道，自动清理旧版重复渠道。
- 开机、应用升级、时间/时区变化及精确闹钟授权变化后重新安排提醒。
- Android 系统返回键或侧滑返回会先退出设置页，根页面才最小化应用。
- 自动检查 GitHub Release，在应用内下载、校验并打开系统安装确认页。

## 需要的系统权限

基础通知需要通知权限。为提高后台可靠性，建议同时允许：

- 精确闹钟与提醒；
- 使用情况访问权限（用于恢复亮灭屏事件）；
- 显示在其他应用上层（用于悬浮提醒）；
- 全屏提醒（锁屏通知兜底）；
- 厂商系统中的自启动及“不限制”省电策略。

从最近任务划掉不等于系统“强行停止”。被强行停止后，Android 会阻止接收器和已安排任务继续运行，重新打开应用后才会恢复安排。

## 本地开发

```bash
npm ci
npm run build
npx cap sync android
```

如本机已安装 Android SDK 和兼容 Gradle，可执行：

```bash
cd android && gradle assembleDebug
```

## GitHub Actions

- `.github/workflows/android-build.yml`：构建 Debug APK 并上传 artifact。
- `.github/workflows/android-release.yml`：使用固定签名构建 Release APK；`main/master` 发布正式版，`work` 与内部 PR 发布 prerelease。
- 发布版本从源码 `v1.0.6` 开始全局递增，下一个版本为 `v1.0.7`。
- 详细配置见 [`docs/BUILD_RELEASE_UPDATE.md`](docs/BUILD_RELEASE_UPDATE.md)。
- 本次交付的固定签名材料位于被 Git 忽略的 `release-secrets/`，配置完成后请单独安全备份并从工作目录删除。
