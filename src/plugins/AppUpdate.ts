import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'

export type UpdateChannel = 'stable' | 'beta'
export type UpdateDownloadStatus = 'idle' | 'pending' | 'running' | 'downloaded' | 'failed'

export interface AppVersionInfo {
  currentVersionName: string
  currentVersionCode: number
  repository: string
  channel: UpdateChannel
}

export interface AvailableUpdate {
  available: boolean
  latestVersionName?: string
  latestVersionCode?: number
  minimumSupportedVersionCode?: number
  mandatory?: boolean
  prerelease?: boolean
  publishedAt?: string
  releaseNotes?: string[]
  apkUrl?: string
  sha256?: string
  size?: number
  releasePageUrl?: string
}

export interface UpdateState extends AppVersionInfo, AvailableUpdate {
  status: UpdateDownloadStatus
  progress: number
  downloadedBytes: number
  totalBytes: number
  downloadedFile?: string
  error?: string
  lastCheckedAt?: number
}

export interface InstallUpdateResult {
  started: boolean
  requiresPermission: boolean
}

export interface AppUpdatePlugin {
  getUpdateState(): Promise<UpdateState>
  setUpdateChannel(options: { channel: UpdateChannel }): Promise<UpdateState>
  checkForUpdate(options?: { channel?: UpdateChannel; manual?: boolean }): Promise<UpdateState>
  testGithubConnection(): Promise<{ ok: boolean; message: string; latencyMs: number; repository: string }>
  downloadUpdate(): Promise<UpdateState>
  installDownloadedUpdate(): Promise<InstallUpdateResult>
  openUnknownSourceSettings(): Promise<{ opened: boolean }>
  addListener(eventName: 'updateDownloadState', listenerFunc: (state: UpdateState) => void): Promise<PluginListenerHandle>
}

export const AppUpdate = registerPlugin<AppUpdatePlugin>('AppUpdate')
