<template>
  <view class="page">
    <view v-if="!profile" class="empty">加载中…</view>

    <template v-else>
      <!-- 身份卡 -->
      <view class="hero brand-grad">
        <view class="hero-top">
          <view class="avatar">{{ avatarText }}</view>
          <view class="info">
            <view class="name">{{ displayName }}</view>
            <view class="sub">@{{ profile.username }}</view>
            <view class="tags">
              <text v-for="r in roleNames" :key="r" class="role">{{ r }}</text>
            </view>
          </view>
        </view>
        <view class="hero-stats">
          <view class="stat"><text class="num">#{{ profile.id }}</text><text class="label">账号ID</text></view>
          <view class="stat"><text class="num">{{ createDate }}</text><text class="label">注册日期</text></view>
          <view class="stat"><text class="num">{{ convTotal === null ? '—' : convTotal }}</text><text class="label">历史对话</text></view>
          <view class="stat"><text class="num" :class="accountActive ? 'ok' : 'bad'">{{ accountActive ? '正常' : '停用' }}</text><text class="label">账号状态</text></view>
        </view>
      </view>

      <!-- 基础资料 -->
      <view class="sec card">
        <view class="sec-title">✏️ 基础资料</view>

        <view class="fld">
          <view class="lab">用户名</view>
          <view class="ro">{{ profile.username }}</view>
        </view>

        <view class="fld">
          <view class="lab">昵称</view>
          <input
            class="input"
            v-model="form.nickname"
            placeholder="请设置昵称"
            :maxlength="20"
            :disabled="!nicknameEditable"
          />
        </view>
        <view v-if="!nicknameEditable" class="cooldown warn">
          ⏳ 昵称 30 天内仅可修改 1 次，距下次可改还有 {{ profile.nicknameCooldownDays ?? 0 }} 天
        </view>
        <view v-else class="cooldown">昵称 30 天内仅可修改 1 次，请谨慎填写</view>

        <view class="fld">
          <view class="lab">邮箱</view>
          <input class="input" v-model="form.email" placeholder="you@example.com" :maxlength="64" />
        </view>

        <view class="fld">
          <view class="lab">手机号</view>
          <input class="input" v-model="form.phone" type="number" placeholder="请输入手机号" :maxlength="11" />
        </view>

        <template v-if="!isStaff">
          <view class="fld">
            <view class="lab">身份</view>
            <picker mode="selector" :range="identityOptions" :value="identityIndex" @change="onIdentity">
              <view class="picker">{{ form.identity || '请选择身份' }}<text class="arr">▾</text></view>
            </picker>
            <text v-if="form.identity" class="clear" @click.stop="form.identity = ''">清除</text>
          </view>

          <view class="fld">
            <view class="lab">所在地区</view>
            <view class="region-pickers">
              <picker mode="selector" :range="provinceNames" :value="provinceIndex" @change="onProvince">
                <view class="picker half">{{ form.province || '省份' }}<text class="arr">▾</text></view>
              </picker>
              <picker
                mode="selector"
                :range="cityOptions"
                :value="cityIndex"
                :disabled="!form.province"
                @change="onCity"
              >
                <view class="picker half" :class="{ dim: !form.province }">{{ form.city || '城市' }}<text class="arr">▾</text></view>
              </picker>
            </view>
            <text v-if="form.province || form.city" class="clear" @click.stop="form.province = ''; form.city = ''">清除</text>
          </view>
        </template>

        <view class="btn-row">
          <text class="save grad-btn" :class="{ disabled: saving }" @click="saveProfile">保存资料</text>
        </view>
      </view>

      <!-- 修改密码 -->
      <view class="sec card">
        <view class="sec-title">🔒 修改密码</view>

        <view class="fld">
          <view class="lab">原密码</view>
          <view class="pwd-wrap">
            <input class="input" v-model="pwd.oldPassword" :password="!pwdShow.old" placeholder="请输入原密码" />
            <text class="eye" @click="pwdShow.old = !pwdShow.old">{{ pwdShow.old ? '🙈' : '👁' }}</text>
          </view>
        </view>

        <view class="fld">
          <view class="lab">新密码</view>
          <view class="pwd-wrap">
            <input class="input" v-model="pwd.newPassword" :password="!pwdShow.n" placeholder="至少 6 位" />
            <text class="eye" @click="pwdShow.n = !pwdShow.n">{{ pwdShow.n ? '🙈' : '👁' }}</text>
          </view>
        </view>

        <view class="fld">
          <view class="lab">确认密码</view>
          <view class="pwd-wrap">
            <input class="input" v-model="pwd.confirm" :password="!pwdShow.c" placeholder="请再次输入新密码" />
            <text class="eye" @click="pwdShow.c = !pwdShow.c">{{ pwdShow.c ? '🙈' : '👁' }}</text>
          </view>
        </view>

        <view class="btn-row">
          <text class="save grad-btn" :class="{ disabled: changing }" @click="changePwd">修改密码</text>
        </view>
      </view>

      <view class="logout" @click="doLogout">退出登录</view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { reactive, ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getProfile, updateProfile, changePassword } from '@/api/auth'
