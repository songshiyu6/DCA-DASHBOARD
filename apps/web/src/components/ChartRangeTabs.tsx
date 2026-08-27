interface ChartRangeTabsProps {
  ranges: string[]
  value: string
  onChange: (range: string) => void
}

export function ChartRangeTabs({ ranges, value, onChange }: ChartRangeTabsProps) {
  return <div className="range-tabs" role="tablist" aria-label="Chart range">
    {ranges.map((range) => <button type="button" key={range} className={range === value ? 'range-tab range-tab-active' : 'range-tab'} role="tab" aria-selected={range === value} onClick={() => onChange(range)}>{range}</button>)}
  </div>
}
