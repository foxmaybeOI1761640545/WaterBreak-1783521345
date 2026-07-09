<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { App as CapacitorApp } from '@capacitor/app'
import type { PluginListenerHandle } from '@capacitor/core'
import { WaterReminder, type ReminderConfig, type ReminderSoundMode, type ReminderStatus, type PermissionStatus, type PermissionValue, type ScreenStateStatus, type ReminderType } from './plugins/WaterReminder'

type MainPage = 'water' | 'screen'
type AppPage = MainPage | 'waterSettings' | 'screenSettings' | 'permissionGuide'
type PermissionGuideKey = 'notifications' | 'exact' | 'overlay' | 'usage' | 'fullScreen' | 'waterNotification' | 'screenNotification' | 'deviceAdmin' | 'battery' | 'miuiPermissions' | 'miuiAutostart'
type PermissionGuidePhase = 'idle' | 'refreshing' | 'ready' | 'launching' | 'waitingReturn' | 'reviewing' | 'complete'
interface PermissionGuideItem { key: PermissionGuideKey; title: string; purpose: string; status: PermissionValue; applicable: boolean; required: boolean; actionLabel?: string; details?: string }

interface State {
  loading: boolean
  saving: boolean
  importingSound: ReminderType | ''
  message: string
  permissions: PermissionStatus | null
  screenState: ScreenStateStatus | null
  now: number
  nextReminderInput: string
  activePage: AppPage
  settingsSource: MainPage
  permissionGuidePhase: PermissionGuidePhase
  permissionGuideCurrentKey: PermissionGuideKey | ''
  permissionGuideSkipped: PermissionGuideKey[]
  permissionGuideWaitingToken: number
  permissionGuideDidLeaveApp: boolean
  permissionGuideMessage: string
  permissionGuideLastOpenedAt: number
  status: ReminderStatus
}

const defaultStatus: ReminderStatus = {
  enabled: false,
  startHour: 6,
  startMinute: 35,
  endHour: 23,
  endMinute: 45,
  minIntervalMinutes: 35,
  maxIntervalMinutes: 45,
  nextReminderTime: 0,
  waterNotificationTitle: '该喝水啦',
  waterNotificationText: '稳健做人，认真做事。',
  waterSoundMode: 'default',
  waterCustomSoundUri: '',
  waterCustomSoundName: '',
  waterVolumePercent: 100,
  screenLimitEnabled: true,
  screenOnLimitMinutes: 5,
  requiredScreenOffMinutes: 5,
  cancelBeforeLockCount: 5,
  cancelCycleCount: 0,
  screenSoundMode: 'default',
  screenCustomSoundUri: '',
  screenCustomSoundName: '',
  screenVolumePercent: 100,
}

const state = reactive<State>({
  loading: true,
  saving: false,
  importingSound: '',
  message: '',
  permissions: null,
  screenState: null,
  now: Date.now(),
  nextReminderInput: '',
  activePage: 'water',
  settingsSource: 'water',
  permissionGuidePhase: 'idle',
  permissionGuideCurrentKey: '',
  permissionGuideSkipped: [],
  permissionGuideWaitingToken: 0,
  permissionGuideDidLeaveApp: false,
  permissionGuideMessage: '',
  permissionGuideLastOpenedAt: 0,
  status: { ...defaultStatus },
})

const isEnabledText = computed(() => state.status.enabled ? '喝水提醒已开启' : '喝水提醒已关闭')
const screenLimitText = computed(() => state.status.screenLimitEnabled ? '屏幕超时提醒已开启' : '屏幕超时提醒已关闭')
const waterCompactStatusText = computed(() => state.status.enabled ? '已开启' : '已关闭')
const screenCompactStatusText = computed(() => state.status.screenLimitEnabled ? '已开启' : '已关闭')
const pageTitle = computed(() => ({ water: '喝水提醒', screen: '屏幕记录', waterSettings: '喝水提醒设置', screenSettings: '屏幕记录设置', permissionGuide: '权限配置' }[state.activePage]))
const nextReminderText = computed(() => formatNextReminder(state.status.nextReminderTime))
const timeRangeText = computed(() => `${pad(state.status.startHour)}:${pad(state.status.startMinute)} - ${pad(state.status.endHour)}:${pad(state.status.endMinute)}`)
const waterSoundModeText = computed(() => soundModeText(state.status.waterSoundMode, state.status.waterCustomSoundName))
const screenSoundModeText = computed(() => soundModeText(state.status.screenSoundMode, state.status.screenCustomSoundName))
const currentScreenStateText = computed(() => state.screenState?.currentScreenState === 'on' ? '亮屏中' : state.screenState?.currentScreenState === 'off' ? '息屏中' : '未知')
const currentScreenDurationText = computed(() => {
  if (!state.screenState) return '暂无记录'
  const base = state.screenState.currentScreenState === 'on' && state.screenState.lastScreenOnTime ? state.screenState.lastScreenOnTime : state.screenState.currentScreenStateSince
  return formatDuration(base ? state.now - base : 0)
})
const lastScreenOnText = computed(() => formatTimestamp(state.screenState?.lastScreenOnTime || 0))
const lastScreenOffText = computed(() => formatTimestamp(state.screenState?.lastScreenOffTime || 0))
const screenCyclePhase = computed(() => state.screenState?.cyclePhase ?? state.status.screenCyclePhase ?? 'idle')
const screenCycleCount = computed(() => state.screenState?.cycleCancelCount ?? state.status.cancelCycleCount ?? 0)
const screenCycleLimit = computed(() => state.screenState?.cycleLimit ?? state.status.screenCycleLimit ?? state.status.cancelBeforeLockCount ?? 1)
const cancelCycleText = computed(() => {
  if (screenCyclePhase.value === 'idle') return '未进入提醒循环'
  if (screenCyclePhase.value === 'alerting') return '提醒中'
  if (screenCyclePhase.value === 'force_lock') return `强制熄屏中 ${screenCycleCount.value}/${screenCycleLimit.value}`
  if (screenCyclePhase.value === 'grace') return '紧急宽限中'
  return screenCycleCount.value > 0 ? `已取消 ${screenCycleCount.value} / ${screenCycleLimit.value} 次` : '等待息屏休息'
})
const isSettingsPage = computed(() => state.activePage === 'waterSettings' || state.activePage === 'screenSettings')
const isPermissionGuidePage = computed(() => state.activePage === 'permissionGuide')

