import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Plus, Save, SlidersHorizontal, Trash2 } from 'lucide-react'
import { useFieldArray, useForm, useWatch } from 'react-hook-form'
import { z } from 'zod'
import { zodResolver } from '@hookform/resolvers/zod'
import { useTranslation } from 'react-i18next'
import { api } from '../lib/api'
import { decimal, decimalMax, decimalMin, formatMoney, formatPercent, formatPeriod, formatSignedPercent } from '../lib/format'
import { invalidatePlanQueries, queryKeys } from '../lib/queryKeys'
import type { Instrument, InvestmentPlan, PlanCycle, Recommendation } from '../types'
import { DataStateBanner, EmptyState, ErrorState, LoadingBlock } from '../components/DataState'
import { Panel } from '../components/Panel'
import { StatusBadge } from '../components/StatusBadge'

const weightPattern = /^\d{1,3}(?:\.\d{1,4})?$/
const planSchema = z.object({
  name: z.string().trim().min(2, 'validation.planNameRequired'),
  initialCapital: z.string().regex(/^\d*(?:\.\d{1,2})?$/, 'validation.budgetInvalid'),
  monthlyBudget: z.string().regex(/^\d+(?:\.\d{1,2})?$/, 'validation.budgetInvalid').refine((value) => decimal(value).gt(0), 'validation.budgetPositive'),
  startDate: z.string().min(1, 'validation.startDateRequired'),
  executionStartDay: z.coerce.number().int().min(1).max(31),
  executionEndDay: z.coerce.number().int().min(1).max(31),
  assets: z.array(z.object({ symbol: z.string().trim().min(1, 'validation.selectEtf'), targetWeight: z.string().regex(weightPattern, 'validation.percentageInvalid') })).min(1, 'validation.assetRequired'),
}).superRefine((values, context) => {
  const total = values.assets.reduce((sum, asset) => sum.plus(decimal(asset.targetWeight)), decimal(0))
  if (!total.minus(100).abs().lte('0.01')) context.addIssue({ code: z.ZodIssueCode.custom, path: ['assets'], message: 'validation.weightsTotal' })
  const symbols = values.assets.map((asset) => asset.symbol.toUpperCase())
  if (new Set(symbols).size !== symbols.length) context.addIssue({ code: z.ZodIssueCode.custom, path: ['assets'], message: 'validation.duplicateEtf' })
  if (values.executionEndDay < values.executionStartDay) context.addIssue({ code: z.ZodIssueCode.custom, path: ['executionEndDay'], message: 'validation.executionOrder' })
})

type PlanFormValues = z.infer<typeof planSchema>

const emptyForm: PlanFormValues = {
  name: 'Core ETF Plan',
  initialCapital: '',
  monthlyBudget: '1500.00',
  startDate: '2026-01-01',
  executionStartDay: 1,
  executionEndDay: 7,
  assets: [{ symbol: 'VOO', targetWeight: '100.00' }],
}

