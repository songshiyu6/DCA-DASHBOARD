import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useTranslation } from 'react-i18next'
import type { PortfolioHistoryPoint } from '../../types'
import { formatMoney } from '../../lib/format'
import { isUsMarketTradingDay } from '../../lib/usMarketCalendar'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

export interface PortfolioChartPoint {
  date: string
  marketValue: number | null
  netInvested: number
}

export interface PortfolioTooltipItem {
  axisValue?: string | number
  axisValueLabel?: string
  seriesName?: string
  value?: unknown
  marker?: string
  dataIndex?: number
}

function chartDate(value: string): string {
  return value.includes('T') ? value.slice(0, 10) : value
}

export function toPortfolioChartPoints(data: PortfolioHistoryPoint[]): PortfolioChartPoint[] {
  return data.flatMap((point) => {
    const date = chartDate(point.date)
    if (!isUsMarketTradingDay(date)) return []

    const netInvested = Number(point.netInvested)
    if (!Number.isFinite(netInvested)) return []
    if (point.marketValue === null || point.dataStatus !== 'FRESH') {
      return [{ date, marketValue: null, netInvested }]
    }
    const marketValue = Number(point.marketValue)
    return [{ date, marketValue: Number.isFinite(marketValue) ? marketValue : null, netInvested }]
  })
}

function pointTimestamp(value: string): number | undefined {
  const parsed = Date.parse(value.includes('T') ? value : `${value}T12:00:00Z`)
  return Number.isFinite(parsed) ? parsed : undefined
}

function earliestPointDay(points: PortfolioChartPoint[], fundedOnly: boolean): string | undefined {
  let earliest: string | undefined
  for (const point of points) {
    if (fundedOnly && point.netInvested <= 0) continue
    const date = chartDate(point.date)
    if (!earliest || date < earliest) earliest = date
  }
  return earliest
}

function latestValuationDay(points: PortfolioChartPoint[]): string | undefined {
  let latest: string | undefined
  for (const point of points) {
    if (point.marketValue === null) continue
    const date = chartDate(point.date)
    if (!latest || date > latest) latest = date
  }
  return latest
}

function latestValuationIndex(points: PortfolioChartPoint[]): number {
  let latest = -1
  points.forEach((point, index) => {
    if (point.marketValue !== null) latest = index
  })
  return latest
}

export function resolvePortfolioChartStart(points: PortfolioChartPoint[], rangeStart?: string): string | undefined {
  const requestedStart = rangeStart ? chartDate(rangeStart) : undefined
  const portfolioStart = earliestPointDay(points, true) ?? earliestPointDay(points, false)
  if (!requestedStart) return portfolioStart
  if (!portfolioStart) return requestedStart
  return requestedStart > portfolioStart ? requestedStart : portfolioStart
}

export function resolvePortfolioChartEnd(points: PortfolioChartPoint[], rangeEnd?: string): string | undefined {
  const requestedEnd = rangeEnd ? chartDate(rangeEnd) : undefined
  const latestValuation = latestValuationDay(points)
  if (!requestedEnd) return latestValuation
  if (!latestValuation) return requestedEnd
  return requestedEnd < latestValuation ? requestedEnd : latestValuation
}

