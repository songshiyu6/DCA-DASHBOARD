import type { TransactionInput } from '../types'

/**
 * Structural CSV parsing for demo/fixture mode only.
 *
 * The live Transactions import UI does not call this module. Financial and
 * transaction-domain validation belongs to the server preview endpoint so the
 * browser cannot drift into a second set of ledger rules.
 */
export function parseTransactionCsv(csv: string): { rows: TransactionInput[]; errors: string[] } {
  const lines = csv.trim().split(/\r?\n/).map((line) => line.trimEnd()).filter(Boolean)
  if (lines.length < 2) return { rows: [], errors: ['CSV needs a header and at least one data row.'] }

  const headers = parseCsvLine(lines[0].replace(/^\uFEFF/, '')).map((header) => header.trim().toLowerCase())
  const required = ['date', 'type']
  const missing = required.filter((header) => !headers.includes(header))
  if (missing.length) return { rows: [], errors: [`Missing columns: ${missing.join(', ')}`] }

  const duplicateHeaders = headers.filter((header, index) => headers.indexOf(header) !== index)
  if (duplicateHeaders.length) return { rows: [], errors: [`Duplicate columns: ${[...new Set(duplicateHeaders)].join(', ')}`] }

  const rows = lines.slice(1).map((line) => {
    const values = parseCsvLine(line)
    const record = Object.fromEntries(headers.map((header, valueIndex) => [header, values[valueIndex]?.trim() ?? '']))
    const symbol = record.symbol?.trim().toUpperCase() ?? ''
    const transactionType = (record.type?.trim().toUpperCase() ?? '') as TransactionInput['transactionType']
    return {
      tradeDate: record.date ?? '',
      transactionType,
      ...(symbol ? { instrumentSymbol: symbol } : {}),
      ...(record.quantity ? { quantity: record.quantity } : {}),
      ...(record.price || record.unitprice || record.unit_price ? { unitPrice: record.price || record.unitprice || record.unit_price } : {}),
      ...(record.amount ? { amount: record.amount } : {}),
      fee: record.fee || '0',
      currency: 'USD' as const,
      ...(record.notes ? { notes: record.notes } : {}),
      ...(record.planCycleId || record.plan_cycle_id ? { planCycleId: record.planCycleId || record.plan_cycle_id } : {}),
    } as TransactionInput
  })

  return { rows, errors: [] }
}

function parseCsvLine(line: string): string[] {
  const values: string[] = []
  let current = ''
  let quoted = false
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index]
    if (character === '"' && line[index + 1] === '"' && quoted) {
      current += '"'
      index += 1
    } else if (character === '"') {
      quoted = !quoted
    } else if (character === ',' && !quoted) {
      values.push(current)
      current = ''
    } else {
      current += character
    }
  }
  values.push(current)
  return values
}
