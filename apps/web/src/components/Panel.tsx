import type { ReactNode } from 'react'

interface PanelProps {
  title?: string
  detail?: string
  action?: ReactNode
  children: ReactNode
  className?: string
  flush?: boolean
}

export function Panel({ title, detail, action, children, className = '', flush = false }: PanelProps) {
  return <section className={`panel ${flush ? 'panel-flush' : ''} ${className}`}>
    {title ? <div className="panel-header"><div><h2>{title}</h2>{detail ? <p>{detail}</p> : null}</div>{action ? <div className="panel-action">{action}</div> : null}</div> : null}
    {children}
  </section>
}