let ticker: number | undefined
let screenRefreshTicker: number | undefined
let backButtonHandle: PluginListenerHandle | undefined
let appStateHandle: PluginListenerHandle | undefined
let messageTimer: number | undefined
let refreshStatusToken = 0
let screenStateToken = 0
let componentActive = false
const contentScroller = ref<HTMLElement | null>(null)
const touchStart = reactive({ x: 0, y: 0, active: false })

function pad(value: number) { return String(value).padStart(2, '0') }
function soundModeText(mode: ReminderSoundMode, name?: string) { return mode === 'custom' ? `自定义：${name || '已导入音频'}` : '应用默认提示音' }
function formatTimestamp(timestamp: number) { return timestamp ? new Date(timestamp).toLocaleString('zh-CN', { hour12: false }) : '暂无记录' }
function formatDuration(durationMs: number) {
  if (!durationMs) return '暂无记录'
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) return `${hours} 小时 ${minutes} 分钟`
  if (minutes > 0) return `${minutes} 分钟 ${seconds} 秒`
  return `${seconds} 秒`
}
function formatNextReminder(timestamp: number) {
  if (timestamp == null || timestamp <= 0) return '未安排'
  const target = new Date(timestamp)
  const now = new Date()
  const tomorrow = new Date(now)
  tomorrow.setDate(now.getDate() + 1)
  const prefix = target.toDateString() === now.toDateString() ? '今天' : target.toDateString() === tomorrow.toDateString() ? '明天' : target.toLocaleDateString('zh-CN')
  return `${prefix} ${pad(target.getHours())}:${pad(target.getMinutes())}`
}

function buildConfig(overrides: Partial<ReminderConfig> = {}): ReminderConfig {
  return {
    enabled: Boolean(state.status.enabled),
    startHour: Number(state.status.startHour),
    startMinute: Number(state.status.startMinute),
    endHour: Number(state.status.endHour),
    endMinute: Number(state.status.endMinute),
    minIntervalMinutes: Number(state.status.minIntervalMinutes),
    maxIntervalMinutes: Number(state.status.maxIntervalMinutes),
    nextReminderTime: parseNextReminderInput(),
    waterNotificationTitle: state.status.waterNotificationTitle.trim() || '该喝水啦',
    waterNotificationText: state.status.waterNotificationText.trim() || '稳健做人，认真做事。',
    waterSoundMode: state.status.waterSoundMode,
    waterCustomSoundUri: state.status.waterCustomSoundUri,
    waterCustomSoundName: state.status.waterCustomSoundName,
    waterVolumePercent: Number(state.status.waterVolumePercent ?? 100),
    screenLimitEnabled: Boolean(state.status.screenLimitEnabled),
    screenOnLimitMinutes: Number(state.status.screenOnLimitMinutes ?? 5),
    requiredScreenOffMinutes: Number(state.status.requiredScreenOffMinutes ?? 5),
    cancelBeforeLockCount: Number(state.status.cancelBeforeLockCount ?? 5),
    screenSoundMode: state.status.screenSoundMode,
    screenCustomSoundUri: state.status.screenCustomSoundUri,
    screenCustomSoundName: state.status.screenCustomSoundName,
    screenVolumePercent: Number(state.status.screenVolumePercent ?? 100),
    ...overrides,
  }
}

function validateConfig(config: ReminderConfig) {
  if (config.startHour < 0 || config.startHour > 23 || config.endHour < 0 || config.endHour > 23) throw new Error('开始/结束小时必须在 0-23 之间')
  if (config.startMinute < 0 || config.startMinute > 59 || config.endMinute < 0 || config.endMinute > 59) throw new Error('分钟必须在 0-59 之间')
  if (config.minIntervalMinutes < 15) throw new Error('最小间隔至少 15 分钟')
  if (config.maxIntervalMinutes < config.minIntervalMinutes) throw new Error('最大间隔不可小于最小间隔')
  if (config.screenOnLimitMinutes < 0) throw new Error('亮屏超时提醒分钟数不可小于 0')
  if (config.requiredScreenOffMinutes < 1) throw new Error('连续息屏分钟数必须大于 0')
  if (config.cancelBeforeLockCount < 1) throw new Error('取消后强制熄屏次数必须大于 0')
  if (config.waterVolumePercent < 0 || config.waterVolumePercent > 100 || config.screenVolumePercent < 0 || config.screenVolumePercent > 100) throw new Error('提醒音量必须在 0-100% 之间')
  if (config.waterSoundMode === 'custom' && !config.waterCustomSoundUri) throw new Error('请先导入喝水提醒自定义提示音')
  if (config.screenSoundMode === 'custom' && !config.screenCustomSoundUri) throw new Error('请先导入屏幕提醒自定义提示音')
}

