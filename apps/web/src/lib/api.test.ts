import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TransactionInput } from '../types'
import { getFixtureTransactions } from './fixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  } as Response
}

function rawJsonResponse(body: string, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => body,
  } as Response
}

async function loadApi() {
  vi.resetModules()
  return import('./api')
}

const transactionInput: TransactionInput = {
  instrumentSymbol: 'VOO',
  transactionType: 'BUY',
  tradeDate: '2026-08-27',
  quantity: '1.23842300',
  unitPrice: '520.450000',
  fee: '0.000000',
  currency: 'USD',
  planCycleId: null,
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('API contract adapter', () => {
  it('preserves NUMERIC(20,6) and NUMERIC(20,8) strings from raw JSON through normalizers', async () => {
    const money = '99999999999999.123456'
    const quantity = '999999999999.12345678'
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(rawJsonResponse(`{"totalInvested":"${money}","initial":{"principal":"${money}","value":"${money}","pnl":"0.000000","returnRate":"0","averageMarketDays":0,"batchCount":1,"dataStatus":"FRESH"},"dca":{"principal":"0.000000","value":"0.000000","pnl":"0.000000","averageMarketDays":0,"batchCount":0,"dataStatus":"FRESH"},"unclassifiedAmount":"0.000000","unclassifiedBuys":[],"unclassifiedScope":"ACCOUNT","batches":[],"dataStatus":"FRESH","asOf":"2026-08-31"}`))
      .mockResolvedValueOnce(rawJsonResponse(`[{"id":"txn-1","instrumentSymbol":"VOO","transactionType":"BUY","tradeDate":"2026-08-31","quantity":"${quantity}","unitPrice":"${money}","amount":null,"fee":"0.000000","currency":"USD"}]`))
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await loadApi()

    const contribution = await api.getContributionAnalysis('plan-1')
    const transactions = await api.getTransactions()

    expect(contribution.data.totalInvested).toBe(money)
    expect(contribution.data.initial.principal).toBe(money)
    expect(transactions.data[0].quantity).toBe(quantity)
    expect(transactions.data[0].unitPrice).toBe(money)
  })

  it('normalizes data and top-level freshness metadata without coercing decimal strings', async () => {
    const { normalizeApiResponse } = await loadApi()
    const result = normalizeApiResponse<{ marketValue: string }>({ data: { marketValue: '28421.62' }, dataStatus: 'STALE', asOf: '2026-08-27', source: 'YAHOO' }, { status: 'FRESH', source: 'API' })

    expect(result.data.marketValue).toBe('28421.62')
    expect(result.meta).toMatchObject({ status: 'STALE', asOf: '2026-08-27', source: 'YAHOO' })
  })

  it('preserves a partial history point market value as null', async () => {
    const { normalizeDashboardData } = await loadApi()
    const result = normalizeDashboardData({
      portfolioHistory: [
        { date: '2026-08-26', marketValue: null, netInvested: '100.00', status: 'PARTIAL' },
        { date: '2026-08-27', marketValue: '101.00', netInvested: '100.00', status: 'FRESH' },
      ],
    })

    expect(result.portfolioHistory).toEqual([
      { date: '2026-08-26', marketValue: null, netInvested: '100.00', dataStatus: 'PARTIAL' },
      { date: '2026-08-27', marketValue: '101.00', netInvested: '100.00', dataStatus: 'FRESH' },
    ])
  })

  it('sends canonical transaction fields with a session CSRF token', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ data: { token: 'csrf-test-token', headerName: 'X-XSRF-TOKEN' } }))
      .mockResolvedValueOnce(jsonResponse({ data: { id: 'txn-100', ...transactionInput, amount: null, total: '644.65' } }))
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await loadApi()

    const result = await api.createTransaction(transactionInput)
    const csrfCall = fetchMock.mock.calls[0] as [string, RequestInit]
    const transactionCall = fetchMock.mock.calls[1] as [string, RequestInit]

    expect(result.data.id).toBe('txn-100')
    expect(csrfCall[0]).toBe('/api/v1/auth/csrf')
    expect(transactionCall[0]).toBe('/api/v1/transactions')
    expect(transactionCall[1].method).toBe('POST')
    expect(new Headers(transactionCall[1].headers).get('X-XSRF-TOKEN')).toBe('csrf-test-token')
    expect(JSON.parse(String(transactionCall[1].body))).toEqual(transactionInput)
  })

  it('uses PUT for transaction edits and does not create a second local row', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-test-token' }))
      .mockResolvedValueOnce(jsonResponse({ data: { id: 'txn-001', ...transactionInput, amount: null, total: '644.65' } }))
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await loadApi()

    await api.updateTransaction('txn-001', transactionInput)
    const transactionCall = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(transactionCall[0]).toBe('/api/v1/transactions/txn-001')
    expect(transactionCall[1].method).toBe('PUT')
  })

  it('surfaces HTTP problem details instead of hiding server validation errors in fixtures', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ detail: 'Quantity is required', code: 'VALIDATION_ERROR' }, 400))
    vi.stubGlobal('fetch', fetchMock)
    const { api, ApiError } = await loadApi()

    const error = await api.getQuote('VOO').catch((reason: unknown) => reason)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 400, message: 'Quantity is required', code: 'VALIDATION_ERROR' })
  })

  it('rejects live dashboard requests on network failure instead of returning fixture assets', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const { api, ApiError } = await loadApi()

    const result = await api.getDashboard().catch((reason: unknown) => reason)

    expect(result).toBeInstanceOf(ApiError)
    expect(result).toMatchObject({ status: 0, message: 'Network unavailable' })
    expect(result).not.toHaveProperty('data.holdings')
    expect(result).not.toHaveProperty('data.plan')
    expect(result).not.toHaveProperty('data.transactions')
  })

  it('uses fixture data only when demo mode is explicitly enabled', async () => {
    const fetchMock = vi.fn()
    vi.stubEnv('VITE_APP_MODE', 'demo')
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await loadApi()

    const result = await api.getDashboard()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.meta).toMatchObject({ status: 'STALE', source: 'FIXTURE' })
    expect(result.data.holdings).toEqual(expect.arrayContaining([expect.objectContaining({ symbol: 'VOO' })]))
  })

  it('fails closed for an unsupported app mode instead of guessing from the environment', async () => {
    vi.stubEnv('VITE_APP_MODE', 'staging')

    await expect(loadApi()).rejects.toThrow('Invalid VITE_APP_MODE')
  })

  it.each([401, 403, 500])('preserves structured HTTP %i errors in live mode', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ detail: `Failure ${status}`, code: `HTTP_${status}` }, status)))
    const { api, ApiError } = await loadApi()

    const error = await api.getQuote('VOO').catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status, message: `Failure ${status}`, code: `HTTP_${status}` })
  })

  it('sends CSV as multipart and commits the validated server rows', async () => {
    const previewBody = {
      batchId: '11111111-1111-1111-1111-111111111111',
      totalRows: 1,
      validRows: 1,
      invalidRows: 0,
      rows: [{
        rowNumber: 2,
        row: { date: '2026-08-27', type: 'BUY', symbol: 'VOO', quantity: '1.2', price: '520.45', fee: '0' },
        valid: true,
        errors: [],
        fingerprint: 'abc',
        suggestedCycleId: null,
      }],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-test-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse(previewBody))
      .mockResolvedValueOnce(jsonResponse({ batchId: previewBody.batchId, importedRows: 1, transactionIds: ['txn-1'] }))
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await loadApi()

    const preview = await api.previewTransactionImport('date,type,symbol,quantity,price,fee\n2026-08-27,BUY,VOO,1.2,520.45,0')
    expect(fetchMock.mock.calls[1]?.[1]?.body).toBeInstanceOf(FormData)
    await api.commitTransactionImport(preview.data)

    const commitCall = fetchMock.mock.calls[2] as [string, RequestInit]
    expect(JSON.parse(String(commitCall[1].body))).toEqual({ batchId: previewBody.batchId, rows: [previewBody.rows[0].row] })
  })

  it('does not create a local transaction when a production mutation cannot reach the API', async () => {
    const before = getFixtureTransactions().data.length
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const { api, ApiError } = await loadApi()

    await expect(api.createTransaction(transactionInput)).rejects.toBeInstanceOf(ApiError)
    expect(getFixtureTransactions().data).toHaveLength(before)
  })

  it('does not delete a local transaction when a production mutation cannot reach the API', async () => {
    const before = getFixtureTransactions().data.length
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const { api, ApiError } = await loadApi()

    await expect(api.deleteTransaction('local-transaction')).rejects.toBeInstanceOf(ApiError)
    expect(getFixtureTransactions().data).toHaveLength(before)
  })

  it('does not update a local transaction when a production mutation cannot reach the API', async () => {
    const before = getFixtureTransactions().data.find((transaction) => transaction.id === 'txn-001')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const { api, ApiError } = await loadApi()

    await expect(api.updateTransaction('txn-001', transactionInput)).rejects.toBeInstanceOf(ApiError)
    expect(getFixtureTransactions().data.find((transaction) => transaction.id === 'txn-001')).toEqual(before)
  })

  it('does not commit a local CSV import when the production API cannot be reached', async () => {
    const before = getFixtureTransactions().data.length
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const { api, ApiError } = await loadApi()

    await expect(api.commitTransactionImport({
      batchId: '11111111-1111-1111-1111-111111111111',
      rows: [transactionInput],
      errors: [],
    })).rejects.toBeInstanceOf(ApiError)
    expect(getFixtureTransactions().data).toHaveLength(before)
  })

  it('does not authenticate through a local session when live login cannot reach the API', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')))
    const { api, ApiError } = await loadApi()

    await expect(api.login('admin', 'password')).rejects.toBeInstanceOf(ApiError)
  })

  it('does not report a local logout success and clears CSRF after live logout failure', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-test-token' }))
      .mockRejectedValueOnce(new TypeError('offline'))
      .mockResolvedValueOnce(jsonResponse({ token: 'fresh-csrf-token' }))
      .mockRejectedValueOnce(new TypeError('offline'))
    vi.stubGlobal('fetch', fetchMock)
    const { api, ApiError } = await loadApi()

    await expect(api.logout()).rejects.toBeInstanceOf(ApiError)
    await expect(api.createTransaction(transactionInput)).rejects.toBeInstanceOf(ApiError)

    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/v1/auth/csrf')
  })

  it('keeps recommendation metadata when the response contains an items field', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      amount: '1000.00',
      dataStatus: 'STALE',
      items: [{ symbol: 'QQQ', currentWeight: '0.25', targetWeight: '0.30', gap: '0.05', suggestedAmount: '1000.00' }],
    }))
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await loadApi()

    const result = await api.getRecommendation('plan-1')

    expect(result.data.amount).toBe('1000.00')
    expect(result.data.items).toHaveLength(1)
    expect(result.data.items[0].symbol).toBe('QQQ')
    expect(result.meta.status).toBe('STALE')
  })

  it('surfaces an unavailable ETF search provider instead of converting it to local results', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ detail: 'ETF search is temporarily unavailable', code: 'MARKET_DATA_UNAVAILABLE' }, 503))
    vi.stubGlobal('fetch', fetchMock)
    const { api, ApiError } = await loadApi()

    const error = await api.searchInstruments('QQQ').catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 503, code: 'MARKET_DATA_UNAVAILABLE' })
  })

  it('posts a history sync request and preserves its actionable result', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-test-token', headerName: 'X-XSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({
        symbol: 'VOO', barsSaved: 1255, splitsSaved: 0, status: 'FRESH',
        completedAt: '2026-08-27T20:02:00Z', message: null,
      }))
    vi.stubGlobal('fetch', fetchMock)
    const { api } = await loadApi()

    const result = await api.syncInstrument('voo')
    const syncCall = fetchMock.mock.calls[1] as [string, RequestInit]

    expect(syncCall[0]).toBe('/api/v1/instruments/VOO/sync')
    expect(syncCall[1].method).toBe('POST')
    expect(new Headers(syncCall[1].headers).get('X-XSRF-TOKEN')).toBe('csrf-test-token')
    expect(result.data).toMatchObject({ symbol: 'VOO', barsSaved: 1255, status: 'FRESH', message: null })
  })

  it('does not invent daily OHLC values when the API only returns close fields', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      data: [{ date: '2026-08-27', close: 620.21, adjustedClose: 620.18 }],
      dataStatus: 'FRESH',
      source: 'YAHOO',
    })))
    const { api } = await loadApi()

    const result = await api.getPrices('VOO', '1Y')

    expect(result.data).toEqual([{
      date: '2026-08-27',
      open: undefined,
      close: '620.21',
      adjustedClose: '620.18',
      volume: undefined,
    }])
  })
})
