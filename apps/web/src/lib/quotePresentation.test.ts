import { describe, expect, it } from 'vitest'
import { quoteSessionLabel } from './quotePresentation'

describe('quoteSessionLabel', () => {
  it('labels overnight and degraded regular quotes explicitly', () => {
    expect(quoteSessionLabel('OVERNIGHT', true)).toBe('夜盘')
    expect(quoteSessionLabel('REGULAR_FALLBACK', true)).toBe('常规价（降级）')
    expect(quoteSessionLabel('OVERNIGHT', false)).toBe('Overnight')
    expect(quoteSessionLabel('REGULAR_FALLBACK', false)).toBe('Regular fallback')
  })

  it('does not guess an unknown quote session', () => {
    expect(quoteSessionLabel('UNKNOWN', true)).toBe('时段未知')
  })
})
