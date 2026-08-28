import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { DashboardPage } from './pages/DashboardPage'
import { EtfDetailPage } from './pages/EtfDetailPage'
import { EtfsPage } from './pages/EtfsPage'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { PlanPage } from './pages/PlanPage'
import { SettingsPage } from './pages/SettingsPage'
import { TransactionsPage } from './pages/TransactionsPage'
import { api } from './lib/api'
import { queryKeys } from './lib/queryKeys'
import { useTranslation } from 'react-i18next'

function RouteLoading() {
  const { t } = useTranslation()
  return <main className="route-loading" aria-live="polite"><span className="loading-spinner" />{t('common.loadingWorkspace')}</main>
}

function prefersLightTheme(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: light)').matches
}

function RequireSession() {
  const location = useLocation()
  const session = useQuery({ queryKey: queryKeys.session, queryFn: api.getSession, staleTime: 300_000, retry: false })
  if (session.isLoading) return <RouteLoading />
  if (session.isError || !session.data?.data.authenticated) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}

export default function App() {
  useEffect(() => {
    const savedTheme = localStorage.getItem('dca-theme')
    const theme = savedTheme === 'light' || savedTheme === 'dark' ? savedTheme : prefersLightTheme() ? 'light' : 'dark'
    document.documentElement.dataset.theme = theme
  }, [])

  return <Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route element={<RequireSession />}>
      <Route element={<AppShell />}>
        <Route index element={<DashboardPage />} />
        <Route path="plan" element={<PlanPage />} />
        <Route path="etfs" element={<EtfsPage />} />
        <Route path="etfs/:symbol" element={<EtfDetailPage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="404" element={<NotFoundPage />} />
        <Route path="*" element={<Navigate to="/404" replace />} />
      </Route>
    </Route>
  </Routes>
}
