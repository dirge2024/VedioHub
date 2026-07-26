const API_BASE = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:9090').replace(/\/$/, '')
const TOKEN_KEY = 'authToken'

export function hasAuthToken() {
  return Boolean(localStorage.getItem(TOKEN_KEY))
}

export function setAuthToken(token) {
  if (!token) throw new Error('登录接口未返回有效令牌')
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/**
 * 后端已统一响应体为 { code, message, data }（code === 0 表示成功；业务码放 body，
 * HTTP 状态只表达传输语义）。为了不让每个调用点都去关心这层信封，这里集中解包：
 *
 *   res.json()  ->  信封里的 data（数组 / 对象 / 字符串原样返回）
 *   res.text()  ->  成功时是 data 的文本形式；失败时是 message，
 *                   让现有的 `throw new Error(await res.text())` 直接拿到可读文案
 *   res.ok / res.status / res.headers  ->  保持原始 HTTP 语义（202 受理仍然 ok）
 *
 * 重要：只对 application/json 响应解包。SSE（text/event-stream，taskEvents.js 依赖
 * response.body 流式读取）和音频下载等非 JSON 响应必须原样透传——一旦在这里把 body
 * 读掉，流式传输会直接失效。
 */
const SUCCESS_CODE = 0

// 以 code + message 判定信封，刻意不依赖 data 键：错误响应的 data 恒为 null，
// 若后端将来配置了 non_null 序列化（data 键被省略），依赖 data 会让所有错误响应
// 退回透传，用户就会看到整串原始 JSON。
function isEnvelope(payload) {
  return payload !== null
    && typeof payload === 'object'
    && !Array.isArray(payload)
    && typeof payload.code === 'number'
    && 'message' in payload
}

/** 把 data 转成文本：字符串直接用，空值给空串，其余序列化。 */
function dataAsText(data) {
  if (data === null || data === undefined) return ''
  return typeof data === 'string' ? data : JSON.stringify(data)
}

/**
 * 登录/注册的过渡适配：App.vue 目前仍按旧的 AuthResponse 结构判断
 * `data.code === 200`，并读取 data.userInfo / data.token / data.msg。
 * 这里把统一响应体还原成旧结构，避免为此改动正在并行开发中的 App.vue。
 * 待前端登录逻辑改读统一结构后，可以删掉这个分支。
 */
function toLegacyAuthShape(envelope, status) {
  if (envelope.code === SUCCESS_CODE) {
    return { code: 200, msg: envelope.message, ...(envelope.data || {}) }
  }
  return { code: status, msg: envelope.message }
}

function unwrap(response, envelope, isAuthEndpoint) {
  const payload = isAuthEndpoint
    ? toLegacyAuthShape(envelope, response.status)
    : (envelope.data ?? null)

  return {
    ok: response.ok,
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
    redirected: response.redirected,
    url: response.url,
    json: async () => payload,
    text: async () => (response.ok ? dataAsText(payload) : (envelope.message || '')),
    raw: response
  }
}

export async function apiRequest(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  if (response.status === 401 && !path.startsWith('/user/')) {
    clearAuthToken()
    window.dispatchEvent(new Event('auth-expired'))
  }

  // 非 JSON（SSE / 音频流 / 空响应）原样返回，绝不触碰 body。
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) return response

  let envelope
  try {
    envelope = await response.clone().json()
  } catch {
    // 声明是 JSON 却解析不了（例如空 body），退回原始响应交给调用方处理。
    return response
  }
  if (!isEnvelope(envelope)) return response

  const isAuthEndpoint = path.startsWith('/user/login') || path.startsWith('/user/register')
  return unwrap(response, envelope, isAuthEndpoint)
}
