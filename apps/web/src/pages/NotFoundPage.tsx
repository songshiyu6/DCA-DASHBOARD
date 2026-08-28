import { ArrowLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'

export function NotFoundPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return <div className="page not-found"><span className="page-eyebrow">DCA TERMINAL</span><h1>404</h1><p>{t('errors.generic')}</p><button type="button" className="button button-primary" onClick={() => { void navigate('/') }}><ArrowLeft size={15} />{t('nav.dashboard')}</button></div>
}