function formValues(plan: InvestmentPlan, initialCapital?: string | null): PlanFormValues {
  return {
    name: plan.name,
    initialCapital: initialCapital ?? '',
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
    <div className="recommendation-method"><SlidersHorizontal size={14} /><span>{t('plan.method')}: <strong>{t('plan.contributionFirst')}</strong></span></div>
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

function PlanEditor({ plan, initialCapital, instruments, pending, saved, onSubmit }: { plan?: InvestmentPlan; initialCapital?: string | null; instruments: Instrument[]; pending: boolean; saved: boolean; onSubmit: (values: PlanFormValues) => void }) {
  const { t, i18n } = useTranslation()
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const form = useForm<PlanFormValues>({ resolver: zodResolver(planSchema), defaultValues: plan ? formValues(plan, initialCapital) : emptyForm, mode: 'onBlur' })
  const { fields, append, remove } = useFieldArray({ control: form.control, name: 'assets' })
  const watchedAssets = useWatch({ control: form.control, name: 'assets' })
  const totalWeight = useMemo(() => watchedAssets?.reduce((sum, asset) => sum.plus(decimal(asset.targetWeight)), decimal(0)) ?? decimal(0), [watchedAssets])

  useEffect(() => {
    form.reset(plan ? formValues(plan, initialCapital) : emptyForm)
  }, [form, initialCapital, plan])

  const totalValid = totalWeight.minus(100).abs().lte('0.01')
  const allocationMessage = typeof form.formState.errors.assets?.message === 'string' ? form.formState.errors.assets.message : undefined
  const allocationError = totalValid ? (allocationMessage ? t(allocationMessage) : undefined) : t('validation.weightsTotal')
  return <form onSubmit={form.handleSubmit(onSubmit)} className="form-stack">
    <div className="form-field"><label htmlFor="plan-name">{t('plan.name')}</label><input id="plan-name" {...form.register('name')} aria-invalid={Boolean(form.formState.errors.name)} aria-describedby={form.formState.errors.name ? 'plan-name-error' : undefined} />{form.formState.errors.name ? <small id="plan-name-error" className="field-error">{t(form.formState.errors.name.message ?? 'errors.validation')}</small> : null}</div>
    <div className="form-grid-two"><div className="form-field"><label htmlFor="initial-capital">{isZh ? '初始资金' : 'Initial capital'}</label><div className="input-prefix"><span>$</span><input id="initial-capital" inputMode="decimal" placeholder="50000.00" {...form.register('initialCapital')} aria-invalid={Boolean(form.formState.errors.initialCapital)} aria-describedby="initial-capital-hint" /></div>{form.formState.errors.initialCapital ? <small className="field-error">{t(form.formState.errors.initialCapital.message ?? 'errors.validation')}</small> : <small id="initial-capital-hint" className="initial-capital-hint">{isZh ? '一次性建仓计划金额，不计入每月定投完成率。实际投入仍以 BUY 交易为准。' : 'One-time opening budget. It does not count toward monthly DCA completion; actual BUYs remain the source of truth.'}</small>}</div><div className="form-field"><label htmlFor="monthly-budget">{t('plan.monthlyBudget')}</label><div className="input-prefix"><span>$</span><input id="monthly-budget" inputMode="decimal" {...form.register('monthlyBudget')} aria-invalid={Boolean(form.formState.errors.monthlyBudget)} aria-describedby={form.formState.errors.monthlyBudget ? 'monthly-budget-error' : undefined} /></div>{form.formState.errors.monthlyBudget ? <small id="monthly-budget-error" className="field-error">{t(form.formState.errors.monthlyBudget.message ?? 'errors.validation')}</small> : null}</div></div>
    <div className="form-grid-two"><div className="form-field"><label htmlFor="start-date">{t('plan.startDate')}</label><input id="start-date" type="date" {...form.register('startDate')} aria-invalid={Boolean(form.formState.errors.startDate)} aria-describedby={form.formState.errors.startDate ? 'start-date-error' : undefined} />{form.formState.errors.startDate ? <small id="start-date-error" className="field-error">{t(form.formState.errors.startDate.message ?? 'errors.validation')}</small> : null}</div><div className="form-field"><label htmlFor="frequency">{t('plan.frequency')}</label><select id="frequency" defaultValue="MONTHLY" disabled><option value="MONTHLY">{t('plan.monthly')}</option></select></div></div>
    <div className="form-field"><span className="form-label">{t('plan.executionWindow')}</span><div className="form-grid-two"><div className="form-field"><label htmlFor="execution-start-day">{t('plan.executionStart')}</label><input id="execution-start-day" type="number" min="1" max="31" {...form.register('executionStartDay')} /></div><div className="form-field"><label htmlFor="execution-end-day">{t('plan.executionEnd')}</label><input id="execution-end-day" type="number" min="1" max="31" {...form.register('executionEndDay')} aria-invalid={Boolean(form.formState.errors.executionEndDay)} aria-describedby={form.formState.errors.executionEndDay ? 'execution-end-day-error' : undefined} />{form.formState.errors.executionEndDay ? <small id="execution-end-day-error" className="field-error">{t(form.formState.errors.executionEndDay.message ?? 'errors.validation')}</small> : null}</div></div><small className="field-hint">{t('plan.executionHint')}</small></div>
    <div className="form-footer"><span className="save-feedback">{saved ? <><Check size={14} />{t('plan.saved')}</> : null}</span><button className="button button-primary" type="submit" disabled={pending || !totalValid}><Save size={15} />{pending ? t('settings.saving') : plan ? t('common.save') : t('plan.createPlan')}</button></div>
    <div className="asset-editor"><div className="asset-editor-header"><span>{t('etfs.ticker')}</span><span>{t('dashboard.target')}</span><span /></div>{fields.map((field, index) => { const symbolError = form.formState.errors.assets?.[index]?.symbol?.message; const weightError = form.formState.errors.assets?.[index]?.targetWeight?.message; const symbolErrorId = `plan-asset-${index}-symbol-error`; const weightErrorId = `plan-asset-${index}-weight-error`; return <div className="asset-editor-row" key={field.id}><select {...form.register(`assets.${index}.symbol`)} aria-label={t('plan.assetLabel', { count: index + 1 })} aria-invalid={Boolean(symbolError)} aria-describedby={symbolError ? symbolErrorId : undefined}><option value="">{t('plan.selectEtf')}</option>{instruments.map((instrument) => <option key={instrument.symbol} value={instrument.symbol}>{instrument.symbol} · {instrument.name}</option>)}</select>{symbolError ? <small id={symbolErrorId} className="field-error">{t(symbolError)}</small> : null}<div className="input-suffix"><input inputMode="decimal" {...form.register(`assets.${index}.targetWeight`)} aria-label={t('plan.targetWeightLabel', { symbol: field.symbol || t('etfs.ticker') })} aria-invalid={Boolean(weightError)} aria-describedby={weightError ? weightErrorId : index === 0 && allocationError ? 'plan-asset-0-weight-error' : undefined} /><span>%</span></div>{weightError ? <small id={weightErrorId} className="field-error">{t(weightError)}</small> : null}<button type="button" className="icon-button subtle-icon" onClick={() => remove(index)} disabled={fields.length <= 1} aria-label={t('plan.removeAsset', { symbol: field.symbol || t('plan.asset') })}><Trash2 size={15} /></button></div> })}</div>
    {allocationError ? <small id="plan-asset-0-weight-error" className="field-error block-error">{allocationError}</small> : null}
    <button type="button" className="button button-ghost add-asset-button" onClick={() => append({ symbol: '', targetWeight: '0.00' })}><Plus size={15} />{t('plan.addAsset')}</button>
    <div className="allocation-editor-bar">{fields.map((field, index) => <span key={field.id} style={{ width: `${Math.max(decimal(watchedAssets?.[index]?.targetWeight).toNumber(), 0)}%` }} className={`allocation-segment segment-${index}`} />)}</div>
  </form>
}

export function PlanPage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const plans = useQuery({ queryKey: queryKeys.plans, queryFn: api.getPlans })
  const instruments = useQuery({ queryKey: queryKeys.instruments, queryFn: api.getInstruments })
  const plan = plans.data?.data.find((candidate) => candidate.status === 'ACTIVE')
  const cycles = useQuery({ queryKey: plan ? queryKeys.planCycles(plan.id) : queryKeys.planCycles('none'), queryFn: () => api.getCycles(plan?.id ?? ''), enabled: Boolean(plan) })
  const recommendation = useQuery({ queryKey: plan ? queryKeys.recommendation(plan.id) : queryKeys.recommendation('none'), queryFn: () => api.getRecommendation(plan?.id ?? ''), enabled: Boolean(plan) })
  const contributions = useQuery({ queryKey: plan ? queryKeys.contributionAnalysis(plan.id) : queryKeys.contributionAnalysis('none'), queryFn: () => api.getContributionAnalysis(plan?.id ?? ''), enabled: Boolean(plan) })
  const [saved, setSaved] = useState(false)
  const savePlan = useMutation({
    mutationFn: async (values: PlanFormValues) => {
      const result = plan ? await api.updatePlan(plan.id, planPayload(values)) : await api.createPlan(planPayload(values))
      await api.updateInitialCapital(result.data.id, values.initialCapital.trim() ? decimal(values.initialCapital).toFixed(2) : null)
      return result
    },
    onSuccess: (result) => {
      setSaved(true)
      void invalidatePlanQueries(queryClient, result.data.id)
    },
  })

  if (plans.isLoading) return <div className="page"><div className="page-intro"><LoadingBlock lines={2} /></div><div className="content-grid plan-grid"><Panel><LoadingBlock lines={8} /></Panel><Panel><LoadingBlock lines={8} /></Panel></div></div>
  if (plans.isError) return <div className="page"><ErrorState onRetry={() => void plans.refetch()} /></div>

  const cycleData = cycles.data?.data ?? plan?.cycles ?? []
  const contributionError = plan && contributions.isError
  return <div className="page plan-page">
    <div className="page-intro"><div><span className="page-eyebrow">{t('plan.eyebrow')}</span><h1>{t('plan.title')}</h1><p>{t('plan.subtitle')}</p></div>{plan ? <div className="page-actions"><span className="active-plan-chip"><span className="status-dot" />{t('plan.active')}</span></div> : null}</div>
    <DataStateBanner status={plans.data?.meta.status ?? 'STALE'} message={plans.data?.meta.message} source={plans.data?.meta.source === 'FIXTURE' ? t('common.demoData') : plans.data?.meta.source} asOf={plans.data?.meta.asOf} retrievedAt={plans.data?.meta.retrievedAt} />
    <div className="content-grid plan-grid">
      <Panel title={t('plan.planSettings')} detail={t('plan.planSettingsHint')}>{instruments.isError ? <ErrorState onRetry={() => void instruments.refetch()} /> : contributionError ? <ErrorState onRetry={() => void contributions.refetch()} /> : <PlanEditor plan={plan} initialCapital={contributions.data?.data.initial.plannedPrincipal} instruments={instruments.data?.data ?? []} pending={savePlan.isPending || Boolean(plan && contributions.isLoading)} saved={saved} onSubmit={(values) => { setSaved(false); savePlan.mutate(values) }} />}</Panel>
      {plan ? <TargetAllocationPanel plan={plan} /> : <Panel title={t('plan.targetAllocation')} detail={t('plan.allocationHint')}><EmptyState title={t('plan.noPlan')} detail={t('plan.noAssets')} /></Panel>}
    </div>
    {savePlan.error instanceof Error ? <p className="form-alert page-alert" role="alert">{savePlan.error.message}</p> : null}
    {plan ? <div className="content-grid plan-secondary-grid"><div className="plan-cycles-column"><Panel title={t('plan.cycles')} detail={t('plan.executionHistory')}>{cycles.isLoading ? <LoadingBlock lines={8} /> : cycles.isError ? <ErrorState onRetry={() => void cycles.refetch()} /> : cycleData.length ? <div className="cycles-list">{cycleData.map((cycle) => <CycleRow key={cycle.id} cycle={cycle} />)}</div> : <EmptyState title={t('common.noData')} />}</Panel></div><div>{recommendation.isLoading ? <Panel title={t('plan.recommendation')}><LoadingBlock lines={6} /></Panel> : recommendation.isError ? <ErrorState onRetry={() => void recommendation.refetch()} /> : recommendation.data ? <><DataStateBanner status={recommendation.data.meta.status} message={recommendation.data.meta.message} source={recommendation.data.meta.source === 'FIXTURE' ? t('common.demoData') : recommendation.data.meta.source} asOf={recommendation.data.meta.asOf} retrievedAt={recommendation.data.meta.retrievedAt} /><RecommendationPanel recommendation={recommendation.data.data} /></> : <EmptyState title={t('common.noData')} />}</div></div> : null}
  </div>
}
