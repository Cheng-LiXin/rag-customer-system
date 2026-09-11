import { WS_URL } from './config'

/**
 * 人工客服 WebSocket 单例（镜像 Web 端协议，后端零改动）：
 * - 连接建立后由调用方发首帧 register：{"type":"register","role":"agent","agentId":n}
 *   或 {"type":"register","role":"user","conversationId":n}
 * - 上行消息帧：{"type":"message","conversationId":n,"content":"..."}
 * - 下行事件帧 JSON：message / assigned / position / closed(reason)
 *
 * 改进：防重复 close、安全错误处理、断连自动重连（指数退避）
 */

export interface WsHandlers {
  onOpen?: () => void
  onMessage: (msg: any) => void
  onClose?: () => void
  onError?: () => void
}

let task: any = null
let opened = false
let closing = false // 防止 onClose 回调期间重复清理
let buffer: string[] = []
let handlers: WsHandlers | null = null

// 自动重连状态
let autoReconnect = false
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectAttempts = 0
const MAX_RECONNECT = 5
const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 16000]

function dispatch(fn?: () => void) {
  try {
    fn && fn()
  } catch (e) {
    console.error('[ws] handler error:', e)
  }
}

function clearReconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function scheduleReconnect() {
  if (!autoReconnect || reconnectAttempts >= MAX_RECONNECT) return
  const delay = RECONNECT_DELAYS[Math.min(reconnectAttempts, RECONNECT_DELAYS.length - 1)]
  reconnectAttempts++
  console.log(`[ws] 将在 ${delay}ms 后重连 (${reconnectAttempts}/${MAX_RECONNECT})`)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    if (handlers && autoReconnect) {
      doOpen(handlers)
    }
  }, delay)
}

function doOpen(h: WsHandlers) {
  // 先安全关闭旧连接
  if (task) {
    try {
      closing = true
      task.close({})
    } catch { /* ignore */ }
    closing = false
  }
  task = null
  opened = false
  buffer = []

  handlers = h

  try {
    task = uni.connectSocket({ url: WS_URL, complete: () => {} })
  } catch (e) {
    console.error('[ws] connectSocket 失败:', e)
    scheduleReconnect()
    return
  }

  task.onOpen(() => {
    opened = true
    reconnectAttempts = 0 // 连接成功重置重连计数
    const pending = buffer.splice(0)
    pending.forEach((s) => {
      try {
        task && task.send({ data: s })
      } catch (e) {
        console.error('[ws] flush buffer 失败:', e)
      }
    })
    dispatch(handlers?.onOpen)
  })

  task.onMessage((e: any) => {
    if (!handlers) return
    try {
      handlers.onMessage(JSON.parse(String(e.data)))
    } catch { /* 非 JSON 忽略 */ }
  })

  task.onClose(() => {
    opened = false
    if (!closing) {
      // 非主动关闭 → 尝试自动重连
      dispatch(handlers?.onClose)
      scheduleReconnect()
    }
  })

  task.onError((e: any) => {
    opened = false
    console.error('[ws] error:', e)
    dispatch(handlers?.onError)
    // onError 后通常会紧跟 onClose，重连逻辑在 onClose 中处理
  })
}

/** 打开单例连接（重复调用先关旧的；支持自动重连） */
export function openWs(h: WsHandlers, reconnect = true) {
  clearReconnect()
  autoReconnect = reconnect
  reconnectAttempts = 0
  doOpen(h)
}

/** 发送 JSON 帧；未连接完成前自动入队 */
export function wsSend(obj: unknown) {
  const s = JSON.stringify(obj)
  if (task && opened) {
    try {
      task.send({ data: s })
    } catch (e) {
      console.error('[ws] send 失败:', e)
      buffer.push(s)
    }
  } else {
    buffer.push(s)
  }
}

/** 主动关闭连接（不触发自动重连） */
export function wsClose() {
  clearReconnect()
  autoReconnect = false
  if (task) {
    try {
      closing = true
      task.close({})
    } catch { /* ignore */ }
    closing = false
  }
  task = null
  opened = false
  buffer = []
  handlers = null
}

export const wsOpen = () => opened
