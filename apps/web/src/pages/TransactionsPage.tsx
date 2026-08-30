import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDownLeft, ArrowUpRight, FileUp, Pencil, Plus, Trash2, Upload, X } from 'lucide-react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { api } from '../lib/api'
import { decimal, formatDate, formatMoney, formatShares } from '../lib/format'
import { parseTransactionCsv } from '../lib/transactionCsv'
import { invalidateTransactionQueries, queryKeys } from '../lib/queryKeys'
import type { ContributionType, InvestmentPlan, PlanCycle, Transaction, TransactionImportPreview, TransactionInput, TransactionType } from '../types'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Dialog } from '../components/Dialog'
import { Panel } from '../components/Panel'

const decimalPattern = /^\d+(?:\.\d{1,6})?$/
const quantityPattern = /^\d+(?:\.\d{1,8})?$/
const transactionSchema = z.object({
  tradeDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'validation.dateRequired'),
  transactionType: z.enum(['BUY', 'SELL', 'DIVIDEND', 'FEE']),
  instrumentSymbol: z.string().trim().regex(/^[A-Za-z][A-Za-z0-9.-]{0,9}$/, 'validation.symbolRequired'),
  quantity: z.string().optional(),
  unitPrice: z.string().optional(),
  amount: z.string().optional(),
  fee: z.string().regex(/^\d*(?:\.\d{0,6})?$/, 'validation.feeInvalid'),
  notes: z.string().optional(),
  planCycleId: z.string().optional(),
  contributionType: z.enum(['INITIAL', 'DCA', 'UNPLANNED']).optional(),
}).superRefine((values, context) => {
  if (values.transactionType === 'BUY' || values.transactionType === 'SELL') {
    if (!values.quantity || !quantityPattern.test(values.quantity) || decimal(values.quantity).lte(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['quantity'], message: 'validation.quantityRequired' })
    if (!values.unitPrice || !decimalPattern.test(values.unitPrice) || decimal(values.unitPrice).lte(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['unitPrice'], message: 'validation.unitPriceRequired' })
  }
  if (values.transactionType === 'BUY' && values.contributionType === 'DCA' && !values.planCycleId) {
    context.addIssue({ code: z.ZodIssueCode.custom, path: ['planCycleId'], message: 'Select a DCA cycle.' })
  }
  if (values.transactionType === 'DIVIDEND' || values.transactionType === 'FEE') {
    if (!values.amount || !decimalPattern.test(values.amount) || decimal(values.amount).lte(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['amount'], message: 'validation.amountRequired' })
    if (values.fee && decimal(values.fee).gt(0)) context.addIssue({ code: z.ZodIssueCode.custom, path: ['fee'], message: 'validation.cashEventFee' })
  }
})

type TransactionFormValues = z.infer<typeof transactionSchema>

const initialForm: TransactionFormValues = { tradeDate: '2026-08-27', transactionType: 'BUY', instrumentSymbol: 'VOO', quantity: '', unitPrice: '', amount: '', fee: '0.00', notes: '', planCycleId: '', contributionType: 'UNPLANNED' }

function inferredContributionType(transaction: Transaction): ContributionType {
  if (transaction.contributionType) return transaction.contributionType
  return transaction.planCycleId ? 'DCA' : 'UNPLANNED'
}

function transactionValues(transaction?: Transaction): TransactionFormValues {
  if (!transaction) return initialForm
  return { tradeDate: transaction.tradeDate, transactionType: transaction.transactionType, instrumentSymbol: transaction.instrumentSymbol, quantity: transaction.quantity ?? '', unitPrice: transaction.unitPrice ?? '', amount: transaction.amount ?? '', fee: transaction.fee, notes: transaction.notes ?? '', planCycleId: transaction.planCycleId ?? '', contributionType: inferredContributionType(transaction) }
}

function transactionInput(values: TransactionFormValues, planId?: string): TransactionInput {
  const trade = values.transactionType === 'BUY' || values.transactionType === 'SELL'
  const buy = values.transactionType === 'BUY'
  const contributionType = buy ? values.contributionType ?? 'UNPLANNED' : null
  return {
    tradeDate: values.tradeDate,
    transactionType: values.transactionType,
    instrumentSymbol: values.instrumentSymbol.toUpperCase(),
    quantity: trade ? values.quantity || undefined : undefined,
    unitPrice: trade ? values.unitPrice || undefined : undefined,
    amount: trade ? undefined : values.amount || undefined,
    fee: values.fee || '0',
    currency: 'USD',
    notes: values.notes?.trim() || undefined,
    planCycleId: buy && contributionType === 'DCA' ? values.planCycleId || null : null,
    contributionType,
    contributionPlanId: buy && contributionType === 'INITIAL' ? planId ?? null : null,
  }
}

