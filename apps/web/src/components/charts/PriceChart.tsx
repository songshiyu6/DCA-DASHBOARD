import { useEffect, useRef } from 'react'
import { AreaSeries, ColorType, createChart, type UTCTimestamp } from 'lightweight-charts'
import type { PricePoint } from '../../types'

export function PriceChart({ data }: { data: PricePoint[] }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!ref.current || data.length === 0) return
    const seen = new Set<string>()
    const seriesData = data.flatMap((point) => {
      const value = Number(point.adjustedClose ?? point.close)
      if (!Number.isFinite(value)) return []
      const parsedTime = point.date.includes('T') ? Math.floor(new Date(point.date).getTime() / 1000) : point.date
      if (typeof parsedTime === 'number' && !Number.isFinite(parsedTime)) return []
      const key = String(parsedTime)
      if (seen.has(key)) return []
      seen.add(key)
      return [{ time: parsedTime as string | UTCTimestamp, value }]
    })
    if (seriesData.length === 0) return
    const styles = getComputedStyle(document.documentElement)
    const background = styles.getPropertyValue('--surface-1').trim() || '#151c27'
    const text = styles.getPropertyValue('--text-muted').trim() || '#7c8798'
    const line = styles.getPropertyValue('--line-subtle').trim() || '#27303d'
    const autoSize = typeof ResizeObserver !== 'undefined'
    const chart = createChart(ref.current, { autoSize, ...(autoSize ? {} : { width: ref.current.clientWidth || 640, height: ref.current.clientHeight || 320 }), layout: { background: { type: ColorType.Solid, color: background }, textColor: text, fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif', fontSize: 11 }, grid: { vertLines: { color: line }, horzLines: { color: line } }, rightPriceScale: { borderColor: line }, timeScale: { borderColor: line, timeVisible: seriesData.some((point) => typeof point.time === 'number'), rightOffset: 4, barSpacing: seriesData.length > 300 ? 3 : 7 }, crosshair: { vertLine: { color: '#6f89a8', width: 1, style: 3 }, horzLine: { color: '#6f89a8', width: 1, style: 3 } } })
    const series = chart.addSeries(AreaSeries, { lineColor: '#7ab8ff', topColor: 'rgba(122,184,255,.20)', bottomColor: 'rgba(122,184,255,0)', lineWidth: 2, priceLineVisible: false, lastValueVisible: true })
    series.setData(seriesData)
    chart.timeScale().fitContent()
    const resize = () => chart.resize(ref.current?.clientWidth || 640, ref.current?.clientHeight || 320)
    if (!autoSize) window.addEventListener('resize', resize)
    return () => { if (!autoSize) window.removeEventListener('resize', resize); chart.remove() }
  }, [data])
  return <div ref={ref} className="price-chart" role="img" aria-label="ETF historical price chart" />
}
