import { message } from 'ant-design-vue'
import type { ApiEnvelope } from './types'
import { clearAuth, getToken } from './token'

/**
 * HTTP 客户端：把后端的统一响应包装解掉，并把错误变成异常。
 *
 * 设计要点：
 *  1. **单点解包**：后端所有接口都返回 `{code, message, data}`，
 *     解包只在这里做一次，页面拿到的就是纯业务数据，不用每处都写 `res.data.data`。
 *  2. **错误统一提示**：业务码非 0 或 HTTP 非 2xx 时，弹一条 Ant Design 的 message，
 *     同时抛出异常，让调用方可以决定是否额外处理（比如派单失败要刷新列表）。
 *  3. **不吞异常**：抛出去的 Error 带有 code，页面可以选择性忽略，
 *     但默认行为是"提示 + 中断流程"，避免静默失败。
 *
 * 之所以用原生 fetch 而不是 axios：本项目只有 GET/POST/PATCH/DELETE 四种简单调用，
 * 少一个依赖就少一份升级负担；将来需要请求取消、重试、拦截器再换 axios 也不难。
 */

/** 业务异常，带后端返回的 code */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number

  constructor(code: number, msg: string, httpStatus: number) {
    super(msg)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  /** 查询参数，值为 undefined/null/'' 的会被自动丢弃 */
  params?: Record<string, string | number | boolean | undefined | null>
  body?: unknown
  /** 是否自动弹出错误提示（默认 true）。批量轮询类请求可设为 false 避免刷屏 */
  silent?: boolean
}

/** 把对象拼成查询串，自动跳过空值 */
function buildQuery(params?: RequestOptions['params']): string {
  if (!params) return ''
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.append(key, String(value))
    }
  })
  const qs = search.toString()
  return qs ? `?${qs}` : ''
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', params, body, silent = false } = options
  const url = `/api/v1${path}${buildQuery(params)}`
  const headers: Record<string, string> = {}
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers: Object.keys(headers).length > 0 ? headers : undefined,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (e) {
    // 网络层失败（后端没启动、被防火墙拦等）
    const msg = `无法连接后端（${url}）。请确认 Java 后端已在 8080 端口启动。`
    if (!silent) message.error(msg)
    throw new ApiError(-1, msg, 0)
  }

  // 204/205 没有响应体
  if (response.status === 204) {
    return undefined as T
  }

  let envelope: ApiEnvelope<T>
  try {
    envelope = (await response.json()) as ApiEnvelope<T>
  } catch {
    // 502/503/504 几乎都是"后端没起 / 重启中 / 代理目标端口不对"，
    // 给出可操作指引，比一句"响应不是合法 JSON"有用得多
    const gatewayMsg =
      response.status === 502 || response.status === 503 || response.status === 504
        ? `后端服务当前不可达（HTTP ${response.status}）。请确认 Java 后端已在 8080 端口启动，或运行 start-dev.ps1 一键启动。`
        : `响应不是合法 JSON（HTTP ${response.status}）`
    if (!silent) message.error(gatewayMsg)
    throw new ApiError(-1, gatewayMsg, response.status)
  }

  // 登录态失效：清理 token 并回登录页（登录页本身不跳，避免死循环）
  if (response.status === 401) {
    clearAuth()
    const onLoginPage = window.location.pathname === '/login'
    if (!onLoginPage) {
      if (!silent) message.error('登录已过期，请重新登录')
      window.location.replace('/login')
    }
    throw new ApiError(
      envelope?.code ?? 1005,
      envelope?.message || '未登录或登录已过期',
      401,
    )
  }

  if (!response.ok || envelope.code !== 0) {
    const msg = envelope?.message || `请求失败（HTTP ${response.status}）`
    if (!silent) message.error(msg)
    throw new ApiError(envelope?.code ?? -1, msg, response.status)
  }

  return envelope.data
}

/** 文件下载：不走统一包装，直接拿字节流并触发浏览器下载 */
async function download(path: string, params?: RequestOptions['params']): Promise<void> {
  const url = `/api/v1${path}${buildQuery(params)}`
  const token = getToken()
  const response = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  })
  if (response.status === 401) {
    clearAuth()
    if (window.location.pathname !== '/login') {
      window.location.replace('/login')
    }
    message.error('登录已过期，请重新登录')
    throw new ApiError(1005, '登录已过期', 401)
  }
  if (!response.ok) {
    message.error(`导出失败（HTTP ${response.status}）`)
    throw new ApiError(-1, '导出失败', response.status)
  }
  const blob = await response.blob()
  // 文件名优先取后端 Content-Disposition 里的；取不到就用 URL 最后一段兜底
  const disposition = response.headers.get('Content-Disposition') || ''
  const matched = /filename="?([^"]+)"?/.exec(disposition)
  const filename = matched?.[1] || path.split('/').pop() || 'export.csv'

  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 释放内存，否则大文件反复下载会泄漏
  URL.revokeObjectURL(objectUrl)
  message.success(`已开始下载 ${filename}`)
}

export const http = {
  get: <T>(path: string, params?: RequestOptions['params'], silent = false) =>
    request<T>(path, { method: 'GET', params, silent }),
  post: <T>(path: string, body?: unknown, params?: RequestOptions['params']) =>
    request<T>(path, { method: 'POST', body, params }),
  /** 静默 POST：错误由调用方自己展示（登录页用，避免全局弹窗与行内提示重复） */
  postSilent: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body, silent: true }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  download,
}