function transactionTotal(transaction: Transaction): string {
  if (transaction.total) return transaction.total
  if (transaction.quantity && transaction.unitPrice) {
    const gross = decimal(transaction.quantity).mul(transaction.unitPrice)
    return gross.plus(transaction.transactionType === 'BUY' ? transaction.fee : transaction.transactionType === 'SELL' ? decimal(transaction.fee).neg() : 0).toFixed(6)
  }
  return transaction.amount ?? '0'
}

function TransactionTypeIcon({ type }: { type: TransactionType }) {
  if (type === 'BUY') return <span className="transaction-icon transaction-buy"><ArrowDownLeft size={14} /></span>
  if (type === 'SELL') return <span className="transaction-icon transaction-sell"><ArrowUpRight size={14} /></span>
  return <span className="transaction-icon transaction-neutral"><FileUp size={14} /></span>
}

function TransactionModal({ transaction, cycles, plan, onClose, onSaved }: { transaction?: Transaction; cycles: PlanCycle[]; plan?: InvestmentPlan; onClose: () => void; onSaved: () => void }) {
  const { t, i18n } = useTranslation()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const queryClient = useQueryClient()
  const form = useForm<TransactionFormValues>({ resolver: zodResolver(transactionSchema), defaultValues: transactionValues(transaction), mode: 'onBlur' })
  const type = form.watch('transactionType')
  const tradeDate = form.watch('tradeDate')
  const contributionType = form.watch('contributionType')
  const initialAllowed = Boolean(plan && tradeDate === plan.startDate)
  const matchingCycles = useMemo(() => cycles.filter((cycle) => cycle.period === tradeDate.slice(0, 7)), [cycles, tradeDate])
  const invalidInitialDate = type === 'BUY' && contributionType === 'INITIAL' && !initialAllowed
  const save = useMutation({ mutationFn: (values: TransactionFormValues) => transaction ? api.updateTransaction(transaction.id, transactionInput(values, plan?.id)) : api.createTransaction(transactionInput(values, plan?.id)), onSuccess: () => { void invalidateTransactionQueries(queryClient, plan?.id); onSaved() } })
  const errorMessage = save.error instanceof Error ? save.error.message : undefined
  const sourceRegistration = form.register('contributionType')
  const dateRegistration = form.register('tradeDate')
  return <Dialog className="modal-form" labelledBy="transaction-modal-title" describedBy="transaction-modal-description" onClose={onClose}><button type="button" className="modal-close icon-button" onClick={onClose} aria-label={t('common.close')}><X size={17} /></button><span className="modal-icon modal-icon-blue"><ArrowDownLeft size={18} /></span><h2 id="transaction-modal-title">{transaction ? t('transactions.editTransaction') : t('transactions.addTransaction')}</h2><p id="transaction-modal-description">{t('transactions.formHint')}</p><form onSubmit={(event) => { void form.handleSubmit((values) => save.mutate(values))(event) }} className="form-stack">
    <div className="form-grid-two"><div className="form-field"><label htmlFor="transaction-date">{t('transactions.date')}</label><input id="transaction-date" type="date" {...dateRegistration} onChange={(event) => { void dateRegistration.onChange(event); const selected = cycles.find((cycle) => cycle.id === form.getValues('planCycleId')); if (selected && selected.period !== event.target.value.slice(0, 7)) form.setValue('planCycleId', '') }} aria-invalid={Boolean(form.formState.errors.tradeDate)} aria-describedby={form.formState.errors.tradeDate ? 'transaction-date-error' : undefined} />{form.formState.errors.tradeDate ? <small id="transaction-date-error" className="field-error">{t(form.formState.errors.tradeDate.message ?? 'errors.validation')}</small> : null}</div><div className="form-field"><label htmlFor="transaction-type">{t('transactions.type')}</label><select id="transaction-type" {...form.register('transactionType')}><option value="BUY">BUY</option><option value="SELL">SELL</option><option value="DIVIDEND">DIVIDEND</option><option value="FEE">FEE</option></select></div></div>
    <div className="form-field"><label htmlFor="transaction-symbol">{t('etfs.ticker')}</label><input id="transaction-symbol" placeholder="VOO" {...form.register('instrumentSymbol')} aria-invalid={Boolean(form.formState.errors.instrumentSymbol)} aria-describedby={form.formState.errors.instrumentSymbol ? 'transaction-symbol-error' : undefined} />{form.formState.errors.instrumentSymbol ? <small id="transaction-symbol-error" className="field-error">{t(form.formState.errors.instrumentSymbol.message ?? 'errors.validation')}</small> : null}</div>
    {type === 'BUY' || type === 'SELL' ? <div className="form-grid-two"><div className="form-field"><label htmlFor="transaction-quantity">{t('transactions.quantity')}</label><input id="transaction-quantity" inputMode="decimal" placeholder="1.238423" {...form.register('quantity')} aria-invalid={Boolean(form.formState.errors.quantity)} aria-describedby={form.formState.errors.quantity ? 'transaction-quantity-error' : undefined} />{form.formState.errors.quantity ? <small id="transaction-quantity-error" className="field-error">{t(form.formState.errors.quantity.message ?? 'errors.validation')}</small> : null}</div><div className="form-field"><label htmlFor="transaction-price">{t('transactions.unitPrice')}</label><div className="input-prefix"><span>$</span><input id="transaction-price" inputMode="decimal" placeholder="520.45" {...form.register('unitPrice')} aria-invalid={Boolean(form.formState.errors.unitPrice)} aria-describedby={form.formState.errors.unitPrice ? 'transaction-price-error' : undefined} /></div>{form.formState.errors.unitPrice ? <small id="transaction-price-error" className="field-error">{t(form.formState.errors.unitPrice.message ?? 'errors.validation')}</small> : null}</div></div> : <div className="form-field"><label htmlFor="transaction-amount">{t('transactions.amount')}</label><div className="input-prefix"><span>$</span><input id="transaction-amount" inputMode="decimal" placeholder="42.18" {...form.register('amount')} aria-invalid={Boolean(form.formState.errors.amount)} aria-describedby={form.formState.errors.amount ? 'transaction-amount-error' : undefined} /></div>{form.formState.errors.amount ? <small id="transaction-amount-error" className="field-error">{t(form.formState.errors.amount.message ?? 'errors.validation')}</small> : null}</div>}
    {type === 'BUY' ? <div className="form-grid-two"><div className="form-field"><label htmlFor="transaction-fee">{t('transactions.fee')}</label><div className="input-prefix"><span>$</span><input id="transaction-fee" inputMode="decimal" {...form.register('fee')} aria-invalid={Boolean(form.formState.errors.fee)} aria-describedby={form.formState.errors.fee ? 'transaction-fee-error' : undefined} /></div>{form.formState.errors.fee ? <small id="transaction-fee-error" className="field-error">{t(form.formState.errors.fee.message ?? 'errors.validation')}</small> : null}</div><div className="form-field"><label htmlFor="transaction-contribution-source">{isZh ? '资金来源' : 'Contribution source'}</label><select id="transaction-contribution-source" {...sourceRegistration} onChange={(event) => { void sourceRegistration.onChange(event); if (event.target.value !== 'DCA') form.setValue('planCycleId', '') }}><option value="DCA" disabled={!plan}>{isZh ? '定投' : 'DCA'}</option><option value="INITIAL" disabled={!initialAllowed}>{isZh ? `初始资金${plan ? `（仅 ${plan.startDate}）` : ''}` : `Initial capital${plan ? ` (${plan.startDate} only)` : ''}`}</option><option value="UNPLANNED">{isZh ? '计划外' : 'Outside plan'}</option></select>{invalidInitialDate ? <small className="field-error">{isZh ? `初始资金只能登记在投资计划开始日 ${plan?.startDate ?? ''}。` : `Initial capital can only be recorded on the plan start date ${plan?.startDate ?? ''}.`}</small> : <small className="field-hint">{isZh ? '初始资金与每月定投分开统计。' : 'Initial capital is tracked separately from monthly DCA.'}</small>}</div></div> : <div className="form-field"><label htmlFor="transaction-fee">{t('transactions.fee')}</label><div className="input-prefix"><span>$</span><input id="transaction-fee" inputMode="decimal" {...form.register('fee')} aria-invalid={Boolean(form.formState.errors.fee)} aria-describedby={form.formState.errors.fee ? 'transaction-fee-error' : undefined} /></div>{form.formState.errors.fee ? <small id="transaction-fee-error" className="field-error">{t(form.formState.errors.fee.message ?? 'errors.validation')}</small> : null}</div>}
    {type === 'BUY' && contributionType === 'DCA' ? <div className="form-field"><label htmlFor="transaction-cycle">{isZh ? '定投月份' : 'DCA cycle'}</label><select id="transaction-cycle" {...form.register('planCycleId')} aria-invalid={Boolean(form.formState.errors.planCycleId)}><option value="">{isZh ? '请选择定投月份' : 'Select DCA cycle'}</option>{matchingCycles.map((cycle) => <option key={cycle.id} value={cycle.id}>{formatDate(`${cycle.period}-01`)}</option>)}</select>{form.formState.errors.planCycleId ? <small className="field-error">{isZh ? '请选择与交易日期对应的定投月份。' : 'Select the DCA cycle that matches the trade date.'}</small> : matchingCycles.length === 0 ? <small className="field-hint">{isZh ? '当前交易日期没有可用的定投周期。' : 'No DCA cycle is available for this trade date.'}</small> : null}</div> : null}
    <div className="form-field"><label htmlFor="transaction-notes">{t('transactions.notes')}</label><textarea id="transaction-notes" rows={2} placeholder={t('transactions.optionalNote')} {...form.register('notes')} /></div>
    {errorMessage ? <p className="form-alert" role="alert">{errorMessage}</p> : null}
    <div className="form-footer"><button type="button" className="button button-ghost" onClick={onClose}>{t('common.cancel')}</button><button type="submit" className="button button-primary" disabled={save.isPending || invalidInitialDate}><Plus size={15} />{save.isPending ? t('transactions.saving') : transaction ? t('common.save') : t('transactions.saveTransaction')}</button></div>
  </form></Dialog>
}

