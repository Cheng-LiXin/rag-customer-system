<template>
  <el-container class="admin-layout">
    <!-- 侧边栏 -->
    <el-aside width="220px" class="admin-aside">
      <div class="aside-logo">
        <span class="logo-icon">🎓</span>
        <span>智能客服后台</span>
      </div>
      <el-menu :default-active="route.path" router class="aside-menu">
        <el-menu-item index="/admin/statistics">
          <el-icon><DataLine /></el-icon>
          <span>数据统计</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/knowledge">
          <el-icon><Collection /></el-icon>
          <span>知识库管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/conversation">
          <el-icon><ChatDotRound /></el-icon>
          <span>会话管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/ticket">
          <el-icon><Tickets /></el-icon>
          <span>工单管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/user">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/banned-word">
          <el-icon><Warning /></el-icon>
          <span>违禁词管理</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/guard">
          <el-icon><Lock /></el-icon>
          <span>安全防护</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/retrieval">
          <el-icon><Aim /></el-icon>
          <span>检索策略</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/unresolved">
          <el-icon><QuestionFilled /></el-icon>
          <span>未解决问题</span>
        </el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin/log">
          <el-icon><Document /></el-icon>
          <span>操作日志</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container class="admin-body">
      <el-header class="admin-header">
        <div class="header-title">{{ currentTitle }}</div>
        <div class="header-right">
          <el-button v-if="auth.isAgent" text @click="router.push('/agent')">
            <el-icon style="margin-right: 4px"><Service /></el-icon>
            客服工作台
          </el-button>
          <el-button text @click="router.push('/chat')">
            <el-icon style="margin-right: 4px"><ChatLineSquare /></el-icon>
            返回对话
          </el-button>
          <el-dropdown @command="onCommand">
            <span class="user-chip">
              <el-avatar :size="30" class="user-avatar">{{ avatarText }}</el-avatar>
              <span>{{ auth.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon style="margin-right: 4px"><User /></el-icon>
                  个人信息
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  Collection,
  ChatDotRound,
  Tickets,
  User,
  Document,
  ChatLineSquare,
  DataLine,
  Service,
  ArrowDown,
  Warning,
  Lock,
  Aim,
  QuestionFilled
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const currentTitle = computed(() => (route.meta.title as string) || '管理后台')
const avatarText = computed(() => (auth.displayName || '客').slice(0, 1).toUpperCase())

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  } else if (cmd === 'profile') {
    router.push('/profile')
  }
}
</script>

<style scoped>
.admin-layout {
  height: 100vh;
}

.admin-aside {
  background: #1f2430;
  display: flex;
  flex-direction: column;
}

.aside-logo {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo-icon {
  font-size: 20px;
}

.aside-menu {
  border-right: none;
  background: transparent;
  padding: 12px 0;
}

.aside-menu :deep(.el-menu-item) {
  color: #a7adbd;
  height: 48px;
  margin: 4px 12px;
  border-radius: 10px;
}

.aside-menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}

.aside-menu :deep(.el-menu-item.is-active) {
  background: var(--brand-gradient);
  color: #fff;
}

.admin-body {
  flex: 1;
}

.admin-header {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #eef0f6;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 14px;
}

.user-avatar {
  background: var(--brand-gradient);
  color: #fff;
  font-weight: 600;
}

.admin-main {
  background: var(--bg-page);
  padding: 20px;
  overflow-y: auto;
}
</style>
