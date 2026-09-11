import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/Login.vue'),
      meta: { public: true }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/Register.vue'),
      meta: { public: true }
    },
    { path: '/', redirect: '/chat' },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/ChatHome.vue'),
      // 游客可直接访问：点预设问题即可问答，转人工时再提示登录
      meta: { public: true }
    },
    {
      path: '/agent',
      name: 'agent',
      component: () => import('@/views/agent/Workbench.vue'),
      meta: { roles: ['ROLE_ADMIN', 'ROLE_AGENT'], title: '客服工作台' }
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/Profile.vue'),
      meta: { title: '个人信息' }
    },
    {
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      meta: { roles: ['ROLE_ADMIN', 'ROLE_AGENT'] },
      children: [
        { path: '', redirect: '/admin/statistics' },
        {
          path: 'statistics',
          name: 'statistics',
          component: () => import('@/views/admin/Statistics.vue'),
          meta: { title: '数据统计' }
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('@/views/admin/Knowledge.vue'),
          meta: { roles: ['ROLE_ADMIN'], title: '知识库管理' }
        },
        {
          path: 'conversation',
          name: 'conversation',
          component: () => import('@/views/admin/Conversation.vue'),
          meta: { title: '会话管理' }
        },
        {
          path: 'ticket',
          name: 'ticket',
          component: () => import('@/views/admin/Ticket.vue'),
          meta: { title: '工单管理' }
        },
        {
          path: 'user',
          name: 'user',
          component: () => import('@/views/admin/User.vue'),
          meta: { roles: ['ROLE_ADMIN'], title: '用户管理' }
        },
        {
          path: 'banned-word',
          name: 'banned-word',
          component: () => import('@/views/admin/BannedWord.vue'),
          meta: { roles: ['ROLE_ADMIN'], title: '违禁词管理' }
        },
        {
          path: 'log',
          name: 'log',
          component: () => import('@/views/admin/Log.vue'),
          meta: { roles: ['ROLE_ADMIN'], title: '操作日志' }
        }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/chat' }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 游客可访问的页面：登录态用户仍需要加载 profile 以正确显示角色入口
  if (to.meta.public) {
    if (auth.isLoggedIn && !auth.profile) {
      await auth.fetchProfile()
    }
    return true
  }

  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!auth.profile) {
    await auth.fetchProfile()
  }

  if (to.meta.roles) {
    const roles = to.meta.roles as string[]
    if (!roles.some((r) => auth.roles.includes(r))) {
      return { path: '/chat' }
    }
  }

  return true
})

export default router
