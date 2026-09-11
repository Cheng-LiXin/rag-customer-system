import { API_BASE, TOKEN_KEY, USERNAME_KEY } from './config'

/**
 * 统一请求封装（镜像 Web 端 axios 拦截器语义）：
 * - 自动携带 Authorization: Bearer <token>
 * - 响应形如 { code, message, data }，code===200 时返回 data；其余弹 toast 并 reject
 * - HTTP 401/403：清登录态并跳登录页
 */
export interface RequestOptions {
  url: string
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** body（GET/PUT/DELETE 均可带；GET 的 body 会被并入 query） */
  data?: Record<string, unknown>
  /** 显式追加到 URL 的查询参数（PUT 等需要 query 的接口用） */
  query?: Record<string, unknown>
  /** 显示错误 toast（默认 true；静默请求可关闭） */
  showError?: boolean
  /** 401/403 时不自动跳登录（如登录接口自身） */
  noRedirect?: boolean
}

export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

function toast(title: string) {
  uni.showToast({ title, icon: 'none' })
}

function clearAuth() {
  uni.removeStorageSync(TOKEN_KEY)
  uni.removeStorageSync(USERNAME_KEY)
}

function buildQuery(data?: Record<string, unknown>): string {
  if (!data) return ''
  const parts = Object.entries(data)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

export function request<T = unknown>(options: RequestOptions): Promise<T> {
  const { url, method = 'GET', data, query, showError = true, noRedirect = false } = options
  return new Promise<T>((resolve, reject) => {
    const header: Record<string, string> = { 'Content-Type': 'application/json' }
    const token = uni.getStorageSync(TOKEN_KEY)
    if (token) header.Authorization = `Bearer ${token}`

    // GET：data 并入 query；PUT/DELETE：data 走 body，query 单独拼
    const isGet = method === 'GET'
    const queryStr = buildQuery(isGet ? { ...(data || {}), ...(query || {}) } : query)
    const targetUrl = API_BASE + url + queryStr

    uni.request({
      url: targetUrl,
      method,
      header,
      data: isGet ? undefined : data,
      timeout: 60000,
      success: (res) => {
        const status = res.statusCode
        const body = res.data as unknown
        if (status < 200 || status >= 300) {
          if (status === 401 || status === 403) {
            if (!noRedirect) {
              clearAuth()
              toast('登录已过期，请重新登录')
              uni.reLaunch({ url: '/pages/login/login' })
            }
          } else {
            const msg = (body as ApiResult)?.message || `请求失败(${status})`
            if (showError) toast(msg)
          }
          reject(new Error((body as ApiResult)?.message || `HTTP ${status}`))
          return
        }
        if (body && typeof body === 'object' && 'code' in (body as object)) {
          const api = body as ApiResult
          if (api.code === 200) {
            resolve(api.data as T)
          } else {
            if (showError) toast(api.message || '请求失败')
            reject(new Error(api.message || '请求失败'))
          }
          return
        }
        resolve(body as T)
      },
      fail: (err) => {
        if (showError) toast('网络异常，请确认后端已启动')
        reject(new Error(err.errMsg || '网络异常'))
      }
    })
  })
}

interface Extra {
  showError?: boolean
  query?: Record<string, unknown>
}

export const get = <T = unknown>(url: string, params?: Record<string, unknown>, showError = true) =>
  request<T>({ url, method: 'GET', data: params, showError })

export const post = <T = unknown>(
  url: string,
  data?: Record<string, unknown>,
  extra?: boolean | Extra
) =>
  request<T>({
    url,
    method: 'POST',
    data,
    showError: typeof extra === 'boolean' ? extra : extra?.showError,
    noRedirect: false
  })

export const put = <T = unknown>(url: string, data?: Record<string, unknown>, extra?: Extra) =>
  request<T>({
    url,
    method: 'PUT',
    data,
    showError: extra?.showError,
    query: extra?.query
  })

export const del = <T = unknown>(url: string, data?: Record<string, unknown>, showError = true) =>
  request<T>({ url, method: 'DELETE', data, showError })
