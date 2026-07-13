<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import { App as CapacitorApp } from '@capacitor/app'
import type { PluginListenerHandle } from '@capacitor/core'
import { AppUpdate, type UpdateChannel, type UpdateState } from './plugins/AppUpdate'
import { WaterReminder, type ReminderConfig, type ReminderSoundMode, type ReminderStatus, type PermissionStatus, type PermissionValue, type ScreenStateStatus, type ReminderType, type WaterCheckInHistory, type WaterCheckInRecord, type WaterContainer } from './plugins/WaterReminder'

type MainPage = 'water' | 'screen'
type AppPage = MainPage | 'waterSettings' | 'screenSettings' | 'permissionGuide' | 'waterCheckIn' | 'waterHistory'
type WaterAmountMode = 'volume' | 'container'
type WaterDrinkType = '白水' | '冲剂' | '药物' | '饮料' | '茶水' | '果茶' | '奶茶' | '其他'
type PermissionGuideKey = 'notifications' | 'exact' | 'overlay' | 'usage' | 'fullScreen' | 'waterNotification' | 'screenNotification' | 'deviceAdmin' | 'battery' | 'miuiPermissions' | 'miuiAutostart'
type PermissionGuidePhase = 'idle' | 'refreshing' | 'ready' | 'launching' | 'waitingReturn' | 'reviewing' | 'complete'
interface PermissionGuideItem { key: PermissionGuideKey; title: string; purpose: string; status: PermissionValue; applicable: boolean; required: boolean; actionLabel?: string; details?: string }
interface SelectedWaterPhoto { id: string; name: string; mimeType: string; dataBase64: string }

