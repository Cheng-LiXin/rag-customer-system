<template>
  <div class="profile-page">
    <!-- 顶部导航 -->
    <header class="page-top">
      <div class="top-left" @click="router.push('/chat')">
        <div class="logo">🎓</div>
        <div class="top-title">
          <span class="title-main">燕山大学智能客服</span>
          <span class="title-sub">个人中心</span>
        </div>
      </div>

      <div class="top-right">
        <el-button v-if="auth.isAdmin" text @click="router.push('/admin/statistics')">
          <el-icon style="margin-right: 4px"><Setting /></el-icon>
          管理后台
        </el-button>
        <el-button v-else-if="auth.isAgent" text @click="router.push('/agent')">
          <el-icon style="margin-right: 4px"><Service /></el-icon>
          客服工作台
        </el-button>
        <el-button v-if="!isStaff" type="primary" plain round @click="router.push('/chat')">
          <el-icon style="margin-right: 4px"><ChatDotRound /></el-icon>
          回到对话
        </el-button>
        <el-tooltip content="退出登录" placement="bottom">
          <el-button circle :icon="SwitchButton" @click="handleLogout" />
        </el-tooltip>
      </div>
    </header>

    <main class="page-main">
      <div class="profile-wrap">
        <!-- 身份卡片 -->
        <div class="hero">
          <div class="hero-deco deco-1"></div>
          <div class="hero-deco deco-2"></div>
          <div class="hero-inner">
            <div class="hero-left">
              <el-avatar
                v-if="profile.avatar"
                :size="96"
                :src="profile.avatar"
                class="avatar avatar-img"
              />
              <el-avatar v-else :size="96" class="avatar avatar-text">
                {{ avatarText }}
              </el-avatar>
              <div class="hero-info">
                <div class="name-line">
                  <h1 class="hero-name">{{ displayName }}</h1>
                  <div class="role-tags">
                    <el-tag
                      v-for="role in profile.roles"
                      :key="role"
                      size="small"
                      :type="roleTagType(role)"
                      effect="light"
                      round
                    >
                      {{ roleName(role) }}
                    </el-tag>
                  </div>
                </div>
                <div class="hero-username">@{{ profile.username }}</div>
                <div v-if="showIdentity || showRegion || profile.email || profile.phone" class="hero-meta">
                  <span v-if="showIdentity">
                    <el-icon><User /></el-icon>
                    {{ profile.identity }}
                  </span>
                  <span v-if="showRegion">
                    <el-icon><Location /></el-icon>
                    {{ profile.province }} {{ profile.city }}
                  </span>
                  <span v-if="profile.email">
                    <el-icon><Message /></el-icon>
                    {{ profile.email }}
                  </span>
                  <span v-if="profile.phone">
                    <el-icon><Phone /></el-icon>
                    {{ profile.phone }}
                  </span>
                </div>
              </div>
            </div>

            <div class="hero-stats">
              <div class="stat">
                <div class="stat-num">#{{ profile.id }}</div>
                <div class="stat-label">用户ID</div>
              </div>
              <div class="stat">
                <div class="stat-num">{{ createDate }}</div>
                <div class="stat-label">注册日期</div>
              </div>
              <div class="stat">
                <div class="stat-num">{{ convTotal === null ? '—' : convTotal }}</div>
                <div class="stat-label">历史对话</div>
              </div>
              <div class="stat">
                <div class="stat-num">
                  <el-tag size="small" :type="accountActive ? 'success' : 'danger'" effect="light">
                    {{ accountActive ? '正常' : '已禁用' }}
                  </el-tag>
                </div>
                <div class="stat-label">账号状态</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 资料编辑 + 账号安全 -->
        <div class="content-grid">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <span class="panel-icon"><User /></span>
                <span>基础资料</span>
              </div>
            </template>
            <el-form
              ref="formRef"
              :model="form"
              :rules="rules"
              label-width="86px"
              label-position="left"
              class="edit-form"
            >
              <el-form-item label="用户名">
                <div class="readonly-value">{{ profile.username }}</div>
              </el-form-item>
              <el-form-item label="昵称" prop="nickname">
                <el-input
                  v-model="form.nickname"
                  placeholder="设置一个便于识别的昵称"
                  maxlength="20"
                  clearable
                  :disabled="!nicknameEditable"
                >
                  <template #prefix><el-icon><User /></el-icon></template>
                </el-input>
              </el-form-item>
              <el-form-item v-if="!nicknameEditable" class="nick-cooldown-tip">
                <div class="cooldown-tip">
                  <el-icon><Clock /></el-icon>
                  <span>昵称 30 天内仅可修改 1 次，距下次可改还有
                    <b>{{ nicknameCooldownDays }}</b> 天</span>
                </div>
              </el-form-item>
              <el-form-item label="邮箱" prop="email">
                <el-input
                  v-model="form.email"
                  placeholder="you@example.com"
                  maxlength="64"
                  clearable
                >
                  <template #prefix><el-icon><Message /></el-icon></template>
                </el-input>
              </el-form-item>
              <el-form-item label="手机号" prop="phone">
                <el-input
                  v-model="form.phone"
                  placeholder="请输入手机号"
                  maxlength="11"
                  clearable
                >
                  <template #prefix><el-icon><Phone /></el-icon></template>
                </el-input>
              </el-form-item>
              <el-form-item v-if="!isStaff" label="身份" prop="identity">
                <el-select v-model="form.identity" placeholder="请选择身份" clearable style="width: 100%">
                  <el-option v-for="opt in identityOptions" :key="opt" :label="opt" :value="opt" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="!isStaff" label="所在地区" prop="province">
                <div class="region-row">
                  <el-select
                    v-model="form.province"
                    placeholder="省份"
                    filterable
                    clearable
                    style="flex: 1"
                    @change="onProvinceChange"
                  >
                    <el-option v-for="r in regions" :key="r.name" :label="r.name" :value="r.name" />
                  </el-select>
                  <el-select
                    v-model="form.city"
                    placeholder="城市"
                    filterable
                    clearable
                    :disabled="!form.province"
                    style="flex: 1"
                  >
                    <el-option v-for="c in cityOptions" :key="c" :label="c" :value="c" />
                  </el-select>
                </div>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="saveProfile">
                  保存修改
                </el-button>
                <el-button :disabled="saving" @click="loadProfile">重置</el-button>
              </el-form-item>
            </el-form>
          </el-card>

          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <span class="panel-icon"><Lock /></span>
                <span>修改密码</span>
              </div>
            </template>
            <el-form
              ref="pwdFormRef"
              :model="pwdForm"
              :rules="pwdRules"
              label-width="86px"
              label-position="left"
              class="edit-form"
            >
              <el-form-item label="原密码" prop="oldPassword">
                <el-input
                  v-model="pwdForm.oldPassword"
                  type="password"
                  show-password
                  placeholder="请输入原密码"
                />
              </el-form-item>
              <el-form-item label="新密码" prop="newPassword">
                <el-input
                  v-model="pwdForm.newPassword"
                  type="password"
                  show-password
                  placeholder="至少 6 位"
                />
              </el-form-item>
              <el-form-item label="确认密码" prop="confirmPassword">
                <el-input
                  v-model="pwdForm.confirmPassword"
                  type="password"
                  show-password
                  placeholder="请再次输入新密码"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="changingPwd" @click="changePwd">
                  修改密码
                </el-button>
              </el-form-item>
              <div class="pwd-tip">
                为保障账号安全，建议定期更换密码并避免与其他平台重复。
              </div>
            </el-form>
          </el-card>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Setting, Service, ChatDotRound, SwitchButton, User, Lock, Message, Phone, Location, Clock } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { getProfile, updateProfile, changePassword, type ProfileResult } from '@/api/auth'
