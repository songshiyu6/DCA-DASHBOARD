import { useEffect, useMemo, useRef, useState } from 'react'
import { AreaSeries, ColorType, createChart } from 'lightweight-charts'
import { ChartRangeTabs } from '../ChartRangeTabs'
import type { FundNavPoint } from '../../types'

const ranges = ['1Y', '3Y', 'ALL']

function selectedRows(rows: FundNavPoint[], range: string): FundNavPoint[] {
  const byDate = new Map<string, FundNavPoint>()
  for (const row of rows) {
    const current = byDate.get(row.navDate)
    if (!current || row.retrievedAt > current.retrievedAt) byDate.set(row.navDate, row)
  }
  const sorted = [...byDate.values()].sort((left, right) => left.navDate.localeCompare(right.navDate))
  if (range === 'ALL' || sorted.length === 0) return sorted

  const latest = new Date(`${sorted[sorted.length - 1].navDate}T00:00:00Z`)
  latest.setUTCFullYear(latest.getUTCFullYear() - (range === '3Y' ? 3 : 1))
  const threshold = latest.toISOString().slice(0, 10)
  return sorted.filter((row) => row.navDate >= threshold)
}

export function FundNavChart({ data }: { data: FundNavPoint[] }) {
  const ref = useRef<HTMLDivElement>(null)
  const [range, setRange] = useState('1Y')
  const rows = useMemo(() => selectedRows(data, range), [data, range])

  useEffect(() => {
    if (!ref.current || rows.length === 0) return
    const styles = getComputedStyle(document.documentElement)
    const background = styles.getPropertyValue('--surface-1').trim() || '#151c27'
    const text = styles.getPropertyValue('--text-muted').trim() || '#7c8798'
    const line = styles.getPropertyValue('--line-subtle').trim() || '#27303d'
    const accent = styles.getPropertyValue('--accent').trim() || '#7ab8ff'
    const chart = createChart(ref.current, {
      autoSize: typeof ResizeObserver !== 'undefined',
      layout: {
        background: { type: ColorType.Solid, color: background },
        textColor: text,
        fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
        fontSize: 11,
      },
      grid: { vertLines: { color: line }, horzLines: { color: line } },
      rightPriceScale: { borderColor: line },
      timeScale: { borderColor: line, rightOffset: 3, barSpacing: rows.length > 500 ? 2 : 6 },
    })
    const series = chart.addSeries(AreaSeries, {
      lineColor: accent,
      topColor: 'rgba(122,184,255,.18)',
      bottomColor: 'rgba(122,184,255,0)',
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: true,
      priceFormat: { type: 'price', precision: 4, minMove: 0.0001 },
    })
    series.setData(rows.flatMap((row) => {
      const value = Number(row.nav)
      return Number.isFinite(value) && value > 0 ? [{ time: row.navDate, value }] : []
    }))
    chart.timeScale().fitContent()
    return () => chart.remove()
  }, [rows])

  return <div className="fund-nav-chart">
    <div className="fund-nav-chart-toolbar"><ChartRangeTabs ranges={ranges} value={range} onChange={setRange} /></div>
    <div ref={ref} className="fund-nav-chart-canvas" role="img" aria-label="Fund NAV history" />
  </div>
}
