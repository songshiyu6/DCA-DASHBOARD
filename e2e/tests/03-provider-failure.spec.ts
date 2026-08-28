import { expect, test, type APIRequestContext } from '@playwright/test'
import { login, setMockChart } from '../playwright.config'

async function csrfHeaders(request: APIRequestContext) {
  const response = await request.get('/api/v1/auth/csrf')
  expect(response.ok()).toBeTruthy()
  const body = await response.json()
  return { [body.headerName as string]: body.token as string }
}

test.describe('E2E-03 provider failure statuses', () => {
  test('keeps STALE prices and shows UNAVAILABLE without fabricating history', async ({ page }) => {
    await setMockChart('ok')
    await login(page)

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

    await setMockChart('fail')
    // Incremental sync is a no-op when today's bar is already stored. Full
    // resync always calls the provider. Quote results also stay in the 1-minute
    // Caffeine cache; a settings write evicts it so the next quote fetch hits
    // the failed provider and marks the stored $110 price STALE.
    const evict = await page.request.put('/api/v1/settings', {
      headers: { ...(await csrfHeaders(page.request)), 'content-type': 'application/json' },
      data: { theme: 'DARK' },
    })
    expect(evict.ok(), await evict.text()).toBeTruthy()
    const sync = await page.request.post('/api/v1/instruments/VOO/sync/full', {
      headers: await csrfHeaders(page.request),
    })
    expect(sync.ok(), await sync.text()).toBeTruthy()
    const syncBody = await sync.json() as { status?: string; dataStatus?: string }
    expect(syncBody.status ?? syncBody.dataStatus).toBe('STALE')
    const quote = await page.request.get('/api/v1/instruments/VOO/quote')
    expect(quote.ok(), await quote.text()).toBeTruthy()
    const quoteBody = await quote.json() as { status?: string; price?: number | string }
    expect(quoteBody.status).toBe('STALE')
    expect(Number(quoteBody.price)).toBe(110)

    await page.reload()
    await expect(page).toHaveURL(/\/etfs\/VOO$/i)
    await expect(page.getByRole('heading', { name: 'VOO' })).toBeVisible()
    await expect(page.getByText('$110.00').first()).toBeVisible()
    await expect(page.getByText('Data delayed').first()).toBeVisible()
    await expect(page.getByText('Internal Server Error')).toHaveCount(0)

    await page.getByRole('link', { name: 'ETFs' }).click()
    await page.getByRole('button', { name: 'Add ETF' }).first().click()
    await page.getByLabel('Search ETF catalog').fill('QQQ')
    await expect(page.getByRole('dialog').getByText('Invesco QQQ Trust')).toBeVisible()
    await page.getByRole('dialog').getByRole('button', { name: 'Add', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Open QQQ details' })).toBeVisible()
    await page.getByRole('button', { name: 'Open QQQ details' }).click()
    await expect(page.getByRole('heading', { name: 'QQQ' })).toBeVisible()
    await expect(
      page.getByText(/Unavailable|Insufficient history|No data yet/).first(),
    ).toBeVisible()
    await expect(page.locator('canvas')).toHaveCount(0)
    await setMockChart('ok')
  })
})
