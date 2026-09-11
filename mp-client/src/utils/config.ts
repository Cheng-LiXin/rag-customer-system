/**
 * 环境配置（毕设演示形态）：
 * - 微信开发者工具预览：后端跑本机，用 http://127.0.0.1:8080 即可（详情→本地设置勾选「不校验合法域名」）
 * - 真机预览：后端地址改为电脑局域网 IP，如 http://192.168.1.100:8080（需同一 Wi-Fi + 不校验合法域名）
 * 小程序正式发布则需将 BASE_URL 换成已备案 HTTPS 域名。
 */
export const BASE_URL = 'http://127.0.0.1:8080'

export const API_BASE = `${BASE_URL}/api`

export const WS_URL = `${BASE_URL.replace(/^http/, 'ws')}/ws/customer-service`

// ===== uni storage 键（与 Web 端 localStorage 键错开，避免同浏览器串场） =====
export const TOKEN_KEY = 'mp_token'
export const USERNAME_KEY = 'mp_username'
export const CONV_KEY = 'mp_conv_id'
/** 预留：从历史会话“继续查看”某会话 id（index onShow 消费后清除） */
export const REOPEN_KEY = 'mp_reopen_conv'
