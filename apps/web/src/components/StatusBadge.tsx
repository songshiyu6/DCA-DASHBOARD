import { useTranslation } from 'react-i18next'
import type { DataStatus, CycleStatus } from '../types'

interface StatusBadgeProps {
  status: DataStatus | CycleStatus
  compact?: boolean
}

export function StatusBadge({ status, compact = false }: StatusBadgeProps) {
  const { t } = useTranslation()
  const label = t(`status.${status}`, { defaultValue: status.replace('_', ' ') })
  return <span className={`status-badge status-${status.toLowerCase()} ${compact ? 'status-compact' : ''}`}><span className="status-dot" />{label}</span>
}
