import type { LucideIcon } from 'lucide-react'

interface MetricCardProps {
  label: string
  value: string
  detail?: string
  icon?: LucideIcon
  tone?: 'neutral' | 'positive' | 'negative' | 'accent' | 'warning'
  className?: string
}

export function MetricCard({ label, value, detail, icon: Icon, tone = 'neutral', className = '' }: MetricCardProps) {
  return <article className={`metric-card metric-${tone} ${className}`}>
    <div className="metric-card-top">
      <span className="metric-label">{label}</span>
      {Icon ? <span className="metric-icon"><Icon size={15} strokeWidth={1.8} /></span> : null}
    </div>
    <strong className="metric-value">{value}</strong>
    {detail ? <span className="metric-detail">{detail}</span> : null}
  </article>
}
