import { describe, expect, it } from 'vitest'
import { MARKET_TIME_ZONE, USER_TIME_ZONE, formatMoney, formatPercent, formatShares, formatSignedMoney, formatSignedPercent, formatTime, formatUserTime } from './format'

describe('financial display formatting', () => {
  it('formats money from decimal strings without locale float artifacts', () => {
    expect(formatMoney('28421.62')).toBe('$28,421.62')
    expect(formatSignedMoney('-0.01')).toBe('-$0.01')
  })

  it('formats ratios as percentages', () => {
    expect(formatPercent('0.1421')).toBe('14.21%')
    expect(formatSignedPercent('-0.016')).toBe('-1.60%')
  })

  it('keeps missing financial percentages visibly missing', () => {
    expect(formatPercent(null)).toBe('—')
    expect(formatSignedPercent(undefined)).toBe('—')
  })

  it('keeps fractional shares readable', () => {
    expect(formatShares('18.43210000')).toBe('18.4321')
    expect(formatShares('0')).toBe('0')
  })

  it('keeps market and user display timezones explicit', () => {
    expect(MARKET_TIME_ZONE).toBe('America/New_York')
    expect(USER_TIME_ZONE).toBe('Asia/Shanghai')
    expect(formatUserTime('2026-08-29T15:00:00Z')).not.toBe(formatTime('2026-08-29T15:00:00Z'))
  })
})