function scrollContentToTop() { nextTick(() => contentScroller.value?.scrollTo({ top: 0, behavior: 'auto' })) }
function switchPage(page: AppPage, pushHistory = false) {
  if (state.activePage === page) { scrollContentToTop(); return }
  state.activePage = page
  if (page === 'water' || page === 'screen') {
    state.settingsSource = page
    history.replaceState({ appPage: page, settingsSource: page }, '', location.href)
  } else if (pushHistory) {
    history.pushState({ appPage: page, settingsSource: state.settingsSource }, '', location.href)
  }
  scrollContentToTop()
}
function openSettings() {
  const source: MainPage = state.activePage === 'screen' ? 'screen' : 'water'
  state.settingsSource = source
  switchPage(source === 'screen' ? 'screenSettings' : 'waterSettings', true)
}
function navigateBack() {
  if (isSettingsPage.value || isPermissionGuidePage.value) {
    if (history.state?.appPage === state.activePage) {
      history.back()
    } else {
      state.activePage = state.settingsSource
      history.replaceState({ appPage: state.settingsSource, settingsSource: state.settingsSource }, '', location.href)
      scrollContentToTop()
    }
    return
  }
  CapacitorApp.minimizeApp().catch(() => undefined)
}
function closeSettings() { navigateBack() }
function handlePopState(event: PopStateEvent) {
  const page = event.state?.appPage as AppPage | undefined
  const source = event.state?.settingsSource as MainPage | undefined
  if (source === 'water' || source === 'screen') state.settingsSource = source
  if (page === 'water' || page === 'screen' || page === 'waterSettings' || page === 'screenSettings' || page === 'permissionGuide') {
    state.activePage = page
    if (page === 'water' || page === 'screen') state.settingsSource = page
    scrollContentToTop()
    return
  }
  if (isSettingsPage.value || isPermissionGuidePage.value) {
    state.activePage = state.settingsSource
    history.replaceState({ appPage: state.settingsSource, settingsSource: state.settingsSource }, '', location.href)
    scrollContentToTop()
  }
}
function isInteractiveTarget(target: EventTarget | null) {
  return target instanceof Element && Boolean(target.closest('button,input,textarea,select,label,a,.file-picker'))
}
function onTouchStart(event: TouchEvent) {
  if (isSettingsPage.value || isPermissionGuidePage.value || isInteractiveTarget(event.target) || event.touches.length !== 1) return
  const touch = event.touches[0]
  if (touch.clientX < 24 || touch.clientX > window.innerWidth - 24) return
  touchStart.x = touch.clientX; touchStart.y = touch.clientY; touchStart.active = true
}
function onTouchEnd(event: TouchEvent) {
  if (!touchStart.active || isSettingsPage.value || isPermissionGuidePage.value) return
  touchStart.active = false
  const touch = event.changedTouches?.[0]
  if (!touch) return
  const dx = touch.clientX - touchStart.x
  const dy = touch.clientY - touchStart.y
  if (Math.abs(dx) < 72 || Math.abs(dx) < Math.abs(dy) * 1.4) return
  if (dx < 0 && state.activePage === 'water') switchPage('screen')
  if (dx > 0 && state.activePage === 'screen') switchPage('water')
}


function timeValue(hour: number, minute: number) { return `${pad(hour)}:${pad(minute)}` }
function updateTime(kind: 'start' | 'end', event: Event) {
  const value = (event.target as HTMLInputElement).value
  const [hour, minute] = value.split(':').map(Number)
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return
  if (kind === 'start') { state.status.startHour = hour; state.status.startMinute = minute }
  else { state.status.endHour = hour; state.status.endMinute = minute }
}

function updateNextReminderInput() { state.nextReminderInput = state.status.nextReminderTime ? toDateTimeLocalValue(state.status.nextReminderTime) : '' }
function parseNextReminderInput() { const parsed = state.nextReminderInput ? new Date(state.nextReminderInput).getTime() : 0; return Number.isFinite(parsed) ? parsed : (state.status.nextReminderTime ?? 0) }
function toDateTimeLocalValue(timestamp: number) { const date = new Date(timestamp); return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16) }

async function refreshScreenStateOnly() {
  const token = ++screenStateToken
  try {
    const nextScreenState = await WaterReminder.getScreenState()
    if (componentActive && token === screenStateToken) state.screenState = nextScreenState
  } catch (error) {
    if (componentActive) setUserMessage(error instanceof Error ? error.message : '刷新屏幕状态失败', 3500)
  }
}
function setUserMessage(message: string, durationMs = state.activePage === 'screen' ? 2200 : 0) {
  state.message = message
  if (messageTimer) window.clearTimeout(messageTimer)
  if (durationMs > 0) messageTimer = window.setTimeout(() => { if (state.message === message) state.message = '' }, durationMs)
}

async function refreshStatus(showMessage = false) {
  const token = ++refreshStatusToken
  state.loading = true
  try {
    const [nextStatus, nextPermissions, nextScreenState] = await Promise.all([
      WaterReminder.getStatus(),
      WaterReminder.getPermissionStatus(),
      WaterReminder.getScreenState(),
    ])
    if (!componentActive || token !== refreshStatusToken) return
    state.status = { ...defaultStatus, ...nextStatus }
    state.permissions = nextPermissions
    state.screenState = nextScreenState
    updateNextReminderInput()
    if (showMessage) setUserMessage('状态已更新')
  } catch (error) {
    if (componentActive) setUserMessage(error instanceof Error ? error.message : '读取状态失败', 4000)
  } finally {
    if (componentActive && token === refreshStatusToken) state.loading = false
  }
}
async function saveConfig(message: string, overrides: Partial<ReminderConfig> = {}) {
  state.saving = true
  try {
    const config = buildConfig(overrides)
    validateConfig(config)
    state.permissions = await WaterReminder.requestNotificationPermission()
    state.status = { ...defaultStatus, ...await WaterReminder.startReminder(config) }
    updateNextReminderInput()
    setUserMessage(message)
  } catch (error) { setUserMessage(error instanceof Error ? error.message : message.includes('开启') ? '开启提醒失败' : '保存提醒设置失败', 4000) } finally { state.saving = false }
}
function saveWaterSettings() { return saveConfig('喝水提醒设置已保存') }
function saveScreenSettings() { return saveConfig('屏幕记录设置已保存') }
function enableReminder() { return saveConfig('喝水提醒已开启，可划掉应用后等待闹钟触发', { enabled: true }) }
async function disableReminder() {
  state.saving = true
  try { state.status = { ...defaultStatus, ...await WaterReminder.stopReminder() }; setUserMessage('喝水提醒已关闭') }
  catch (error) { setUserMessage(error instanceof Error ? error.message : '关闭提醒失败', 4000) }
  finally { state.saving = false }
}
function enableScreenLimit() { return saveConfig('屏幕超时提醒已开启', { screenLimitEnabled: true }) }
function disableScreenLimit() { return saveConfig('屏幕超时提醒已关闭', { screenLimitEnabled: false }) }



