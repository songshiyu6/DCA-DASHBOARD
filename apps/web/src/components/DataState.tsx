import { AlertTriangle, Database, RefreshCw, WifiOff } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { DataStatus } from '../types'
import { formatDate, formatTime } from '../lib/format'
import { StatusBadge } from './StatusBadge'

export function LoadingBlock({ lines = 3 }: { lines?: number }) {
  return <div className="loading-block" aria-label="Loading">
    {Array.from({ length: lines }).map((_, index) => <span key={index} className="skeleton-line" style={{ width: `${88 - index * 12}%` }} />)}
  </div>
}

export function ErrorState({ onRetry, message }: { onRetry?: () => void; message?: string }) {
  const { t } = useTranslation()
  return <div className="state-panel state-error" role="alert">
    <span className="state-icon"><AlertTriangle size={20} /></span>
    <strong>{message ?? t('errors.generic')}</strong>
    {onRetry ? <button className="button button-ghost" onClick={onRetry}><RefreshCw size={15} />{t('common.retry')}</button> : null}
  </div>
}

export function EmptyState({ title, detail, action }: { title: string; detail?: string; action?: ReactNode }) {
  return <div className="state-panel state-empty">
    <span className="state-icon"><Database size={20} /></span>
    <strong>{title}</strong>
    {detail ? <p>{detail}</p> : null}
    {action}
  </div>
}

export function OfflineState({ status, message }: { status: DataStatus; message?: string }) {
  const { t } = useTranslation()
  return <div className="data-banner">
    <WifiOff size={14} />
    <StatusBadge status={status} compact />
    <span>{message ?? t('errors.apiOffline')}</span>
  </div>
}

export function DataStateBanner({ status, message, source, asOf, retrievedAt }: { status: DataStatus; message?: string; source?: string; asOf?: string; retrievedAt?: string }) {
  const { t } = useTranslation()
  if (status === 'FRESH' && !message) return null
  const statusLabel = t(`status.${status}`)
  const detail = message && message !== statusLabel ? message : undefined
  const timestamp = asOf ? `${t('common.asOf')} ${formatDate(asOf)}` : retrievedAt ? `${t('common.updated')} ${formatTime(retrievedAt)}` : undefined
  return <div className={`data-banner banner-${status.toLowerCase()}`}>
    <StatusBadge status={status} compact />
    {detail ? <span>{detail}</span> : null}
    {timestamp ? <small>{timestamp}</small> : null}
    {source ? <small>{source}</small> : null}
  </div>
}
