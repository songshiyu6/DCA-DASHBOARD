import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Plus, Save, SlidersHorizontal, Trash2 } from 'lucide-react'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { api } from '../lib/api'
import { decimal, decimalMax, decimalMin, formatMoney, formatPercent, formatPeriod, formatSignedPercent } from '../lib/format'
import type { Instrument, InvestmentPlan, PlanCycle, Recommendation } from '../types'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Panel } from '../components/Panel'
import { StatusBadge } from '../components/StatusBadge'

const weightPattern = /^\d{1,3}(?:\.\d{1,4})?$/
const planSchema = z.object({
  name: z.string().trim().min(2, 'Plan name is required.'),
  monthlyBudget: z.string().regex(/^\d+(?:\.\d{1,2})?$/, 'Enter a valid budget.').refine((value) => decimal(value).gt(0), 'Budget must be greater than zero.'),
  startDate: z.string().min(1, 'Start date is required.'),
  executionStartDay: z.coerce.number().int().min(1).max(28),
  executionEndDay: z.coerce.number().int().min(1).max(31),
  assets: z.array(z.object({ symbol: z.string().trim().min(1, 'Select an ETF.'), targetWeight: z.string().regex(weightPattern, 'Enter a percentage.') })).min(1),
}).superRefine((values, context) => {
  const total = values.assets.reduce((sum, asset) => sum.plus(decimal(asset.targetWeight)), decimal(0))
  if (!total.minus(100).abs().lte('0.01')) context.addIssue({ code: z.ZodIssueCode.custom, path: ['assets'], message: 'Target weights must total 100.00%.' })
  const symbols = values.assets.map((asset) => asset.symbol.toUpperCase())
  if (new Set(symbols).size !== symbols.length) context.addIssue({ code: z.ZodIssueCode.custom, path: ['assets'], message: 'Each ETF can only appear once.' })
  if (values.executionEndDay < values.executionStartDay) context.addIssue({ code: z.ZodIssueCode.custom, path: ['executionEndDay'], message: 'Execution end must be on or after the start.' })
})

type PlanFormValues = z.infer<typeof planSchema>

const emptyForm: PlanFormValues = {
  name: 'Core ETF Plan',
  monthlyBudget: '1500.00',
  startDate: '2026-01-01',
  executionStartDay: 1,
  executionEndDay: 7,
  assets: [{ symbol: 'VOO', targetWeight: '100.00' }],
}

function formValues(plan: InvestmentPlan): PlanFormValues {
  return {
    name: plan.name,
    monthlyBudget: plan.monthlyBudget,
    startDate: plan.startDate,
    executionStartDay: plan.executionStartDay,
    executionEndDay: plan.executionEndDay,
    assets: plan.assets.map((asset) => ({ symbol: asset.symbol, targetWeight: decimal(asset.targetWeight).mul(100).toFixed(2) })),
  }
}

function planPayload(values: PlanFormValues): Omit<InvestmentPlan, 'id' | 'cycles'> {
  return {
    name: values.name.trim(),
    currency: 'USD',
    frequency: 'MONTHLY',
    monthlyBudget: decimal(values.monthlyBudget).toFixed(2),
    startDate: values.startDate,
    executionStartDay: values.executionStartDay,
    executionEndDay: values.executionEndDay,
    status: 'ACTIVE',
    assets: values.assets.map((asset) => ({ symbol: asset.symbol.toUpperCase(), targetWeight: decimal(asset.targetWeight).div(100).toFixed(8) })),
  }
}

