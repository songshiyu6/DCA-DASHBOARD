import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Database, KeyRound, Monitor, Moon, Save, Sun } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { api } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import { DataStateBanner, ErrorState, LoadingBlock } from '../components/DataState'
import { Panel } from '../components/Panel'
import type { AppSettings, AppTimezone } from '../types'

const DEFAULT_MARKET_TIMEZONE: AppTimezone = 'America/New_York'
const DEFAULT_DISPLAY_TIMEZONE: AppTimezone = 'Asia/Shanghai'

function applyTheme(theme: AppSettings['theme']): void {
  const resolved = theme === 'SYSTEM' ? (typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark') : theme.toLowerCase()
  document.documentElement.dataset.theme = resolved
}

function persistDisplayTimezone(timezone?: AppTimezone): void {
  localStorage.setItem('dca-display-timezone', timezone ?? DEFAULT_DISPLAY_TIMEZONE)
}

export function SettingsPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const settings = useQuery({ queryKey: queryKeys.settings, queryFn: api.getSettings })
  const [draft, setDraft] = useState<AppSettings | null>(null)
  const save = useMutation({ mutationFn: (patch: Partial<AppSettings>) => api.updateSettings(patch), onSuccess: (result) => { setDraft(result.data); applyTheme(result.data.theme); persistDisplayTimezone(result.data.displayTimezone); void queryClient.invalidateQueries({ queryKey: queryKeys.settings }) } })
  useEffect(() => { if (settings.data) { setDraft(settings.data.data); applyTheme(settings.data.data.theme); persistDisplayTimezone(settings.data.data.displayTimezone) } }, [settings.data])

  if (settings.isError) return <div className="page"><ErrorState onRetry={() => void settings.refetch()} /></div>
  if (settings.isLoading || !draft) return <div className="page"><div className="page-intro"><LoadingBlock lines={2} /></div><div className="content-grid settings-grid"><Panel><LoadingBlock lines={7} /></Panel><Panel><LoadingBlock lines={7} /></Panel></div></div>
  const update = <T extends keyof AppSettings>(key: T, value: AppSettings[T]) => setDraft((current) => current ? { ...current, [key]: value } : current)
  const marketTimezone = draft.marketTimezone ?? DEFAULT_MARKET_TIMEZONE
  const displayTimezone = draft.displayTimezone ?? DEFAULT_DISPLAY_TIMEZONE
  return <div className="page settings-page">
    <div className="page-intro"><div><span className="page-eyebrow">{t('settings.eyebrow')}</span><h1>{t('settings.title')}</h1><p>{t('settings.subtitle')}</p></div><button type="button" className="button button-primary" onClick={() => save.mutate({ theme: draft.theme, marketTimezone, displayTimezone, primaryProvider: draft.primaryProvider, fallbackProvider: draft.fallbackProvider })} disabled={save.isPending}><Save size={15} />{save.isPending ? t('settings.saving') : t('common.save')}</button></div>
    <DataStateBanner status={settings.data?.meta.status ?? 'STALE'} message={settings.data?.meta.message} source={settings.data?.meta.source === 'FIXTURE' ? t('common.demoData') : settings.data?.meta.source} asOf={settings.data?.meta.asOf} retrievedAt={settings.data?.meta.retrievedAt} />
    <div className="content-grid settings-grid"><Panel title={t('settings.preferences')} detail={t('settings.preferencesHint')}><div className="settings-form"><div className="setting-row"><div><strong>{t('settings.baseCurrency')}</strong><small>{t('settings.currencyHint')}</small></div><span className="setting-value">USD</span></div><div className="setting-row"><div><label htmlFor="settings-market-timezone"><strong>{t('settings.marketTimezone', { defaultValue: 'Market timezone' })}</strong></label><small>{t('settings.marketTimezoneHint', { defaultValue: 'Controls US-market business dates and price-related time boundaries.' })}</small></div><select id="settings-market-timezone" value={marketTimezone} onChange={(event) => update('marketTimezone', event.target.value as AppTimezone)}><option value="America/New_York">New York (America/New_York)</option><option value="Asia/Shanghai">Beijing (Asia/Shanghai)</option></select></div><div className="setting-row"><div><label htmlFor="settings-display-timezone"><strong>{t('settings.displayTimezone', { defaultValue: 'Display timezone' })}</strong></label><small>{t('settings.displayTimezoneHint', { defaultValue: 'Controls ordinary timestamps such as update and status times.' })}</small></div><select id="settings-display-timezone" value={displayTimezone} onChange={(event) => update('displayTimezone', event.target.value as AppTimezone)}><option value="Asia/Shanghai">Beijing (Asia/Shanghai)</option><option value="America/New_York">New York (America/New_York)</option></select></div><div className="setting-row setting-row-column"><div><strong>{t('settings.theme')}</strong><small>{t('settings.themeHint')}</small></div><div className="segmented-control" role="radiogroup" aria-label={t('settings.theme')}><button type="button" role="radio" aria-checked={draft.theme === 'SYSTEM'} className={draft.theme === 'SYSTEM' ? 'segment-active' : ''} onClick={() => { update('theme', 'SYSTEM'); applyTheme('SYSTEM') }}><Monitor size={15} />{t('settings.system')}</button><button type="button" role="radio" aria-checked={draft.theme === 'LIGHT'} className={draft.theme === 'LIGHT' ? 'segment-active' : ''} onClick={() => { update('theme', 'LIGHT'); applyTheme('LIGHT') }}><Sun size={15} />{t('settings.light')}</button><button type="button" role="radio" aria-checked={draft.theme === 'DARK'} className={draft.theme === 'DARK' ? 'segment-active' : ''} onClick={() => { update('theme', 'DARK'); applyTheme('DARK') }}><Moon size={15} />{t('settings.dark')}</button></div></div></div></Panel><Panel title={t('settings.providers')} detail={t('settings.providersHint')}><div className="provider-form"><div className="provider-field"><label htmlFor="primary-provider">{t('settings.primary')}</label><select id="primary-provider" value={draft.primaryProvider} onChange={(event) => update('primaryProvider', event.target.value as AppSettings['primaryProvider'])}><option value="YAHOO">Yahoo Finance</option><option value="TWELVE_DATA">Twelve Data</option><option value="ALPHA_VANTAGE">Alpha Vantage</option></select></div><div className="provider-field"><label htmlFor="fallback-provider">{t('settings.fallback')}</label><select id="fallback-provider" value={draft.fallbackProvider} onChange={(event) => update('fallbackProvider', event.target.value as AppSettings['fallbackProvider'])}><option value="TWELVE_DATA">Twelve Data</option><option value="ALPHA_VANTAGE">Alpha Vantage</option><option value="YAHOO">Yahoo Finance</option><option value="NONE">None</option></select></div><div className="secret-setting"><span className="secret-icon"><KeyRound size={16} /></span><div><strong>{t('settings.twelveDataKey')}</strong><small>{draft.twelveDataConfigured ? t('settings.configured') : t('settings.notConfigured')} · {t('settings.secretHint')}</small></div><span className="secret-mask">••••••••</span></div><div className="secret-setting"><span className="secret-icon"><Database size={16} /></span><div><strong>{t('settings.alphaVantageKey')}</strong><small>{draft.alphaVantageConfigured ? t('settings.configured') : t('settings.notConfigured')} · {t('settings.secretHint')}</small></div><span className={`provider-status ${draft.alphaVantageConfigured ? 'provider-ready' : ''}`}><span className="status-dot" />{draft.alphaVantageConfigured ? t('settings.configured') : t('settings.notConfigured')}</span></div></div></Panel></div><div className="settings-footer"><span><Check size={14} /> {t('settings.connection')}: {t('settings.connected')}</span><span>{t('settings.apiBase')}: /api/v1</span></div>
  </div>
}
