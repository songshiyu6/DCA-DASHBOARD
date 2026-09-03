import { describe, expect, it } from 'vitest'
import { dailyPerformanceFromSettlement } from './dailySettlementPerformance'

describe('daily settlement performance', () => {
  it('measures daily profit from the frozen midnight opening mark', () => {
    const result = dailyPerformanceFromSettlement(
      { marketValue: '1000', netInvested: '1000', dataStatus: 'FRESH' },
      '1120',
      '1100',
    )

    expect(Number(result.pnl)).toBeCloseTo(20, 8)
    expect(Number(result.returnRate)).toBeCloseTo(0.02, 8)
  })

  it('does not use an incomplete settlement as a daily baseline', () => {
    expect(dailyPerformanceFromSettlement(
      { marketValue: '1000', netInvested: '1000', dataStatus: 'PARTIAL' },
      '1010',
      '1000',
    )).toEqual({ pnl: null, returnRate: null })
  })

  it('does not fall back to a regular-close baseline when the midnight settlement is missing', () => {
    expect(dailyPerformanceFromSettlement(undefined, '1010', '1000'))
      .toEqual({ pnl: null, returnRate: null })
  })
})
