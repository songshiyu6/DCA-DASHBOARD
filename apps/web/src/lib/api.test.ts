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
})

describe('API contract adapter', () => {
  it('normalizes data and top-level freshness metadata without coercing decimal strings', async () => {
    const { normalizeApiResponse } = await loadApi()
    const result = normalizeApiResponse<{ marketValue: string }>({ data: { marketValue: '28421.62' }, dataStatus: 'STALE', asOf: '2026-08-27', source: 'YAHOO' }, { status: 'FRESH', source: 'API' })

    expect(result.data.marketValue).toBe('28421.62')
    expect(result.meta).toMatchObject({ status: 'STALE', asOf: '2026-08-27', source: 'YAHOO' })
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
})
