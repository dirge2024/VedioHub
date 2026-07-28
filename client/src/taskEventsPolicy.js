/**
 * 4xx 中除请求超时与限流外都不会自行恢复；网络错误和服务端错误继续退避重连。
 */
export function isTerminalStatus(status) {
  return status >= 400 && status < 500 && status !== 408 && status !== 429
}
