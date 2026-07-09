import { registerPlugin } from '@capacitor/core'

export type ReminderSoundMode = 'default' | 'custom'
export type ReminderType = 'water' | 'screen_limit'
export type PermissionValue = 'granted' | 'denied' | 'prompt' | 'unknown' | 'unavailable' | 'optional'

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
  screenCyclePhase?: 'idle' | 'alerting' | 'waiting_rest' | 'force_lock' | 'grace'
  screenCycleLimit?: number
  screenCycleActiveSessionId?: string
  screenCycleSessionStartedAt?: number
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
  cycleCancelCount?: number
  cycleLimit?: number
  cyclePhase?: 'idle' | 'alerting' | 'waiting_rest' | 'force_lock' | 'grace'
  cycleActiveSessionId?: string
  cycleSessionStartedAt?: number
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
export interface SettingsLaunchResult { opened: boolean; target?: string; fallback?: boolean; reason?: string }

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
  openExactAlarmSettings(): Promise<SettingsLaunchResult>
  openNotificationSettings(payload?: ReminderTypePayload): Promise<SettingsLaunchResult>
  openOverlaySettings(): Promise<SettingsLaunchResult>
  openUsageAccessSettings(): Promise<SettingsLaunchResult>
  openFullScreenIntentSettings(): Promise<SettingsLaunchResult>
  openDeviceAdminSettings(): Promise<SettingsLaunchResult>
  openAppDetailsSettings(): Promise<SettingsLaunchResult>
  openManufacturerPermissionSettings(payload?: { target?: string }): Promise<SettingsLaunchResult>
  openBatteryOptimizationSettings(): Promise<SettingsLaunchResult>
}

export const WaterReminder = registerPlugin<WaterReminderPlugin>('WaterReminder')
