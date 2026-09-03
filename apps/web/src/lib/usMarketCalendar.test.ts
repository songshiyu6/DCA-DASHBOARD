import { describe, expect, it } from 'vitest'
import { isUsMarketTradingDay } from './usMarketCalendar'

describe('US market trading calendar', () => {
  it('skips weekends while keeping the adjacent Friday and Monday as sessions', () => {
    expect(isUsMarketTradingDay('2026-08-28')).toBe(true)
    expect(isUsMarketTradingDay('2026-08-29')).toBe(false)
    expect(isUsMarketTradingDay('2026-08-30')).toBe(false)
    expect(isUsMarketTradingDay('2026-08-31')).toBe(true)
  })

  it('skips standard full-day exchange holidays and observed holidays', () => {
    expect(isUsMarketTradingDay('2026-04-03')).toBe(false) // Good Friday
    expect(isUsMarketTradingDay('2026-07-03')).toBe(false) // Independence Day observed
    expect(isUsMarketTradingDay('2026-09-07')).toBe(false) // Labor Day
    expect(isUsMarketTradingDay('2026-09-08')).toBe(true)
  })

  it('handles the NYSE Saturday New Year rule without incorrectly closing the prior Friday', () => {
    expect(isUsMarketTradingDay('2021-12-31')).toBe(true)
    expect(isUsMarketTradingDay('2022-01-01')).toBe(false)
  })

  it('includes known ad-hoc full-day exchange closures', () => {
    expect(isUsMarketTradingDay('2025-01-09')).toBe(false)
    expect(isUsMarketTradingDay('2025-01-10')).toBe(true)
  })
})
