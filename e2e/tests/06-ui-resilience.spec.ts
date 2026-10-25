import { expect, test, type Page } from '@playwright/test'

// Browser regressions use intercepted responses and never write account data.
async function mockWorkspace(page: Page) {
  await page.addInitScript(() => localStorage.setItem('dca-language', 'en'))
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown = []
    if (path.endsWith('/auth/session')) data = { authenticated: true, username: 'test' }
    if (path.endsWith('/auth/csrf')) data = { token: 'ui-test', headerName: 'X-CSRF-TOKEN' }
    await route.fulfill({ json: { data, meta: { status: 'FRESH', source: 'API' } } })
  })
}

test('session failure offers a working retry even when browser storage is blocked', async ({ page }) => {
  let unavailable = true
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript(() => {
    Storage.prototype.getItem = () => { throw new DOMException('Blocked', 'SecurityError') }
    Storage.prototype.setItem = () => { throw new DOMException('Blocked', 'SecurityError') }
  })
  await page.route('**/api/v1/auth/session', (route) => route.fulfill(unavailable
    ? { status: 503, json: { detail: 'Temporarily unavailable' } }
    : { json: { authenticated: false } }))
  await page.goto('/transactions')
  await expect(page.getByRole('alert')).toBeVisible()
  unavailable = false
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
  await page.getByRole('button', { name: 'Switch language' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  expect(errors).toEqual([])
})

test('CSV cannot import an old preview after editing the input', async ({ page }) => {
  await mockWorkspace(page)
  let finishPreview!: () => void
  let previewRequested = false
  let commits = 0
  const released = new Promise<void>((resolve) => { finishPreview = resolve })
  await page.route('**/api/v1/transactions/import/preview', async (route) => {
    previewRequested = true
    await released
    await route.fulfill({ json: { data: {
      batchId: 'old-preview', totalRows: 1, validRows: 1, invalidRows: 0,
      rows: [{ rowNumber: 2, row: { date: '2026-09-01', type: 'DEPOSIT', amount: '100', fee: '0' }, valid: true, errors: [], fingerprint: 'test' }],
    } } })
  })
  await page.route('**/api/v1/transactions/import/commit', (route) => {
    commits++
    return route.fulfill({ json: {} })
  })
  await page.goto('/transactions')
  await page.getByRole('button', { name: 'Add transaction' }).click()
  await expect(page.getByLabel('Ticker', { exact: true })).toHaveValue('')
  await page.keyboard.press('Escape')
  await page.getByRole('button', { name: 'Import CSV' }).click()
  const editor = page.getByLabel('CSV input')
  await expect(editor).toHaveValue('')
  await expect(page.getByRole('button', { name: 'Server preview' })).toBeDisabled()
  const first = 'date,type,amount\n2026-09-01,DEPOSIT,100'
  await editor.pressSequentially(first)
  await expect(editor).toHaveValue(first)
  await page.getByRole('button', { name: 'Server preview' }).click()
  await expect.poll(() => previewRequested).toBe(true)
  await editor.fill('date,type,amount\n2026-09-01,DEPOSIT,200')
  const response = page.waitForResponse('**/api/v1/transactions/import/preview')
  finishPreview()
  await response
  await expect(page.getByRole('button', { name: 'Server preview' })).toBeEnabled()
  await expect(page.getByRole('button', { name: /Import \d+ rows/ })).toBeDisabled()
  expect(commits).toBe(0)
})

test('cross-market chart renders with blocked preference writes and safe tooltip labels', async ({ page }) => {
  await mockWorkspace(page)
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('dca-performance-benchmarks-v1', JSON.stringify([
      { symbol: '000001.SS', name: '<img src=x onerror=alert(1)> & Fund', type: 'INDEX', visible: true },
    ]))
    Storage.prototype.setItem = () => { throw new DOMException('Full', 'QuotaExceededError') }
  })
  await page.route('**/api/v1/dashboard', (route) => route.fulfill({ json: { data: {
    summary: { marketValue: '102', netInvested: '100' },
    portfolioHistory: [], holdings: [], allocation: [], nextDca: null,
    contributionProgress: { year: 2026, executed: '0', planned: '0', remaining: '0', executionRate: '0', months: [] },
  } } }))
  await page.route('**/api/v1/performance/portfolio?*', (route) => route.fulfill({ json: { data: {
    range: '1Y', requestedStartDate: '2026-09-01', endpointDate: '2026-09-10', dataStatus: 'FRESH',
    points: [
      { date: '2026-09-04', level: '1', dataStatus: 'FRESH' },
      { date: '2026-09-08', level: '1.01', dataStatus: 'FRESH' },
      { date: '2026-09-08', level: '1.02', pointType: 'LIVE', dataStatus: 'FRESH' },
      { date: '2026-09-09', level: null, dataStatus: 'PARTIAL' },
      { date: '2026-09-10', level: '1.03', dataStatus: 'FRESH' },
    ],
  } } }))
  await page.route('**/api/v1/benchmarks/history?*', (route) => route.fulfill({ json: { data: {
    symbol: '000001.SS', type: 'INDEX', dataStatus: 'FRESH', points: [
      { date: '2026-09-04', value: '100' }, { date: '2026-09-07', value: '101' },
      { date: '2026-09-08', value: null }, { date: '2026-09-10', value: '104' },
    ],
  } } }))
  await page.goto('/')
  const chart = page.getByRole('img', { name: 'Portfolio performance benchmark chart' })
  await expect(chart.locator('canvas')).toBeVisible()
  await chart.scrollIntoViewIfNeeded()
  await chart.hover({ position: { x: 70, y: 120 } })
  await expect(chart).toContainText('<img src=x onerror=alert(1)> & Fund')
  await expect(chart.locator('img')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Remove 000001.SS' })).toBeVisible()
  expect(errors).toEqual([])
})
