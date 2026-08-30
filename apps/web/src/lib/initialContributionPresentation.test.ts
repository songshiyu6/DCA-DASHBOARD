import { describe, expect, it } from 'vitest'
import { isInitialContributionPeriod } from './initialContributionPresentation'

describe('initial contribution presentation', () => {
  it('recognizes an initial-capital start month without turning other skipped months into initial contributions', () => {
    expect(isInitialContributionPeriod('2026-01', 'SKIPPED', '2026-01-01', '50000', '0')).toBe(true)
    expect(isInitialContributionPeriod('2026-02', 'SKIPPED', '2026-01-01', '50000', '0')).toBe(false)
    expect(isInitialContributionPeriod('2026-01', 'SKIPPED', '2026-01-01', '0', '0')).toBe(false)
    expect(isInitialContributionPeriod('2026-01', 'SKIPPED', '2026-01-01', '50000', '1500')).toBe(false)
  })
})
