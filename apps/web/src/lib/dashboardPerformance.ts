import { decimal } from './format'
import type { DataStatus, PortfolioHistoryPoint } from '../types'

export type ChartRange = '1M' | '3M' | 'YTD' | '1Y'

export interface PeriodPerformance {
  pnl: string | null
  returnRate: string | null
}

function day(value: string): string {
  return value.slice(0, 10)
}

function validPoints(history: PortfolioHistoryPoint[]): PortfolioHistoryPoint[] {
  return history.filter((point) => point.date && point.marketValue !== null)
}

export function withCurrentPortfolioPoint(
  history: PortfolioHistoryPoint[],
  marketValue: string | undefined,
  netInvested: string | undefined,
  retrievedAt?: string,
  dataStatus: DataStatus = 'FRESH',
): PortfolioHistoryPoint[] {
  if (!marketValue || !netInvested) return history
  const timestamp = retrievedAt || new Date().toISOString()
  const businessDay = history.length ? day(history[history.length - 1].date) : day(timestamp)
  const timePart = timestamp.includes('T') ? timestamp.slice(timestamp.indexOf('T') + 1) : '12:00:00Z'
  const liveTimestamp = `${businessDay}T${timePart}`
  return [
    ...history.filter((point) => day(point.date) !== businessDay),
    { date: liveTimestamp, marketValue, netInvested, dataStatus },
  ]
}

export function latestDayPerformance(history: PortfolioHistoryPoint[]): PeriodPerformance {
  const points = validPoints(history)
  if (points.length < 2) return { pnl: null, returnRate: null }
  const current = points[points.length - 1]
  const previous = points[points.length - 2]
  if (day(current.date) === day(previous.date)) return { pnl: null, returnRate: null }

  const openingValue = decimal(previous.marketValue)
  if (openingValue.lte(0)) return { pnl: null, returnRate: null }
  const externalFlow = decimal(current.netInvested).minus(previous.netInvested)
  const pnl = decimal(current.marketValue).minus(openingValue).minus(externalFlow)
  return { pnl: pnl.toString(), returnRate: pnl.div(openingValue).toString() }
}

export function timeWeightedReturn(history: PortfolioHistoryPoint[], startDay?: string): string | null {
  const points = validPoints(history)
  if (!points.length) return null

  const inScope = startDay ? points.filter((point) => day(point.date) >= startDay) : points
  if (!inScope.length) return null
  const firstIndex = points.indexOf(inScope[0])
  let previous = firstIndex > 0 ? points[firstIndex - 1] : undefined
  let factor = decimal(1)
  let periods = 0

  if (!previous) {
    const first = inScope[0]
    const initialCapital = decimal(first.netInvested)
    const initialValue = decimal(first.marketValue)
    if (initialCapital.gt(0) && initialValue.gt(0)) {
      factor = factor.mul(initialValue.div(initialCapital))
      periods++
    }
    previous = first
  }

  for (const point of inScope) {
    if (point === previous) continue
    const openingValue = decimal(previous.marketValue)
    if (openingValue.lte(0)) {
      previous = point
      continue
    }
    const externalFlow = decimal(point.netInvested).minus(previous.netInvested)
    const adjustedEndingValue = decimal(point.marketValue).minus(externalFlow)
    if (adjustedEndingValue.gt(0)) {
      factor = factor.mul(adjustedEndingValue.div(openingValue))
      periods++
    }
    previous = point
  }
  return periods ? factor.minus(1).toString() : null
}

export function ytdTimeWeightedReturn(history: PortfolioHistoryPoint[]): string | null {
  const points = validPoints(history)
  if (!points.length) return null
  const year = Number(day(points[points.length - 1].date).slice(0, 4))
  if (!Number.isFinite(year)) return null
  return timeWeightedReturn(points, `${year}-01-01`)
}

export function filterPortfolioHistory(history: PortfolioHistoryPoint[], range: ChartRange): PortfolioHistoryPoint[] {
  if (!history.length || range === '1Y') return history
  const endRaw = history[history.length - 1].date
  const end = new Date(endRaw.includes('T') ? endRaw : `${endRaw}T12:00:00Z`)
  if (Number.isNaN(end.getTime())) return history
  const start = new Date(end)
  if (range === '1M') start.setUTCMonth(start.getUTCMonth() - 1)
  if (range === '3M') start.setUTCMonth(start.getUTCMonth() - 3)
  if (range === 'YTD') start.setTime(Date.UTC(end.getUTCFullYear(), 0, 1))
  return history.filter((point) => {
    const date = new Date(point.date.includes('T') ? point.date : `${point.date}T12:00:00Z`)
    return !Number.isNaN(date.getTime()) && date.getTime() >= start.getTime()
  })
}