import { getHistory } from '@/api/chat'
import { regions, identityOptions } from '@/data/region'

const router = useRouter()
const auth = useAuthStore()

const profile = ref<ProfileResult>({ id: 0, username: '', roles: [] })
const convTotal = ref<number | null>(null)

const avatarText = computed(() =>
  (profile.value.nickname || profile.value.username || '用').slice(0, 1).toUpperCase()
)
const displayName = computed(() => profile.value.nickname || profile.value.username || '未设置昵称')
const accountActive = computed(() => profile.value.status === undefined || profile.value.status === 1)
/** 员工角色（客服/管理员）：不展示普通用户/考生语境字段（身份、所在地区），顶栏「回到对话」也对其隐藏 */
const isStaff = computed(() => auth.isAdmin || auth.isAgent)
const showIdentity = computed(() => !isStaff.value && !!profile.value.identity)
const showRegion = computed(() => !isStaff.value && (!!profile.value.province || !!profile.value.city))
/** 昵称 30 天限改：冷却期内禁改（后端返回 false）；未返回时默认可改，避免加载瞬间误禁用 */
const nicknameEditable = computed(() =>
  profile.value.nicknameEditable === undefined || profile.value.nicknameEditable
)
const nicknameCooldownDays = computed(() => profile.value.nicknameCooldownDays ?? 0)

