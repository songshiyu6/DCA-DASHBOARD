interface SectionHeaderProps {
  eyebrow?: string
  title: string
  detail?: string
  action?: React.ReactNode
}

export function SectionHeader({ eyebrow, title, detail, action }: SectionHeaderProps) {
  return <div className="section-header">
    <div>
      {eyebrow ? <span className="section-eyebrow">{eyebrow}</span> : null}
      <h2>{title}</h2>
      {detail ? <p>{detail}</p> : null}
    </div>
    {action ? <div className="section-action">{action}</div> : null}
  </div>
}