import { getHistory } from '@/api/chat'
import { fetchProfile, isAgent, isAdmin, logout } from '@/stores/auth'
import { identityOptions, regions } from '@/data/region'
import { CONV_KEY } from '@/utils/config'
import type { ProfileResult } from '@/types'

const profile = ref<ProfileResult | null>(null)
const convTotal = ref<number | null>(null)

const isStaff = computed(() => isAgent.value || isAdmin.value)
const avatarText = computed(() =>
  (profile.value?.nickname || profile.value?.username || '用').slice(0, 1).toUpperCase()
)
const displayName = computed(() => profile.value?.nickname || profile.value?.username || '未设置昵称')
const accountActive = computed(() => profile.value?.status === undefined || profile.value?.status === 1)
const nicknameEditable = computed(() =>
  profile.value ? profile.value.nicknameEditable !== false : true
)
const createDate = computed(() => formatDate(profile.value?.createTime))
const roleNames = computed(() =>
  (profile.value?.roles || [])
    .map((r) => r.replace('ROLE_', ''))
    .map((r) => ({ ADMIN: '管理员', AGENT: '客服', USER: '普通用户' } as Record<string, string>)[r] || r)
)

function formatDate(s?: string) {
  if (!s) return '—'
  const d = String(s).replace('T', ' ').slice(0, 10)
  return d || '—'
}

// ==================== 基础资料表单 ====================
const saving = ref(false)
const form = reactive({ nickname: '', email: '', phone: '', identity: '', province: '', city: '' })

function applyForm(p: ProfileResult) {
  form.nickname = p.nickname || ''
  form.email = p.email || ''
  form.phone = p.phone || ''
  form.identity = p.identity || ''
  form.province = p.province || ''
  form.city = p.city || ''
}

// 省市 / 身份 picker 计算
const provinceNames = regions.map((r) => r.name)
const cityOptions = computed(() => {
  const hit = regions.find((r) => r.name === form.province)
  return hit ? hit.cities : []
})
const identityIndex = computed(() => {
  const i = identityOptions.indexOf(form.identity)
  return i < 0 ? 0 : i
})
const provinceIndex = computed(() => {
  const i = provinceNames.indexOf(form.province)
  return i < 0 ? 0 : i
})
const cityIndex = computed(() => {
  const i = cityOptions.value.indexOf(form.city)
  return i < 0 ? 0 : i
})

function onIdentity(e: any) {
  form.identity = identityOptions[Number(e.detail.value)] || ''
}
function onProvince(e: any) {
  form.province = provinceNames[Number(e.detail.value)] || ''
  form.city = '' // 换省清空已选市
}
function onCity(e: any) {
  form.city = cityOptions.value[Number(e.detail.value)] || ''
}