function CycleRow({ cycle }: { cycle: PlanCycle }) {
  const planned = decimal(cycle.plannedAmount)
  const ratio = planned.gt(0) ? decimalMin(decimalMax(decimal(cycle.executedAmount).div(planned), 0), 1).toNumber() * 100 : 0
  return <div className="cycle-row">
    <div className="cycle-period"><span className={`cycle-marker cycle-marker-${cycle.status.toLowerCase()}`}>{cycle.status === 'COMPLETED' ? <Check size={13} /> : cycle.status === 'PARTIAL' ? '½' : '·'}</span><div><strong>{formatPeriod(cycle.period)}</strong><small>{cycle.period}</small></div></div>
    <div className="cycle-progress"><div className="cycle-track"><span style={{ width: `${ratio}%` }} /></div><small>{formatMoney(cycle.executedAmount)} <span>/ {formatMoney(cycle.plannedAmount)}</span></small></div>
    <div className="cycle-status"><StatusBadge status={cycle.status} compact /></div>
  </div>
}

function RecommendationPanel({ recommendation }: { recommendation: Recommendation }) {
  const { t } = useTranslation()
  return <Panel title={t('plan.recommendation')} detail={t('plan.recommendationHint')} action={<span className="recommendation-total">{formatMoney(recommendation.amount)}</span>}>
    <div className="recommendation-method"><SlidersHorizontal size={14} /><span>{t('plan.method')}: <strong>Contribution-first</strong></span></div>
    {recommendation.items.length ? <div className="recommendation-table"><div className="recommendation-header"><span>{t('etfs.ticker')}</span><span>{t('plan.current')}</span><span>{t('plan.target')}</span><span>{t('plan.gap')}</span><span>{t('plan.suggested')}</span></div>{recommendation.items.map((item) => <div className="recommendation-item" key={item.symbol}><span className="ticker-chip">{item.symbol}</span><span>{formatPercent(item.currentWeight)}</span><span>{formatPercent(item.targetWeight)}</span><span className={decimal(item.gap).gt(0) ? 'text-positive' : decimal(item.gap).lt(0) ? 'text-negative' : ''}>{formatSignedPercent(item.gap)}</span><strong>{formatMoney(item.suggestedAmount)}</strong></div>)}</div> : <EmptyState title={t('common.noData')} />}
  </Panel>
}

function TargetAllocationPanel({ plan }: { plan: InvestmentPlan }) {
  const { t } = useTranslation()
  const total = plan.assets.reduce((sum, asset) => sum.plus(decimal(asset.targetWeight)), decimal(0))
  return <Panel title={t('plan.targetAllocation')} detail={t('plan.allocationHint')}>
    {plan.assets.length ? <div className="plan-allocation-list">{plan.assets.map((asset, index) => <div className="plan-allocation-row" key={asset.id ?? asset.symbol}><div className="plan-allocation-label"><span className={`allocation-swatch segment-${index}`} /><strong>{asset.symbol}</strong><span>{formatPercent(asset.targetWeight)}</span></div><div className="plan-allocation-track"><span className={`allocation-fill segment-${index}`} style={{ width: `${Math.max(decimal(asset.targetWeight).mul(100).toNumber(), 0)}%` }} /></div></div>)}<div className="plan-allocation-total"><span>{t('plan.total')}</span><strong>{formatPercent(total.toString())}</strong></div></div> : <EmptyState title={t('plan.noAssets')} />}
  </Panel>
}

