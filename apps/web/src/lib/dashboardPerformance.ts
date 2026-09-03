import { decimal } from './format'
import type { DataStatus, PortfolioHistoryPoint } from '../types'

export const CHART_RANGE_OPTIONS = ['1M', '3M', '1Y', 'YTD'] as const
export type ChartRange = (typeof CHART_RANGE_OPTIONS)[number]

export interface PeriodPerformance {
  pnl: string | null
  returnRate: string | null
}

const MARKET_TIME_ZONE = 'America/New_York'
const marketDayFormatter = new Intl.DateTimeFormat('en-US', {
  timeZone: MARKET_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

function day(value: string): string {
  return value.slice(0, 10)
}

export function marketBusinessDay(value: string | null | undefined): string | null {
  if (!value) return null
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value
  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) return null
  const parts = marketDayFormatter.formatToParts(instant)
  const year = parts.find((part) => part.type === 'year')?.value
  const month = parts.find((part) => part.type === 'month')?.value
  const date = parts.find((part) => part.type === 'day')?.value
  return year && month && date ? `${year}-${month}-${date}` : null
}

function parseUtcDay(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const date = Number(match[3])
  const parsed = new Date(Date.UTC(year, month - 1, date))
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== date) return null
  return parsed
}

function formatUtcDay(value: Date): string {
  return `${value.getUTCFullYear()}-${String(value.getUTCMonth() + 1).padStart(2, '0')}-${String(value.getUTCDate()).padStart(2, '0')}`
}

function subtractMonthsClamped(value: Date, months: number): Date {
  const target = new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth() - months, 1))
  const lastDay = new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate()
  target.setUTCDate(Math.min(value.getUTCDate(), lastDay))
  return target
}

export function isFreshPortfolioHistoryPoint(point: PortfolioHistoryPoint): boolean {
  return Boolean(point.date && point.marketValue !== null && point.dataStatus === 'FRESH')
}

function validPoints(history: PortfolioHistoryPoint[]): PortfolioHistoryPoint[] {
  return history.filter(isFreshPortfolioHistoryPoint)
}

export function latestFreshPortfolioHistoryPoint(history: PortfolioHistoryPoint[]): PortfolioHistoryPoint | undefined {
  return validPoints(history).at(-1)
}

export function portfolioRangeStartDay(endValue: string, range: ChartRange): string | null {
  const end = parseUtcDay(endValue)
  if (!end) return null
  if (range === 'YTD') return `${end.getUTCFullYear()}-01-01`
  if (range === '1M') return formatUtcDay(subtractMonthsClamped(end, 1))
  if (range === '3M') return formatUtcDay(subtractMonthsClamped(end, 3))
  return formatUtcDay(subtractMonthsClamped(end, 12))
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
  const businessDay = marketBusinessDay(timestamp) ?? (history.length ? day(history[history.length - 1].date) : day(timestamp))
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

function performanceFromBaseline(
  baseline: PortfolioHistoryPoint | undefined,
  marketValue: string | undefined,
  netInvested: string | undefined,
): PeriodPerformance {
  if (!baseline || !marketValue || !netInvested) return { pnl: null, returnRate: null }
  const openingValue = decimal(baseline.marketValue)
  if (openingValue.lte(0)) return { pnl: null, returnRate: null }
  const externalFlow = decimal(netInvested).minus(baseline.netInvested)
  const pnl = decimal(marketValue).minus(openingValue).minus(externalFlow)
  return { pnl: pnl.toString(), returnRate: pnl.div(openingValue).toString() }
}

export function livePerformanceSinceLastClose(
  history: PortfolioHistoryPoint[],
  marketValue: string | undefined,
  netInvested: string | undefined,
): PeriodPerformance {
  return performanceFromBaseline(validPoints(history).at(-1), marketValue, netInvested)
}

export function livePerformanceSincePreviousTradingClose(
  history: PortfolioHistoryPoint[],
  currentBusinessDay: string | null | undefined,
  marketValue: string | undefined,
  netInvested: string | undefined,
): PeriodPerformance {
  if (!currentBusinessDay) return { pnl: null, returnRate: null }
  const baseline = validPoints(history).filter((point) => day(point.date) < currentBusinessDay).at(-1)
  return performanceFromBaseline(baseline, marketValue, netInvested)
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

export function annualizedTimeWeightedReturn(history: PortfolioHistoryPoint[]): string | null {
  const points = validPoints(history)
  if (points.length < 2) return null
  const firstDate = parseUtcDay(points[0].date)
  const lastDate = parseUtcDay(points[points.length - 1].date)
  if (!firstDate || !lastDate) return null
  const elapsedDays = (lastDate.getTime() - firstDate.getTime()) / 86_400_000
  if (elapsedDays <= 0) return null

  const initialCapital = decimal(points[0].netInvested)
  const initialValue = decimal(points[0].marketValue)
  if (initialCapital.lte(0) || initialValue.lte(0)) return null

  let factor = initialValue.div(initialCapital)
  let periods = 1
  let previous = points[0]
  for (const point of points.slice(1)) {
    const openingValue = decimal(previous.marketValue)
    if (openingValue.lte(0)) {
      previous = point
      continue
    }
    const externalFlow = decimal(point.netInvested).minus(previous.netInvested)
    const adjustedEndingValue = decimal(point.marketValue).minus(externalFlow)
    if (adjustedEndingValue.lte(0)) return null
    factor = factor.mul(adjustedEndingValue.div(openingValue))
    periods++
    previous = point
  }
  if (!periods || factor.lte(0)) return null

  return factor.pow(365.2425 / elapsedDays).minus(1).toString()
}

export function ytdTimeWeightedReturn(history: PortfolioHistoryPoint[]): string | null {
  const points = validPoints(history)
  if (!points.length) return null
  const year = Number(day(points[points.length - 1].date).slice(0, 4))
  if (!Number.isFinite(year)) return null
  return timeWeightedReturn(points, `${year}-01-01`)
}

export function ytdPerformance(history: PortfolioHistoryPoint[]): PeriodPerformance {
  const points = validPoints(history)
  if (!points.length) return { pnl: null, returnRate: null }

  const current = points[points.length - 1]
  const year = Number(day(current.date).slice(0, 4))
  if (!Number.isFinite(year)) return { pnl: null, returnRate: null }
  const startDay = `${year}-01-01`
  const beforeYear = points.filter((point) => day(point.date) < startDay).at(-1)
  const inYear = points.filter((point) => day(point.date) >= startDay)
  if (!inYear.length) return { pnl: null, returnRate: null }

  const returnRate = ytdTimeWeightedReturn(points)
  if (beforeYear) {
    const openingValue = decimal(beforeYear.marketValue)
    const externalFlow = decimal(current.netInvested).minus(beforeYear.netInvested)
    const pnl = decimal(current.marketValue).minus(openingValue).minus(externalFlow)
    return { pnl: pnl.toString(), returnRate }
  }

  // With no pre-year valuation, the portfolio began during the current year.
  // Net invested is the external capital base, so the residual is the YTD P/L amount.
  const pnl = decimal(current.marketValue).minus(current.netInvested)
  return { pnl: pnl.toString(), returnRate }
}

export function filterPortfolioHistory(history: PortfolioHistoryPoint[], range: ChartRange): PortfolioHistoryPoint[] {
  if (!history.length) return history
  const endPoint = latestFreshPortfolioHistoryPoint(history)
  if (!endPoint) return []
  const startDay = portfolioRangeStartDay(endPoint.date, range)
  if (!startDay) return history
  return history.filter((point) => day(point.date) >= startDay && day(point.date) <= day(endPoint.date))
}
