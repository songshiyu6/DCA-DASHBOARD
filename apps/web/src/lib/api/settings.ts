import type { AppSettings, AppTimezone } from '../../types'
import { apiMeta, request, type ApiResponse } from './transport'
import { isRecord, normalizeResult, normalizeSettings } from './normalize'

function timezone(value: unknown, fallback: AppTimezone): AppTimezone {
  return value === 'America/New_York' || value === 'Asia/Shanghai' ? value : fallback
}

function normalizeSettingsWithTimezones(value: unknown): AppSettings {
  const normalized = normalizeSettings(value)
  const body = isRecord(value) ? value : {}
  return {
    ...normalized,
    marketTimezone: timezone(body.marketTimezone, 'America/New_York'),
    displayTimezone: timezone(body.displayTimezone, 'Asia/Shanghai'),
  }
}

export const settingsApi = {
  getSettings: async (): ApiResponse<AppSettings> => normalizeResult(await request<unknown>('/settings'), normalizeSettingsWithTimezones, apiMeta()),
  updateSettings: async (patch: Partial<AppSettings>): ApiResponse<AppSettings> => normalizeResult(await request<unknown>('/settings', {
    method: 'PUT',
    body: JSON.stringify(patch),
  }), normalizeSettingsWithTimezones, apiMeta()),
}