async function load() {
  try {
    const p = await getProfile()
    profile.value = p
    applyForm(p)
    // 历史对话数
    try {
      const res = await getHistory(1, 1)
      convTotal.value = res.total ?? 0
    } catch {
      convTotal.value = null
    }
  } catch {
    /* 未登录等由请求层处理 */
  }
}

async function saveProfile() {
  if (!profile.value || saving.value) return
  const nick = form.nickname.trim()
  if (nicknameEditable.value && !nick) return uni.showToast({ title: '昵称不能为空', icon: 'none' })
  if (nicknameEditable.value && nick.length > 20)
    return uni.showToast({ title: '昵称不能超过 20 个字符', icon: 'none' })

  const email = form.email.trim()
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
    return uni.showToast({ title: '请输入正确的邮箱地址', icon: 'none' })

  const phone = form.phone.trim()
  if (phone && !/^1[3-9]\d{9}$/.test(phone))
    return uni.showToast({ title: '请输入正确的 11 位手机号', icon: 'none' })

  // 仅提交发生变化的字段：昵称冷却期未改动则不触发限改
  const base = profile.value
  const changed: Record<string, unknown> = {}
  const diff = (a: string, b: string) => (a || '') !== (b || '')
  if (diff(nick, base.nickname || '')) changed.nickname = nick
  if (diff(email, base.email || '')) changed.email = email
  if (diff(phone, base.phone || '')) changed.phone = phone
  if (!isStaff.value) {
    if (diff(form.identity, base.identity || '')) changed.identity = form.identity
    if (diff(form.province, base.province || '')) changed.province = form.province
    if (diff(form.city, base.city || '')) changed.city = form.city
  }
  if (!Object.keys(changed).length) return uni.showToast({ title: '没有需要保存的修改', icon: 'none' })

  saving.value = true
  try {
    await updateProfile(changed)
    uni.showToast({ title: '保存成功', icon: 'success' })
    await load()
    fetchProfile().catch(() => {}) // 同步顶栏/首页显示昵称
  } catch (e: any) {
    uni.showToast({ title: e?.message || '保存失败', icon: 'none' })
  } finally {
    saving.value = false
  }
}

// ==================== 修改密码 ====================
const changing = ref(false)
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const pwdShow = reactive({ old: false, n: false, c: false })

function resetPwd() {
  pwd.oldPassword = ''
  pwd.newPassword = ''
  pwd.confirm = ''
  pwdShow.old = false
  pwdShow.n = false
  pwdShow.c = false
}

async function changePwd() {
  if (changing.value) return
  if (!pwd.oldPassword) return uni.showToast({ title: '请输入原密码', icon: 'none' })
  if (!pwd.newPassword) return uni.showToast({ title: '请输入新密码', icon: 'none' })
  if (pwd.newPassword.length < 6) return uni.showToast({ title: '密码长度不能少于 6 位', icon: 'none' })
  if (pwd.newPassword === pwd.oldPassword)
    return uni.showToast({ title: '新密码不能与原密码相同', icon: 'none' })
  if (!pwd.confirm) return uni.showToast({ title: '请再次输入新密码', icon: 'none' })
  if (pwd.confirm !== pwd.newPassword)
    return uni.showToast({ title: '两次输入的密码不一致', icon: 'none' })

  changing.value = true
  try {
    await changePassword({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    uni.showToast({ title: '密码修改成功，下次登录请使用新密码', icon: 'none' })
    resetPwd()
  } catch (e: any) {
    uni.showToast({ title: e?.message || '修改失败', icon: 'none' })
  } finally {
    changing.value = false
  }
}

// ==================== 退出登录 ====================
function doLogout() {
  uni.showModal({
    title: '提示',
    content: '确定要退出当前账号吗？',
    confirmText: '退出',
    success: (r) => {
      if (!r.confirm) return
      uni.removeStorageSync(CONV_KEY)
      logout()
      uni.reLaunch({ url: '/pages/index/index' })
    }
  })
}

onShow(load)
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 28rpx 60rpx;
  box-sizing: border-box;
}

.empty {
  padding: 200rpx 0;
  text-align: center;
  color: #a6adbf;
}

.hero {
  border-radius: 28rpx;
  padding: 34rpx 30rpx 24rpx;
  color: #fff;
}

.hero-top {
  display: flex;
  align-items: center;
}

.avatar {
  width: 116rpx;
  height: 116rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.25);
  font-size: 52rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 24rpx;
  flex-shrink: 0;
}

