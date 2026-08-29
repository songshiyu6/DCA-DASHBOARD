import { expect, test } from '@playwright/test'
import { appBusinessDate, assertNoFixtureLeak, login, setMockChart } from '../playwright.config'

test.describe('E2E-01 core journey @smoke', () => {
  test('login, track VOO, plan, BUY, and ledger-derived dashboard persist after refresh', async ({ page, context }) => {
    await setMockChart('ok')
    await login(page)

    const cookies = await context.cookies()
    expect(cookies.some((cookie) => cookie.name === 'JSESSIONID' && cookie.value)).toBeTruthy()

    await page.getByRole('link', { name: 'ETFs' }).click()
    await expect(page.getByRole('heading', { name: 'ETFs', exact: true })).toBeVisible()
    await page.waitForLoadState('networkidle')
    const openVoo = page.getByRole('button', { name: 'Open VOO details' })
    if (await openVoo.count() === 0) {
      await page.getByRole('button', { name: 'Add ETF' }).first().click()
      await page.getByLabel('Search ETF catalog').fill('VOO')
      await expect(page.getByRole('dialog').getByText('Vanguard S&P 500 ETF')).toBeVisible()
      await page.getByRole('dialog').getByRole('button', { name: 'Add', exact: true }).click()
    }
    await expect(openVoo.first()).toBeVisible()
    await openVoo.first().click()
    await expect(page.getByRole('heading', { name: 'VOO' })).toBeVisible()
    await expect(page.getByText('$110.00').first()).toBeVisible()

    await page.getByRole('link', { name: 'Plan' }).click()
    await page.getByLabel('Plan name').fill('E2E Monthly Plan')
    await page.getByLabel('Monthly budget').fill('1500.00')
    await page.getByLabel('Start date').fill('2026-01-01')
    await page.getByLabel('Start day').fill('1')
    await page.getByLabel('End day').fill('31')
    await page.getByLabel('Asset 1').selectOption('VOO')
    await page.getByLabel('VOO target weight').fill('100.00')
    await page.getByRole('button', { name: /Create plan|Save changes/ }).click()
    await expect(page.getByText('Saved')).toBeVisible()

    await page.getByRole('link', { name: 'Transactions' }).click()
    await page.getByRole('button', { name: 'Add transaction' }).click()
    const isoDate = appBusinessDate()
    await page.getByLabel('Date').fill(isoDate)
    await page.getByLabel('Ticker').fill('VOO')
    await page.getByLabel('Quantity').fill('10')
    await page.getByLabel('Unit price').fill('100')
    await page.getByLabel('Fee').fill('0')
    const cycleLabel = new Date(`${isoDate}T12:00:00Z`).toLocaleString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    })
    const cycleSelect = page.getByLabel('Plan cycle')
    const matchingCycle = cycleSelect.locator('option', { hasText: cycleLabel })
    if (await matchingCycle.count()) {
      await cycleSelect.selectOption({ label: cycleLabel })
    }
    await page.getByRole('button', { name: 'Save transaction' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByRole('table')).toContainText('10')

    await page.getByRole('link', { name: 'Dashboard' }).click()
    await expect(page.getByText('$1,100.00').first()).toBeVisible()
    await expect(page.getByText('$1,000.00').first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Open VOO holding' })).toContainText('10')
    await assertNoFixtureLeak(page)

    await page.reload()
    await expect(page.getByText('$1,100.00').first()).toBeVisible()
    await expect(page.getByText('$1,000.00').first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Open VOO holding' })).toContainText('10')
    await expect(page.locator('text=Demo data')).toHaveCount(0)
    await assertNoFixtureLeak(page)
  })
})