/** 注册日期（展示到“年-月-日”） */
const createDate = computed(() => formatDate(profile.value.createTime))

function formatDate(s?: string) {
  if (!s) return '—'
  const d = s.replace('T', ' ').slice(0, 10)
  return d || '—'
}

function roleName(role: string) {
  const map: Record<string, string> = {
    ROLE_ADMIN: '管理员',
    ROLE_AGENT: '客服',
    ROLE_USER: '普通用户'
  }
  return map[role] || role
}

function roleTagType(role: string): 'danger' | 'warning' | 'primary' | 'info' {
  if (role === 'ROLE_ADMIN') return 'danger'
  if (role === 'ROLE_AGENT') return 'warning'
  if (role === 'ROLE_USER') return 'primary'
  return 'info'
}

// ==================== 基础资料 ====================

const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({
  nickname: '',
  email: '',
  phone: '',
  identity: '',
  province: '',
  city: ''
})

/** 省市联动：城市选项随所选省份变化 */
const cityOptions = computed(() => {
  const hit = regions.find((r) => r.name === form.province)
  return hit ? hit.cities : []
})

function onProvinceChange() {
  form.city = ''
}

const rules: FormRules = {
  nickname: [
    { required: true, message: '昵称不能为空', trigger: 'blur' },
    { max: 20, message: '昵称不能超过20个字符', trigger: 'blur' }
  ],
  email: [{ type: 'email', message: '请输入正确的邮箱地址', trigger: 'blur' }],
  phone: [
    {
      pattern: /^1[3-9]\d{9}$/,
      message: '请输入正确的11位手机号',
      trigger: 'blur'
    }
  ]
}

async function loadProfile() {
  try {
    const data = await getProfile()
    profile.value = data
    form.nickname = data.nickname || ''
    form.email = data.email || ''
    form.phone = data.phone || ''
    form.identity = data.identity || ''
    form.province = data.province || ''
    form.city = data.city || ''
  } catch {
    ElMessage.error('加载个人信息失败')
  }
}

async function loadConversationTotal() {
  try {
    const res = await getHistory(1, 1)
    convTotal.value = res.total
  } catch {
    convTotal.value = null
  }
}

async function saveProfile() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await updateProfile({
      nickname: form.nickname.trim(),
      email: form.email.trim(),
      phone: form.phone.trim(),
      identity: form.identity,
      province: form.province,
      city: form.city
    })
    ElMessage.success('保存成功')
    // 只拉一次资料，再用其结果同步顶栏/下拉展示，省一次重复的 /auth/me 请求
    await loadProfile()
    auth.profile = {
      username: profile.value.username,
      roles: profile.value.roles,
      id: profile.value.id,
      nickname: profile.value.nickname,
      avatar: profile.value.avatar
    }
  } catch {
    /* 错误提示已由拦截器统一处理 */
  } finally {
    saving.value = false
  }
}

// ==================== 修改密码 ====================

const pwdFormRef = ref<FormInstance>()
const changingPwd = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (!value) callback(new Error('请再次输入新密码'))
        else if (value !== pwdForm.newPassword) callback(new Error('两次输入的密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

async function changePwd() {
  if (!pwdFormRef.value) return
  const valid = await pwdFormRef.value.validate().catch(() => false)
  if (!valid) return
  changingPwd.value = true
  try {
    await changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword
    })
    ElMessage.success('密码修改成功，下次登录请使用新密码')
    pwdFormRef.value.resetFields()
  } catch {
    /* 原密码错误等提示已由拦截器统一处理 */
  } finally {
    changingPwd.value = false
  }
}

