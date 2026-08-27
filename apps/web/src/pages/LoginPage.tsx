import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowRight, LockKeyhole, Sparkles } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router-dom'
import { api } from '../lib/api'

export function LoginPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const login = useMutation({ mutationFn: () => api.login(username, password), onSuccess: (result) => { if (!result.data.authenticated) { setError('Unable to sign in with those credentials.'); return }; queryClient.setQueryData(['session'], result); const from = (location.state as { from?: string } | null)?.from ?? '/'; navigate(from, { replace: true }) }, onError: (reason) => setError(reason instanceof Error ? reason.message : 'Unable to sign in with those credentials.') })
  const toggleLanguage = () => { const next = i18n.language === 'zh' ? 'en' : 'zh'; void i18n.changeLanguage(next); localStorage.setItem('dca-language', next) }
  return <main className="login-page"><div className="login-orbit orbit-one" /><div className="login-orbit orbit-two" /><div className="login-topbar"><div className="brand-lockup"><span className="brand-mark"><span /></span><span><strong>DCA</strong><small>TERMINAL</small></span></div><button type="button" className="language-button login-language" onClick={toggleLanguage}>{i18n.language === 'zh' ? '中' : 'EN'}</button></div><div className="login-layout"><div className="login-context"><span className="page-eyebrow">{t('auth.eyebrow')}</span><h1>Invest with<br /><em>intention.</em></h1><p>One place for the market, your positions, and the discipline behind every contribution.</p><div className="login-signal"><span className="signal-icon"><Sparkles size={17} /></span><span><strong>Private by design</strong><small>Single-user workspace · local-first fallback</small></span></div></div><section className="login-card"><div className="login-card-header"><span className="login-lock"><LockKeyhole size={17} /></span><div><h2>{t('auth.title')}</h2><p>{t('auth.subtitle')}</p></div></div><form className="form-stack" onSubmit={(event) => { event.preventDefault(); setError(''); login.mutate() }}><div className="form-field"><label htmlFor="login-username">{t('auth.username')}</label><input id="login-username" autoComplete="username" required value={username} onChange={(event) => setUsername(event.target.value)} placeholder="you" /></div><div className="form-field"><label htmlFor="login-password">{t('auth.password')}</label><input id="login-password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} placeholder="********" /></div>{error ? <p className="form-alert" role="alert">{error}</p> : null}<button className="button button-primary login-submit" type="submit" disabled={login.isPending}>{login.isPending ? t('auth.signingIn') : t('auth.signIn')}<ArrowRight size={16} /></button></form><div className="login-demo-note">{t('auth.demoHint')}</div></section></div><footer className="login-footer"><span>Copyright 2026 DCA Terminal</span><span>Market data is informational only.</span></footer></main>
}