function PlanEditor({ plan, instruments, pending, saved, onSubmit }: { plan?: InvestmentPlan; instruments: Instrument[]; pending: boolean; saved: boolean; onSubmit: (values: PlanFormValues) => void }) {
  const { t } = useTranslation()
  const form = useForm<PlanFormValues>({ resolver: zodResolver(planSchema), defaultValues: plan ? formValues(plan) : emptyForm, mode: 'onBlur' })
  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'assets' })
  const watchedAssets = useWatch({ control: form.control, name: 'assets' })
  const totalWeight = useMemo(() => watchedAssets?.reduce((sum, asset) => sum.plus(decimal(asset.targetWeight)), decimal(0)) ?? decimal(0), [watchedAssets])

  useEffect(() => {
    form.reset(plan ? formValues(plan) : emptyForm)
  }, [form, plan?.id])

  const totalValid = totalWeight.minus(100).abs().lte('0.01')
  const allocationError = totalValid ? (typeof form.formState.errors.assets?.message === 'string' ? form.formState.errors.assets.message : undefined) : 'Target weights must total 100.00%.'
  return <form onSubmit={form.handleSubmit(onSubmit)} className="form-stack">
    <div className="form-field"><label htmlFor="plan-name">{t('plan.name')}</label><input id="plan-name" {...form.register('name')} aria-invalid={Boolean(form.formState.errors.name)} />{form.formState.errors.name ? <small className="field-error">{form.formState.errors.name.message}</small> : null}</div>
    <div className="form-grid-two"><div className="form-field"><label htmlFor="monthly-budget">{t('plan.monthlyBudget')}</label><div className="input-prefix"><span>$</span><input id="monthly-budget" inputMode="decimal" {...form.register('monthlyBudget')} aria-invalid={Boolean(form.formState.errors.monthlyBudget)} /></div>{form.formState.errors.monthlyBudget ? <small className="field-error">{form.formState.errors.monthlyBudget.message}</small> : null}</div><div className="form-field"><label htmlFor="frequency">{t('plan.frequency')}</label><select id="frequency" defaultValue="MONTHLY" disabled><option value="MONTHLY">Monthly</option></select></div></div>
    <div className="form-field"><label htmlFor="start-date">{t('plan.startDate')}</label><input id="start-date" type="date" {...form.register('startDate')} />{form.formState.errors.startDate ? <small className="field-error">{form.formState.errors.startDate.message}</small> : null}</div>
    <div className="form-field"><label>{t('plan.executionWindow')}</label><div className="form-grid-two"><input aria-label="Execution start day" type="number" min="1" max="28" {...form.register('executionStartDay')} /><input aria-label="Execution end day" type="number" min="1" max="31" {...form.register('executionEndDay')} /></div><small className="field-hint">1st - 7th day of each month</small>{form.formState.errors.executionEndDay ? <small className="field-error">{form.formState.errors.executionEndDay.message}</small> : null}</div>
    <div className="form-footer"><span className="save-feedback">{saved ? <><Check size={14} />Saved</> : null}</span><button className="button button-primary" type="submit" disabled={pending || !totalValid}><Save size={15} />{pending ? 'Saving...' : plan ? t('common.save') : t('plan.createPlan')}</button></div>
    <div className="asset-editor"><div className="asset-editor-header"><span>{t('etfs.ticker')}</span><span>{t('dashboard.target')}</span><span /></div>{fields.map((field, index) => <div className="asset-editor-row" key={field.id}><select {...form.register(`assets.${index}.symbol`)} aria-label={`Asset ${index + 1}`}><option value="">Select ETF</option>{instruments.map((instrument) => <option key={instrument.symbol} value={instrument.symbol}>{instrument.symbol} · {instrument.name}</option>)}</select><div className="input-suffix"><input inputMode="decimal" {...form.register(`assets.${index}.targetWeight`)} aria-label={`${field.symbol || 'ETF'} target weight`} /><span>%</span></div><button type="button" className="icon-button subtle-icon" onClick={() => remove(index)} disabled={fields.length <= 1} aria-label={`Remove ${field.symbol || 'asset'}`}><Trash2 size={15} /></button></div>)}</div>
    {allocationError ? <small className="field-error block-error">{allocationError}</small> : null}
    <button type="button" className="button button-ghost add-asset-button" onClick={() => append({ symbol: '', targetWeight: '0.00' })}><Plus size={15} />{t('plan.addAsset')}</button>
    <div className="allocation-editor-bar">{fields.map((field, index) => <span key={field.id} style={{ width: `${Math.max(decimal(watchedAssets?.[index]?.targetWeight).toNumber(), 0)}%` }} className={`allocation-segment segment-${index}`} />)}</div>
  </form>
}

