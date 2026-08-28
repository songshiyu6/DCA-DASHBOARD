import { expect, test, type APIRequestContext } from '@playwright/test'
import { e2ePassword, e2eUser, setMockChart } from '../playwright.config'

async function csrf(request: APIRequestContext) {
  const response = await request.get('/api/v1/auth/csrf')
  expect(response.ok()).toBeTruthy()
  const body = await response.json()
  return { token: body.token as string, header: body.headerName as string }
}

async function loginApi(request: APIRequestContext) {
  const token = await csrf(request)
  const response = await request.post('/api/v1/auth/login', {
    headers: { [token.header]: token.token, 'content-type': 'application/json' },
    data: { username: e2eUser, password: e2ePassword },
  })
  expect(response.ok(), await response.text()).toBeTruthy()
  return csrf(request)
}

test.describe('API integration contracts', () => {
  test.beforeEach(async () => {
    await setMockChart('ok')
  })

  test('rejects mutations without CSRF and accepts a token', async ({ request }) => {
    await loginApi(request)
    const denied = await request.post('/api/v1/instruments', {
      data: { symbol: 'VOO' },
    })
    expect(denied.status()).toBeGreaterThanOrEqual(403)

    const token = await csrf(request)
    const created = await request.post('/api/v1/instruments', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: { symbol: 'VOO' },
    })
    expect([200, 201].includes(created.status()), await created.text()).toBeTruthy()
  })

  test('CSV duplicate preview is all-or-nothing on commit', async ({ request }) => {
    const token = await loginApi(request)
    await request.post('/api/v1/instruments', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: { symbol: 'VOO' },
    })

    const csv = 'date,type,symbol,quantity,price,fee\n2026-02-02,BUY,VOO,1,100,0\n2026-02-02,BUY,VOO,1,100,0\n'
    const preview = await request.post('/api/v1/transactions/import/preview', {
      headers: { [token.header]: token.token },
      multipart: {
        file: {
          name: 'transactions.csv',
          mimeType: 'text/csv',
          buffer: Buffer.from(csv, 'utf8'),
        },
      },
    })
    expect(preview.ok(), await preview.text()).toBeTruthy()
    const previewBody = await preview.json()
    expect(previewBody.invalidRows).toBeGreaterThan(0)

    const listed = await request.get('/api/v1/transactions')
    const before = await listed.json() as unknown[]
    const commit = await request.post('/api/v1/transactions/import/commit', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: { batchId: previewBody.batchId, rows: previewBody.rows.map((row: { row: unknown }) => row.row) },
    })
    expect(commit.ok()).toBeFalsy()
    const after = await (await request.get('/api/v1/transactions')).json() as unknown[]
    expect(after.length).toBe(before.length)
  })

  test('backdated BUY does not look ahead in history', async ({ request }) => {
    const token = await loginApi(request)
    await request.post('/api/v1/instruments', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: { symbol: 'VOO' },
    })
    const created = await request.post('/api/v1/transactions', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: {
        instrumentSymbol: 'VOO',
        transactionType: 'BUY',
        tradeDate: '2026-03-02',
        quantity: 2,
        unitPrice: 90,
        fee: 0,
      },
    })
    expect(created.status(), await created.text()).toBe(201)

    const history = await request.get('/api/v1/portfolio/history?range=1Y')
    expect(history.ok(), await history.text()).toBeTruthy()
    const points = await history.json() as Array<{ date: string; marketValue: number | null }>
    const before = points.filter((point) => point.date < '2026-03-02')
    const early = points.find((point) => point.date >= '2026-03-02' && point.date < '2026-08-01')
    for (const point of before) {
      expect(point.marketValue === null || Number(point.marketValue) === 0).toBeTruthy()
    }
    expect(early).toBeTruthy()
    expect(Number(early!.marketValue)).toBeGreaterThan(0)
    expect(Number(early!.marketValue)).toBeLessThan(500)
  })

  test('missing adjusted close leaves metrics null with an honest status', async ({ request }) => {
    await setMockChart('missing-adj')
    const token = await loginApi(request)
    await request.post('/api/v1/instruments', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: { symbol: 'VOO' },
    })
    const sync = await request.post('/api/v1/instruments/VOO/sync/full', {
      headers: { [token.header]: token.token },
    })
    expect(sync.ok(), await sync.text()).toBeTruthy()
    const metrics = await request.get('/api/v1/instruments/VOO/metrics')
    expect(metrics.ok(), await metrics.text()).toBeTruthy()
    const body = await metrics.json()
    expect(['PARTIAL', 'INSUFFICIENT_HISTORY', 'UNAVAILABLE']).toContain(body.dataStatus ?? body.status)
    expect(body.oneYear ?? null).toBeNull()
    await setMockChart('ok')
  })

  test('current cycle planned amount stays frozen after a later plan edit', async ({ request }) => {
    const token = await loginApi(request)
    await request.post('/api/v1/instruments', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: { symbol: 'VOO' },
    })
    const today = new Date()
    const period = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}`
    const created = await request.post('/api/v1/plans', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: {
        name: 'Freeze Plan',
        frequency: 'MONTHLY',
        monthlyBudget: 500,
        startDate: '2026-01-01',
        executionStartDay: 1,
        executionEndDay: 31,
        assets: [{ symbol: 'VOO', targetWeight: 1 }],
      },
    })
    expect([200, 201, 409].includes(created.status()), await created.text()).toBeTruthy()
    const plans = await (await request.get('/api/v1/plans')).json() as Array<{ id: string; status: string; monthlyBudget: number }>
    const plan = plans.find((item) => item.status === 'ACTIVE')
    expect(plan).toBeTruthy()
    const cycles = await (await request.get(`/api/v1/plans/${plan!.id}/cycles`)).json() as Array<{ period: string; plannedAmount: number }>
    const current = cycles.find((cycle) => cycle.period === period)
    expect(current).toBeTruthy()
    const frozen = Number(current!.plannedAmount)

    const updateToken = await csrf(request)
    const updated = await request.put(`/api/v1/plans/${plan!.id}`, {
      headers: { [updateToken.header]: updateToken.token, 'content-type': 'application/json' },
      data: {
        name: 'Freeze Plan',
        frequency: 'MONTHLY',
        monthlyBudget: 9000,
        startDate: '2026-01-01',
        executionStartDay: 1,
        executionEndDay: 31,
        assets: [{ symbol: 'VOO', targetWeight: 1 }],
      },
    })
    expect(updated.ok(), await updated.text()).toBeTruthy()
    const after = await (await request.get(`/api/v1/plans/${plan!.id}/cycles/${period}`)).json()
    expect(Number(after.plannedAmount)).toBe(frozen)
    expect(Number(after.plannedAmount)).not.toBe(9000)
  })
})