interface State {
  loading: boolean
  saving: boolean
  importingSound: ReminderType | ''
  message: string
  permissions: PermissionStatus | null
  screenState: ScreenStateStatus | null
  screenDataStale: boolean
  waterHistory: WaterCheckInHistory
  waterDeletedRecords: WaterCheckInRecord[]
  waterContainers: WaterContainer[]
  waterHistoryImages: Record<string, string[]>
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
  waterRetryMinutes: 10,
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

const appUpdate = reactive<UpdateState>({
  currentVersionName: '1.0.8',
  currentVersionCode: 9,
  repository: '',
  channel: 'stable',
  available: false,
  status: 'idle',
  progress: 0,
  downloadedBytes: 0,
  totalBytes: 0,
})
const updateDialogDismissed = ref(false)
const updateBusy = ref(false)
const updatePanelOpen = ref(false)
const githubTestBusy = ref(false)
const githubTestResult = reactive({ tested: false, ok: false, message: '', latencyMs: 0 })
const githubConnectionToast = ref('')
const installAfterDownload = ref(false)
const retryInstallOnResume = ref(false)
const photoPreviewOpen = ref(false)
const photoPreviewIndex = ref(0)
const photoSourceDialogOpen = ref(false)
const waterGalleryInput = ref<HTMLInputElement | null>(null)
const waterCameraInput = ref<HTMLInputElement | null>(null)
const historyShowingTrash = ref(false)
const HISTORY_HIDE_NOT_DRANK_KEY = 'water-history-hide-not-drank'
const historyHideNotDrank = ref(readStoredBoolean(HISTORY_HIDE_NOT_DRANK_KEY))
const historySelectedDayKey = ref('')
const expandedHistoryDays = reactive<Record<string, boolean>>({})
const editingRecordId = ref('')
const editingDescription = ref('')

const state = reactive<State>({
  loading: true,
  saving: false,
  importingSound: '',
  message: '',
  permissions: null,
  screenState: null,
  screenDataStale: false,
  waterHistory: { consecutiveNotDrank: 0, requiresStatePhoto: false, todayTotalMl: 0, todayRecordCount: 0, lastDrankAt: 0, records: [] },
  waterDeletedRecords: [],
  waterContainers: [],
  waterHistoryImages: {},
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

const waterCheckIn = reactive({
  sessionId: '',
  isTest: false,
  action: 'prompt' as 'prompt' | 'drank' | 'forced_state',
  amountMode: 'container' as WaterAmountMode,
  directMl: null as number | null,
  totalWeightGrams: null as number | null,
  containerId: '',
  drinkType: '白水' as WaterDrinkType,
  description: '',
  photos: [] as SelectedWaterPhoto[],
  submitting: false,
  message: '',
})

const isEnabledText = computed(() => state.status.enabled ? '喝水提醒已开启' : '喝水提醒已关闭')
const screenLimitText = computed(() => state.status.screenLimitEnabled ? '屏幕超时提醒已开启' : '屏幕超时提醒已关闭')
const screenLimitShortText = computed(() => state.status.screenLimitEnabled ? '已开启' : '已关闭')
const pageTitle = computed(() => state.activePage === 'waterCheckIn' && !waterCheckIn.sessionId && !waterCheckIn.isTest
  ? '记录喝水'
  : ({ water: '喝水提醒', screen: '屏幕记录', waterSettings: '喝水提醒设置', screenSettings: '屏幕记录设置', permissionGuide: '权限配置', waterCheckIn: '喝水确认', waterHistory: '喝水历史' }[state.activePage]))
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
const todayScreenOnDurationParts = computed(() => {
  const totalMinutes = Math.max(0, Math.floor((state.screenState?.todayScreenOnDurationMs || 0) / 60_000))
  return { hours: Math.floor(totalMinutes / 60), minutes: totalMinutes % 60 }
})
const todayScreenAlertCount = computed(() => state.screenState?.todayScreenAlertCount || 0)
const todayScreenOnCount = computed(() => state.screenState?.todayScreenOnCount || 0)
const lastScreenAlertText = computed(() => state.screenState?.lastScreenAlertAt ? new Date(state.screenState.lastScreenAlertAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '暂无')
const lastScreenOnText = computed(() => formatTimestamp(state.screenState?.lastScreenOnTime || 0))
const lastScreenOffText = computed(() => formatTimestamp(state.screenState?.lastScreenOffTime || 0))
const screenCyclePhase = computed(() => state.screenState?.cyclePhase ?? state.status.screenCyclePhase ?? 'idle')
const screenCycleCount = computed(() => state.screenState?.cycleCancelCount ?? state.status.cancelCycleCount ?? 0)
const screenCycleLimit = computed(() => state.screenState?.cycleLimit ?? state.status.screenCycleLimit ?? state.status.cancelBeforeLockCount ?? 1)
const screenRestRemainingMs = computed(() => {
  const remaining = Math.max(0, state.screenState?.restRemainingOffMs || 0)
  if (state.screenState?.currentScreenState !== 'off' || !state.screenState.updatedAt) return remaining
  return Math.max(0, remaining - Math.max(0, state.now - state.screenState.updatedAt))
})
const screenRecoveryHint = computed(() => {
  if (!state.screenState?.restRequired) return ''
  const remainingMinutes = Math.max(1, Math.ceil(screenRestRemainingMs.value / 60_000))
  if (state.screenState.currentScreenState === 'off') {
    return `保持息屏到 ${formatClock(state.now + screenRestRemainingMs.value)}，即可恢复正常使用。`
  }
  const windowEndsAt = state.screenState.restWindowEndsAt || state.now
  return `请在 ${formatClock(windowEndsAt)} 前累计息屏 ${remainingMinutes} 分钟，即可恢复正常使用。`
})
const latestWaterRecord = computed(() => state.waterHistory.records[0])
const historyChartDays = computed(() => {
  const today = new Date(state.now)
  today.setHours(0, 0, 0, 0)
  const recordsByDay = new Map<string, { amountMl: number; count: number }>()
  for (const record of state.waterHistory.records) {
    if (record.type !== 'drank') continue
    const key = localDayKey(record.timestamp)
    const current = recordsByDay.get(key) || { amountMl: 0, count: 0 }
    current.amountMl += Number(record.amountMl || 0)
    current.count += 1
    recordsByDay.set(key, current)
  }
  return Array.from({ length: 7 }, (_, index) => {
    const date = new Date(today)
    date.setDate(today.getDate() - (6 - index))
    const key = localDayKey(date.getTime())
    const values = recordsByDay.get(key) || { amountMl: 0, count: 0 }
    return { key, date: date.getTime(), label: `${date.getMonth() + 1}/${date.getDate()}`, weekday: date.toLocaleDateString('zh-CN', { weekday: 'short' }), ...values }
  })
})
const historyChartMaxAmount = computed(() => Math.max(1, ...historyChartDays.value.map(day => day.amountMl)))
const historyChartMaxCount = computed(() => Math.max(1, ...historyChartDays.value.map(day => day.count)))
const displayedWaterHistory = computed(() => {
  const source = historyShowingTrash.value ? state.waterDeletedRecords : state.waterHistory.records
  return source.filter(record => {
    if (!historyShowingTrash.value && historyHideNotDrank.value && record.type === 'not_drank') return false
    if (!historyShowingTrash.value && historySelectedDayKey.value && localDayKey(record.timestamp) !== historySelectedDayKey.value) return false
    return true
  })
})
const historyGroups = computed(() => {
  const groups = new Map<string, WaterCheckInRecord[]>()
  for (const record of displayedWaterHistory.value) {
    const key = localDayKey(record.timestamp)
    const items = groups.get(key) || []
    items.push(record)
    groups.set(key, items)
  }
  const todayKey = localDayKey(state.now)
  return Array.from(groups.entries()).map(([key, records]) => ({
    key,
    records,
    isToday: key === todayKey,
    label: key === todayKey ? '今天' : new Date(`${key}T00:00:00`).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }),
  }))
})
const todayWaterAverageMl = computed(() => state.waterHistory.todayRecordCount > 0 ? Math.round(state.waterHistory.todayTotalMl / state.waterHistory.todayRecordCount) : 0)
const todayWaterDateText = computed(() => new Date(state.now).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }))
const lastDrankTimeText = computed(() => state.waterHistory.lastDrankAt ? new Date(state.waterHistory.lastDrankAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : '暂无')
const selectedWaterContainer = computed(() => state.waterContainers.find(item => item.id === waterCheckIn.containerId) || state.waterContainers[0])
const calculatedWaterMl = computed(() => {
  const total = Number(waterCheckIn.totalWeightGrams)
  const empty = Number(selectedWaterContainer.value?.emptyWeightGrams)
  if (!Number.isFinite(total) || !Number.isFinite(empty)) return 0
  return Math.max(0, Math.round((total - empty) * 10) / 10)
})
const waterCheckInPhotoPreview = computed(() => {
  const photo = waterCheckIn.photos[photoPreviewIndex.value]
  return photo ? `data:${photo.mimeType || 'image/jpeg'};base64,${photo.dataBase64}` : ''
})
const waterPhotoPickerText = computed(() => waterCheckIn.photos.length
  ? `已选 ${waterCheckIn.photos.length} 张，可继续添加`
  : waterCheckIn.action === 'forced_state' ? '拍照或从相册选择' : '添加凭证照片')
const waterDrinkTypes: WaterDrinkType[] = ['白水', '冲剂', '药物', '饮料', '茶水', '果茶', '奶茶', '其他']
const isManualWaterCheckIn = computed(() => state.activePage === 'waterCheckIn' && !waterCheckIn.sessionId && !waterCheckIn.isTest)
const cancelCycleText = computed(() => {
  if (screenCyclePhase.value === 'idle') return '未进入提醒循环'
  if (screenCyclePhase.value === 'alerting') return '提醒中'
  if (screenCyclePhase.value === 'force_lock') return `强制熄屏中 ${screenCycleCount.value}/${screenCycleLimit.value}`
  if (screenCyclePhase.value === 'blocked_admin') return `已达阈值，等待锁屏权限`
  if (screenCyclePhase.value === 'grace') return '紧急宽限中'
  return screenCycleCount.value > 0 ? `已取消 ${screenCycleCount.value} / ${screenCycleLimit.value} 次` : '等待息屏休息'
})
const isSettingsPage = computed(() => state.activePage === 'waterSettings' || state.activePage === 'screenSettings')
const isPermissionGuidePage = computed(() => state.activePage === 'permissionGuide')
const isWaterFlowPage = computed(() => state.activePage === 'waterCheckIn' || state.activePage === 'waterHistory')
const updateStatusText = computed(() => {
  if (updateBusy.value && appUpdate.status === 'idle') return '正在检查更新…'
  if (appUpdate.status === 'running' || appUpdate.status === 'pending') return `正在下载 ${appUpdate.progress}%`
  if (appUpdate.status === 'downloaded') return '更新包已下载并通过校验'
  if (appUpdate.status === 'failed') return appUpdate.error || '更新下载失败'
  if (appUpdate.available) return `发现新版本 ${appUpdate.latestVersionName || ''}`.trim()
  if (appUpdate.lastCheckedAt) return '当前已是最新版本'
  return '尚未检查更新'
})
const updateActionText = computed(() => appUpdate.status === 'downloaded' ? '安装更新' : appUpdate.status === 'running' || appUpdate.status === 'pending' ? `下载中 ${appUpdate.progress}%` : '下载并安装')
const shouldShowUpdateDialog = computed(() => appUpdate.available && !updateDialogDismissed.value && !updatePanelOpen.value)
const formattedUpdateSize = computed(() => appUpdate.size ? `${(appUpdate.size / 1024 / 1024).toFixed(1)} MB` : '')

let ticker: number | undefined
let screenRefreshTicker: number | undefined
let backButtonHandle: PluginListenerHandle | undefined
let appStateHandle: PluginListenerHandle | undefined
let appUrlOpenHandle: PluginListenerHandle | undefined
let appUpdateHandle: PluginListenerHandle | undefined
let updateCheckTimer: number | undefined
let githubConnectionToastTimer: number | undefined
let messageTimer: number | undefined
let autoSaveTimer: number | undefined
let resumeTimer: number | undefined
let filePickerRecoveryTimer: number | undefined
let waterPhotoMessageTimer: number | undefined
let lastSavedConfigSignature = ''
let screenDashboardRefresh: Promise<void> | null = null
let statusRefresh: Promise<void> | null = null
let filePickerRestore: Promise<void> | null = null
let resumeFromNative = false
let filePickerActive = false
let filePickerProcessing = false
let filePickerSettledAt = 0
let filePickerScrollTop = 0
let filePickerPage: AppPage = 'water'
let waterSummaryDayKey = new Date().toDateString()
const contentScroller = ref<HTMLElement | null>(null)
const touchStart = reactive({ x: 0, y: 0, active: false })

function pad(value: number) { return String(value).padStart(2, '0') }
function localDayKey(timestamp: number) { const date = new Date(timestamp); return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` }
function historyAmountBarHeight(amountMl: number) { return `${Math.max(amountMl > 0 ? 10 : 3, Math.round(amountMl / historyChartMaxAmount.value * 100))}%` }
function historyCountBarHeight(count: number) { return `${Math.max(count > 0 ? 10 : 3, Math.round(count / historyChartMaxCount.value * 100))}%` }
function soundModeText(mode: ReminderSoundMode, name?: string) { return mode === 'custom' ? `自定义：${name || '已导入音频'}` : '应用默认提示音' }
function readStoredBoolean(key: string) { try { return window.localStorage.getItem(key) === 'true' } catch { return false } }
function formatTimestamp(timestamp: number) { return timestamp ? new Date(timestamp).toLocaleString('zh-CN', { hour12: false }) : '暂无记录' }
function formatClock(timestamp: number) { return new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) }
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
function waterRecordText(record?: WaterCheckInRecord) {
  if (!record) return '暂无记录'
  if (record.type === 'drank') return `${record.drinkType || '饮水'} · ${record.amountMl || 0} 毫升`
  if (record.type === 'state_check') return '已完成状态自拍验证'
  return `未喝（连续 ${record.consecutiveNotDrank || 1}/3 次）`
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
    waterRetryMinutes: Number(state.status.waterRetryMinutes ?? 10),
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
  if (config.waterRetryMinutes < 1 || config.waterRetryMinutes > 180) throw new Error('未喝后的再次提醒间隔必须在 1-180 分钟之间')
  if (config.screenOnLimitMinutes < 0) throw new Error('亮屏超时提醒分钟数不可小于 0')
  if (config.requiredScreenOffMinutes < 1) throw new Error('累计息屏分钟数必须大于 0')
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
  if (page === 'screen') void refreshScreenDashboard()
}
function openSettings() {
  const source: MainPage = state.activePage === 'screen' ? 'screen' : 'water'
  state.settingsSource = source
  switchPage(source === 'screen' ? 'screenSettings' : 'waterSettings', true)
}
function navigateBack() {
  if (photoPreviewOpen.value) { photoPreviewOpen.value = false; return }
  if (photoSourceDialogOpen.value) { photoSourceDialogOpen.value = false; return }
  if (updatePanelOpen.value) { updatePanelOpen.value = false; return }
  if (isWaterFlowPage.value) {
    if (state.activePage === 'waterCheckIn' && waterCheckIn.action === 'forced_state' && !waterCheckIn.isTest) {
      waterCheckIn.message = '连续三次未喝后，需要先完成状态自拍验证。'
      return
    }
    switchPage('water')
    return
  }
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
  if (page === 'water' || page === 'screen' || page === 'waterSettings' || page === 'screenSettings' || page === 'permissionGuide' || page === 'waterCheckIn' || page === 'waterHistory') {
    state.activePage = page
    if (page === 'water' || page === 'screen') state.settingsSource = page
    scrollContentToTop()
    return
  }
  if (isSettingsPage.value || isPermissionGuidePage.value || isWaterFlowPage.value) {
    state.activePage = state.settingsSource
    history.replaceState({ appPage: state.settingsSource, settingsSource: state.settingsSource }, '', location.href)
    scrollContentToTop()
  }
}
function isInteractiveTarget(target: EventTarget | null) {
  return target instanceof Element && Boolean(target.closest('button,input,textarea,select,label,a,.file-picker'))
}
function onTouchStart(event: TouchEvent) {
  if (isSettingsPage.value || isPermissionGuidePage.value || isWaterFlowPage.value || isInteractiveTarget(event.target) || event.touches.length !== 1) return
  const touch = event.touches[0]
  if (touch.clientX < 24 || touch.clientX > window.innerWidth - 24) return
  touchStart.x = touch.clientX; touchStart.y = touch.clientY; touchStart.active = true
}
function onTouchEnd(event: TouchEvent) {
  if (!touchStart.active || isSettingsPage.value || isPermissionGuidePage.value || isWaterFlowPage.value) return
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
function nextMinuteTimestamp() { return (Math.floor(Date.now() / 60_000) + 1) * 60_000 }
function parseNextReminderInput() {
  const parsed = state.nextReminderInput ? new Date(state.nextReminderInput).getTime() : nextMinuteTimestamp()
  return Number.isFinite(parsed) && parsed > 0 ? parsed : nextMinuteTimestamp()
}
function toDateTimeLocalValue(timestamp: number) { const date = new Date(timestamp); return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16) }
function normalizedNumber(value: unknown, fallback: number, min: number, max: number) {
  if (value === '' || value == null) return fallback
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback
}
function normalizeSettings(source: MainPage) {
  if (source === 'water') {
    state.status.minIntervalMinutes = normalizedNumber(state.status.minIntervalMinutes, defaultStatus.minIntervalMinutes, 15, 360)
    state.status.maxIntervalMinutes = normalizedNumber(state.status.maxIntervalMinutes, Math.max(defaultStatus.maxIntervalMinutes, state.status.minIntervalMinutes), state.status.minIntervalMinutes, 360)
    state.status.waterRetryMinutes = normalizedNumber(state.status.waterRetryMinutes, defaultStatus.waterRetryMinutes, 1, 180)
    state.status.waterVolumePercent = normalizedNumber(state.status.waterVolumePercent, defaultStatus.waterVolumePercent, 0, 100)
    state.status.waterNotificationTitle = state.status.waterNotificationTitle.trim() || defaultStatus.waterNotificationTitle
    state.status.waterNotificationText = state.status.waterNotificationText.trim() || defaultStatus.waterNotificationText
    if (!state.nextReminderInput || !Number.isFinite(new Date(state.nextReminderInput).getTime())) {
      state.nextReminderInput = toDateTimeLocalValue(nextMinuteTimestamp())
    }
  } else {
    state.status.screenOnLimitMinutes = normalizedNumber(state.status.screenOnLimitMinutes, defaultStatus.screenOnLimitMinutes, 0, 1440)
    state.status.requiredScreenOffMinutes = normalizedNumber(state.status.requiredScreenOffMinutes, defaultStatus.requiredScreenOffMinutes, 1, 1440)
    state.status.cancelBeforeLockCount = normalizedNumber(state.status.cancelBeforeLockCount, defaultStatus.cancelBeforeLockCount, 1, 99)
    state.status.screenVolumePercent = normalizedNumber(state.status.screenVolumePercent, defaultStatus.screenVolumePercent, 0, 100)
  }
}
function configSignature(config: ReminderConfig) { return JSON.stringify(config) }

async function refreshScreenDashboard(showMessage = false) {
  if (screenDashboardRefresh) return screenDashboardRefresh
  screenDashboardRefresh = (async () => {
    try {
      const dashboard = await WaterReminder.getScreenDashboardState()
      state.screenState = dashboard
      state.status = {
        ...state.status,
        screenLimitEnabled: dashboard.screenLimitEnabled,
        screenOnLimitMinutes: dashboard.screenOnLimitMinutes,
        requiredScreenOffMinutes: dashboard.requiredScreenOffMinutes,
        cancelBeforeLockCount: dashboard.cancelBeforeLockCount,
        screenSoundMode: dashboard.screenSoundMode,
        screenCustomSoundName: dashboard.screenCustomSoundName || '',
        screenVolumePercent: dashboard.screenVolumePercent,
        cancelCycleCount: dashboard.cycleCancelCount ?? 0,
        screenCyclePhase: dashboard.cyclePhase,
        screenCycleLimit: dashboard.cycleLimit,
        screenCycleActiveSessionId: dashboard.cycleActiveSessionId,
        screenCycleSessionStartedAt: dashboard.cycleSessionStartedAt,
        screenCycleId: dashboard.cycleId,
        screenCycleStartedAt: dashboard.cycleStartedAt,
        screenCycleUpdatedAt: dashboard.cycleUpdatedAt,
      }
      state.screenDataStale = false
      if (showMessage) setUserMessage('屏幕记录已刷新')
    } catch (error) {
      state.screenDataStale = true
      if (showMessage || state.activePage === 'screen') setUserMessage(error instanceof Error ? `刷新失败：${error.message}` : '刷新屏幕记录失败', 4000)
    } finally {
      screenDashboardRefresh = null
    }
  })()
  return screenDashboardRefresh
}
async function refreshScreenStateOnly() { await refreshScreenDashboard(false) }
function setUserMessage(message: string, durationMs = 2800) {
  state.message = message
  if (messageTimer) window.clearTimeout(messageTimer)
  if (durationMs > 0) messageTimer = window.setTimeout(() => { if (state.message === message) state.message = '' }, durationMs)
}

async function refreshStatus(showMessage = false) {
  if (statusRefresh) {
    await statusRefresh
    if (showMessage) setUserMessage('状态已更新')
    return
  }
  state.loading = true
  statusRefresh = (async () => {
    try {
      const [status, permissions, waterHistory, containerResult] = await Promise.all([
        WaterReminder.getStatus(),
        WaterReminder.getPermissionStatus(),
        WaterReminder.getWaterCheckInHistory(),
        WaterReminder.getWaterContainers(),
      ])
      state.status = { ...defaultStatus, ...status }
      state.permissions = permissions
      state.waterHistory = waterHistory
      state.waterContainers = containerResult.containers
      if (!waterCheckIn.containerId && state.waterContainers.length) waterCheckIn.containerId = state.waterContainers[0].id
      updateNextReminderInput()
      lastSavedConfigSignature = configSignature(buildConfig())
      await refreshScreenDashboard(false)
      if (showMessage) setUserMessage('状态已更新')
    } catch (error) {
      if (state.activePage === 'screen') state.screenDataStale = true
      setUserMessage(error instanceof Error ? error.message : '读取状态失败', 4000)
    } finally {
      state.loading = false
      statusRefresh = null
    }
  })()
  await statusRefresh
}
async function saveConfig(message: string, overrides: Partial<ReminderConfig> = {}, requestPermission = true, skipUnchanged = false) {
  state.saving = true
  try {
    const config = buildConfig(overrides)
    validateConfig(config)
    const signature = configSignature(config)
    if (skipUnchanged && signature === lastSavedConfigSignature) return
    if (requestPermission) state.permissions = await WaterReminder.requestNotificationPermission()
    state.status = { ...defaultStatus, ...await WaterReminder.startReminder(config) }
    updateNextReminderInput()
    lastSavedConfigSignature = configSignature(buildConfig())
    setUserMessage(message)
  } catch (error) { setUserMessage(error instanceof Error ? error.message : message.includes('开启') ? '开启提醒失败' : '保存提醒设置失败', 4000) } finally { state.saving = false }
}
function autoSaveSettings(source: MainPage) {
  normalizeSettings(source)
  if (autoSaveTimer) window.clearTimeout(autoSaveTimer)
  autoSaveTimer = window.setTimeout(() => {
    if (state.saving) { autoSaveSettings(source); return }
    void saveConfig(`${source === 'water' ? '喝水提醒' : '屏幕记录'}设置已自动保存`, {}, false, true)
  }, 260)
}
function enableReminder() { return saveConfig('喝水提醒已开启，可划掉应用后等待闹钟触发', { enabled: true }) }
async function disableReminder() {
  state.saving = true
  try { state.status = { ...defaultStatus, ...await WaterReminder.stopReminder() }; setUserMessage('喝水提醒已关闭') }
  catch (error) { setUserMessage(error instanceof Error ? error.message : '关闭提醒失败', 4000) }
  finally { state.saving = false }
}
function enableScreenLimit() { return saveConfig('屏幕超时提醒已开启', { screenLimitEnabled: true }) }
function disableScreenLimit() { return saveConfig('屏幕超时提醒已关闭', { screenLimitEnabled: false }) }
async function toggleWaterReminder() {
  if (state.loading || state.saving) return
  if (state.status.enabled) await disableReminder()
  else await enableReminder()
}
async function toggleScreenReminder() {
  if (state.loading || state.saving) return
  if (state.status.screenLimitEnabled) await disableScreenLimit()
  else await enableScreenLimit()
}



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
async function launchPermissionIntent(kind: PermissionGuideKey) {
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
    else if (type === 'water' && !state.status.waterCustomSoundUri) { setUserMessage('请先导入喝水提醒自定义音频文件'); return }
    else if (type === 'screen_limit' && !state.status.screenCustomSoundUri) { setUserMessage('请先导入屏幕提醒自定义音频文件'); return }
    else state.status = { ...defaultStatus, ...await WaterReminder.startReminder(buildConfig(type === 'water' ? { waterSoundMode: 'custom' } : { screenSoundMode: 'custom' })) }
    setUserMessage('已切换提示音')
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '切换提示音失败', 4000) } finally { state.saving = false }
}
function nextPaint() { return new Promise<void>(resolve => window.requestAnimationFrame(() => resolve())) }
function beginFilePicker() {
  if (filePickerRecoveryTimer) window.clearTimeout(filePickerRecoveryTimer)
  filePickerActive = true
  filePickerProcessing = false
  filePickerPage = state.activePage
  filePickerScrollTop = contentScroller.value?.scrollTop ?? 0
}
async function finishFilePicker() {
  if (filePickerRestore) return filePickerRestore
  filePickerRestore = (async () => {
    if (filePickerRecoveryTimer) window.clearTimeout(filePickerRecoveryTimer)
    filePickerActive = false
    await nextTick()
    await nextPaint()
    await nextPaint()
    const scroller = contentScroller.value
    if (scroller && state.activePage === filePickerPage) {
      const maximum = Math.max(0, scroller.scrollHeight - scroller.clientHeight)
      scroller.scrollTop = Math.min(filePickerScrollTop, maximum)
      // Reading the final bounds invalidates Android WebView's stale scroll layer.
      scroller.getBoundingClientRect()
    }
    filePickerSettledAt = Date.now()
  })().finally(() => { filePickerRestore = null })
  return filePickerRestore
}
function scheduleFilePickerRecovery() {
  if (filePickerRecoveryTimer) window.clearTimeout(filePickerRecoveryTimer)
  filePickerRecoveryTimer = window.setTimeout(() => {
    if (filePickerProcessing) { scheduleFilePickerRecovery(); return }
    void finishFilePicker()
  }, 450)
}
async function importCustomSound(type: ReminderType, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) { await finishFilePicker(); return }
  if (!isSupportedAudioFile(file)) { setUserMessage('请选择常见音频格式文件'); input.value = ''; await finishFilePicker(); return }
  filePickerProcessing = true
  state.importingSound = type
  try {
    state.status = { ...defaultStatus, ...await WaterReminder.saveCustomSound({ type, fileName: file.name, mimeType: file.type || inferAudioMimeType(file.name), dataBase64: await fileToBase64(file) }) }
    setUserMessage(`已导入自定义提示音：${file.name}`)
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '导入提示音失败', 4000) }
  finally { state.importingSound = ''; filePickerProcessing = false; input.value = ''; await finishFilePicker() }
}
function isSupportedAudioFile(file: File) { return file.type.startsWith('audio/') || /\.(mp3|wav|ogg|m4a|aac|flac)$/i.test(file.name) }
function inferAudioMimeType(fileName: string) { return ({ mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', m4a: 'audio/mp4', aac: 'audio/aac', flac: 'audio/flac' } as Record<string, string>)[fileName.split('.').pop()?.toLowerCase() || ''] || 'audio/mpeg' }
function fileToBase64(file: File): Promise<string> { return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result || '').split(',').pop() || ''); reader.onerror = () => reject(new Error('读取文件失败')); reader.readAsDataURL(file) }) }
function setWaterCheckInMessage(message: string, clearAfterMs = 0) {
  if (waterPhotoMessageTimer) window.clearTimeout(waterPhotoMessageTimer)
  waterCheckIn.message = message
  if (clearAfterMs > 0) {
    waterPhotoMessageTimer = window.setTimeout(() => {
      if (waterCheckIn.message === message) waterCheckIn.message = ''
      waterPhotoMessageTimer = undefined
    }, clearAfterMs)
  }
}
function resetWaterCheckIn(sessionId = '', isTest = false, action: 'prompt' | 'drank' | 'forced_state' = 'prompt') {
  waterCheckIn.sessionId = sessionId
  waterCheckIn.isTest = isTest
  waterCheckIn.action = action
  waterCheckIn.amountMode = 'container'
  waterCheckIn.directMl = null
  waterCheckIn.totalWeightGrams = null
  waterCheckIn.drinkType = '白水'
  waterCheckIn.description = ''
  waterCheckIn.photos.splice(0)
  waterCheckIn.message = ''
  waterCheckIn.submitting = false
  photoPreviewOpen.value = false
  photoSourceDialogOpen.value = false
  photoPreviewIndex.value = 0
  if (waterPhotoMessageTimer) window.clearTimeout(waterPhotoMessageTimer)
  if (!waterCheckIn.containerId && state.waterContainers.length) waterCheckIn.containerId = state.waterContainers[0].id
}
async function openWaterCheckInPage(sessionId: string, isTest: boolean, action: 'prompt' | 'drank' | 'forced_state') {
  if (!state.waterContainers.length) {
    const result = await WaterReminder.getWaterContainers()
    state.waterContainers = result.containers
  }
  const waterHistory = await WaterReminder.getWaterCheckInHistory()
  state.waterHistory = waterHistory
  resetWaterCheckIn(sessionId, isTest, sessionId && waterHistory.requiresStatePhoto ? 'forced_state' : action)
  state.activePage = 'waterCheckIn'
  state.settingsSource = 'water'
  window.history.replaceState({ appPage: 'waterCheckIn', settingsSource: 'water' }, '', location.href)
  await WaterReminder.dismissWaterAlertUi().catch(() => undefined)
  scrollContentToTop()
}
function openWaterPhotoSourceDialog() {
  photoSourceDialogOpen.value = true
}
function chooseWaterPhotoSource(source: 'camera' | 'gallery') {
  photoSourceDialogOpen.value = false
  nextTick(() => {
    const input = source === 'camera' ? waterCameraInput.value : waterGalleryInput.value
    if (!input) return
    beginFilePicker()
    input.click()
  })
}
function showWaterPhoto(index: number) {
  photoPreviewIndex.value = index
  photoPreviewOpen.value = true
}
function removeWaterPhoto(index: number) {
  if (index < 0 || index >= waterCheckIn.photos.length) return
  waterCheckIn.photos.splice(index, 1)
  if (!waterCheckIn.photos.length) photoPreviewOpen.value = false
  photoPreviewIndex.value = Math.max(0, Math.min(photoPreviewIndex.value, waterCheckIn.photos.length - 1))
  setWaterCheckInMessage(waterCheckIn.photos.length ? `已删除，当前保留 ${waterCheckIn.photos.length} 张凭证照片。` : '已删除所选凭证照片。', 3000)
}
async function handleWaterPhoto(event: Event) {
  const input = event.target as HTMLInputElement
  const pickedFiles = Array.from(input.files || [])
  if (!pickedFiles.length) { await finishFilePicker(); return }
  const files = waterCheckIn.action === 'forced_state' ? pickedFiles.slice(0, 1) : pickedFiles
  const available = waterCheckIn.action === 'forced_state' ? 1 : Math.max(0, 6 - waterCheckIn.photos.length)
  if (!available) { setWaterCheckInMessage('每条记录最多添加 6 张凭证照片'); input.value = ''; await finishFilePicker(); return }
  const accepted = files.slice(0, available)
  if (accepted.some(file => !file.type.startsWith('image/'))) { setWaterCheckInMessage('请选择图片文件'); input.value = ''; await finishFilePicker(); return }
  if (accepted.some(file => file.size > 20 * 1024 * 1024)) { setWaterCheckInMessage('单张图片不能超过 20MB'); input.value = ''; await finishFilePicker(); return }
  filePickerProcessing = true
  try {
    const photos = await Promise.all(accepted.map(async (file, index) => ({
      id: `${Date.now()}-${index}-${Math.random().toString(36).slice(2)}`,
      name: file.name || `照片-${index + 1}.jpg`,
      mimeType: file.type || 'image/jpeg',
      dataBase64: await fileToBase64(file),
    })))
    if (waterCheckIn.action === 'forced_state') waterCheckIn.photos.splice(0, waterCheckIn.photos.length, ...photos)
    else waterCheckIn.photos.push(...photos)
    photoPreviewOpen.value = false
    const omitted = files.length - accepted.length
    setWaterCheckInMessage(
      waterCheckIn.action === 'forced_state'
        ? '状态照片已就绪，仅会保存在本机。'
        : `已添加 ${photos.length} 张凭证照片${omitted > 0 ? '，其余照片因最多 6 张未添加' : '，可继续添加、预览或删除'}。`,
      3000,
    )
  } catch (error) { setWaterCheckInMessage(error instanceof Error ? error.message : '读取图片失败') }
  finally { filePickerProcessing = false; input.value = ''; await finishFilePicker() }
}
async function submitWaterDrank() {
  if (waterCheckIn.submitting) return
  const amount = waterCheckIn.amountMode === 'container' ? calculatedWaterMl.value : Number(waterCheckIn.directMl)
  if (!Number.isFinite(amount) || amount < 1 || amount > 5000) { setWaterCheckInMessage('饮水量必须在 1-5000 毫升之间'); return }
  if (waterCheckIn.description.length > 500) { setWaterCheckInMessage('喝水说明不能超过 500 个字符'); return }
  if (waterCheckIn.isTest) { setWaterCheckInMessage(`测试完成：${amount} 毫升，不保存记录`); window.setTimeout(() => switchPage('water'), 900); return }
  waterCheckIn.submitting = true
  try {
    state.waterHistory = await WaterReminder.saveWaterDrankRecord({
      sessionId: waterCheckIn.sessionId,
      amountMl: amount,
      entryMode: waterCheckIn.amountMode,
      drinkType: waterCheckIn.drinkType,
      description: waterCheckIn.description.trim(),
      containerId: waterCheckIn.amountMode === 'container' ? selectedWaterContainer.value?.id : undefined,
      containerName: waterCheckIn.amountMode === 'container' ? selectedWaterContainer.value?.name : undefined,
      emptyWeightGrams: waterCheckIn.amountMode === 'container' ? selectedWaterContainer.value?.emptyWeightGrams : undefined,
      totalWeightGrams: waterCheckIn.amountMode === 'container' ? Number(waterCheckIn.totalWeightGrams) : undefined,
      photos: waterCheckIn.photos.map(photo => ({ fileName: photo.name, mimeType: photo.mimeType, dataBase64: photo.dataBase64 })),
    })
    await refreshStatus()
    setUserMessage(`已保存本次${waterCheckIn.drinkType} ${amount} 毫升${waterCheckIn.photos.length ? `及 ${waterCheckIn.photos.length} 张本地凭证照片` : ''}`)
    switchPage('water')
  } catch (error) { setWaterCheckInMessage(error instanceof Error ? error.message : '保存喝水记录失败') }
  finally { waterCheckIn.submitting = false }
}
async function submitWaterNotDrank() {
  if (waterCheckIn.submitting) return
  if (waterCheckIn.isTest) { waterCheckIn.message = '测试完成，不保存未喝记录'; window.setTimeout(() => switchPage('water'), 900); return }
  waterCheckIn.submitting = true
  try {
    const result = await WaterReminder.recordWaterNotDrank({ sessionId: waterCheckIn.sessionId })
    if (result.requiresStatePhoto) {
      waterCheckIn.action = 'forced_state'
      waterCheckIn.photos.splice(0)
      setWaterCheckInMessage('已连续三次未喝，请完成状态自拍验证。')
    } else {
      setUserMessage(`将在 ${result.retryMinutes} 分钟后再次提醒`)
      await refreshStatus()
      switchPage('water')
    }
  } catch (error) { setWaterCheckInMessage(error instanceof Error ? error.message : '处理未喝失败') }
  finally { waterCheckIn.submitting = false }
}
async function submitWaterStateCheck() {
  if (waterCheckIn.submitting) return
  const photo = waterCheckIn.photos[0]
  if (!photo) { setWaterCheckInMessage('请先拍摄或选择状态自拍'); return }
  waterCheckIn.submitting = true
  try {
    state.waterHistory = await WaterReminder.saveWaterStateCheck({ sessionId: waterCheckIn.sessionId, mimeType: photo.mimeType, dataBase64: photo.dataBase64 })
    setUserMessage(`状态验证已保存在本机，将在 ${state.status.waterRetryMinutes} 分钟后再次提醒`)
    switchPage('water')
  } catch (error) { setWaterCheckInMessage(error instanceof Error ? error.message : '保存状态验证失败') }
  finally { waterCheckIn.submitting = false }
}
async function openWaterHistory() {
  const [history, deleted] = await Promise.all([
    WaterReminder.getWaterCheckInHistory({ limit: 200 }),
    WaterReminder.getWaterCheckInHistory({ limit: 200, deletedOnly: true }),
  ])
  state.waterHistory = history
  state.waterDeletedRecords = deleted.records
  historyShowingTrash.value = false
  historySelectedDayKey.value = ''
  editingRecordId.value = ''
  switchPage('waterHistory', true)
}
function showCurrentWaterHistory() { historyShowingTrash.value = false }
function toggleHistoryHideNotDrank() {
  historyHideNotDrank.value = !historyHideNotDrank.value
  try { window.localStorage.setItem(HISTORY_HIDE_NOT_DRANK_KEY, String(historyHideNotDrank.value)) } catch { /* Keep the in-memory preference when storage is unavailable. */ }
}
function showDeletedWaterHistory() { historyShowingTrash.value = true; historySelectedDayKey.value = '' }
function selectHistoryDay(key: string) { historySelectedDayKey.value = key; expandedHistoryDays[key] = true }
function clearHistoryDayFilter() { historySelectedDayKey.value = '' }
function isHistoryGroupExpanded(key: string, isToday: boolean) {
  if (historySelectedDayKey.value === key) return true
  return Object.prototype.hasOwnProperty.call(expandedHistoryDays, key) ? expandedHistoryDays[key] : isToday
}
function toggleHistoryGroup(key: string, isToday: boolean) { expandedHistoryDays[key] = !isHistoryGroupExpanded(key, isToday) }
function beginEditWaterRecord(record: WaterCheckInRecord) {
  editingRecordId.value = record.id
  editingDescription.value = record.description || ''
}
function cancelEditWaterRecord() { editingRecordId.value = ''; editingDescription.value = '' }
async function saveWaterRecordDescription(record: WaterCheckInRecord) {
  try {
    const result = await WaterReminder.updateWaterRecordDescription({ id: record.id, description: editingDescription.value })
    if (!result.updated) throw new Error('找不到这条本地记录')
    state.waterHistory = result.history
    cancelEditWaterRecord()
    setUserMessage('喝水说明已保存')
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '保存喝水说明失败', 4000) }
}
async function deleteWaterRecord(record: WaterCheckInRecord) {
  if (!window.confirm('删除后记录会移入本地回收站，可随时恢复。确定删除吗？')) return
  try {
    const result = await WaterReminder.deleteWaterRecord({ id: record.id })
    if (!result.deleted) throw new Error('找不到这条本地记录')
    state.waterHistory = result.history
    const deleted = await WaterReminder.getWaterCheckInHistory({ limit: 200, deletedOnly: true })
    state.waterDeletedRecords = deleted.records
    delete state.waterHistoryImages[record.id]
    setUserMessage('记录已移入本地回收站')
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '删除记录失败', 4000) }
}
async function restoreWaterRecord(record: WaterCheckInRecord) {
  try {
    const result = await WaterReminder.restoreWaterRecord({ id: record.id })
    if (!result.restored) throw new Error('找不到这条本地记录')
    state.waterHistory = result.history
    state.waterDeletedRecords = state.waterDeletedRecords.filter(item => item.id !== record.id)
    setUserMessage('记录已恢复')
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '恢复记录失败', 4000) }
}
function waterRecordPhotoNames(record: WaterCheckInRecord) {
  return Array.from(new Set([...(record.photoFileNames || []), record.photoFileName].filter((name): name is string => Boolean(name))))
}
async function toggleWaterHistoryPhoto(record: WaterCheckInRecord) {
  const fileNames = waterRecordPhotoNames(record)
  if (!fileNames.length) return
  if (state.waterHistoryImages[record.id]) { delete state.waterHistoryImages[record.id]; return }
  try {
    const photos = await Promise.all(fileNames.map(photoFileName => WaterReminder.getWaterPhoto({ photoFileName })))
    state.waterHistoryImages[record.id] = photos.map(photo => `data:${photo.mimeType};base64,${photo.dataBase64}`)
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '读取本地照片失败', 4000) }
}
function addWaterContainer() { state.waterContainers.push({ id: '', name: '新容器', emptyWeightGrams: 0 }) }
async function saveWaterContainer(item: WaterContainer) {
  if (!item.name.trim() || !Number.isFinite(Number(item.emptyWeightGrams)) || Number(item.emptyWeightGrams) <= 0) {
    setUserMessage('容器名称或空重未填写，暂未自动保存')
    return
  }
  try {
    const result = await WaterReminder.saveWaterContainer({ id: item.id || undefined, name: item.name, emptyWeightGrams: Number(item.emptyWeightGrams) })
    state.waterContainers = result.containers
    setUserMessage('饮水容器已保存')
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '保存容器失败', 4000) }
}
async function deleteWaterContainer(item: WaterContainer) {
  if (!item.id) { state.waterContainers.splice(state.waterContainers.indexOf(item), 1); return }
  const result = await WaterReminder.deleteWaterContainer({ id: item.id })
  state.waterContainers = result.containers
  if (!result.deleted) setUserMessage('至少需要保留一个饮水容器')
}
async function exportWaterData() {
  try {
    const result = await WaterReminder.shareWaterDataExport()
    setUserMessage(result.opened ? '已打开系统分享面板，请保存迁移档案' : '未能打开导出面板')
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '导出喝水数据失败', 4000) }
}
async function importWaterData(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) { await finishFilePicker(); return }
  if (file.size > 180 * 1024 * 1024) { setUserMessage('迁移档案不能超过 180MB'); input.value = ''; await finishFilePicker(); return }
  if (!window.confirm('导入会替换当前的饮水容器与喝水历史，确定继续吗？')) { input.value = ''; await finishFilePicker(); return }
  filePickerProcessing = true
  state.loading = true
  try {
    const result = await WaterReminder.importWaterData({ dataBase64: await fileToBase64(file) })
    state.waterHistory = result.history
    state.waterContainers = result.containers
    state.waterHistoryImages = {}
    setUserMessage(`已导入 ${result.history.records.length} 条喝水记录`)
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '导入喝水数据失败', 4000) }
  finally { state.loading = false; filePickerProcessing = false; input.value = ''; await finishFilePicker() }
}
function mergeUpdateState(next: UpdateState) {
  const previousLatest = appUpdate.latestVersionCode
  Object.assign(appUpdate, next)
  if (next.available && next.latestVersionCode !== previousLatest) updateDialogDismissed.value = false
}
function openUpdatePanel() {
  updateDialogDismissed.value = true
  githubConnectionToast.value = ''
  updatePanelOpen.value = true
}
function showGithubConnectionToast(ok: boolean) {
  githubConnectionToast.value = ok ? '连接成功' : '连接失败'
  if (githubConnectionToastTimer) window.clearTimeout(githubConnectionToastTimer)
  githubConnectionToastTimer = window.setTimeout(() => { githubConnectionToast.value = '' }, 3000)
}
async function testGithubConnection() {
  githubTestBusy.value = true
  githubTestResult.tested = false
  try {
    const result = await AppUpdate.testGithubConnection()
    Object.assign(githubTestResult, { tested: true, ...result })
    showGithubConnectionToast(result.ok)
  } catch (error) {
    Object.assign(githubTestResult, { tested: true, ok: false, latencyMs: 0, message: error instanceof Error ? error.message : '连接测试失败，请检查网络后重试' })
    showGithubConnectionToast(false)
  } finally { githubTestBusy.value = false }
}
async function loadUpdateState() {
  try { mergeUpdateState(await AppUpdate.getUpdateState()) }
  catch { /* Browser preview or an old native build may not expose the update plugin. */ }
}
async function checkForAppUpdate(manual = true) {
  updateBusy.value = true
  try {
    if (manual && (!githubTestResult.tested || !githubTestResult.ok)) {
      const connection = await AppUpdate.testGithubConnection()
      Object.assign(githubTestResult, { tested: true, ...connection })
      if (!connection.ok) {
        showGithubConnectionToast(false)
        setUserMessage(connection.message, 5000)
        return
      }
    }
    mergeUpdateState(await AppUpdate.checkForUpdate({ channel: appUpdate.channel, manual }))
    if (manual) setUserMessage(appUpdate.available ? `发现新版本 ${appUpdate.latestVersionName}` : '当前已是最新版本', 2600)
  } catch (error) {
    if (manual) setUserMessage(error instanceof Error ? error.message : '检查更新失败', 4000)
  } finally { updateBusy.value = false }
}
async function changeUpdateChannel(event: Event) {
  const channel = (event.target as HTMLSelectElement).value as UpdateChannel
  try {
    mergeUpdateState(await AppUpdate.setUpdateChannel({ channel }))
    await checkForAppUpdate(true)
  } catch (error) { setUserMessage(error instanceof Error ? error.message : '切换更新渠道失败', 4000) }
}
async function installDownloadedUpdate() {
  updateBusy.value = true
  try {
    const result = await AppUpdate.installDownloadedUpdate()
    if (result.requiresPermission) {
      retryInstallOnResume.value = true
      await AppUpdate.openUnknownSourceSettings()
      setUserMessage('请允许水息守护安装未知应用，返回后会再次打开安装确认页', 5000)
    } else if (result.started) {
      retryInstallOnResume.value = false
      setUserMessage('已打开系统安装确认页', 2500)
    }
  } catch (error) {
    installAfterDownload.value = false
    setUserMessage(error instanceof Error ? error.message : '无法安装更新', 5000)
  } finally { updateBusy.value = false }
}
async function downloadAndInstallUpdate() {
  if (appUpdate.status === 'downloaded') { await installDownloadedUpdate(); return }
  if (appUpdate.status === 'running' || appUpdate.status === 'pending') return
  installAfterDownload.value = true
  updateBusy.value = true
  try { mergeUpdateState(await AppUpdate.downloadUpdate()) }
  catch (error) {
    installAfterDownload.value = false
    setUserMessage(error instanceof Error ? error.message : '开始下载更新失败', 5000)
  } finally { updateBusy.value = false }
}
function dismissUpdateDialog() {
  if (!appUpdate.mandatory) updateDialogDismissed.value = true
}
function handleUpdateDownloadState(next: UpdateState) {
  mergeUpdateState(next)
  if (next.status === 'downloaded' && installAfterDownload.value) {
    installAfterDownload.value = false
    void installDownloadedUpdate()
  }
  if (next.status === 'failed') {
    installAfterDownload.value = false
    setUserMessage(next.error || '更新下载失败', 5000)
  }
}
function scheduleAppResume(source: 'native' | 'web') {
  resumeFromNative ||= source === 'native'
  if (resumeTimer) window.clearTimeout(resumeTimer)
  resumeTimer = window.setTimeout(() => {
    const effectiveSource = resumeFromNative ? 'native' : 'web'
    resumeFromNative = false
    void handleAppResume(effectiveSource)
  }, 250)
}
async function handleAppResume(source: 'native' | 'web') {
  state.now = Date.now()
  if (retryInstallOnResume.value && appUpdate.status === 'downloaded') void installDownloadedUpdate()
  if (filePickerActive) { scheduleFilePickerRecovery(); return }
  // The input change handler already committed the selected file. Ignore the
  // trailing visibility/app-state event so it cannot start a second redraw.
  if (Date.now() - filePickerSettledAt < 1200) return
  if (state.permissionGuidePhase === 'waitingReturn') {
    if (state.permissionGuideDidLeaveApp) await reviewPermissionReturn('native')
    else if (source === 'web' && Date.now() - state.permissionGuideLastOpenedAt > 1200) await reviewPermissionReturn('web')
    return
  }
  if (state.activePage === 'screen') await refreshScreenDashboard(false)
  else await refreshStatus()
}
function handleVisibilityChange() { if (document.visibilityState === 'visible') scheduleAppResume('web') }
function handleAppStateChange(isActive: boolean) {
  if (!isActive) {
    if (state.permissionGuidePhase === 'waitingReturn') state.permissionGuideDidLeaveApp = true
    return
  }
  scheduleAppResume('native')
}
function handleAppLaunchUrl(url?: string) {
  if (!url) return
  try {
    const parsed = new URL(url)
    const target = parsed.pathname.replace(/^\//, '')
    if (target === 'water' || target === 'screen') switchPage(target)
    if (target === 'water-history') void openWaterHistory()
    if (target === 'water-checkin') {
      const action = parsed.searchParams.get('action')
      void openWaterCheckInPage(
        parsed.searchParams.get('sessionId') || '',
        parsed.searchParams.get('isTest') === 'true',
        action === 'drank' || action === 'forced_state' ? action : 'prompt',
      )
    }
  } catch { /* Ignore unrelated or malformed launch URLs. */ }
}

onMounted(() => {
  refreshStatus()
  history.replaceState({ appPage: state.activePage, settingsSource: state.settingsSource }, '', location.href)
  ticker = window.setInterval(() => {
    state.now = Date.now()
    const dayKey = new Date(state.now).toDateString()
    if (dayKey !== waterSummaryDayKey) {
      waterSummaryDayKey = dayKey
      refreshStatus()
    }
  }, 1000)
  screenRefreshTicker = window.setInterval(refreshScreenStateOnly, 15_000)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('popstate', handlePopState)
  CapacitorApp.addListener('backButton', () => navigateBack()).then(handle => { backButtonHandle = handle })
  CapacitorApp.addListener('appStateChange', ({ isActive }) => handleAppStateChange(isActive)).then(handle => { appStateHandle = handle })
  CapacitorApp.addListener('appUrlOpen', ({ url }) => handleAppLaunchUrl(url)).then(handle => { appUrlOpenHandle = handle })
  CapacitorApp.getLaunchUrl().then(result => handleAppLaunchUrl(result?.url)).catch(() => undefined)
  void loadUpdateState()
  AppUpdate.addListener('updateDownloadState', handleUpdateDownloadState).then(handle => { appUpdateHandle = handle }).catch(() => undefined)
  updateCheckTimer = window.setTimeout(() => { void checkForAppUpdate(false) }, 2500)
})
onUnmounted(() => {
  if (ticker) window.clearInterval(ticker)
  if (screenRefreshTicker) window.clearInterval(screenRefreshTicker)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('popstate', handlePopState)
  backButtonHandle?.remove()
  appStateHandle?.remove()
  appUrlOpenHandle?.remove()
  appUpdateHandle?.remove()
  if (updateCheckTimer) window.clearTimeout(updateCheckTimer)
  if (githubConnectionToastTimer) window.clearTimeout(githubConnectionToastTimer)
  if (messageTimer) window.clearTimeout(messageTimer)
  if (autoSaveTimer) window.clearTimeout(autoSaveTimer)
  if (resumeTimer) window.clearTimeout(resumeTimer)
  if (filePickerRecoveryTimer) window.clearTimeout(filePickerRecoveryTimer)
  if (waterPhotoMessageTimer) window.clearTimeout(waterPhotoMessageTimer)
})
</script>

<template>
  <div class="app-shell">
    <input ref="waterGalleryInput" class="hidden-file-input" type="file" accept="image/*" :multiple="waterCheckIn.action === 'drank'" @change="handleWaterPhoto" />
    <input ref="waterCameraInput" class="hidden-file-input" type="file" accept="image/*" :capture="waterCheckIn.action === 'forced_state' ? 'user' : 'environment'" @change="handleWaterPhoto" />
    <header class="top-bar">
      <div>
        <p class="eyebrow">水息守护</p>
        <h1>{{ pageTitle }}</h1>
      </div>
      <div v-if="isSettingsPage || isPermissionGuidePage || isWaterFlowPage" class="top-actions">
        <button v-if="isSettingsPage" class="icon-button update-entry-button" aria-label="应用更新" title="应用更新" @click="openUpdatePanel">⬆</button>
        <button class="icon-button" aria-label="返回" @click="closeSettings">←</button>
      </div>
      <button v-else class="icon-button" aria-label="打开设置" @click="openSettings">⚙</button>
    </header>

    <main ref="contentScroller" class="app-content" :class="{ 'main-dashboard': state.activePage === 'water' || state.activePage === 'screen', 'water-dashboard-page': state.activePage === 'water', 'screen-dashboard-page': state.activePage === 'screen', 'water-check-in-page': state.activePage === 'waterCheckIn' }" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
      <template v-if="state.activePage === 'water'">
        <div class="water-dashboard-layout">
        <section class="today-water-card" aria-label="今日饮水统计">
          <div class="water-card-heading"><div><span>今日饮水</span><small>{{ todayWaterDateText }}</small></div><div class="water-drop" aria-hidden="true">💧</div></div>
          <div class="water-total"><strong>{{ state.waterHistory.todayTotalMl.toLocaleString('zh-CN') }}</strong><span>ml</span></div>
          <div class="water-today-stats">
            <div><span>今日记录</span><strong>{{ state.waterHistory.todayRecordCount }} 次</strong></div>
            <div><span>平均每次</span><strong>{{ todayWaterAverageMl }} ml</strong></div>
            <div><span>最近一次</span><strong>{{ lastDrankTimeText }}</strong></div>
          </div>
          <p>{{ state.waterHistory.todayRecordCount ? '每一次认真记录，都让今天的饮水节奏更清晰。' : '完成一次“已喝”验证后，今日饮水量会显示在这里。' }}</p>
        </section>
        <section class="card compact-dashboard-card water-dashboard-overview">
          <div class="dashboard-card-title">
            <h2>喝水提醒概览</h2>
            <div>
              <button class="ghost mini-button" :aria-label="`权限配置：${permissionSummaryText}`" :title="permissionSummaryText" @click="startPermissionGuide">权限</button>
              <button class="status-pill compact-status status-toggle" :class="{ enabled: state.status.enabled }" :aria-label="`${isEnabledText}，点击切换`" :aria-pressed="state.status.enabled" :title="`${isEnabledText}，点击切换`" :disabled="state.loading || state.saving" @click="toggleWaterReminder">{{ state.status.enabled ? '已开启' : '已关闭' }}</button>
            </div>
          </div>
          <p class="dashboard-note">到点后选择已喝或未喝；凭证照片、喝水量与状态验证仅保存在本机。</p>
          <p v-if="state.waterHistory.requiresStatePhoto" class="dashboard-warning">已连续三次未喝，下次喝水提醒将要求完成状态自拍验证。</p>
          <div class="summary-grid compact">
            <div><span>下一次提醒</span><strong>{{ nextReminderText }}</strong></div>
            <div><span>提醒时段</span><strong>{{ timeRangeText }}</strong></div>
            <div><span>随机间隔</span><strong>{{ state.status.minIntervalMinutes }} - {{ state.status.maxIntervalMinutes }} 分钟</strong></div>
            <div><span>未喝后重试</span><strong>{{ state.status.waterRetryMinutes }} 分钟</strong></div>
            <div><span>喝水铃声</span><strong>{{ waterSoundModeText }}</strong></div>
            <div><span>连续未喝</span><strong>{{ state.waterHistory.consecutiveNotDrank }}/3 次</strong></div>
            <div><span>最近记录</span><strong>{{ waterRecordText(latestWaterRecord) }}</strong></div>
          </div>
        </section>
        <section class="actions compact-actions water-dashboard-actions">
          <button class="ghost" :disabled="state.loading || state.saving" @click="openWaterCheckInPage('', false, 'drank')">记录喝水</button>
          <button class="ghost" :disabled="state.loading" @click="openWaterHistory">喝水历史</button>
        </section>
        </div>
      </template>

      <template v-else-if="state.activePage === 'screen'">
        <div class="screen-dashboard-layout">
        <section class="today-screen-card" aria-label="今日屏幕超时统计">
          <div class="screen-card-heading"><div><span>今日屏幕警示</span><small>{{ todayWaterDateText }}</small></div><div class="screen-warning-icon" aria-hidden="true">⏱</div></div>
          <div class="screen-alert-total"><strong>{{ todayScreenAlertCount }}</strong><span>次超时提醒</span></div>
          <div class="screen-today-stats">
            <div><span>今日亮屏</span><strong class="screen-duration-value"><b>{{ todayScreenOnDurationParts.hours }} 小时</b><small>{{ todayScreenOnDurationParts.minutes }} 分钟</small></strong></div>
            <div><span>亮屏次数</span><strong>{{ todayScreenOnCount }} 次</strong></div>
            <div><span>最近警示</span><strong>{{ lastScreenAlertText }}</strong></div>
          </div>
          <p>{{ todayScreenAlertCount ? `今天已经触发 ${todayScreenAlertCount} 次超时提醒，给眼睛和注意力留一点休息。` : '保持适度亮屏节奏，超时后及时息屏休息。' }}</p>
        </section>
        <section class="card compact-dashboard-card compact-screen-card">
          <div class="dashboard-card-title screen-card-title">
            <h2>亮屏/息屏记录</h2>
            <div>
              <button class="ghost mini-button" :aria-label="`权限配置：${permissionSummaryText}`" :title="permissionSummaryText" @click="startPermissionGuide">权限</button>
              <button class="status-pill compact-status status-toggle" :class="{ enabled: state.status.screenLimitEnabled }" :aria-label="`${screenLimitText}，点击切换`" :aria-pressed="state.status.screenLimitEnabled" :title="`${screenLimitText}，点击切换`" :disabled="state.loading || state.saving" @click="toggleScreenReminder">{{ screenLimitShortText }}</button>
            </div>
          </div>
          <p v-if="state.screenDataStale" class="dashboard-warning">数据刷新失败，以下内容可能不是最新状态。</p>
          <p v-if="screenRecoveryHint" class="rest-recovery-hint">{{ screenRecoveryHint }}</p>
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
        </div>
      </template>

      <template v-else-if="state.activePage === 'waterCheckIn'">
        <div class="water-check-in-layout" :class="`check-in-${waterCheckIn.action}`">
        <section class="card check-in-hero" :class="{ urgent: waterCheckIn.action === 'forced_state' }">
          <span class="check-in-icon">{{ waterCheckIn.action === 'forced_state' ? '🛟' : '💧' }}</span>
          <div>
            <h2>{{ waterCheckIn.action === 'forced_state' ? '请先确认当前状态' : isManualWaterCheckIn ? '记录喝水' : waterCheckIn.action === 'drank' ? '记录这次喝水' : '这次喝水了吗？' }}</h2>
            <p>{{ waterCheckIn.action === 'forced_state' ? '你已连续三次选择未喝。请上传一张当前状态自拍，照片只保存在本机。' : isManualWaterCheckIn ? '当前不是提醒会话，也可以随时记录饮水类型、容量、说明和凭证照片。' : '提醒已进入应用内处理；关闭本页不会截断独立播放的提示音。' }}</p>
          </div>
        </section>

        <section v-if="waterCheckIn.action === 'prompt'" class="card check-in-choice">
          <button class="check-in-choice-button drank" @click="waterCheckIn.action = 'drank'"><span>💧</span><strong>已喝</strong><small>记录类型、饮水量和凭证</small></button>
          <button class="check-in-choice-button missed" :disabled="waterCheckIn.submitting" @click="submitWaterNotDrank"><span>⏳</span><strong>暂时未喝</strong><small>{{ state.status.waterRetryMinutes }} 分钟后再次提醒</small></button>
          <p>连续三次选择未喝后，需要完成一次仅保存在本机的状态自拍验证。</p>
        </section>

        <section v-else-if="waterCheckIn.action === 'drank'" class="card form-card check-in-form compact-check-in-form">
          <h2 class="check-in-section-title">喝水类型</h2>
          <div class="drink-type-grid" role="radiogroup" aria-label="喝水类型">
            <button v-for="type in waterDrinkTypes" :key="type" class="ghost" :class="{ selected: waterCheckIn.drinkType === type }" @click="waterCheckIn.drinkType = type">{{ type }}</button>
          </div>
          <label class="compact-description">喝水说明（选填）<input v-model="waterCheckIn.description" type="text" maxlength="500" placeholder="例如：感冒颗粒、低糖饮料或茶叶种类" /></label>
          <h2 class="check-in-section-title">本次饮水量</h2>
          <div class="mode-switch" role="tablist" aria-label="饮水量录入方式">
            <button :class="{ active: waterCheckIn.amountMode === 'volume' }" @click="waterCheckIn.amountMode = 'volume'">直接输入容量</button>
            <button :class="{ active: waterCheckIn.amountMode === 'container' }" @click="waterCheckIn.amountMode = 'container'">容器称重换算</button>
          </div>
          <label v-if="waterCheckIn.amountMode === 'volume'">喝水量（毫升）<input v-model.number="waterCheckIn.directMl" type="number" min="1" max="5000" step="1" inputmode="decimal" placeholder="例如 300" /></label>
          <template v-else>
            <label>饮水容器<select v-model="waterCheckIn.containerId"><option v-for="item in state.waterContainers" :key="item.id" :value="item.id">{{ item.name }} · 空重 {{ item.emptyWeightGrams }}g</option></select></label>
            <label>容器加水总重量（克）<input v-model.number="waterCheckIn.totalWeightGrams" type="number" min="0" max="105000" step="0.1" inputmode="decimal" placeholder="放上秤后输入总重量" /></label>
            <div class="calculation-card"><span>自动换算</span><strong>{{ calculatedWaterMl }} ml</strong><small>总重量 {{ Number(waterCheckIn.totalWeightGrams || 0) }}g − 空容器 {{ selectedWaterContainer?.emptyWeightGrams || 0 }}g；按 1g 水≈1ml 计算</small></div>
          </template>
          <div class="check-in-photo-row">
            <button class="photo-picker photo-picker-button" type="button" @click="openWaterPhotoSourceDialog"><span>饮水凭证照片（选填，仅本机保存）</span><strong>{{ waterPhotoPickerText }}</strong></button>
            <div v-if="waterCheckIn.photos.length" class="selected-photo-strip" aria-label="已选择的凭证照片">
              <div v-for="(photo, index) in waterCheckIn.photos" :key="photo.id" class="selected-photo-item">
                <button class="photo-preview-thumb" :aria-label="`预览第 ${index + 1} 张凭证照片`" @click="showWaterPhoto(index)"><img :src="`data:${photo.mimeType};base64,${photo.dataBase64}`" :alt="`第 ${index + 1} 张凭证照片缩略图`" /></button>
                <button class="selected-photo-remove" :aria-label="`删除第 ${index + 1} 张凭证照片`" @click="removeWaterPhoto(index)">×</button>
              </div>
            </div>
          </div>
          <p v-if="waterCheckIn.message" class="inline-message compact-inline-message" role="status">{{ waterCheckIn.message }}</p>
          <div class="actions check-in-actions"><button :disabled="waterCheckIn.submitting" @click="submitWaterDrank">{{ waterCheckIn.submitting ? '正在保存…' : '保存本次记录' }}</button><button class="ghost" @click="isManualWaterCheckIn ? switchPage('water') : waterCheckIn.action = 'prompt'">{{ isManualWaterCheckIn ? '取消记录' : '返回选择' }}</button></div>
        </section>

        <section v-else class="card form-card state-check-card">
          <h2>状态自拍验证</h2>
          <p>照片只写入应用本地目录，不会上传网络。完成后会重新安排稍后提醒。</p>
          <div class="check-in-photo-row">
            <button class="photo-picker photo-picker-button urgent-picker" type="button" @click="openWaterPhotoSourceDialog"><span>当前状态自拍</span><strong>{{ waterPhotoPickerText }}</strong></button>
            <div v-if="waterCheckIn.photos.length" class="selected-photo-strip single-photo">
              <div class="selected-photo-item"><button class="photo-preview-thumb urgent" aria-label="预览状态自拍" @click="showWaterPhoto(0)"><img :src="waterCheckInPhotoPreview" alt="所选状态自拍缩略图" /></button><button class="selected-photo-remove" aria-label="删除状态自拍" @click="removeWaterPhoto(0)">×</button></div>
            </div>
          </div>
          <p v-if="waterCheckIn.message" class="inline-message warning">{{ waterCheckIn.message }}</p>
          <button class="full-button" :disabled="waterCheckIn.submitting" @click="submitWaterStateCheck">{{ waterCheckIn.submitting ? '正在保存…' : '完成验证' }}</button>
        </section>
        </div>
      </template>

      <template v-else-if="state.activePage === 'waterHistory'">
        <section class="history-summary">
          <div><span>今日饮水</span><strong>{{ state.waterHistory.todayTotalMl.toLocaleString('zh-CN') }} ml</strong></div>
          <div><span>今日次数</span><strong>{{ state.waterHistory.todayRecordCount }} 次</strong></div>
        </section>
        <section v-if="!historyShowingTrash" class="card history-chart-card">
          <div class="history-chart-heading"><div><h2>最近七天</h2><p>饮水量与饮水次数</p></div><button v-if="historySelectedDayKey" class="ghost mini-button" @click="clearHistoryDayFilter">显示全部</button></div>
          <div class="history-chart-legend"><span><i class="amount"></i>饮水量</span><span><i class="count"></i>次数</span></div>
          <div class="history-chart" role="list" aria-label="最近七天饮水趋势">
            <button v-for="day in historyChartDays" :key="day.key" class="history-chart-day" :class="{ selected: historySelectedDayKey === day.key }" :aria-label="`${day.label}，饮水 ${day.amountMl} 毫升，${day.count} 次`" :aria-pressed="historySelectedDayKey === day.key" @click="selectHistoryDay(day.key)">
              <span class="history-chart-values"><strong>{{ Math.round(day.amountMl) }}ml</strong><small>{{ day.count }}次</small></span>
              <span class="history-bars"><i class="amount" :style="{ height: historyAmountBarHeight(day.amountMl) }"></i><i class="count" :style="{ height: historyCountBarHeight(day.count) }"></i></span>
              <span>{{ day.weekday }}</span><small>{{ day.label }}</small>
            </button>
          </div>
        </section>
        <div class="history-view-switch" role="tablist" aria-label="喝水记录分类">
          <button class="ghost" :class="{ selected: !historyShowingTrash }" @click="showCurrentWaterHistory">当前记录（{{ state.waterHistory.records.length }}）</button>
          <button class="ghost" :class="{ selected: historyShowingTrash }" @click="showDeletedWaterHistory">本地回收站（{{ state.waterDeletedRecords.length }}）</button>
          <button v-if="!historyShowingTrash" class="ghost history-filter-button" :class="{ selected: historyHideNotDrank }" :aria-pressed="historyHideNotDrank" @click="toggleHistoryHideNotDrank">{{ historyHideNotDrank ? '显示未喝记录' : '隐藏未喝记录' }}</button>
        </div>
        <section v-if="!historyGroups.length" class="card empty-history"><span>{{ historyShowingTrash ? '♻️' : '💧' }}</span><h2>{{ historyShowingTrash ? '回收站为空' : historySelectedDayKey ? '当天没有记录' : '还没有喝水记录' }}</h2><p>{{ historyShowingTrash ? '软删除的记录会保留在本机，并可在这里恢复。' : historyHideNotDrank ? '当前筛选条件下没有可显示的记录。' : '完成一次饮水记录后，类型、说明和凭证照片会显示在这里。' }}</p></section>
        <section v-else class="history-groups">
          <section v-for="group in historyGroups" :key="group.key" class="history-day-group">
            <button class="history-day-heading" :aria-expanded="isHistoryGroupExpanded(group.key, group.isToday)" @click="toggleHistoryGroup(group.key, group.isToday)"><span><strong>{{ group.label }}</strong><small>{{ group.records.length }} 条记录</small></span><b>{{ isHistoryGroupExpanded(group.key, group.isToday) ? '收起' : '展开' }}⌄</b></button>
            <div v-if="isHistoryGroupExpanded(group.key, group.isToday)" class="history-list">
          <article v-for="record in group.records" :key="record.id" class="history-item" :class="{ deleted: historyShowingTrash }">
            <div class="history-marker" :class="record.type">{{ record.type === 'drank' ? '💧' : record.type === 'state_check' ? '📷' : '⏳' }}</div>
            <div class="history-content">
              <div class="history-record-title"><strong>{{ waterRecordText(record) }}</strong></div>
              <div class="history-meta"><time>{{ formatTimestamp(record.timestamp) }}</time><small v-if="record.entryMode === 'container'">{{ record.containerName || '容器' }}称重</small></div>
              <div v-if="editingRecordId === record.id" class="history-editor">
                <label>喝水说明（可补充或清空）<textarea v-model="editingDescription" rows="3" maxlength="500" /></label>
                <div><button @click="saveWaterRecordDescription(record)">保存说明</button><button class="ghost" @click="cancelEditWaterRecord">取消</button></div>
              </div>
              <p v-else-if="record.description" class="history-description">{{ record.description }}</p>
              <div class="history-actions">
                <button v-if="waterRecordPhotoNames(record).length" class="ghost mini-button" @click="toggleWaterHistoryPhoto(record)">{{ state.waterHistoryImages[record.id] ? '收起图片' : `查看图片（${waterRecordPhotoNames(record).length}）` }}</button>
                <template v-if="historyShowingTrash"><button class="ghost mini-button" @click="restoreWaterRecord(record)">恢复记录</button></template>
                <template v-else><button v-if="record.type === 'drank'" class="ghost mini-button" @click="beginEditWaterRecord(record)">{{ record.description ? '修改说明' : '补充说明' }}</button><button class="danger-ghost mini-button" @click="deleteWaterRecord(record)">删除记录</button></template>
              </div>
              <div v-if="state.waterHistoryImages[record.id]" class="history-photo-grid"><img v-for="(photo, index) in state.waterHistoryImages[record.id]" :key="index" :src="photo" :alt="`喝水记录本地凭证照片 ${index + 1}`" loading="lazy" /></div>
            </div>
          </article>
            </div>
          </section>
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
        <section class="card form-card" @change="autoSaveSettings('water')">
          <h2>喝水提醒设置</h2>
          <div class="form-grid">
            <label>开始时间<input :value="timeValue(state.status.startHour, state.status.startMinute)" type="time" @input="updateTime('start', $event)" /></label>
            <label>结束时间<input :value="timeValue(state.status.endHour, state.status.endMinute)" type="time" @input="updateTime('end', $event)" /></label>
            <label>最小间隔（分钟）<input v-model.number="state.status.minIntervalMinutes" type="number" min="15" max="360" /></label>
            <label>最大间隔（分钟）<input v-model.number="state.status.maxIntervalMinutes" type="number" min="15" max="360" /></label>
            <label>未喝后再次提醒（分钟）<input v-model.number="state.status.waterRetryMinutes" type="number" min="1" max="180" /></label>
            <label class="next-reminder-field">下次提醒时间<input v-model="state.nextReminderInput" type="datetime-local" /><small class="field-note">清空后会自动设置为下一分钟</small></label>
          </div>
          <label>通知标题<input v-model="state.status.waterNotificationTitle" type="text" /></label>
          <label>通知内容<textarea v-model="state.status.waterNotificationText" rows="3" /></label>
        </section>
        <section class="card form-card container-settings">
          <div class="section-heading"><div><h2>饮水容器</h2><p>称重录入时，用“容器加水总重量 − 空容器重量”换算饮水毫升数。</p></div><button class="ghost mini-button" @click="addWaterContainer">添加容器</button></div>
          <div v-for="item in state.waterContainers" :key="item.id || `draft-${state.waterContainers.indexOf(item)}`" class="container-row">
            <label>容器名称<input v-model="item.name" type="text" maxlength="30" @change="saveWaterContainer(item)" /></label>
            <label>空容器重量（克）<input v-model.number="item.emptyWeightGrams" type="number" min="0.1" max="100000" step="0.1" inputmode="decimal" @change="saveWaterContainer(item)" /></label>
            <div><button class="danger-ghost" @click="deleteWaterContainer(item)">删除</button></div>
          </div>
          <p class="sound-note">已预置“常用水杯 241.5g”。容器使用稳定 ID 存储，修改应用包名时可随饮水数据一起迁移。</p>
          <div class="data-transfer">
            <button class="ghost" :disabled="state.loading" @click="exportWaterData">导出数据与照片</button>
            <label class="file-picker">导入迁移档案<input type="file" accept="application/zip,.zip,.waterdata" :disabled="state.loading" @click="beginFilePicker" @change="importWaterData" /></label>
          </div>
          <p class="sound-note">迁移档案采用与包名无关的版本化格式，包含容器、历史记录和本地图片；导入前不会读取绝对文件路径。</p>
        </section>
        <section class="card form-card" @change="autoSaveSettings('water')">
          <h2>喝水提醒铃声</h2>
          <div class="sound-status-list"><div><span>提示音</span><strong>{{ waterSoundModeText }}</strong></div><div><span>喝水音量</span><strong>{{ state.status.waterVolumePercent }}%</strong></div><div><span>音量说明</span><strong>独立播放任务，关闭弹窗后仍播放到结束；受系统铃声/勿扰约束</strong></div><div><span>通知权限</span><strong>{{ state.permissions?.notifications ?? 'unknown' }}</strong></div><div><span>全屏提醒权限</span><strong>{{ state.permissions?.fullScreenIntent ?? 'unknown' }}</strong></div><div><span>精确闹钟权限</span><strong>{{ state.permissions?.exactAlarms ?? 'unknown' }}</strong></div></div>
          <label>喝水提醒音量 {{ state.status.waterVolumePercent }}%<input v-model.number="state.status.waterVolumePercent" type="range" min="0" max="100" /></label>
          <div class="sound-options">
            <button class="ghost" :class="{ selected: state.status.waterSoundMode === 'default' }" :disabled="state.loading || state.saving" @click="setSoundMode('water', 'default')">使用系统默认提示音</button>
            <label class="file-picker">导入自定义音频<input type="file" accept="audio/*,.mp3,.wav,.ogg,.m4a,.aac,.flac" :disabled="state.loading || state.importingSound === 'water'" @click="beginFilePicker" @change.stop="importCustomSound('water', $event)" /></label>
          </div>
        </section>
        <p class="auto-save-note">输入内容在焦点移出后自动保存，无需手动提交。</p>
        <section class="actions"><button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('water')">测试喝水提醒</button><button class="ghost" :disabled="state.loading || state.saving" @click="() => refreshStatus(true)">刷新状态</button><button class="ghost" @click="openPermissionSettings('exact')">精确闹钟设置</button><button class="ghost" @click="openPermissionSettings('overlay')">悬浮窗设置</button><button class="ghost" @click="openPermissionSettings('fullScreen')">全屏提醒设置</button><button class="ghost" @click="openPermissionSettings('waterNotification')">喝水通知设置</button></section>
      </template>

      <template v-else>
        <section class="card form-card" @change="autoSaveSettings('screen')">
          <h2>屏幕记录设置</h2>
          <div class="form-grid">
            <label>屏幕超时提醒启用<select v-model="state.status.screenLimitEnabled"><option :value="true">开启</option><option :value="false">关闭</option></select></label>
            <label>亮屏时长阈值（分钟）<input v-model.number="state.status.screenOnLimitMinutes" type="number" min="0" max="1440" /></label>
            <label>累计息屏恢复（分钟）<input v-model.number="state.status.requiredScreenOffMinutes" type="number" min="1" max="1440" /></label>
            <label>取消几次后强制熄屏<input v-model.number="state.status.cancelBeforeLockCount" type="number" min="1" max="99" /></label>
          </div>
          <p class="sound-note">触发后须在设置时长的 2 倍时间内累计完成息屏；连续取消达到设置次数后会尝试执行设备管理锁屏。</p>
        </section>
        <section class="card form-card" @change="autoSaveSettings('screen')">
          <h2>屏幕提醒铃声</h2>
          <div class="sound-status-list"><div><span>提示音</span><strong>{{ screenSoundModeText }}</strong></div><div><span>屏幕音量</span><strong>{{ state.status.screenVolumePercent }}%</strong></div><div><span>音量说明</span><strong>独立播放任务，关闭弹窗后仍播放到结束；受系统铃声/勿扰约束</strong></div><div><span>通知权限</span><strong>{{ state.permissions?.notifications ?? 'unknown' }}</strong></div><div><span>全屏提醒权限</span><strong>{{ state.permissions?.fullScreenIntent ?? 'unknown' }}</strong></div><div><span>悬浮窗权限</span><strong>{{ state.permissions?.overlays ?? 'unknown' }}</strong></div><div><span>使用情况权限</span><strong>{{ state.permissions?.usageStats ?? 'unknown' }}</strong></div><div><span>精确闹钟权限</span><strong>{{ state.permissions?.exactAlarms ?? 'unknown' }}</strong></div></div>
          <label>屏幕提醒音量 {{ state.status.screenVolumePercent }}%<input v-model.number="state.status.screenVolumePercent" type="range" min="0" max="100" /></label>
          <div class="sound-options">
            <button class="ghost" :class="{ selected: state.status.screenSoundMode === 'default' }" :disabled="state.loading || state.saving" @click="setSoundMode('screen_limit', 'default')">使用系统默认提示音</button>
            <label class="file-picker">导入自定义音频<input type="file" accept="audio/*,.mp3,.wav,.ogg,.m4a,.aac,.flac" :disabled="state.loading || state.importingSound === 'screen_limit'" @click="beginFilePicker" @change.stop="importCustomSound('screen_limit', $event)" /></label>
          </div>
        </section>
        <p class="auto-save-note">输入内容在焦点移出后自动保存，无需手动提交。</p>
        <section class="actions"><button class="ghost" :disabled="state.loading || state.saving" @click="testNotification('screen_limit')">测试屏幕提醒</button><button class="ghost" :disabled="state.loading || state.saving" @click="() => refreshScreenDashboard(true)">刷新屏幕记录</button><button class="ghost" @click="openPermissionSettings('usage')">使用情况权限</button><button class="ghost" @click="openPermissionSettings('exact')">精确闹钟设置</button><button class="ghost" @click="openPermissionSettings('overlay')">悬浮窗设置</button><button class="ghost" @click="openPermissionSettings('fullScreen')">全屏提醒设置</button><button class="ghost" @click="openPermissionSettings('screenNotification')">屏幕通知设置</button></section>
      </template>

      <p v-if="state.message && (state.activePage === 'water' || state.activePage === 'screen')" class="snackbar" role="status" aria-live="polite">{{ state.message }}</p>
      <p v-else-if="state.message" class="message" role="status" aria-live="polite">{{ state.message }}</p>
      <p v-if="isSettingsPage" class="hint">如使用 MIUI/HyperOS，请在权限配置中允许通知、自启动、锁屏显示与不限制省电。</p>
    </main>

    <div v-if="photoSourceDialogOpen" class="photo-preview-overlay" role="dialog" aria-modal="true" aria-label="选择照片来源" @click.self="photoSourceDialogOpen = false">
      <section class="photo-source-dialog">
        <h2>{{ waterCheckIn.action === 'forced_state' ? '添加状态自拍' : '添加凭证照片' }}</h2>
        <p>请选择照片来源。照片只会保存在本机。</p>
        <button @click="chooseWaterPhotoSource('camera')"><span>📷</span><strong>调用相机拍照</strong></button>
        <button class="ghost" @click="chooseWaterPhotoSource('gallery')"><span>🖼️</span><strong>从相册选择{{ waterCheckIn.action === 'drank' ? '（可多选）' : '' }}</strong></button>
        <button class="ghost photo-source-cancel" @click="photoSourceDialogOpen = false">取消</button>
      </section>
    </div>

    <div v-if="photoPreviewOpen && waterCheckInPhotoPreview" class="photo-preview-overlay" role="dialog" aria-modal="true" aria-label="图片预览" @click.self="photoPreviewOpen = false">
      <section class="photo-preview-dialog">
        <button class="photo-preview-close" aria-label="关闭图片预览" @click="photoPreviewOpen = false">×</button>
        <img :src="waterCheckInPhotoPreview" :alt="waterCheckIn.photos[photoPreviewIndex]?.name || '所选图片预览'" />
        <p>{{ waterCheckIn.photos[photoPreviewIndex]?.name || '所选图片' }} · 第 {{ photoPreviewIndex + 1 }} / {{ waterCheckIn.photos.length }} 张</p>
        <button class="danger-ghost photo-preview-delete" @click="removeWaterPhoto(photoPreviewIndex)">删除这张照片</button>
      </section>
    </div>

    <div v-if="updatePanelOpen" class="update-overlay" role="dialog" aria-modal="true" aria-label="应用更新" @click.self="updatePanelOpen = false">
      <section class="update-dialog update-panel">
        <button class="update-panel-close" aria-label="关闭应用更新" @click="updatePanelOpen = false">×</button>
        <div class="section-heading">
          <div><h2>应用更新</h2><p>通过 GitHub Release 检查、校验并安装新版本。</p></div>
          <span class="status-pill" :class="{ enabled: appUpdate.available }">v{{ appUpdate.currentVersionName }}</span>
        </div>
        <div class="update-status-grid">
          <div><span>当前版本</span><strong>{{ appUpdate.currentVersionName }}（{{ appUpdate.currentVersionCode }}）</strong></div>
          <div><span>更新渠道</span><strong>{{ appUpdate.channel === 'stable' ? '稳定版' : '测试版（含 Pre-release）' }}</strong></div>
          <div><span>检查状态</span><strong>{{ updateStatusText }}</strong></div>
          <div v-if="appUpdate.available"><span>最新版本</span><strong>{{ appUpdate.latestVersionName }}（{{ appUpdate.latestVersionCode }}）</strong></div>
        </div>
        <label>更新渠道<select :value="appUpdate.channel" :disabled="updateBusy" @change="changeUpdateChannel"><option value="stable">稳定版</option><option value="beta">测试版（含 Pre-release）</option></select></label>
        <progress v-if="appUpdate.status === 'running' || appUpdate.status === 'pending'" class="update-progress" max="100" :value="appUpdate.progress">{{ appUpdate.progress }}%</progress>
        <p v-if="appUpdate.error" class="inline-message warning">{{ appUpdate.error }}</p>
        <div class="update-panel-actions">
          <button class="ghost" :disabled="githubTestBusy" :aria-busy="githubTestBusy" @click="testGithubConnection">测试连接</button>
          <button class="ghost" :disabled="updateBusy" @click="checkForAppUpdate(true)">{{ updateBusy ? '正在检查…' : '检查更新' }}</button>
          <button v-if="appUpdate.available" :disabled="updateBusy || appUpdate.status === 'running' || appUpdate.status === 'pending'" @click="downloadAndInstallUpdate">{{ updateActionText }}</button>
        </div>
        <p class="sound-note">应用每 12 小时自动检查一次。若连接测试失败，请切换网络或检查代理后再更新；安装前仍会显示 Android 系统确认页。</p>
      </section>
      <p v-if="githubConnectionToast" class="connection-toast" :class="{ failed: githubConnectionToast === '连接失败' }" role="status" aria-live="polite">{{ githubConnectionToast }}</p>
    </div>

    <div v-if="shouldShowUpdateDialog" class="update-overlay" role="dialog" aria-modal="true" aria-label="发现应用更新">
      <section class="update-dialog">
        <div class="update-dialog-icon">⬆</div>
        <h2>发现新版本 {{ appUpdate.latestVersionName }}</h2>
        <p>{{ appUpdate.prerelease ? '这是测试版更新。' : '这是稳定版更新。' }} {{ formattedUpdateSize ? `安装包约 ${formattedUpdateSize}。` : '' }}</p>
        <ul v-if="appUpdate.releaseNotes?.length"><li v-for="note in appUpdate.releaseNotes" :key="note">{{ note }}</li></ul>
        <progress v-if="appUpdate.status === 'running' || appUpdate.status === 'pending'" class="update-progress" max="100" :value="appUpdate.progress">{{ appUpdate.progress }}%</progress>
        <p v-if="appUpdate.error" class="inline-message warning">{{ appUpdate.error }}</p>
        <div class="update-dialog-actions">
          <button v-if="!appUpdate.mandatory" class="ghost" :disabled="updateBusy" @click="dismissUpdateDialog">稍后</button>
          <button :disabled="updateBusy || appUpdate.status === 'running' || appUpdate.status === 'pending'" @click="downloadAndInstallUpdate">{{ updateActionText }}</button>
        </div>
      </section>
    </div>

    <nav v-if="!isSettingsPage && !isPermissionGuidePage && !isWaterFlowPage" class="bottom-tabs" aria-label="主功能切换">
      <button :class="{ active: state.activePage === 'water' || state.activePage === 'waterSettings' }" @click="switchPage('water')">💧<span>喝水提醒</span></button>
      <button :class="{ active: state.activePage === 'screen' || state.activePage === 'screenSettings' }" @click="switchPage('screen')">📱<span>屏幕记录</span></button>
    </nav>
  </div>
</template>
