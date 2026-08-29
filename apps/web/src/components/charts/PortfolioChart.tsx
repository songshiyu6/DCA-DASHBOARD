import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { useTranslation } from 'react-i18next'
import type { PortfolioHistoryPoint } from '../../types'
import { formatMoney } from '../../lib/format'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

export interface PortfolioChartPoint {
  date: string
  marketValue: number | null
  netInvested: number
}

export function toPortfolioChartPoints(data: PortfolioHistoryPoint[]): PortfolioChartPoint[] {
  return data.flatMap((point) => {
    const netInvested = Number(point.netInvested)
    if (!Number.isFinite(netInvested)) return []
    if (point.marketValue === null) {
      return [{ date: point.date, marketValue: null, netInvested }]
    }
    const marketValue = Number(point.marketValue)
    return [{ date: point.date, marketValue: Number.isFinite(marketValue) ? marketValue : null, netInvested }]
  })
}

export function PortfolioChart({ data, netLiqLabel = 'Net Liq Value', netInvestmentLabel = 'Net investment' }: { data: PortfolioHistoryPoint[]; netLiqLabel?: string; netInvestmentLabel?: string }) {
  const { t } = useTranslation()
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!ref.current || data.length === 0) return
    const points = toPortfolioChartPoints(data)
    if (points.length === 0) return
    const chart = echarts.init(ref.current)
    const styles = getComputedStyle(document.documentElement)
    const text = styles.getPropertyValue('--text-muted').trim() || '#7c8798'
    const grid = styles.getPropertyValue('--line-subtle').trim() || '#27303d'
    const accent = styles.getPropertyValue('--accent').trim() || '#7ab8ff'
    const positive = styles.getPropertyValue('--positive').trim() || '#73d3a1'
    const liveIndex = points[points.length - 1]?.date.includes('T') ? points.length - 1 : -1
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
          const params = Array.isArray(rawParams)
            ? rawParams as Array<{ axisValue?: string; seriesName?: string; value?: unknown; marker?: string }>
            : []
          const nlv = params.find((item) => item.seriesName === netLiqLabel)
          if (!nlv) return ''
          return [`<strong>${nlv.axisValue ?? ''}</strong>`, `${nlv.marker ?? ''} ${netLiqLabel}: ${formatMoney(String(nlv.value ?? ''))}`].join('<br/>')
        },
      },
      xAxis: { type: 'category', boundaryGap: false, data: points.map((point) => point.date.includes('T') ? point.date.slice(0, 10) : point.date), axisLine: { lineStyle: { color: grid } }, axisLabel: { color: text, fontSize: 10, hideOverlap: true }, axisTick: { show: false } },
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
  }, [data, netInvestmentLabel, netLiqLabel])
  return <div ref={ref} className="portfolio-chart" role="img" aria-label={t('charts.portfolioValue')} />
}