.info {
  min-width: 0;
}

.name {
  font-size: 36rpx;
  font-weight: 700;
}

.sub {
  font-size: 24rpx;
  opacity: 0.85;
  margin: 6rpx 0;
}

.tags {
  display: flex;
}

.role {
  font-size: 20rpx;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 8rpx;
  padding: 2rpx 14rpx;
  margin-right: 10rpx;
}

.hero-stats {
  display: flex;
  margin-top: 26rpx;
  padding-top: 22rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.22);
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.num {
  font-size: 26rpx;
  font-weight: 700;
}

.num.ok {
  color: #d7ff9f;
}

.num.bad {
  color: #ffc7c0;
}

.label {
  font-size: 20rpx;
  opacity: 0.8;
  margin-top: 6rpx;
}

/* 通用卡片 + 表单项 */
.sec {
  margin-top: 24rpx;
  padding: 26rpx 28rpx;
}

.sec-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #2b2f3a;
  margin-bottom: 6rpx;
}

.fld {
  display: flex;
  align-items: center;
  padding: 20rpx 0;
  border-bottom: 1rpx solid #f1f3f9;
  flex-wrap: wrap;
  position: relative;
}

.fld:last-of-type {
  border-bottom: none;
}

.lab {
  width: 140rpx;
  color: #8a91a3;
  font-size: 26rpx;
  flex-shrink: 0;
}

.ro {
  color: #2b2f3a;
  font-size: 26rpx;
}

.input {
  flex: 1;
  min-width: 0;
  height: 72rpx;
  background: #f3f5fa;
  border-radius: 14rpx;
  padding: 0 22rpx;
  font-size: 26rpx;
}

.picker {
  flex: 1;
  height: 72rpx;
  line-height: 72rpx;
  background: #f3f5fa;
  border-radius: 14rpx;
  padding: 0 22rpx;
  font-size: 26rpx;
  color: #2b2f3a;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.picker.half {
  flex: none;
  width: 46%;
}

.picker.dim {
  color: #b6bccb;
}

.arr {
  color: #b6bccb;
  font-size: 22rpx;
}

.clear {
  font-size: 22rpx;
  color: #9aa0b0;
  margin-left: 16rpx;
}

.region-pickers {
  flex: 1;
  display: flex;
  gap: 14rpx;
  min-width: 0;
}

.cooldown {
  font-size: 22rpx;
  color: #37a067;
  padding: 4rpx 0 10rpx 140rpx;
}

.cooldown.warn {
  color: #d58a1f;
}

.btn-row {
  padding-top: 24rpx;
}

.save {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 22rpx 0;
  font-size: 28rpx;
  font-weight: 600;
}

.save.disabled {
  opacity: 0.45;
}

/* 密码行 */
.pwd-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  background: #f3f5fa;
  border-radius: 14rpx;
  min-width: 0;
}

.pwd-wrap .input {
  background: transparent;
}

.eye {
  padding: 0 20rpx;
  font-size: 30rpx;
}

.logout {
  margin-top: 32rpx;
  text-align: center;
  color: #e5594c;
  font-size: 28rpx;
  background: #fff;
  border-radius: 18rpx;
  padding: 24rpx 0;
}
</style>