function channelPermissionStatus(enabled?: boolean, importance?: number): PermissionValue {
  if (!enabled) return 'denied'
  return (importance ?? 0) >= 4 ? 'granted' : 'prompt'
}
const permissionGuideItems = computed<PermissionGuideItem[]>(() => {
  const p = state.permissions
  const isXiaomi = Boolean(p?.manufacturerSettingsAvailable)
  return [
    { key: 'notifications', title: '通知运行时权限', purpose: '发送喝水和屏幕提醒通知。', status: p?.notifications ?? 'unknown', applicable: true, required: true },
    { key: 'exact', title: '精确闹钟', purpose: '划掉应用后仍按计划触发提醒。', status: p?.exactAlarms ?? 'unknown', applicable: p?.exactAlarms !== 'unavailable', required: true },
    { key: 'overlay', title: '悬浮窗', purpose: '屏幕超时时显示悬浮操作按钮。', status: p?.overlays ?? 'unknown', applicable: p?.overlays !== 'unavailable', required: true },
    { key: 'usage', title: '使用情况访问', purpose: '校准亮屏/息屏记录。', status: p?.usageStats ?? 'unknown', applicable: p?.usageStats !== 'unavailable', required: true },
    { key: 'fullScreen', title: '全屏提醒', purpose: '锁屏或息屏时弹出提醒。', status: p?.fullScreenIntent ?? 'unknown', applicable: p?.fullScreenIntent !== 'unavailable', required: true },
    { key: 'waterNotification', title: '喝水通知渠道', purpose: '渠道需开启且保持高优先级。', status: channelPermissionStatus(p?.waterChannelEnabled, p?.waterChannelImportance), applicable: true, required: true },
    { key: 'screenNotification', title: '屏幕通知渠道', purpose: '渠道需开启且保持高优先级。', status: channelPermissionStatus(p?.screenChannelEnabled, p?.screenChannelImportance), applicable: true, required: true },
    { key: 'deviceAdmin', title: '设备管理器锁屏权限', purpose: '连续取消屏幕提醒达到阈值后执行系统锁屏。', status: p?.deviceAdmin ?? 'unknown', applicable: true, required: true },
    { key: 'battery', title: '省电策略建议', purpose: '建议设为不限制，提升后台提醒稳定性。', status: p?.batteryOptimization ?? 'prompt', applicable: p?.batteryOptimization !== 'unavailable', required: false, actionLabel: '打开省电设置' },
    { key: 'miuiPermissions', title: 'MIUI/HyperOS 权限管理', purpose: '建议允许后台弹出、锁屏显示等。', status: 'optional', applicable: isXiaomi, required: false, actionLabel: '打开权限管理', details: '该项无法被普通应用可靠检测，完成后请手动确认。' },
    { key: 'miuiAutostart', title: 'MIUI/HyperOS 自启动', purpose: '建议允许自启动以提升重启后提醒可靠性。', status: 'optional', applicable: isXiaomi, required: false, actionLabel: '打开自启动', details: '非小米设备不会显示该入口。' },
  ]
})
const requiredPermissionItems = computed(() => permissionGuideItems.value.filter(item => item.required && item.applicable))
const requiredDoneCount = computed(() => requiredPermissionItems.value.filter(item => item.status === 'granted' || item.status === 'unavailable').length)
const requiredPendingItems = computed(() => requiredPermissionItems.value.filter(item => item.status !== 'granted' && item.status !== 'unavailable' && !state.permissionGuideSkipped.includes(item.key)))
const permissionSummaryText = computed(() => `已完成 ${requiredDoneCount.value}/${requiredPermissionItems.value.length}，待处理 ${requiredPendingItems.value.length} 项`)
const permissionGuideCurrent = computed(() => permissionGuideItems.value.find(item => item.key === state.permissionGuideCurrentKey) || requiredPendingItems.value[0] || permissionGuideItems.value.find(item => item.applicable))
const guideBusy = computed(() => ['refreshing', 'launching', 'waitingReturn', 'reviewing'].includes(state.permissionGuidePhase))
const guideComplete = computed(() => requiredPendingItems.value.length === 0)
function permissionStatusText(status: PermissionValue, required = true) { return status === 'granted' ? '已完成' : status === 'unavailable' ? '无需设置' : status === 'optional' || !required ? '需手动确认/可选' : status === 'prompt' ? '需调整' : status === 'unknown' ? '未知' : '待设置' }
function permissionStatusClass(status: PermissionValue) { return status === 'granted' || status === 'unavailable' ? 'ok' : status === 'optional' ? 'optional' : status === 'prompt' || status === 'unknown' ? 'pending' : 'missing' }
function selectFirstMissingPermission() { state.permissionGuideCurrentKey = requiredPendingItems.value[0]?.key || '' ; state.permissionGuidePhase = guideComplete.value ? 'complete' : 'ready' }
function openPermissionGuidePage() { switchPage('permissionGuide', true); refreshPermissionGuide(false) }
async function refreshPermissionGuide(showMessage = true) {
  const token = ++state.permissionGuideWaitingToken
  state.permissionGuidePhase = 'refreshing'
  await refreshStatus()
  if (token !== state.permissionGuideWaitingToken) return
  if (!state.permissionGuideCurrentKey || permissionGuideCurrent.value?.status === 'granted' || permissionGuideCurrent.value?.status === 'unavailable') selectFirstMissingPermission()
  else state.permissionGuidePhase = guideComplete.value ? 'complete' : 'ready'
  if (showMessage) state.permissionGuideMessage = guideComplete.value ? '必需权限已配置完成。' : '权限状态已刷新。'
}
function startPermissionGuide() { openPermissionGuidePage() }
function choosePermission(key: PermissionGuideKey) { if (guideBusy.value) return; state.permissionGuideCurrentKey = key; state.permissionGuidePhase = 'ready'; state.permissionGuideMessage = '' }
function nextPermissionItem() { if (guideBusy.value) return; selectFirstMissingPermission(); state.permissionGuideMessage = guideComplete.value ? '必需权限已配置完成。' : '已选择下一项待处理权限。' }
function skipPermissionGuideItem() { const item = permissionGuideCurrent.value; if (item?.required && !state.permissionGuideSkipped.includes(item.key)) state.permissionGuideSkipped.push(item.key); nextPermissionItem() }
function stopPermissionGuide() { state.permissionGuidePhase = 'idle'; state.permissionGuideCurrentKey = ''; state.permissionGuideMessage = '已结束权限配置，可稍后继续。' }
async function openCurrentPermissionSettings() {
  const item = permissionGuideCurrent.value
  if (!item || guideBusy.value) return
  const token = ++state.permissionGuideWaitingToken
  state.permissionGuidePhase = 'launching'
  state.permissionGuideDidLeaveApp = false
  state.permissionGuideLastOpenedAt = Date.now()
  state.permissionGuideMessage = ''
  const result = await launchPermissionIntent(item.key)
  if (token !== state.permissionGuideWaitingToken) return
  if (!result.opened) {
    state.permissionGuidePhase = 'ready'
    state.permissionGuideMessage = result.reason || '当前系统没有可打开的设置页面，请手动进入系统设置。'
    return
  }
  state.permissionGuidePhase = 'waitingReturn'
  state.permissionGuideMessage = `已打开${item.title}设置，返回后请点击“重新检查”。`
}
async function launchPermissionIntent(kind: PermissionGuideKey): Promise<{ opened: boolean; target?: string; fallback?: boolean; reason?: string }> {
  if (kind === 'notifications') { const status = await WaterReminder.requestNotificationPermission(); state.permissions = status; return { opened: true, target: 'runtime-notification' } }
  if (kind === 'exact') return WaterReminder.openExactAlarmSettings()
  if (kind === 'overlay') return WaterReminder.openOverlaySettings()
  if (kind === 'usage') return WaterReminder.openUsageAccessSettings()
  if (kind === 'fullScreen') return WaterReminder.openFullScreenIntentSettings()
  if (kind === 'waterNotification') return WaterReminder.openNotificationSettings({ type: 'water' })
  if (kind === 'screenNotification') return WaterReminder.openNotificationSettings({ type: 'screen_limit' })
  if (kind === 'deviceAdmin') return WaterReminder.openDeviceAdminSettings()
  if (kind === 'battery') return WaterReminder.openBatteryOptimizationSettings()
  if (kind === 'miuiPermissions') return WaterReminder.openManufacturerPermissionSettings({ target: 'permissions' })
  if (kind === 'miuiAutostart') return WaterReminder.openManufacturerPermissionSettings({ target: 'autostart' })
  return { opened: false, reason: '未知权限项' }
}
async function reviewPermissionReturn(source: 'native' | 'web' | 'manual') {
  if (state.permissionGuidePhase !== 'waitingReturn' && source !== 'manual') return
  const token = ++state.permissionGuideWaitingToken
  const before = permissionGuideCurrent.value
  state.permissionGuidePhase = 'reviewing'
  await refreshStatus()
  if (token !== state.permissionGuideWaitingToken) return
  const after = permissionGuideItems.value.find(item => item.key === before?.key)
  state.permissionGuidePhase = guideComplete.value ? 'complete' : 'ready'
  state.permissionGuideMessage = after?.status === 'granted' || after?.status === 'unavailable' ? '已开启。可点击“下一项”继续。' : '仍未开启或需要手动确认，可重试、跳过或结束。'
}
async function openPermissionSettings(kind: Exclude<PermissionGuideKey, 'notifications'>) {
  const result = await launchPermissionIntent(kind)
  setUserMessage(result.opened ? '已打开对应系统设置，授权后请返回应用刷新状态' : (result.reason || '无法打开系统设置'), result.opened ? 2200 : 4000)
}

