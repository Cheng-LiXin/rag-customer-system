# =============================================================================
# 前端镜像：多阶段构建（Node 构建 → nginx 托管静态产物）
# -----------------------------------------------------------------------------
# 构建上下文 = frontend/ 目录（compose 里 context: ./frontend）。
#
# 前端全程使用**相对路径**访问后端（axios baseURL='/api'、SSE 用 /api/chat/stream、
# WebSocket 用 ${location.host}/ws/...），所以不需要任何构建期注入的 BASE_URL ——
# 同一份镜像换域名/端口都不用重新构建，靠 nginx 反代即可。
# =============================================================================

# ---------- 阶段 1：构建 ----------
FROM node:20-alpine AS build
WORKDIR /app

# 先拷依赖清单并安装：这一层能被缓存，改业务代码时不必重装依赖
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- 阶段 2：托管 ----------
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