// ==================== 其他 ====================

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  localStorage.removeItem('convId')
  auth.logout()
  router.push('/login')
}

onMounted(() => {
  loadProfile()
  loadConversationTotal()
})
</script>

<style scoped>
.profile-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #f0f3ff 0%, #f7f8fc 100%);
}

/* ==================== 顶部导航 ==================== */
.page-top {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid #eef0f6;
  box-shadow: 0 1px 4px rgba(31, 35, 41, 0.04);
  z-index: 10;
}

.top-left {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.logo {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  background: var(--brand-gradient);
  border-radius: 12px;
}

.top-title {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.title-main {
  font-size: 16px;
  font-weight: 700;
}

.title-sub {
  font-size: 12px;
  color: var(--text-sub);
}

.top-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* ==================== 主体 ==================== */
.page-main {
  flex: 1;
  padding: 28px 20px 40px;
}

.profile-wrap {
  max-width: 960px;
  margin: 0 auto;
}

/* 身份卡片 */
.hero {
  position: relative;
  overflow: hidden;
  border-radius: 18px;
  background: linear-gradient(120deg, #4c6fff 0%, #7b5cff 55%, #a45cff 100%);
  color: #fff;
  box-shadow: 0 12px 32px rgba(76, 111, 255, 0.28);
  padding: 30px 34px;
}

.hero-deco {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
}

.deco-1 {
  width: 220px;
  height: 220px;
  right: -60px;
  top: -90px;
}

.deco-2 {
  width: 140px;
  height: 140px;
  right: 160px;
  bottom: -80px;
}

.hero-inner {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  flex-wrap: wrap;
}

.hero-left {
  display: flex;
  align-items: center;
  gap: 22px;
  min-width: 260px;
}

.avatar {
  flex-shrink: 0;
  background: #fff;
  color: #4c6fff;
  font-size: 38px;
  font-weight: 700;
  box-shadow: 0 0 0 4px rgba(255, 255, 255, 0.35);
}

.avatar-img {
  border-radius: 50%;
}

.hero-info {
  min-width: 0;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.hero-name {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.role-tags {
  display: flex;
  gap: 6px;
}

.hero-username {
  margin-top: 6px;
  font-size: 13px;
  opacity: 0.85;
}

.hero-meta {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  opacity: 0.9;
}

.hero-meta span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

/* 统计 */
.hero-stats {
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 14px;
  padding: 10px 4px;
}

.stat {
  min-width: 92px;
  text-align: center;
  padding: 8px 6px;
}

.stat + .stat {
  border-left: 1px solid rgba(255, 255, 255, 0.18);
}

.stat-num {
  font-size: 17px;
  font-weight: 700;
}

.stat-label {
  margin-top: 4px;
  font-size: 12px;
  opacity: 0.8;
}

/* 编辑区 */
.content-grid {
  margin-top: 20px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  align-items: start;
}

.panel {
  border-radius: 16px;
}

.panel :deep(.el-card__header) {
  border-bottom: 1px solid #f0f1f6;
}

.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}

.panel-icon {
  display: inline-flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: #fff;
  background: var(--brand-gradient);
}

.readonly-value {
  color: #6b7280;
  line-height: 32px;
}

.cooldown-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #b88230;
  background: #fdf6ec;
  border-radius: 8px;
  padding: 6px 10px;
  line-height: 1.4;
}

.cooldown-tip b {
  color: #e6a23c;
  font-size: 15px;
}

.nick-cooldown-tip {
  margin-bottom: 6px;
}

.edit-form {
  padding: 6px 4px 0;
}

.edit-form :deep(.el-input__prefix) {
  color: #a0a6b4;
}

.region-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.pwd-tip {
  margin-top: -4px;
  font-size: 12px;
  color: #a0a6b4;
  line-height: 1.6;
}

@media (max-width: 980px) {
  .content-grid {
    grid-template-columns: 1fr;
  }

  .hero-inner {
    justify-content: center;
  }
}

@media (max-width: 560px) {
  .hero-left {
    flex-direction: column;
    text-align: center;
  }

  .name-line {
    justify-content: center;
  }
}
</style>
