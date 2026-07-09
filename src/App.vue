<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { App as CapacitorApp } from '@capacitor/app'
import type { PluginListenerHandle } from '@capacitor/core'
import { WaterReminder, type ReminderConfig, type ReminderSoundMode, type ReminderStatus, type PermissionStatus, type PermissionValue, type ScreenStateStatus, type ReminderType } from './plugins/WaterReminder'

type MainPage = 'water' | 'screen'
type AppPage = MainPage | 'waterSettings' | 'screenSettings'
type PermissionGuideKey = 'notifications' | 'exact' | 'overlay' | 'usage' | 'fullScreen' | 'waterNotification' | 'screenNotification' | 'deviceAdmin' | 'manufacturer'
interface PermissionGuideItem { key: PermissionGuideKey; title: string; purpose: string; status: PermissionValue; applicable: boolean }

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
  permissionGuideActive: boolean
  permissionGuideIndex: number
  permissionGuideSkipped: PermissionGuideKey[]
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
  permissionGuideActive: false,
  permissionGuideIndex: 0,
  permissionGuideSkipped: [],
  status: { ...defaultStatus },
})

const isEnabledText = computed(() => state.status.enabled ? '喝水提醒已开启' : '喝水提醒已关闭')
const screenLimitText = computed(() => state.status.screenLimitEnabled ? '屏幕超时提醒已开启' : '屏幕超时提醒已关闭')
const pageTitle = computed(() => ({ water: '喝水提醒', screen: '屏幕记录', waterSettings: '喝水提醒设置', screenSettings: '屏幕记录设置' }[state.activePage]))
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
const cancelCycleText = computed(() => {
  const total = state.status.cancelBeforeLockCount ?? 1
  const current = Math.min((state.status.cancelCycleCount ?? 0), total)
  return current > 0 ? `已取消 ${current} / ${total} 次` : '未进入提醒循环'
})
const isSettingsPage = computed(() => state.activePage === 'waterSettings' || state.activePage === 'screenSettings')

