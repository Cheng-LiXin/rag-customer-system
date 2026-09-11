<template>
  <view class="page">
    <!-- 渐变头部 -->
    <view class="hero brand-grad">
      <view class="hero-emoji">🎓</view>
      <view class="hero-title">欢迎回来</view>
      <view class="hero-sub">登录后可与人工客服对话、查看历史会话</view>
    </view>

    <view class="form card">
      <view class="field">
        <text class="label">手机号 / 账号</text>
        <input
          v-model="username"
          class="input"
          placeholder="请输入注册手机号或账号"
          :maxlength="20"
        />
      </view>
      <view class="field">
        <text class="label">密码</text>
        <view class="pwd-row">
          <input
            v-model="password"
            class="input pwd-input"
            placeholder="请输入密码"
            :password="!showPwd"
            :maxlength="20"
            confirm-type="done"
            @confirm="onSubmit"
          />
          <text class="eye" @click="showPwd = !showPwd">{{ showPwd ? '🙈' : '👁' }}</text>
        </view>
      </view>

      <button class="submit grad-btn" :loading="loading" @click="onSubmit">登 录</button>

      <view class="foot">
        <text>还没有账号？</text>
        <text class="link" @click="goRegister">立即注册</text>
      </view>
      <view class="tip">游客可直接返回使用智能问答，无需登录</view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { login as doLogin, isAgent, isAdmin } from '@/stores/auth'

const username = ref('')
const password = ref('')
const showPwd = ref(false)
const loading = ref(false)

function goRegister() {
  uni.navigateTo({ url: '/pages/register/register' })
}

async function onSubmit() {
  const u = username.value.trim()
  const p = password.value
  if (!u) return uni.showToast({ title: '请输入手机号/账号', icon: 'none' })
  if (!p) return uni.showToast({ title: '请输入密码', icon: 'none' })

  loading.value = true
  try {
    await doLogin(u, p)
    uni.showToast({ title: '登录成功', icon: 'success' })
    // 客服/管理员直接进工作台，普通用户进问答首页
    const target = (isAgent.value || isAdmin.value)
      ? '/pages/agent/workbench'
      : '/pages/index/index'
    setTimeout(() => uni.reLaunch({ url: target }), 500)
  } catch (e: any) {
    uni.showToast({ title: e?.message || '登录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  background: linear-gradient(180deg, #eef3ff 0%, #f6f7fb 30%);
  padding: 0 40rpx 60rpx;
  box-sizing: border-box;
}

.hero {
  margin: 0 -40rpx;
  padding: 90rpx 48rpx 110rpx;
  border-radius: 0 0 48rpx 48rpx;
  color: #fff;
  position: relative;
}

.hero-emoji {
  font-size: 88rpx;
}

.hero-title {
  margin-top: 20rpx;
  font-size: 44rpx;
  font-weight: 700;
}

.hero-sub {
  margin-top: 10rpx;
  font-size: 26rpx;
  opacity: 0.85;
}

.form {
  margin-top: -60rpx;
  padding: 48rpx 40rpx 40rpx;
  position: relative;
}

.field {
  margin-bottom: 32rpx;
}

.label {
  display: block;
  font-size: 26rpx;
  color: #6b7280;
  margin-bottom: 12rpx;
}

.input {
  width: 100%;
  height: 92rpx;
  background: #f5f7fb;
  border-radius: 16rpx;
  padding: 0 24rpx;
  box-sizing: border-box;
  font-size: 30rpx;
}

.pwd-row {
  position: relative;
}

.pwd-input {
  padding-right: 80rpx;
}

.eye {
  position: absolute;
  right: 24rpx;
  top: 50%;
  transform: translateY(-50%);
  font-size: 32rpx;
}

.submit {
  margin-top: 12rpx;
  height: 92rpx;
  line-height: 92rpx;
  font-size: 32rpx;
  font-weight: 600;
}

.foot {
  margin-top: 28rpx;
  text-align: center;
  font-size: 26rpx;
  color: #6b7280;
}

.link {
  color: #5b7cfa;
  font-weight: 600;
  margin-left: 6rpx;
}

.tip {
  margin-top: 20rpx;
  text-align: center;
  font-size: 22rpx;
  color: #a0a6b4;
}
</style>
