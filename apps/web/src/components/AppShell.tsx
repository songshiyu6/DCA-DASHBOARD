import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { AlertTriangle, ArrowLeftRight, CalendarClock, ChevronDown, Globe2, LayoutDashboard, LineChart, LogOut, Menu, Settings, WalletCards } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { api } from '../lib/api'
import { clearUserQueryCache, queryKeys } from '../lib/queryKeys'

const navItems = [
  { key: 'dashboard', to: '/', icon: LayoutDashboard },
  { key: 'plan', to: '/plan', icon: CalendarClock },
  { key: 'contributions', to: '/contributions', icon: WalletCards },
  { key: 'etfs', to: '/etfs', icon: LineChart },
  { key: 'transactions', to: '/transactions', icon: ArrowLeftRight },
  { key: 'settings', to: '/settings', icon: Settings },
] as const

const isDemoMode = import.meta.env.VITE_APP_MODE === 'demo'

export function AppShell() {
  const { t, i18n } = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const title = location.pathname.startsWith('/etfs/') ? 'etfs' : navItems.find((item) => item.to === location.pathname)?.key ?? 'dashboard'
  const isZh = (i18n.resolvedLanguage ?? i18n.language).toLowerCase().startsWith('zh')
  const contributionsLabel = isZh ? '投入分析' : 'Contributions'

  const toggleLanguage = () => {
    const next = i18n.language === 'zh' ? 'en' : 'zh'
    void i18n.changeLanguage(next)
    localStorage.setItem('dca-language', next)
  }

  const logout = async () => {
    try {
      await api.logout()
    } catch {
      // The local session must still be cleared when the API is unavailable.
    } finally {
      clearUserQueryCache(queryClient)
      queryClient.setQueryData(queryKeys.session, { data: { authenticated: false }, meta: { status: 'FRESH', source: 'API' } })
      void navigate('/login', { replace: true })
    }
  }

  return <div className="app-shell">
    <aside className={`sidebar ${mobileOpen ? 'sidebar-open' : ''}`}>
      <div className="brand-lockup"><span className="brand-mark"><span /></span><span><strong>DCA</strong><small>TERMINAL</small></span></div>
      <div className="workspace-switcher"><span className="workspace-avatar">S</span><span className="workspace-copy"><strong>{t('common.personalWorkspace')}</strong><small>{t('common.investingWorkspace')}</small></span><ChevronDown size={14} /></div>
      <nav className="primary-nav" aria-label={t('common.primaryNavigation')}>
        <span className="nav-label">{t('common.workspace')}</span>
        {navItems.map(({ key, to, icon: Icon }) => <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => `nav-link ${isActive ? 'nav-link-active' : ''}`} onClick={() => setMobileOpen(false)}><Icon size={17} strokeWidth={1.8} /><span>{key === 'contributions' ? contributionsLabel : t(`nav.${key}`)}</span>{key === 'plan' ? <span className="nav-pulse" /> : null}</NavLink>)}
      </nav>
      <div className="sidebar-bottom"><div className="connection-state"><span className="connection-dot" /><span><strong>{isDemoMode ? t('common.demoData') : t('common.liveMode')}</strong><small>{isDemoMode ? t('common.demoModeShort') : t('common.liveModeShort')}</small></span></div></div>
    </aside>
    {mobileOpen ? <button type="button" className="mobile-scrim" aria-label={t('common.closeNavigation')} onClick={() => setMobileOpen(false)} /> : null}
    <div className="app-main">
      <header className="topbar">
        <button type="button" className="icon-button mobile-menu" aria-label={t('common.openNavigation')} aria-expanded={mobileOpen} onClick={() => setMobileOpen(true)}><Menu size={20} /></button>
        <div className="breadcrumbs"><span>DCA TERMINAL</span><span className="breadcrumb-separator">/</span><strong>{title === 'dashboard' ? t('nav.dashboard') : title === 'plan' ? t('nav.plan') : title === 'contributions' ? contributionsLabel : title === 'transactions' ? t('nav.transactions') : title === 'settings' ? t('nav.settings') : t('nav.etfs')}</strong></div>
        <div className="topbar-actions"><button type="button" className="icon-button language-button" onClick={toggleLanguage} title={t('common.switchLanguage')} aria-label={t('common.switchLanguage')}><Globe2 size={16} /><span>{i18n.language === 'zh' ? '中' : 'EN'}</span></button><span className="topbar-divider" /><div className="profile-menu"><button type="button" className="profile-button" onClick={() => setProfileOpen((open) => !open)} aria-expanded={profileOpen} aria-haspopup="menu" aria-label={t('common.profileLabel', { name: 'Song' })}><span className="profile-avatar">SS</span><span className="profile-name">Song</span><ChevronDown size={14} /></button>{profileOpen ? <div className="profile-popover" role="menu"><div className="profile-popover-header"><strong>Song Shiyu</strong><small>{t('common.personalAccount')}</small></div><button type="button" onClick={() => { void logout() }}><LogOut size={15} />{t('common.signOut')}</button></div> : null}</div></div>
      </header>
      {isDemoMode ? <div className="demo-mode-banner" role="status"><AlertTriangle size={16} aria-hidden="true" /><strong>{t('common.demoData')}</strong><span>{t('common.demoModeNotice')}</span></div> : null}
      <main className="page-content"><Outlet /></main>
    </div>
  </div>
}
