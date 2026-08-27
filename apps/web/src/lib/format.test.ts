import { describe, expect, it } from 'vitest'
import { formatMoney, formatPercent, formatShares, formatSignedMoney, formatSignedPercent } from './format'

describe('financial display formatting', () => {
  it('formats money from decimal strings without locale float artifacts', () => {
    expect(formatMoney('28421.62')).toBe('$28,421.62')
    expect(formatSignedMoney('-0.01')).toBe('-$0.01')
  })

  it('formats ratios as percentages', () => {
    expect(formatPercent('0.1421')).toBe('14.21%')
    expect(formatSignedPercent('-0.016')).toBe('-1.60%')
  })

  it('keeps fractional shares readable', () => {
    expect(formatShares('18.43210000')).toBe('18.4321')
    expect(formatShares('0')).toBe('0')
  })
})
