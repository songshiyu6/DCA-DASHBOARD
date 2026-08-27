import { describe, expect, it } from 'vitest'
import { calculateRecommendation, fixturePlan, parseTransactionCsv } from './fixtures'
import { decimal } from './format'

describe('fixture domain behavior', () => {
  it('allocates a contribution to underweight ETFs and leaves an overweight ETF at zero', () => {
    const recommendation = calculateRecommendation(fixturePlan, '1000.00')
    const voo = recommendation.items.find((item) => item.symbol === 'VOO')
    const total = recommendation.items.reduce((sum, item) => sum.plus(item.suggestedAmount), decimal(0))
    expect(voo?.suggestedAmount).toBe('0.00')
    expect(recommendation.items.find((item) => item.symbol === 'QQQ')?.suggestedAmount).not.toBe('0.00')
    expect(recommendation.items.find((item) => item.symbol === 'SCHD')?.suggestedAmount).not.toBe('0.00')
    expect(total.toFixed(2)).toBe('1000.00')
  })

  it('previews valid CSV rows and reports malformed lines', () => {
    const valid = parseTransactionCsv('date,type,symbol,quantity,price,fee\n2026-08-01,BUY,VOO,1.2,620.00,0')
    expect(valid.errors).toHaveLength(0)
    expect(valid.rows[0]).toMatchObject({ instrumentSymbol: 'VOO', transactionType: 'BUY', quantity: '1.2', unitPrice: '620.00', currency: 'USD' })

    const invalid = parseTransactionCsv('date,type,symbol,quantity,price,fee\nnot-a-date,BUY,VOO,,,0')
    expect(invalid.rows).toHaveLength(0)
    expect(invalid.errors.length).toBeGreaterThan(0)
  })
})
