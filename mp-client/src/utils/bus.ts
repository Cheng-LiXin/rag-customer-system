/**
 * 极简事件总线：跨页面通信（工作台 WS 事件 → 会话详情页）。
 * 会话列表 WS 常驻「工作台」页，详情页在导航栈上层时通过 bus 接收实时推送。
 */
type Listener = (payload?: any) => void
const map = new Map<string, Set<Listener>>()

export function on(ev: string, fn: Listener) {
  if (!map.has(ev)) map.set(ev, new Set())
  map.get(ev)!.add(fn)
}

export function off(ev: string, fn: Listener) {
  map.get(ev)?.delete(fn)
}

export function emit(ev: string, payload?: any) {
  map.get(ev)?.forEach((fn) => {
    try {
      fn(payload)
    } catch (e) {
      console.error('[bus]', ev, e)
    }
  })
}