let ticker: number | undefined
let screenRefreshTicker: number | undefined
let backButtonHandle: PluginListenerHandle | undefined
let appStateHandle: PluginListenerHandle | undefined
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
  if (isSettingsPage.value) {
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
  if (page === 'water' || page === 'screen' || page === 'waterSettings' || page === 'screenSettings') {
    state.activePage = page
    if (page === 'water' || page === 'screen') state.settingsSource = page
    scrollContentToTop()
    return
  }
  if (isSettingsPage.value) {
    state.activePage = state.settingsSource
    history.replaceState({ appPage: state.settingsSource, settingsSource: state.settingsSource }, '', location.href)
    scrollContentToTop()
  }
}
function isInteractiveTarget(target: EventTarget | null) {
  return target instanceof Element && Boolean(target.closest('button,input,textarea,select,label,a,.file-picker'))
}
function onTouchStart(event: TouchEvent) {
  if (isSettingsPage.value || isInteractiveTarget(event.target) || event.touches.length !== 1) return
  const touch = event.touches[0]
  if (touch.clientX < 24 || touch.clientX > window.innerWidth - 24) return
  touchStart.x = touch.clientX; touchStart.y = touch.clientY; touchStart.active = true
}
function onTouchEnd(event: TouchEvent) {
  if (!touchStart.active || isSettingsPage.value) return
  touchStart.active = false
  const touch = event.changedTouches[0]
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

async function refreshScreenStateOnly() { try { state.screenState = await WaterReminder.getScreenState() } catch { /* keep current UI */ } }
async function refreshStatus() {
  state.loading = true
  try {
    state.status = { ...defaultStatus, ...await WaterReminder.getStatus() }
    state.permissions = await WaterReminder.getPermissionStatus()
    state.screenState = await WaterReminder.getScreenState()
    updateNextReminderInput()
    state.message = '状态已更新'
  } catch (error) { state.message = error instanceof Error ? error.message : '读取状态失败' } finally { state.loading = false }
}
async function saveConfig(message: string, overrides: Partial<ReminderConfig> = {}) {
  state.saving = true
  try {
    const config = buildConfig(overrides)
    validateConfig(config)
    state.permissions = await WaterReminder.requestNotificationPermission()
    state.status = { ...defaultStatus, ...await WaterReminder.startReminder(config) }
    updateNextReminderInput()
    state.message = message
  } catch (error) { state.message = error instanceof Error ? error.message : message.includes('开启') ? '开启提醒失败' : '保存提醒设置失败' } finally { state.saving = false }
}
function saveWaterSettings() { return saveConfig('喝水提醒设置已保存') }
function saveScreenSettings() { return saveConfig('屏幕记录设置已保存') }
function enableReminder() { return saveConfig('喝水提醒已开启，可划掉应用后等待闹钟触发', { enabled: true }) }
async function disableReminder() {
  state.saving = true
  try { state.status = { ...defaultStatus, ...await WaterReminder.stopReminder() }; state.message = '喝水提醒已关闭' }
  catch (error) { state.message = error instanceof Error ? error.message : '关闭提醒失败' }
  finally { state.saving = false }
}
function enableScreenLimit() { return saveConfig('屏幕超时提醒已开启', { screenLimitEnabled: true }) }
function disableScreenLimit() { return saveConfig('屏幕超时提醒已关闭', { screenLimitEnabled: false }) }


const permissionGuideItems = computed<PermissionGuideItem[]>(() => {
  const p = state.permissions
  const channelReady = (kind: 'water' | 'screen') => kind === 'water' ? Boolean(p?.waterChannelEnabled) : Boolean(p?.screenChannelEnabled)
  return [
    { key: 'notifications' as PermissionGuideKey, title: '通知运行时权限', purpose: '用于发送喝水和屏幕提醒通知。', status: p?.notifications ?? 'unknown', applicable: true },
    { key: 'exact' as PermissionGuideKey, title: '精确闹钟', purpose: '应用被划掉后仍按计划触发提醒。', status: p?.exactAlarms ?? 'unknown', applicable: p?.exactAlarms !== 'unavailable' },
    { key: 'overlay' as PermissionGuideKey, title: '悬浮窗', purpose: '屏幕超时提醒时显示悬浮操作按钮。', status: p?.overlays ?? 'unknown', applicable: p?.overlays !== 'unavailable' },
    { key: 'usage' as PermissionGuideKey, title: '使用情况访问', purpose: '恢复进程后校准亮屏/息屏记录。', status: p?.usageStats ?? 'unknown', applicable: p?.usageStats !== 'unavailable' },
    { key: 'fullScreen' as PermissionGuideKey, title: '全屏提醒', purpose: '锁屏或息屏时尽可能弹出居中提醒。', status: p?.fullScreenIntent ?? 'unknown', applicable: p?.fullScreenIntent !== 'unavailable' },
    { key: 'waterNotification' as PermissionGuideKey, title: '喝水通知渠道', purpose: '确保喝水提醒渠道未被关闭。', status: channelReady('water') ? 'granted' : 'denied', applicable: true },
    { key: 'screenNotification' as PermissionGuideKey, title: '屏幕通知渠道', purpose: '确保屏幕提醒渠道未被关闭。', status: channelReady('screen') ? 'granted' : 'denied', applicable: true },
    { key: 'deviceAdmin' as PermissionGuideKey, title: '设备管理器锁屏权限', purpose: '连续取消屏幕提醒达到阈值后执行系统锁屏。', status: p?.deviceAdmin ?? 'unknown', applicable: true },
    { key: 'manufacturer' as PermissionGuideKey, title: '省电/自启动/后台弹出建议项', purpose: 'MIUI/HyperOS 等系统上提升后台提醒稳定性。', status: 'prompt' as const, applicable: Boolean(p?.manufacturerSettingsAvailable) },
  ]
})
const permissionGuideCurrent = computed(() => permissionGuideItems.value[state.permissionGuideIndex])
function permissionStatusText(status: PermissionValue) { return status === 'granted' ? '已完成' : status === 'unavailable' ? '无需设置' : status === 'prompt' ? '待设置' : status === 'unknown' ? '未知' : '待设置' }
function permissionStatusClass(status: PermissionValue) { return status === 'granted' || status === 'unavailable' ? 'ok' : status === 'prompt' || status === 'unknown' ? 'pending' : 'missing' }
function guideDisplayStatus(item: { applicable: boolean; status: PermissionValue }) { return item.applicable ? item.status : 'unavailable' }
function nextMissingPermissionIndex(from = 0) { return permissionGuideItems.value.findIndex((item, index) => index >= from && item.applicable && item.status !== 'granted' && item.status !== 'unavailable' && !state.permissionGuideSkipped.includes(item.key)) }
async function startPermissionGuide() { state.permissionGuideActive = true; state.permissionGuideSkipped = []; await refreshStatus(); state.permissionGuideIndex = Math.max(0, nextMissingPermissionIndex(0)); await continuePermissionGuide() }
async function continuePermissionGuide() {
  await refreshStatus()
  const next = nextMissingPermissionIndex(state.permissionGuideIndex)
  if (next < 0) { state.permissionGuideActive = false; state.message = '权限向导已完成或已跳过当前缺失项'; return }
  state.permissionGuideIndex = next
  const item = permissionGuideItems.value[next]
  if (item.key === 'notifications') {
    state.permissions = await WaterReminder.requestNotificationPermission()
    if (state.permissions.notifications === 'granted') { state.permissionGuideIndex += 1; await continuePermissionGuide() }
  } else await openPermissionSettings(item.key as Exclude<PermissionGuideKey, 'notifications'>, true)
}
function skipPermissionGuideItem() { const item = permissionGuideCurrent.value; if (item) state.permissionGuideSkipped.push(item.key); state.permissionGuideIndex += 1; continuePermissionGuide() }
function stopPermissionGuide() { state.permissionGuideActive = false; state.message = '已退出权限向导，可稍后继续配置' }

async function openPermissionSettings(kind: Exclude<PermissionGuideKey, 'notifications'>, fromGuide = false) {
  try {
    if (kind === 'exact') await WaterReminder.openExactAlarmSettings()
    if (kind === 'overlay') await WaterReminder.openOverlaySettings()
    if (kind === 'usage') await WaterReminder.openUsageAccessSettings()
    if (kind === 'fullScreen') await WaterReminder.openFullScreenIntentSettings()
    if (kind === 'waterNotification') await WaterReminder.openNotificationSettings({ type: 'water' })
    if (kind === 'screenNotification') await WaterReminder.openNotificationSettings({ type: 'screen_limit' })
    if (kind === 'deviceAdmin') await WaterReminder.openDeviceAdminSettings()
    if (kind === 'manufacturer') await WaterReminder.openManufacturerPermissionSettings()
    state.message = fromGuide ? '已打开系统设置；返回应用后会刷新状态，可继续、跳过或退出' : '已打开对应系统设置，授权后请返回应用刷新状态'
  } catch (error) {
    state.message = error instanceof Error ? error.message : '无法打开系统设置'
  }
}

async function testNotification(type: ReminderType) {
  state.saving = true
  try { state.permissions = await WaterReminder.requestNotificationPermission(); const result = await WaterReminder.showTestNotification({ type }); state.message = result.ok ? `${type === 'water' ? '喝水' : '屏幕'}测试结果：通知 ${result.notification?.ok ? '成功' : '失败'}，悬浮 ${result.overlay?.ok ? '成功' : '失败'}，居中 ${result.centerDialog?.ok ? '成功' : '失败'}，声音 ${result.sound?.ok ? '成功' : '失败'}，振动 ${result.vibration?.ok ? '成功' : '失败'}` : `测试失败：${result.reason || '权限不足或渠道关闭'}` }
  catch (error) { state.message = error instanceof Error ? error.message : '测试通知失败' }
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
function handleVisibilityChange() { if (document.visibilityState === 'visible') { state.now = Date.now(); refreshStatus().then(() => { if (state.permissionGuideActive) { const next = nextMissingPermissionIndex(state.permissionGuideIndex); if (next < 0) state.permissionGuideActive = false; else if (next > state.permissionGuideIndex) { state.permissionGuideIndex = next; continuePermissionGuide() } else state.permissionGuideIndex = next } }) } }

onMounted(() => {
  refreshStatus()
  history.replaceState({ appPage: state.activePage, settingsSource: state.settingsSource }, '', location.href)
  ticker = window.setInterval(() => { state.now = Date.now() }, 1000)
  screenRefreshTicker = window.setInterval(refreshScreenStateOnly, 15_000)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('popstate', handlePopState)
  CapacitorApp.addListener('backButton', () => navigateBack()).then(handle => { backButtonHandle = handle })
  CapacitorApp.addListener('appStateChange', ({ isActive }) => { if (isActive) handleVisibilityChange() }).then(handle => { appStateHandle = handle })
})
onUnmounted(() => {
  if (ticker) window.clearInterval(ticker)
  if (screenRefreshTicker) window.clearInterval(screenRefreshTicker)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('popstate', handlePopState)
  backButtonHandle?.remove()
  appStateHandle?.remove()
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
      <section class="card permission-guide">
        <div class="guide-header"><div><h2>一键配置所需权限</h2><p>逐项引导到系统官方设置页，返回后自动刷新状态。</p></div><button :disabled="state.loading || state.saving" @click="startPermissionGuide">开始配置</button></div>
        <div v-if="state.permissionGuideActive && permissionGuideCurrent" class="guide-current"><strong>当前：{{ permissionGuideCurrent.title }}</strong><span>{{ permissionGuideCurrent.purpose }}</span><div><button class="ghost" @click="continuePermissionGuide">重试/继续</button><button class="ghost" @click="skipPermissionGuideItem">跳过</button><button class="secondary" @click="stopPermissionGuide">退出</button></div></div>
        <div class="permission-list"><div v-for="item in permissionGuideItems" :key="item.key" class="permission-row"><span><strong>{{ item.title }}</strong><small>{{ item.purpose }}</small></span><em :class="permissionStatusClass(guideDisplayStatus(item))">{{ permissionStatusText(guideDisplayStatus(item)) }}</em></div></div>
      </section>
      <template v-if="state.activePage === 'water'">
        <section class="hero card">
          <p class="subtitle">到点后会显示喝水提醒弹窗，确认后关闭；不会触发熄屏或取消计数。</p>
          <div class="status-pill" :class="{ enabled: state.status.enabled }">{{ isEnabledText }}</div>
        </section>
        <section class="card summary-grid">
          <div><span>下一次提醒</span><strong>{{ nextReminderText }}</strong></div>
          <div><span>提醒时段</span><strong>{{ timeRangeText }}</strong></div>
          <div><span>随机间隔</span><strong>{{ state.status.minIntervalMinutes }} - {{ state.status.maxIntervalMinutes }} 分钟</strong></div>
          <div><span>喝水铃声</span><strong>{{ waterSoundModeText }}</strong></div>
        </section>
        <section class="actions">
          <button :disabled="state.loading || state.saving" @click="enableReminder">开启提醒</button>
          <button class="secondary" :disabled="state.loading || state.saving" @click="disableReminder">关闭提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('water')">测试喝水提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="refreshStatus">刷新状态</button>
        </section>
      </template>

      <template v-else-if="state.activePage === 'screen'">
        <section class="card screen-card">
          <h2>亮屏/息屏记录</h2>
          <div class="status-pill" :class="{ enabled: state.status.screenLimitEnabled }">{{ screenLimitText }}</div>
          <div class="summary-grid compact">
            <div><span>当前屏幕状态</span><strong>{{ currentScreenStateText }}</strong></div>
            <div><span>当前状态持续</span><strong>{{ currentScreenDurationText }}</strong></div>
            <div><span>上一次亮屏时间</span><strong>{{ lastScreenOnText }}</strong></div>
            <div><span>上一次息屏时间</span><strong>{{ lastScreenOffText }}</strong></div>
            <div><span>亮屏超时阈值</span><strong>{{ state.status.screenOnLimitMinutes ? `${state.status.screenOnLimitMinutes} 分钟` : '未设置' }}</strong></div>
            <div><span>当前循环</span><strong>{{ cancelCycleText }}</strong></div>
            <div><span>屏幕铃声</span><strong>{{ screenSoundModeText }}</strong></div>
          </div>
          <p class="sound-note">状态：后台由闹钟核对亮灭屏；未授权时可能延迟。</p>
          <details class="sound-note"><summary>查看记录说明</summary>{{ state.screenState?.trackingNote || '打开应用后开始记录屏幕亮灭状态。' }}</details>
        </section>
        <section class="actions">
          <button :disabled="state.loading || state.saving" @click="enableScreenLimit">开启屏幕提醒</button>
          <button class="secondary" :disabled="state.loading || state.saving" @click="disableScreenLimit">关闭屏幕提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('screen_limit')">测试屏幕提醒</button>
          <button class="ghost" :disabled="state.loading || state.saving" @click="refreshStatus">刷新屏幕记录</button>
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

      <p v-if="state.message" class="message">{{ state.message }}</p>
      <p class="hint">如使用 MIUI/HyperOS，请允许通知、悬浮通知、自启动、熄屏/锁屏与不限制省电，以提升提醒稳定性。</p>
    </main>

    <nav v-if="!isSettingsPage" class="bottom-tabs" aria-label="主功能切换">
      <button :class="{ active: state.activePage === 'water' || state.activePage === 'waterSettings' }" @click="switchPage('water')">💧<span>喝水提醒</span></button>
      <button :class="{ active: state.activePage === 'screen' || state.activePage === 'screenSettings' }" @click="switchPage('screen')">📱<span>屏幕记录</span></button>
    </nav>
  </div>
</template>
