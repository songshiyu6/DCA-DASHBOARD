import type { ApiResult, DataMeta } from '../../types'

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '/api/v1').replace(/\/+$/, '')
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let csrfToken: string | null = null
let csrfHeaderName = 'X-CSRF-TOKEN'
let csrfRequest: Promise<string | null> | null = null

export class ApiError extends Error {
  status: number
  code?: string

  constructor(message: string, status = 0, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function errorFromResponse(body: unknown, status: number): ApiError {
  if (isRecord(body)) {
    const detail = typeof body.detail === 'string' ? body.detail : typeof body.message === 'string' ? body.message : undefined
    const code = typeof body.code === 'string' ? body.code : undefined
    if (detail) return new ApiError(detail, status, code)
  }
  return new ApiError(`Request failed with ${status}`, status)
}

export function apiMeta(): DataMeta {
  return { status: 'FRESH', source: 'API', retrievedAt: new Date().toISOString() }
}

export function resetCsrf(): void {
  csrfToken = null
  csrfHeaderName = 'X-CSRF-TOKEN'
  csrfRequest = null
}

export async function request<T>(path: string, init: RequestInit = {}, retryCsrf = true): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')

  if (MUTATING_METHODS.has(method) && !headers.has(csrfHeaderName)) {
    const token = await getCsrfToken()
    if (token) headers.set(csrfHeaderName, token)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, { ...init, method, credentials: 'include', headers })
  } catch {
    throw new ApiError('Network unavailable')
  }

  const text = await response.text()
  let body: unknown = null
  if (text) {
    try {
      body = JSON.parse(text) as unknown
    } catch {
      body = text
    }
  }

  if (response.status === 403 && MUTATING_METHODS.has(method) && retryCsrf) {
    resetCsrf()
    return request<T>(path, init, false)
  }
  if (!response.ok) throw errorFromResponse(body, response.status)
  return body as T
}

async function getCsrfToken(): Promise<string | null> {
  if (csrfToken) return csrfToken
  if (csrfRequest) return csrfRequest
  csrfRequest = (async () => {
    const body = await request<unknown>('/auth/csrf')
    const value = isRecord(body) && 'data' in body ? body.data : body
    const payload = isRecord(value) ? value : isRecord(body) ? body : undefined
    const token = typeof value === 'string' ? value : payload && typeof payload.token === 'string' ? payload.token : payload && typeof payload.csrfToken === 'string' ? payload.csrfToken : null
    const headerName = payload && typeof payload.headerName === 'string' ? payload.headerName
      : isRecord(body) && typeof body.headerName === 'string' ? body.headerName : null
    if (headerName) csrfHeaderName = headerName
    csrfToken = token
    return token
  })()
  try {
    return await csrfRequest
  } finally {
    csrfRequest = null
  }
}

export type ApiRequest = typeof request
export type ApiResponse<T> = Promise<ApiResult<T>>