function CsvModal({ planId, onClose, onImported }: { planId?: string; onClose: () => void; onImported: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [csv, setCsv] = useState('date,type,symbol,quantity,price,fee\n2026-09-01,BUY,VOO,1.2,620.00,0\n2026-09-01,BUY,QQQ,0.8,574.00,0')
  const [preview, setPreview] = useState<TransactionImportPreview | null>(null)
  const localParse = useMemo(() => parseTransactionCsv(csv), [csv])
  const previewMutation = useMutation({ mutationFn: () => api.previewTransactionImport(csv), onSuccess: (result) => setPreview(result.data) })
  const importMutation = useMutation({ mutationFn: () => api.commitTransactionImport(preview as TransactionImportPreview), onSuccess: () => { void invalidateTransactionQueries(queryClient, planId); onImported() } })
  const currentPreview = preview?.rows.length || preview?.errors.length ? preview : { batchId: '', rows: localParse.rows, errors: localParse.errors }
  const duplicateRows = currentPreview.duplicateRows ?? []
  const hasPreviewErrors = currentPreview.errors.length > 0 || duplicateRows.length > 0
  const previewError = previewMutation.error instanceof Error ? previewMutation.error.message : importMutation.error instanceof Error ? importMutation.error.message : undefined
  return <Dialog className="modal-wide modal-csv" labelledBy="csv-title" describedBy="csv-description" onClose={onClose}><button type="button" className="modal-close icon-button" onClick={onClose} aria-label={t('common.close')}><X size={17} /></button><span className="modal-icon modal-icon-blue"><Upload size={18} /></span><h2 id="csv-title">{t('transactions.csvTitle')}</h2><p id="csv-description">{t('transactions.csvHint')}</p><label className="sr-only" htmlFor="csv-input">{t('transactions.csvInput')}</label><textarea id="csv-input" className="csv-editor" rows={6} value={csv} onChange={(event) => { setCsv(event.target.value); setPreview(null) }} />{hasPreviewErrors ? <div className="csv-errors" role="alert" id="csv-errors">{currentPreview.errors.map((error) => <span key={error}>{error}</span>)}{duplicateRows.length ? <span>{t('transactions.duplicateRows', { rows: duplicateRows.join(', ') })}</span> : null}</div> : <div className="csv-preview"><div className="csv-preview-header"><span>{t('transactions.preview')}</span><strong>{t('transactions.validRows', { count: currentPreview.rows.length })}</strong></div>{currentPreview.rows.slice(0, 4).map((row, index) => <div className="csv-preview-row" key={`${row.instrumentSymbol}-${index}`}><span>{row.tradeDate}</span><span className="ticker-chip">{row.instrumentSymbol}</span><span>{row.transactionType}</span><span>{row.quantity ? `${formatShares(row.quantity)} @ ${formatMoney(row.unitPrice)}` : formatMoney(row.amount)}</span></div>)}</div>}{previewError ? <p className="form-alert" role="alert">{previewError}</p> : null}<div className="form-footer"><button type="button" className="button button-secondary" onClick={() => { void previewMutation.mutate() }} disabled={previewMutation.isPending || localParse.errors.length > 0}>{previewMutation.isPending ? t('transactions.validating') : t('transactions.validateCsv')}</button><button type="button" className="button button-primary" onClick={() => { void importMutation.mutate() }} disabled={!preview || hasPreviewErrors || preview.rows.length === 0 || importMutation.isPending}><Upload size={15} />{t('transactions.importRows', { count: preview?.rows.length ?? 0 })}</button></div></Dialog>
}

function contributionLabel(transaction: Transaction, isZh: boolean): string {
  if (transaction.transactionType !== 'BUY') return '—'
  if (transaction.contributionType === 'INITIAL') return isZh ? '初始资金' : 'Initial'
  if (transaction.contributionType === 'DCA' || transaction.planCycleId) return isZh ? '定投' : 'DCA'
  if (transaction.contributionType === 'UNPLANNED') return isZh ? '计划外' : 'Outside plan'
  return isZh ? '未归类' : 'Unclassified'
}

function TransactionRow({ transaction, onDelete, onEdit }: { transaction: Transaction; onDelete: (id: string) => void; onEdit: (transaction: Transaction) => void }) {
  const { t, i18n } = useTranslation()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const canShowQuantity = Boolean(transaction.quantity && transaction.unitPrice)
  const label = transaction.transactionType.toLowerCase()
  return <tr><td><span className="table-ticker"><TransactionTypeIcon type={transaction.transactionType} /><span><strong>{transaction.instrumentSymbol}</strong><small>{transaction.notes ?? t(`transactions.${label}`, { defaultValue: transaction.transactionType })}</small></span></span></td><td>{formatDate(transaction.tradeDate)}</td><td><span className={`type-label type-${label}`}>{transaction.transactionType}</span></td><td><span className="type-label">{contributionLabel(transaction, isZh)}</span></td><td>{canShowQuantity ? <><strong>{formatShares(transaction.quantity)}</strong><small className="table-subline">@ {formatMoney(transaction.unitPrice)}</small></> : '—'}</td><td><strong>{formatMoney(transactionTotal(transaction))}</strong>{decimal(transaction.fee).gt(0) ? <small className="table-subline">{t('transactions.fee')} {formatMoney(transaction.fee)}</small> : null}</td><td><div className="row-actions"><button type="button" className="icon-button subtle-icon" aria-label={t('common.edit')} onClick={() => onEdit(transaction)}><Pencil size={14} /></button><button type="button" className="icon-button subtle-icon danger-icon" onClick={() => onDelete(transaction.id)} aria-label={t('common.delete')}><Trash2 size={14} /></button></div></td></tr>
}

export function TransactionsPage() {
  const { t, i18n } = useTranslation()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const queryClient = useQueryClient()
  const [addOpen, setAddOpen] = useState(false)
  const [csvOpen, setCsvOpen] = useState(false)
  const [editing, setEditing] = useState<Transaction | null>(null)
  const [filter, setFilter] = useState<'ALL' | TransactionType>('ALL')
  const [search, setSearch] = useState('')
  const transactions = useQuery({ queryKey: queryKeys.transactions, queryFn: api.getTransactions })
  const plans = useQuery({ queryKey: queryKeys.plans, queryFn: api.getPlans })
  const activePlan = plans.data?.data.find((plan) => plan.status === 'ACTIVE')
  const cycles = useQuery({ queryKey: activePlan ? queryKeys.transactionCycles(activePlan.id) : queryKeys.transactionCycles('none'), queryFn: () => api.getCycles(activePlan?.id ?? ''), enabled: Boolean(activePlan) })
  const remove = useMutation({ mutationFn: (id: string) => api.deleteTransaction(id), onSuccess: () => { void invalidateTransactionQueries(queryClient, activePlan?.id) } })
  const filtered = useMemo(() => (transactions.data?.data ?? []).filter((transaction) => (filter === 'ALL' || transaction.transactionType === filter) && (!search || transaction.instrumentSymbol.toLowerCase().includes(search.toLowerCase()))), [filter, search, transactions.data?.data])
  const modalCycles = cycles.data?.data ?? activePlan?.cycles ?? []
  return <div className="page transactions-page">
    <div className="page-intro"><div><span className="page-eyebrow">{t('transactions.eyebrow')}</span><h1>{t('transactions.title')}</h1><p>{t('transactions.subtitle')}</p></div><div className="page-actions"><button type="button" className="button button-secondary" onClick={() => setCsvOpen(true)}><FileUp size={15} />{t('transactions.importCsv')}</button><button type="button" className="button button-primary" onClick={() => { setEditing(null); setAddOpen(true) }}><Plus size={16} />{t('transactions.addTransaction')}</button></div></div>
    <DataStateBanner status={transactions.data?.meta.status ?? (transactions.isError ? 'UNAVAILABLE' : 'STALE')} message={transactions.data?.meta.message} source={transactions.data?.meta.source === 'FIXTURE' ? t('common.demoData') : transactions.data?.meta.source} asOf={transactions.data?.meta.asOf} retrievedAt={transactions.data?.meta.retrievedAt} />
    <Panel className="transactions-panel" flush><div className="table-toolbar"><div><strong>{transactions.data?.data.length ?? 0}</strong><span> {t('transactions.ledgerEntries')}</span></div><div className="table-toolbar-actions"><label className="inline-search"><span className="sr-only">{t('transactions.searchSymbol')}</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder={t('transactions.filterSymbol')} /></label><select value={filter} onChange={(event) => setFilter(event.target.value as 'ALL' | TransactionType)} aria-label={t('transactions.filterType')}><option value="ALL">{t('transactions.allTypes')}</option><option value="BUY">BUY</option><option value="SELL">SELL</option><option value="DIVIDEND">DIVIDEND</option><option value="FEE">FEE</option></select></div></div>{transactions.isLoading ? <div className="table-loading"><LoadingBlock lines={7} /></div> : transactions.isError ? <ErrorState onRetry={() => void transactions.refetch()} /> : filtered.length ? <div className="data-table-wrap"><table className="data-table transactions-table"><thead><tr><th scope="col">{t('transactions.transaction')}</th><th scope="col">{t('transactions.date')}</th><th scope="col">{t('transactions.type')}</th><th scope="col">{isZh ? '资金来源' : 'Source'}</th><th scope="col">{t('transactions.quantity')}</th><th scope="col">{t('transactions.total')}</th><th scope="col" aria-label={t('transactions.actions')} /></tr></thead><tbody>{filtered.map((transaction) => <TransactionRow key={transaction.id} transaction={transaction} onDelete={(id) => remove.mutate(id)} onEdit={(value) => { setEditing(value); setAddOpen(true) }} />)}</tbody></table></div> : <EmptyState title={t('transactions.noTransactions')} detail={t('transactions.sourceHint')} action={<button type="button" className="button button-primary" onClick={() => setAddOpen(true)}><Plus size={15} />{t('transactions.addTransaction')}</button>} />}</Panel>
    {remove.error instanceof Error ? <p className="form-alert page-alert" role="alert">{remove.error.message}</p> : null}
    <div className="ledger-note"><span className="status-dot status-dot-blue" /><span>{t('transactions.ledgerNote')}</span></div>
    {addOpen ? <TransactionModal transaction={editing ?? undefined} cycles={modalCycles} plan={activePlan} onClose={() => { setAddOpen(false); setEditing(null) }} onSaved={() => { setAddOpen(false); setEditing(null) }} /> : null}{csvOpen ? <CsvModal planId={activePlan?.id} onClose={() => setCsvOpen(false)} onImported={() => setCsvOpen(false)} /> : null}
  </div>
}