export function PlanPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const plans = useQuery({ queryKey: ['plans'], queryFn: api.getPlans })
  const instruments = useQuery({ queryKey: ['instruments'], queryFn: api.getInstruments })
  const plan = plans.data?.data.find((candidate) => candidate.status === 'ACTIVE')
  const cycles = useQuery({ queryKey: ['plan-cycles', plan?.id], queryFn: () => api.getCycles(plan?.id ?? ''), enabled: Boolean(plan) })
  const recommendation = useQuery({ queryKey: ['recommendation', plan?.id], queryFn: () => api.getRecommendation(plan?.id ?? ''), enabled: Boolean(plan) })
  const [saved, setSaved] = useState(false)
  const savePlan = useMutation({ mutationFn: (values: PlanFormValues) => plan ? api.updatePlan(plan.id, planPayload(values)) : api.createPlan(planPayload(values)), onSuccess: () => { setSaved(true); void queryClient.invalidateQueries({ queryKey: ['plans'] }); void queryClient.invalidateQueries({ queryKey: ['plan-cycles'] }); void queryClient.invalidateQueries({ queryKey: ['recommendation'] }); void queryClient.invalidateQueries({ queryKey: ['dashboard'] }) } })

  if (plans.isLoading) return <div className="page"><div className="page-intro"><LoadingBlock lines={2} /></div><div className="content-grid plan-grid"><Panel><LoadingBlock lines={8} /></Panel><Panel><LoadingBlock lines={8} /></Panel></div></div>
  if (plans.isError) return <div className="page"><ErrorState onRetry={() => void plans.refetch()} /></div>

  const cycleData = cycles.data?.data ?? plan?.cycles ?? []
  return <div className="page plan-page">
    <div className="page-intro"><div><span className="page-eyebrow">{t('plan.eyebrow')}</span><h1>{t('plan.title')}</h1><p>{t('plan.subtitle')}</p></div>{plan ? <div className="page-actions"><span className="active-plan-chip"><span className="status-dot" />{t('plan.active')}</span></div> : null}</div>
    <DataStateBanner status={plans.data?.meta.status ?? 'STALE'} message={plans.data?.meta.message} source={plans.data?.meta.source === 'FIXTURE' ? t('common.demoData') : plans.data?.meta.source} asOf={plans.data?.meta.asOf} retrievedAt={plans.data?.meta.retrievedAt} />
    <div className="content-grid plan-grid">
      <Panel title={t('plan.planSettings')} detail="Monthly budget and execution window"><PlanEditor plan={plan} instruments={instruments.data?.data ?? []} pending={savePlan.isPending} saved={saved} onSubmit={(values) => { setSaved(false); savePlan.mutate(values) }} /></Panel>
      {plan ? <TargetAllocationPanel plan={plan} /> : <Panel title={t('plan.targetAllocation')} detail={t('plan.allocationHint')}><EmptyState title={t('plan.noPlan')} detail={t('plan.noAssets')} /></Panel>}
    </div>
    {savePlan.error instanceof Error ? <p className="form-alert page-alert" role="alert">{savePlan.error.message}</p> : null}
    {plan ? <div className="content-grid plan-secondary-grid"><div className="plan-cycles-column"><Panel title={t('plan.cycles')} detail="Execution history">{cycles.isLoading ? <LoadingBlock lines={8} /> : cycles.isError ? <ErrorState onRetry={() => void cycles.refetch()} /> : cycleData.length ? <div className="cycles-list">{cycleData.map((cycle) => <CycleRow key={cycle.id} cycle={cycle} />)}</div> : <EmptyState title={t('common.noData')} />}</Panel></div><div>{recommendation.isLoading ? <Panel title={t('plan.recommendation')}><LoadingBlock lines={6} /></Panel> : recommendation.isError ? <ErrorState onRetry={() => void recommendation.refetch()} /> : recommendation.data ? <><DataStateBanner status={recommendation.data.meta.status} message={recommendation.data.meta.message} source={recommendation.data.meta.source === 'FIXTURE' ? t('common.demoData') : recommendation.data.meta.source} asOf={recommendation.data.meta.asOf} retrievedAt={recommendation.data.meta.retrievedAt} /><RecommendationPanel recommendation={recommendation.data.data} /></> : <EmptyState title={t('common.noData')} />}</div></div> : null}
  </div>
}
