#!/usr/bin/env node
import http from 'node:http'

const PORT = Number.parseInt(process.env.DCA_E2E_MOCK_PORT ?? '8080', 10)
const LAST_CLOSE = 110
const FIRST_CLOSE = 80

let chartMode = 'ok'

function json(response, status, body) {
  const payload = typeof body === 'string' ? body : JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json',
    'cache-control': 'no-store',
  })
  response.end(payload)
}

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    request.on('error', reject)
  })
}

function utcDate(year, month, day) {
  return new Date(Date.UTC(year, month - 1, day, 13, 30, 0))
}

function latestWeekday(now = new Date()) {
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 13, 30, 0))
  while (date.getUTCDay() === 0 || date.getUTCDay() === 6) {
    date.setUTCDate(date.getUTCDate() - 1)
  }
  return date
}

function fiveYearStart(end) {
  const start = new Date(end)
  start.setUTCFullYear(start.getUTCFullYear() - 5)
  while (start.getUTCDay() === 0 || start.getUTCDay() === 6) {
    start.setUTCDate(start.getUTCDate() + 1)
  }
  return start
}

function weeklyBars(end = latestWeekday()) {
  const start = fiveYearStart(end)
  const bars = []
  for (let current = new Date(start); current <= end; current = new Date(current.getTime() + 7 * 24 * 60 * 60 * 1000)) {
    bars.push(new Date(current))
  }
  if (bars.length === 0 || bars[bars.length - 1].getTime() !== end.getTime()) {
    bars.push(end)
  }
  return bars
}

function closeAt(index, total) {
  if (total <= 1) return LAST_CLOSE
  return FIRST_CLOSE + ((LAST_CLOSE - FIRST_CLOSE) * index) / (total - 1)
}

function filterBars(bars, url) {
  const period1 = url.searchParams.get('period1')
  const period2 = url.searchParams.get('period2')
  if (!period1 || !period2) return bars
  const from = Number.parseInt(period1, 10) * 1000
  const to = Number.parseInt(period2, 10) * 1000
  return bars.filter((date) => {
    const ms = date.getTime()
    return ms >= from && ms < to
  })
}

function chartBody(url, includeAdj) {
  const end = latestWeekday()
  const bars = filterBars(weeklyBars(end), url)
  const timestamps = bars.map((date) => Math.floor(date.getTime() / 1000))
  const closes = bars.map((_, index) => Number(closeAt(index, Math.max(bars.length, 1)).toFixed(4)))
  if (closes.length > 0) closes[closes.length - 1] = LAST_CLOSE
  const adj = includeAdj ? closes.slice() : closes.map(() => null)
  return {
    chart: {
      result: [{
        meta: {
          symbol: 'VOO',
          currency: 'USD',
          exchangeTimezoneName: 'America/New_York',
          regularMarketPrice: LAST_CLOSE,
          previousClose: 109,
          regularMarketTime: Math.floor(end.getTime() / 1000),
        },
        timestamp: timestamps,
        indicators: {
          quote: [{
            open: closes.map((value) => Number((value * 0.998).toFixed(4))),
            high: closes.map((value) => Number((value * 1.006).toFixed(4))),
            low: closes.map((value) => Number((value * 0.994).toFixed(4))),
            close: closes,
            volume: closes.map((_, index) => 1_000_000 + index * 1000),
          }],
          adjclose: [{ adjclose: adj }],
        },
        events: { splits: {} },
      }],
      error: null,
    },
  }
}

function autocomplete(query) {
  const needle = (query ?? '').toUpperCase()
  const catalog = [
    { symbol: 'VOO', name: 'Vanguard S&P 500 ETF', exch: 'PCX', type: 'E', exchDisp: 'NYSEArca', typeDisp: 'ETF' },
    { symbol: 'QQQ', name: 'Invesco QQQ Trust', exch: 'NGM', type: 'E', exchDisp: 'NASDAQ', typeDisp: 'ETF' },
  ]
  return {
    ResultSet: {
      Query: query ?? '',
      Result: catalog.filter((item) => !needle || item.symbol.includes(needle) || item.name.toUpperCase().includes(needle)),
    },
  }
}

function quoteSummary(symbol) {
  return {
    quoteSummary: {
      result: [{
        summaryDetail: {
          currency: 'USD',
          annualReportExpenseRatio: { raw: 0.0003 },
          yield: { raw: 0.0115 },
        },
        defaultKeyStatistics: { totalAssets: { raw: 1_000_000_000 } },
        assetProfile: { fundFamily: symbol === 'QQQ' ? 'Invesco' : 'Vanguard' },
      }],
      error: null,
    },
  }
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`)
  const path = url.pathname

  if (path === '/health') {
    json(response, 200, { status: 'ok' })
    return
  }
  if (path === '/__e2e/mode' && request.method === 'GET') {
    json(response, 200, { chart: chartMode })
    return
  }
  if (path === '/__e2e/mode' && request.method === 'POST') {
    try {
      const body = JSON.parse((await readBody(request)) || '{}')
      if (body.chart) chartMode = String(body.chart)
      json(response, 200, { chart: chartMode })
    } catch {
      json(response, 400, { error: 'invalid mode' })
    }
    return
  }

  if (chartMode === 'fail') {
    json(response, 429, { error: 'Too Many Requests' })
    return
  }

  if (path === '/v6/finance/autocomplete') {
    json(response, 200, autocomplete(url.searchParams.get('query')))
    return
  }
  if (path.startsWith('/v8/finance/chart/')) {
    if (chartMode === 'empty') {
      json(response, 200, { chart: { result: [], error: null } })
      return
    }
    json(response, 200, chartBody(url, chartMode !== 'missing-adj'))
    return
  }
  if (path.startsWith('/v10/finance/quoteSummary/')) {
    const symbol = path.split('/').pop() ?? 'VOO'
    json(response, 200, quoteSummary(symbol))
    return
  }

  json(response, 404, { error: 'not found' })
})

server.listen(PORT, '0.0.0.0', () => {
  process.stdout.write(`e2e mock yahoo listening on ${PORT}\n`)
})