async function testNotification(type: ReminderType) {
  state.saving = true
  try { state.permissions = await WaterReminder.requestNotificationPermission(); const result = await WaterReminder.showTestNotification({ type }); setUserMessage(result.ok ? `${type === 'water' ? '喝水' : '屏幕'}测试结果：通知 ${result.notification?.ok ? '成功' : '失败'}，悬浮 ${result.overlay?.ok ? '成功' : '失败'}，居中 ${result.centerDialog?.ok ? '成功' : '失败'}，声音 ${result.sound?.ok ? '成功' : '失败'}，振动 ${result.vibration?.ok ? '成功' : '失败'}` : `测试失败：${result.reason || '权限不足或渠道关闭'}`) }
  catch (error) { setUserMessage(error instanceof Error ? error.message : '测试通知失败', 4000) }
  finally { state.saving = false }
}
async function setSoundMode(type: ReminderType, mode: ReminderSoundMode) {
  state.saving = true
  try {
    if (mode === 'default') state.status = { ...defaultStatus, ...await WaterReminder.useDefaultSound({ type }) }
    else if (type === 'water' && !state.status.waterCustomSoundUri) state.message = '请先导入喝水提醒自定义音频文件'
    else if (type === 'screen_limit' && !state.status.screenCustomSoundUri) state.message = '请先导入屏幕提醒自定义音频文件'
    else state.status = { ...defaultStatus, ...await WaterReminder.startReminder(buildConfig(type === 'water' ? { waterSoundMode: 'custom' } : { screenSoundMode: 'custom' })) }
    if (!state.message.startsWith('请先')) state.message = '已切换提示音'
  } catch (error) { state.message = error instanceof Error ? error.message : '切换提示音失败' } finally { state.saving = false }
}
async function importCustomSound(type: ReminderType, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!isSupportedAudioFile(file)) { state.message = '请选择常见音频格式文件'; input.value = ''; return }
  state.importingSound = type
  try {
    state.status = { ...defaultStatus, ...await WaterReminder.saveCustomSound({ type, fileName: file.name, mimeType: file.type || inferAudioMimeType(file.name), dataBase64: await fileToBase64(file) }) }
    state.message = `已导入自定义提示音：${file.name}`
  } catch (error) { state.message = error instanceof Error ? error.message : '导入提示音失败' } finally { state.importingSound = ''; input.value = '' }
}
function isSupportedAudioFile(file: File) { return file.type.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac|flac)$/i.test(file.name) }
function inferAudioMimeType(fileName: string) { return ({ mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', m4a: 'audio/mp4', aac: 'audio/aac', flac: 'audio/flac' } as Record<string, string>)[fileName.split('.').pop()?.toLowerCase() || ''] || 'audio/mpeg' }
function fileToBase64(file: File): Promise<string> { return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result || '').split(',').pop() || ''); reader.onerror = () => reject(new Error('读取音频文件失败')); reader.readAsDataURL(file) }) }
function handleVisibilityChange() {
  if (!componentActive || document.visibilityState !== 'visible') return
  state.now = Date.now()
  if (state.permissionGuidePhase === 'waitingReturn' && !state.permissionGuideDidLeaveApp && Date.now() - state.permissionGuideLastOpenedAt > 1200) reviewPermissionReturn('web')
  else refreshStatus()
}
function handleAppStateChange(isActive: boolean) {
  if (!componentActive) return
  if (!isActive) { if (state.permissionGuidePhase === 'waitingReturn') state.permissionGuideDidLeaveApp = true; return }
  if (state.permissionGuidePhase === 'waitingReturn' && state.permissionGuideDidLeaveApp) reviewPermissionReturn('native')
  else refreshStatus()
}