function formatAxisDate(value: unknown): string {
  if (typeof value === 'string') return chartDate(value)
  const timestamp = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(timestamp)) return ''
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`
}

function seriesValue(value: unknown): unknown {
  return Array.isArray(value) ? value.at(-1) : value
}

export function formatPortfolioTooltip(
  params: PortfolioTooltipItem[],
  points: PortfolioChartPoint[],
  netLiqLabel: string,
  netInvestmentLabel: string,
  netInvestmentColor = '#73d3a1',
): string {
  const nlv = params.find((item) => item.seriesName === netLiqLabel)
  if (!nlv) return ''

  const axisTimestamp = typeof nlv.axisValue === 'number' ? nlv.axisValue : Number(nlv.axisValue)
  const axisDay = typeof nlv.axisValue === 'string' ? chartDate(nlv.axisValue) : null
  const point = typeof nlv.dataIndex === 'number'
    ? points[nlv.dataIndex]
    : points.find((item) => {
        const timestamp = pointTimestamp(item.date)
        return (Number.isFinite(axisTimestamp) && timestamp === axisTimestamp) || (axisDay !== null && chartDate(item.date) === axisDay)
      })
  const investmentMarker = `<span style="display:inline-block;margin-right:4px;border-radius:10px;width:10px;height:10px;background-color:${netInvestmentColor};"></span>`
  const dateLabel = point ? chartDate(point.date) : nlv.axisValueLabel ?? formatAxisDate(nlv.axisValue)
  const marketValue = point?.marketValue ?? seriesValue(nlv.value)
  const lines = [
    `<strong>${dateLabel}</strong>`,
    `${nlv.marker ?? ''} ${netLiqLabel}: ${formatMoney(marketValue === null || marketValue === undefined ? null : String(marketValue))}`,
  ]
  if (point) {
    lines.push(`${investmentMarker} ${netInvestmentLabel}: ${formatMoney(point.netInvested)}`)
  }
  return lines.join('<br/>')
}

export function PortfolioChart({
  data,
  netLiqLabel = 'Net Liq Value',
  netInvestmentLabel = 'Net investment',
  rangeStart,
  rangeEnd,
}: {
  data: PortfolioHistoryPoint[]
  netLiqLabel?: string
  netInvestmentLabel?: string
  rangeStart?: string
  rangeEnd?: string
}) {
  const { t, i18n } = useTranslation()
  const ref = useRef<HTMLDivElement>(null)
  const lastFreshClose = data.filter((point) => point.marketValue !== null
    && point.dataStatus === 'FRESH'
    && isUsMarketTradingDay(chartDate(point.date))).at(-1)
  const hasUntrustedTail = Boolean(lastFreshClose && data.some((point) => chartDate(point.date) > chartDate(lastFreshClose.date)
    && isUsMarketTradingDay(chartDate(point.date))))
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const historyNote = lastFreshClose
    ? (isZh
        ? `${hasUntrustedTail ? '历史日线有延迟 · ' : ''}常规收盘截至 ${chartDate(lastFreshClose.date)} · 当前实时估值见顶部`
        : `${hasUntrustedTail ? 'Daily history delayed · ' : ''}Regular close through ${chartDate(lastFreshClose.date)} · see live valuation above`)
    : (isZh ? '暂无有效常规收盘估值' : 'No valid regular-close valuation yet')

  useEffect(() => {
    if (!ref.current || data.length === 0) return
    const allPoints = toPortfolioChartPoints(data)
    if (allPoints.length === 0) return
    const start = resolvePortfolioChartStart(allPoints, rangeStart)
    const end = resolvePortfolioChartEnd(allPoints, rangeEnd)
    const points = allPoints.filter((point) => (!start || point.date >= start) && (!end || point.date <= end))
    if (points.length === 0) return

    const chart = echarts.init(ref.current)
    const styles = getComputedStyle(document.documentElement)
    const text = styles.getPropertyValue('--text-muted').trim() || '#7c8798'
    const grid = styles.getPropertyValue('--line-subtle').trim() || '#27303d'
    const accent = styles.getPropertyValue('--accent').trim() || '#7ab8ff'
    const positive = styles.getPropertyValue('--positive').trim() || '#73d3a1'
    const liveIndex = latestValuationIndex(points)

    chart.setOption({
      animationDuration: 450,
      grid: { left: 8, right: 10, top: 22, bottom: 8, containLabel: true },
      legend: { show: false },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'line', snap: true, lineStyle: { color: accent, width: 1 } },
        backgroundColor: '#151c27',
        borderColor: grid,
        textStyle: { color: '#e8eef6', fontSize: 12 },
        formatter: (rawParams: unknown) => {
          const params = Array.isArray(rawParams) ? rawParams as PortfolioTooltipItem[] : []
          return formatPortfolioTooltip(params, points, netLiqLabel, netInvestmentLabel, positive)
        },
      },
      xAxis: {
        type: 'category',
        data: points.map((point) => point.date),
        boundaryGap: false,
        axisLine: { lineStyle: { color: grid } },
        axisLabel: { color: text, fontSize: 10, hideOverlap: true, formatter: (value: string) => chartDate(value) },
        axisTick: { show: false },
      },
      yAxis: { type: 'value', scale: true, splitNumber: 3, axisLabel: { color: text, fontSize: 10, formatter: (value: number) => formatMoney(String(value), 'USD', 0) }, splitLine: { lineStyle: { color: grid, type: 'dashed' } }, axisLine: { show: false } },
      series: [
        {
          name: netInvestmentLabel,
          type: 'line',
          smooth: 0.2,
          showSymbol: false,
          silent: true,
          z: 1,
          data: points.map((point) => point.netInvested),
          lineStyle: { width: 1.25, type: 'dashed', color: positive, opacity: 0.62 },
          itemStyle: { color: positive },
          emphasis: { disabled: true },
          // Keep this series passive so ECharts hover/emphasis stays anchored to Net Liq Value.
          // Its value is still rendered in the shared tooltip by formatPortfolioTooltip above.
          tooltip: { show: false },
        },
        {
          name: netLiqLabel,
          type: 'line',
          smooth: 0.25,
          showSymbol: true,
          symbol: 'circle',
          connectNulls: false,
          z: 3,
          data: points.map((point, index) => ({ value: point.marketValue, symbolSize: index === liveIndex ? 7 : 0 })),
          lineStyle: { width: 2.4, color: accent },
          itemStyle: { color: accent, borderColor: '#ffffff', borderWidth: 1 },
          emphasis: { focus: 'series', scale: 1.5 },
          areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(122,184,255,.16)' }, { offset: 1, color: 'rgba(122,184,255,0)' }]) },
        },
      ],
    })
    const resize = () => chart.resize()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(resize)
    observer?.observe(ref.current)
    window.addEventListener('resize', resize)
    return () => { observer?.disconnect(); window.removeEventListener('resize', resize); chart.dispose() }
  }, [data, netInvestmentLabel, netLiqLabel, rangeEnd, rangeStart])
  return <div><div ref={ref} className="portfolio-chart" role="img" aria-label={t('charts.portfolioValue')} /><small>{historyNote}</small></div>
}
