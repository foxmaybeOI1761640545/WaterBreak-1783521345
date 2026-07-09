import { registerPlugin } from '@capacitor/core'

export type ReminderSoundMode = 'default' | 'custom'
export type ReminderType = 'water' | 'screen_limit'
export type PermissionValue = 'granted' | 'denied' | 'prompt' | 'unknown' | 'unavailable'

export interface ReminderConfig {
  enabled: boolean
  startHour: number
  startMinute: number
  endHour: number
  endMinute: number
  minIntervalMinutes: number
  maxIntervalMinutes: number
  nextReminderTime?: number
  waterNotificationTitle: string
  waterNotificationText: string
  waterSoundMode: ReminderSoundMode
  waterCustomSoundUri?: string
  waterCustomSoundName?: string
  waterVolumePercent: number
  screenLimitEnabled: boolean
  screenOnLimitMinutes: number
  requiredScreenOffMinutes: number
  cancelBeforeLockCount: number
  cancelCycleCount?: number
  screenSoundMode: ReminderSoundMode
  screenCustomSoundUri?: string
  screenCustomSoundName?: string
  screenVolumePercent: number
  waterAlarmScheduled?: boolean
  waterAlarmExact?: boolean
  waterAlarmReason?: string
  screenAlarmScheduled?: boolean
  screenAlarmExact?: boolean
  screenAlarmReason?: string
  notificationTitle?: string
  notificationText?: string
  soundMode?: ReminderSoundMode
  customSoundUri?: string
  customSoundName?: string
}

export interface ReminderStatus extends ReminderConfig {
  nextReminderTime: number
}

export interface PermissionStatus {
  notifications: PermissionValue
  exactAlarms: PermissionValue
  fullScreenIntent?: PermissionValue
  overlays?: PermissionValue
  usageStats?: PermissionValue
  waterChannelEnabled?: boolean
  waterChannelImportance?: number
  screenChannelEnabled?: boolean
  screenChannelImportance?: number
  deviceAdmin?: PermissionValue
  batteryOptimization?: PermissionValue
  manufacturerSettingsAvailable?: boolean
}

export interface ScreenStateStatus {
  currentScreenState: 'on' | 'off' | 'unknown'
  currentScreenStateSince: number
  currentScreenStateDurationMs: number
  lastScreenOnTime: number
  lastScreenOffTime: number
  restRequired?: boolean
  restStartedAt?: number
  nextScreenCheckAt?: number
  trackingReliable?: boolean
  trackingNote: string
}

export interface AlertResult {
  ok: boolean
  posted: boolean
  overlayShown: boolean
  inAppShown: boolean
  fullScreenAttempted: boolean
  fallbackUsed: boolean
  reason: string
  channelImportance?: number
  notification?: { ok: boolean; reason: string }
  overlay?: { ok: boolean; reason: string }
  centerDialog?: { ok: boolean; reason: string }
  sound?: { ok: boolean; reason: string }
  vibration?: { ok: boolean; reason: string }
}

export interface CustomSoundPayload {
  type: ReminderType
  fileName: string
  mimeType: string
  dataBase64: string
}

export interface ReminderTypePayload { type: ReminderType }

export interface WaterReminderPlugin {
  startReminder(config: ReminderConfig): Promise<ReminderStatus>
  stopReminder(): Promise<ReminderStatus>
  getStatus(): Promise<ReminderStatus>
  showTestNotification(payload?: ReminderTypePayload): Promise<AlertResult>
  saveCustomSound(payload: CustomSoundPayload): Promise<ReminderStatus>
  useDefaultSound(payload?: ReminderTypePayload): Promise<ReminderStatus>
  getScreenState(): Promise<ScreenStateStatus>
  requestNotificationPermission(): Promise<PermissionStatus>
  getPermissionStatus(): Promise<PermissionStatus>
  openExactAlarmSettings(): Promise<{ opened: boolean }>
  openNotificationSettings(payload?: ReminderTypePayload): Promise<{ opened: boolean }>
  openOverlaySettings(): Promise<{ opened: boolean }>
  openUsageAccessSettings(): Promise<{ opened: boolean }>
  openFullScreenIntentSettings(): Promise<{ opened: boolean }>
  openDeviceAdminSettings(): Promise<{ opened: boolean }>
  openAppDetailsSettings(): Promise<{ opened: boolean }>
  openManufacturerPermissionSettings(): Promise<{ opened: boolean }>
}

export const WaterReminder = registerPlugin<WaterReminderPlugin>('WaterReminder')
