import { expect, test, type APIRequestContext } from '@playwright/test'
import { e2ePassword, e2eUser, login, setMockChart } from '../playwright.config'

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

test.describe('Contribution classification', () => {
  test('records actual opening capital and atomically classifies a legacy BUY from the account queue', async ({ page, request }) => {
    await setMockChart('ok')
    let token = await loginApi(request)
    const plans = await (await request.get('/api/v1/plans')).json() as Array<{ id: string; status: string; startDate: string }>
    const plan = plans.find((item) => item.status === 'ACTIVE')
    expect(plan).toBeTruthy()

    const opening = await request.post('/api/v1/transactions', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: {
        instrumentSymbol: 'VOO',
        transactionType: 'BUY',
        tradeDate: plan!.startDate,
        quantity: '2.00000000',
        unitPrice: '100.000000',
        fee: '0.000000',
        contributionType: 'INITIAL',
        contributionPlanId: plan!.id,
      },
    })
    expect(opening.status(), await opening.text()).toBe(201)

    token = await csrf(request)
    const legacy = await request.post('/api/v1/transactions', {
      headers: { [token.header]: token.token, 'content-type': 'application/json' },
      data: {
        instrumentSymbol: 'VOO',
        transactionType: 'BUY',
        tradeDate: '2026-06-15',
        quantity: '3.00000000',
        unitPrice: '100.000000',
        fee: '0.000000',
      },
    })
    expect(legacy.status(), await legacy.text()).toBe(201)
    const legacyBody = await legacy.json() as { id: string }

    const analysisResponse = await request.get(`/api/v1/plans/${plan!.id}/contribution-analysis`)
    expect(analysisResponse.ok(), await analysisResponse.text()).toBeTruthy()
    const rawAnalysis = await analysisResponse.text()
    const analysis = JSON.parse(rawAnalysis) as {
      initial: { principal: string }
      unclassifiedScope: string
      unclassifiedBuys: Array<{ transactionId: string }>
      batches: Array<{ type: string }>
    }
    expect(analysis.initial.principal).toBe('200.000000')
    expect(analysis.unclassifiedScope).toBe('ACCOUNT')
    expect(analysis.unclassifiedBuys).toEqual(expect.arrayContaining([
      expect.objectContaining({ transactionId: legacyBody.id }),
    ]))
    expect(analysis.batches).toEqual(expect.arrayContaining([expect.objectContaining({ type: 'INITIAL' })]))
    expect(rawAnalysis).toContain('"principal":"200.000000"')

    await login(page)
    await page.getByRole('link', { name: 'Contributions' }).click()
    await expect(page.getByRole('heading', { name: 'Contributions' })).toBeVisible()
    await page.getByRole('checkbox', { name: 'Select VOO 2026-06-15' }).check()
    await expect(page.getByRole('combobox', { name: 'VOO 2026-06-15 classification' })).toHaveValue('UNPLANNED')
    await page.getByRole('button', { name: 'Preview 1 changes' }).click()
    await expect(page.getByText('1 changes validated. Confirm to commit.')).toBeVisible()
    await page.getByRole('button', { name: 'Confirm atomic commit' }).click()
    await expect(page.getByRole('checkbox', { name: 'Select VOO 2026-06-15' })).toHaveCount(0)

    const audit = await (await request.get(`/api/v1/plans/${plan!.id}/contribution-classifications/audit`)).json()
    expect(audit).toEqual(expect.arrayContaining([
      expect.objectContaining({ transactionId: legacyBody.id, newType: 'UNPLANNED' }),
    ]))
  })
})
