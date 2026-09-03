import type { BenchmarkPricePoint } from './benchmarkTypes'
import { decimal } from './format'
import { isUsMarketTradingDay } from './usMarketCalendar'
import type { PortfolioHistoryPoint } from '../types'

export interface PerformanceLevelPoint {
  date: string
  value: number | null
}

export interface PerformanceLine {
  key: string
  label: string
  points: PerformanceLevelPoint[]
}

export interface RebasedPerformanceLine extends PerformanceLine {
  points: Array<{ date: string; value: number | null }>
}

function day(value: string): string {
  return value.slice(0, 10)
}

export function portfolioTwrLevels(
  history: PortfolioHistoryPoint[],
  startDay: string,
  endDay: string,
): PerformanceLevelPoint[] {
  const rows = history
    .filter((point) => {
      const date = day(point.date)
      return date >= startDay && date <= endDay && isUsMarketTradingDay(date)
    })
    .sort((left, right) => day(left.date).localeCompare(day(right.date)))

  let factor = decimal(1)
  let previous: PortfolioHistoryPoint | undefined
  let started = false
  const result: PerformanceLevelPoint[] = []

  for (const point of rows) {
    const date = day(point.date)
    const valid = point.dataStatus === 'FRESH' && point.marketValue !== null
      && decimal(point.marketValue).gt(0)
    if (!valid) {
      if (started) result.push({ date, value: null })
      previous = undefined
      continue
    }

    if (!started) {
      started = true
      previous = point
      result.push({ date, value: 1 })
      continue
    }

    if (!previous) {
      previous = point
      result.push({ date, value: factor.toNumber() })
      continue
    }

    const openingValue = decimal(previous.marketValue)
    const externalFlow = decimal(point.netInvested).minus(previous.netInvested)
    const adjustedEndingValue = decimal(point.marketValue).minus(externalFlow)
    if (openingValue.lte(0) || adjustedEndingValue.lte(0)) {
      result.push({ date, value: null })
      previous = undefined
      continue
    }

    factor = factor.mul(adjustedEndingValue.div(openingValue))
    result.push({ date, value: factor.toNumber() })
    previous = point
  }

  return result
}

export function benchmarkPriceLevels(
  points: BenchmarkPricePoint[],
  startDay: string,
  endDay: string,
): PerformanceLevelPoint[] {
  return points
    .filter((point) => point.date >= startDay && point.date <= endDay && isUsMarketTradingDay(point.date))
    .flatMap((point) => {
      const value = Number(point.value)
      return Number.isFinite(value) && value > 0 ? [{ date: point.date, value }] : []
    })
    .sort((left, right) => left.date.localeCompare(right.date))
}

export function rebasePerformanceLines(lines: PerformanceLine[]): { startDay: string | null; lines: RebasedPerformanceLine[] } {
  const active = lines.filter((line) => line.points.some((point) => point.value !== null))
  if (!active.length) return { startDay: null, lines: [] }

  let commonDates: Set<string> | null = null
  for (const line of active) {
    const dates = new Set<string>(line.points.filter((point) => point.value !== null).map((point) => point.date))
    if (commonDates === null) {
      commonDates = new Set<string>(dates)
      continue
    }
    const intersection = new Set<string>()
    for (const date of commonDates) {
      if (dates.has(date)) intersection.add(date)
    }
    commonDates = intersection
  }

  let startDay: string | null = null
  if (commonDates !== null && commonDates.size > 0) {
    for (const date of commonDates) {
      if (startDay === null || date < startDay) startDay = date
    }
  }
  if (!startDay) return { startDay: null, lines: [] }

  const rebased = active.map((line) => {
    const base = line.points.find((point) => point.date === startDay)?.value ?? null
    if (base === null || !Number.isFinite(base) || base <= 0) return { ...line, points: [] }
    return {
      ...line,
      points: line.points
        .filter((point) => point.date >= startDay)
        .map((point) => ({
          date: point.date,
          value: point.value === null ? null : ((point.value / base) - 1) * 100,
        })),
    }
  })

  return { startDay, lines: rebased }
}
