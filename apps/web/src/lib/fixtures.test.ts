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

  it('keeps fixture CSV parsing structural instead of duplicating live financial validation', () => {
    const trade = parseTransactionCsv('date,type,symbol,quantity,price,fee\n2026-08-01,BUY,VOO,1.2,620.00,0')
    expect(trade.errors).toHaveLength(0)
    expect(trade.rows[0]).toMatchObject({ instrumentSymbol: 'VOO', transactionType: 'BUY', quantity: '1.2', unitPrice: '620.00', currency: 'USD' })

    const cashEvent = parseTransactionCsv('date,type,amount\n2026-08-02,DEPOSIT,1000')
    expect(cashEvent.errors).toHaveLength(0)
    expect(cashEvent.rows[0]).toMatchObject({ transactionType: 'DEPOSIT', amount: '1000', currency: 'USD' })
    expect(cashEvent.rows[0]).not.toHaveProperty('instrumentSymbol')

    // Demo parsing intentionally does not decide whether a domain value is financially valid.
    // The live import flow sends the original CSV to the server preview endpoint for that decision.
    const domainInvalid = parseTransactionCsv('date,type,symbol,quantity,price,fee\nnot-a-date,BUY,VOO,,,0')
    expect(domainInvalid.errors).toHaveLength(0)
    expect(domainInvalid.rows).toHaveLength(1)

    const structurallyInvalid = parseTransactionCsv('type,amount\nDEPOSIT,1000')
    expect(structurallyInvalid.rows).toHaveLength(0)
    expect(structurallyInvalid.errors).toContain('Missing columns: date')
  })
})
