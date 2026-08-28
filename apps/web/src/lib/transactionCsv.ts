import type { TransactionInput } from '../types'
import { decimal } from './format'

export function parseTransactionCsv(csv: string): { rows: TransactionInput[]; errors: string[] } {
  const lines = csv.trim().split(/\r?\n/).map((line) => line.trimEnd()).filter(Boolean)
  if (lines.length < 2) return { rows: [], errors: ['CSV needs a header and at least one data row.'] }
  const headers = parseCsvLine(lines[0].replace(/^\uFEFF/, '')).map((header) => header.trim().toLowerCase())
  const required = ['date', 'type', 'symbol']
  const missing = required.filter((header) => !headers.includes(header))
  if (missing.length) return { rows: [], errors: [`Missing columns: ${missing.join(', ')}`] }
  const duplicateHeaders = headers.filter((header, index) => headers.indexOf(header) !== index)
  if (duplicateHeaders.length) return { rows: [], errors: [`Duplicate columns: ${[...new Set(duplicateHeaders)].join(', ')}`] }
  const rows: TransactionInput[] = []
  const errors: string[] = []
  lines.slice(1).forEach((line, index) => {
    const values = parseCsvLine(line)
    const record = Object.fromEntries(headers.map((header, valueIndex) => [header, values[valueIndex]?.trim() ?? '']))
    const transactionType = record.type?.toUpperCase() as TransactionInput['transactionType']
    const lineNumber = index + 2
    const rowErrors: string[] = []
    if (!isIsoDate(record.date ?? '')) rowErrors.push('date must use a valid YYYY-MM-DD value.')
    if (!['BUY', 'SELL', 'DIVIDEND', 'FEE'].includes(transactionType)) rowErrors.push('unsupported transaction type.')
    if (!/^[A-Z][A-Z0-9.-]{0,9}$/.test(record.symbol?.toUpperCase() ?? '')) rowErrors.push('symbol is required and must be a valid ticker.')
    const quantity = record.quantity || undefined
    const unitPrice = record.price || record.unitprice || record.unit_price || undefined
    const amount = record.amount || undefined
    if (transactionType === 'BUY' || transactionType === 'SELL') {
      if (!isPositiveDecimal(quantity, 8)) rowErrors.push(`quantity is required for ${transactionType} and supports up to 8 decimals.`)
      if (!isPositiveDecimal(unitPrice, 6)) rowErrors.push(`price is required for ${transactionType} and supports up to 6 decimals.`)
    }
    if (transactionType === 'DIVIDEND' || transactionType === 'FEE') {
      if (!isPositiveDecimal(amount, 6)) rowErrors.push(`amount is required for ${transactionType} and supports up to 6 decimals.`)
    }
    if (record.fee && !isNonNegativeDecimal(record.fee, 6)) rowErrors.push('fee must be a non-negative decimal with up to 6 decimals.')
    else if ((transactionType === 'DIVIDEND' || transactionType === 'FEE') && record.fee && decimal(record.fee).gt(0)) rowErrors.push('use amount for DIVIDEND and FEE fees.')
    if (rowErrors.length) {
      rowErrors.forEach((error) => errors.push(`Line ${lineNumber}: ${error}`))
      return
    }
    rows.push({ instrumentSymbol: record.symbol.toUpperCase(), transactionType, tradeDate: record.date, quantity, unitPrice, amount, fee: record.fee || '0', currency: 'USD', notes: record.notes || undefined, planCycleId: record.planCycleId || record.plan_cycle_id || undefined })
  })
  return { rows, errors }
}

function isIsoDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [year, month, day] = value.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

function isPositiveDecimal(value: string | undefined, places: number): boolean {
  return Boolean(value && new RegExp(`^\\d+(?:\\.\\d{1,${places}})?$`).test(value) && decimal(value).gt(0))
}

function isNonNegativeDecimal(value: string, places: number): boolean {
  return new RegExp(`^\\d+(?:\\.\\d{1,${places}})?$`).test(value) && decimal(value).gte(0)
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