onMounted(() => {
  componentActive = true
  refreshStatus()
  history.replaceState({ appPage: state.activePage, settingsSource: state.settingsSource }, '', location.href)
  ticker = window.setInterval(() => { state.now = Date.now() }, 1000)
  screenRefreshTicker = window.setInterval(refreshScreenStateOnly, 15_000)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('popstate', handlePopState)
  CapacitorApp.addListener('backButton', () => navigateBack()).then(handle => { backButtonHandle = handle })
  CapacitorApp.addListener('appStateChange', ({ isActive }) => handleAppStateChange(isActive)).then(handle => { appStateHandle = handle })
})
onUnmounted(() => {
  componentActive = false
  refreshStatusToken++
  screenStateToken++
  state.permissionGuideWaitingToken++
  if (ticker) window.clearInterval(ticker)
  if (screenRefreshTicker) window.clearInterval(screenRefreshTicker)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('popstate', handlePopState)
  backButtonHandle?.remove()
  appStateHandle?.remove()
  if (messageTimer) window.clearTimeout(messageTimer)
})
</script>

<template>
  <div class="app-shell">
    <header class="top-bar">
      <div>
        <p class="eyebrow">水息守护</p>
        <h1>{{ pageTitle }}</h1>
      </div>
      <button v-if="isSettingsPage" class="icon-button" aria-label="返回" @click="closeSettings">←</button>
      <button v-else class="icon-button" aria-label="打开设置" @click="openSettings">⚙</button>
    </header>

    <main ref="contentScroller" class="app-content" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
      <template v-if="state.activePage === 'water'">
        <section class="card dashboard-card compact-screen-card">
          <div class="screen-card-title dashboard-title"><h2>喝水提醒</h2><div><button class="ghost mini-button" @click="startPermissionGuide">权限</button><button class="ghost mini-button" @click="openSettings">设置</button><span class="status-pill compact-status" :class="{ enabled: state.status.enabled }">{{ waterCompactStatusText }}</span></div></div>
          <div class="permission-mini"><strong>权限配置</strong><span>{{ permissionSummaryText }}</span></div>
          <div class="summary-grid compact">
            <div><span>下一次提醒</span><strong>{{ nextReminderText }}</strong></div>
            <div><span>提醒时段</span><strong>{{ timeRangeText }}</strong></div>
            <div><span>随机间隔</span><strong>{{ state.status.minIntervalMinutes }} - {{ state.status.maxIntervalMinutes }} 分钟</strong></div>
            <div><span>喝水铃声</span><strong>{{ waterSoundModeText }}</strong></div>
          </div>
        </section>
        <section class="actions">
          <button :disabled="state.loading || state.saving" @click="enableReminder">开启提醒</button>
          <button class="secondary" :disabled="state.loading || state.saving" @click="disableReminder">关闭提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('water')">测试喝水提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="() => refreshStatus(true)">刷新状态</button>
        </section>
      </template>

      <template v-else-if="state.activePage === 'screen'">
        <section class="card screen-card compact-screen-card">
          <div class="screen-card-title dashboard-title"><h2>亮屏/息屏记录</h2><div><button class="ghost mini-button" @click="startPermissionGuide">权限</button><button class="ghost mini-button" @click="openSettings">设置</button><span class="status-pill compact-status" :class="{ enabled: state.status.screenLimitEnabled }">{{ screenCompactStatusText }}</span></div></div>
          <div class="summary-grid compact">
            <div><span>当前屏幕状态</span><strong>{{ currentScreenStateText }}</strong></div>
            <div><span>当前状态持续</span><strong>{{ currentScreenDurationText }}</strong></div>
            <div><span>上一次亮屏时间</span><strong>{{ lastScreenOnText }}</strong></div>
            <div><span>上一次息屏时间</span><strong>{{ lastScreenOffText }}</strong></div>
            <div><span>亮屏超时阈值</span><strong>{{ state.status.screenOnLimitMinutes ? `${state.status.screenOnLimitMinutes} 分钟` : '未设置' }}</strong></div>
            <div><span>息屏时长阈值</span><strong>{{ state.status.requiredScreenOffMinutes }} 分钟</strong></div>
            <div><span>当前循环</span><strong>{{ cancelCycleText }}</strong></div>
            <div><span>屏幕铃声</span><strong>{{ screenSoundModeText }}</strong></div>
          </div>
          <details class="sound-note compact-note"><summary>ⓘ 记录说明</summary>{{ state.screenState?.trackingNote || '打开应用后开始记录屏幕亮灭状态。' }}</details>
        </section>
        <section class="actions">
          <button :disabled="state.loading || state.saving" @click="enableScreenLimit">开启屏幕提醒</button>
          <button class="secondary" :disabled="state.loading || state.saving" @click="disableScreenLimit">关闭屏幕提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('screen_limit')">测试屏幕提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="() => refreshStatus(true)">刷新屏幕记录</button>
        </section>
      </template>

      <template v-else-if="state.activePage === 'permissionGuide'">
        <section class="card permission-guide-page">
          <div class="guide-header"><div><h2>权限配置</h2><p>点击“去设置”才会离开应用；返回后只刷新当前项，不会自动跳转下一页。</p></div><button class="ghost" :disabled="guideBusy" @click="() => refreshPermissionGuide()">重新检查</button></div>
          <div class="guide-progress"><strong>{{ permissionSummaryText }}</strong><span>厂商优化为可选项，不计入必需完成进度。</span></div>
          <div v-if="permissionGuideCurrent" class="guide-current">
            <strong>当前步骤：{{ permissionGuideCurrent.title }}</strong>
            <span>{{ permissionGuideCurrent.purpose }}</span>
            <p v-if="state.permissionGuideMessage">{{ state.permissionGuideMessage }}</p>
            <div>
              <button :disabled="guideBusy || permissionGuideCurrent.status === 'granted' || permissionGuideCurrent.status === 'unavailable'" @click="openCurrentPermissionSettings">去设置</button>
              <button class="ghost" :disabled="guideBusy" @click="reviewPermissionReturn('manual')">重新检查</button>
              <button class="ghost" :disabled="guideBusy || !permissionGuideCurrent.required" @click="skipPermissionGuideItem">跳过</button>
              <button class="secondary" :disabled="guideBusy" @click="stopPermissionGuide">结束</button>
              <button class="ghost" :disabled="guideBusy" @click="nextPermissionItem">下一项</button>
            </div>
          </div>
          <h3>必需权限</h3>
          <div class="permission-list compact-list"><button v-for="item in permissionGuideItems.filter(i => i.required && i.applicable)" :key="item.key" class="permission-row" :class="{ selected: item.key === state.permissionGuideCurrentKey }" :disabled="guideBusy" @click="choosePermission(item.key)"><span><strong>{{ item.title }}</strong><small>{{ item.purpose }}</small></span><em :class="permissionStatusClass(item.status)">{{ permissionStatusText(item.status, item.required) }}</em></button></div>
          <h3>厂商优化建议（可选）</h3>
          <div class="permission-list compact-list"><button v-for="item in permissionGuideItems.filter(i => !i.required && i.applicable)" :key="item.key" class="permission-row optional-row" :class="{ selected: item.key === state.permissionGuideCurrentKey }" :disabled="guideBusy" @click="choosePermission(item.key)"><span><strong>{{ item.title }}</strong><small>{{ item.purpose }}</small><details v-if="item.details"><summary>查看说明</summary>{{ item.details }}</details></span><em :class="permissionStatusClass(item.status)">{{ permissionStatusText(item.status, item.required) }}</em></button></div>
        </section>
      </template>

      <template v-else-if="state.activePage === 'waterSettings'">
        <section class="card form-card">
          <h2>喝水提醒设置</h2>
          <div class="form-grid">
            <label>开始时间<input :value="timeValue(state.status.startHour, state.status.startMinute)" type="time" @input="updateTime('start', $event)" /></label>
            <label>结束时间<input :value="timeValue(state.status.endHour, state.status.endMinute)" type="time" @input="updateTime('end', $event)" /></label>
            <label>最小间隔（分钟）<input v-model.number="state.status.minIntervalMinutes" type="number" min="15" max="360" /></label>
            <label>最大间隔（分钟）<input v-model.number="state.status.maxIntervalMinutes" type="number" min="15" max="360" /></label>
            <label>下次提醒时间<input v-model="state.nextReminderInput" type="datetime-local" /></label>
          </div>
          <label>通知标题<input v-model="state.status.waterNotificationTitle" type="text" /></label>
          <label>通知内容<textarea v-model="state.status.waterNotificationText" rows="3" /></label>
        </section>
        <section class="card form-card">
          <h2>喝水提醒铃声</h2>
          <div class="sound-status-list"><div><span>提示音</span><strong>{{ waterSoundModeText }}</strong></div><div><span>喝水音量</span><strong>{{ state.status.waterVolumePercent }}%</strong></div><div><span>音量说明</span><strong>应用内播放器音量，仍受系统铃声/勿扰约束</strong></div><div><span>通知权限</span><strong>{{ state.permissions?.notifications ?? 'unknown' }}</strong></div><div><span>全屏提醒权限</span><strong>{{ state.permissions?.fullScreenIntent ?? 'unknown' }}</strong></div><div><span>精确闹钟权限</span><strong>{{ state.permissions?.exactAlarms ?? 'unknown' }}</strong></div></div>
          <label>喝水提醒音量 {{ state.status.waterVolumePercent }}%<input v-model.number="state.status.waterVolumePercent" type="range" min="0" max="100" /></label>
          <div class="sound-options">
            <button class="ghost" :class="{ selected: state.status.waterSoundMode === 'default' }" :disabled="state.loading || state.saving" @click="setSoundMode('water', 'default')">使用系统默认提示音</button>
            <label class="file-picker">导入自定义音频<input type="file" accept="audio/*,.mp3,.wav,.ogg,.m4a,.aac,.flac" :disabled="state.loading || state.importingSound === 'water'" @change="importCustomSound('water', $event)" /></label>
          </div>
        </section>
        <section class="actions"><button :disabled="state.loading || state.saving" @click="saveWaterSettings">保存设置</button><button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('water')">测试喝水提醒</button><button class="ghost" @click="openPermissionSettings('exact')">精确闹钟设置</button><button class="ghost" @click="openPermissionSettings('overlay')">悬浮窗设置</button><button class="ghost" @click="openPermissionSettings('fullScreen')">全屏提醒设置</button><button class="ghost" @click="openPermissionSettings('waterNotification')">喝水通知设置</button></section>
      </template>

      <template v-else>
        <section class="card form-card">
          <h2>屏幕记录设置</h2>
          <div class="form-grid">
            <label>屏幕超时提醒启用<select v-model="state.status.screenLimitEnabled"><option :value="true">开启</option><option :value="false">关闭</option></select></label>
            <label>亮屏时长阈值（分钟）<input v-model.number="state.status.screenOnLimitMinutes" type="number" min="0" max="1440" /></label>
            <label>连续息屏恢复（分钟）<input v-model.number="state.status.requiredScreenOffMinutes" type="number" min="1" max="1440" /></label>
            <label>取消几次后强制熄屏<input v-model.number="state.status.cancelBeforeLockCount" type="number" min="1" max="99" /></label>
          </div>
          <p class="sound-note">屏幕提醒会显示“熄屏”和“取消”按钮；连续取消达到设置次数后会尝试执行设备管理锁屏。</p>
        </section>
        <section class="card form-card">
          <h2>屏幕提醒铃声</h2>
          <div class="sound-status-list"><div><span>提示音</span><strong>{{ screenSoundModeText }}</strong></div><div><span>屏幕音量</span><strong>{{ state.status.screenVolumePercent }}%</strong></div><div><span>音量说明</span><strong>应用内播放器音量，仍受系统铃声/勿扰约束</strong></div><div><span>通知权限</span><strong>{{ state.permissions?.notifications ?? 'unknown' }}</strong></div><div><span>全屏提醒权限</span><strong>{{ state.permissions?.fullScreenIntent ?? 'unknown' }}</strong></div><div><span>悬浮窗权限</span><strong>{{ state.permissions?.overlays ?? 'unknown' }}</strong></div><div><span>使用情况权限</span><strong>{{ state.permissions?.usageStats ?? 'unknown' }}</strong></div><div><span>精确闹钟权限</span><strong>{{ state.permissions?.exactAlarms ?? 'unknown' }}</strong></div></div>
          <label>屏幕提醒音量 {{ state.status.screenVolumePercent }}%<input v-model.number="state.status.screenVolumePercent" type="range" min="0" max="100" /></label>
          <div class="sound-options">
            <button class="ghost" :class="{ selected: state.status.screenSoundMode === 'default' }" :disabled="state.loading || state.saving" @click="setSoundMode('screen_limit', 'default')">使用系统默认提示音</button>
            <label class="file-picker">导入自定义音频<input type="file" accept="audio/*,.mp3,.wav,.ogg,.m4a,.aac,.flac" :disabled="state.loading || state.importingSound === 'screen_limit'" @change="importCustomSound('screen_limit', $event)" /></label>
          </div>
        </section>
        <section class="actions"><button :disabled="state.loading || state.saving" @click="saveScreenSettings">保存设置</button><button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('screen_limit')">测试屏幕提醒</button><button class="ghost" @click="openPermissionSettings('usage')">使用情况权限</button><button class="ghost" @click="openPermissionSettings('exact')">精确闹钟设置</button><button class="ghost" @click="openPermissionSettings('overlay')">悬浮窗设置</button><button class="ghost" @click="openPermissionSettings('fullScreen')">全屏提醒设置</button><button class="ghost" @click="openPermissionSettings('screenNotification')">屏幕通知设置</button></section>
      </template>

      <p v-if="state.message && state.activePage !== 'screen'" class="message">{{ state.message }}</p>
      <p v-if="state.activePage !== 'screen'" class="hint">如使用 MIUI/HyperOS，请允许通知、悬浮通知、自启动、熄屏/锁屏与不限制省电，以提升提醒稳定性。</p>
      <p v-if="state.message && state.activePage === 'screen'" class="snackbar">{{ state.message }}</p>
    </main>

    <nav v-if="!isSettingsPage && !isPermissionGuidePage" class="bottom-tabs" aria-label="主功能切换">
      <button :class="{ active: state.activePage === 'water' || state.activePage === 'waterSettings' }" @click="switchPage('water')">💧<span>喝水提醒</span></button>
      <button :class="{ active: state.activePage === 'screen' || state.activePage === 'screenSettings' }" @click="switchPage('screen')">📱<span>屏幕记录</span></button>
    </nav>
  </div>
</template>
